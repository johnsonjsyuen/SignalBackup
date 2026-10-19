package com.johnsonyuen.signalbackup.ui.home

import android.app.PendingIntent
import android.util.Log
import app.cash.turbine.test
import com.johnsonyuen.signalbackup.data.auth.DriveAuthorizer
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.model.DriveFile
import com.johnsonyuen.signalbackup.domain.model.FailureReason
import com.johnsonyuen.signalbackup.domain.model.GcScanResult
import com.johnsonyuen.signalbackup.domain.model.GcTrashResult
import com.johnsonyuen.signalbackup.domain.model.NeedsConsentException
import com.johnsonyuen.signalbackup.domain.model.UploadProgress
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import com.johnsonyuen.signalbackup.domain.scheduling.UploadRunState
import com.johnsonyuen.signalbackup.domain.scheduling.UploadScheduler
import com.johnsonyuen.signalbackup.domain.usecase.DriveFolderResolution
import com.johnsonyuen.signalbackup.domain.usecase.GarbageCollectUseCase
import com.johnsonyuen.signalbackup.domain.usecase.ManualUploadUseCase
import com.johnsonyuen.signalbackup.domain.usecase.ResolveDriveFolderUseCase
import com.johnsonyuen.signalbackup.ui.COMPLETE_SETTINGS
import com.johnsonyuen.signalbackup.ui.DEFAULT_SETTINGS
import com.johnsonyuen.signalbackup.ui.FakeSettingsRepository
import com.johnsonyuen.signalbackup.ui.MainDispatcherRule
import com.johnsonyuen.signalbackup.ui.record
import com.johnsonyuen.signalbackup.ui.common.LocalFolderAccess
import com.johnsonyuen.signalbackup.ui.common.LocalFolderInfo
import com.johnsonyuen.signalbackup.ui.common.MinuteTicker
import com.johnsonyuen.signalbackup.ui.consent.ConsentAction
import com.johnsonyuen.signalbackup.ui.consent.DriveAuthorizationFlow
import com.johnsonyuen.signalbackup.ui.navigation.FixAction
import com.johnsonyuen.signalbackup.ui.navigation.FixActionBus
import com.johnsonyuen.signalbackup.ui.screen.home.CleanUpState
import com.johnsonyuen.signalbackup.ui.screen.home.HomeEvent
import com.johnsonyuen.signalbackup.ui.screen.home.HomeUiState
import com.johnsonyuen.signalbackup.ui.screen.home.HomeViewModel
import com.johnsonyuen.signalbackup.ui.screen.home.ResultFix
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val now = 1_760_000_000_000L
    private val hour = 60L * 60L * 1000L

    private lateinit var settings: FakeSettingsRepository
    private val latest = MutableStateFlow<UploadRecord?>(null)
    private val latestSuccess = MutableStateFlow<UploadRecord?>(null)
    private val runState = MutableStateFlow<UploadRunState>(UploadRunState.Idle)
    private val nextRun = MutableStateFlow<Long?>(null)

    private val history = mockk<UploadHistoryRepository>()
    private val scheduler = mockk<UploadScheduler>(relaxed = true)
    private val manualUpload = mockk<ManualUploadUseCase>(relaxed = true)
    private val gc = mockk<GarbageCollectUseCase>()
    private val authorizer = mockk<DriveAuthorizer>()
    private val resolveFolder = mockk<ResolveDriveFolderUseCase>()
    private val folderAccess = mockk<LocalFolderAccess>()
    private val ticker = mockk<MinuteTicker>()
    private val fixBus = FixActionBus()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        settings = FakeSettingsRepository(COMPLETE_SETTINGS)
        every { history.getLatest() } returns latest
        every { history.getLatestSuccess() } returns latestSuccess
        every { scheduler.observeRunState() } returns runState
        every { scheduler.observeNextScheduledRunMillis() } returns nextRun
        every { ticker.ticks() } returns flowOf(now)
        coEvery { folderAccess.inspect(any()) } returns
            LocalFolderInfo(name = "Backups", accessible = true, hasSignalBackups = true)
        coEvery { resolveFolder() } returns DriveFolderResolution.Ok("drive-folder", "Signal Backup")
    }

    @After
    fun tearDown() = unmockkAll()

    private fun viewModel() = HomeViewModel(
        settingsRepository = settings,
        historyRepository = history,
        uploadScheduler = scheduler,
        manualUpload = manualUpload,
        garbageCollect = gc,
        authorizationFlow = DriveAuthorizationFlow(authorizer, resolveFolder, scheduler),
        localFolderAccess = folderAccess,
        fixActionBus = fixBus,
        ticker = ticker,
    )

    @Test
    fun `maps run state, last failure, overdue success and next run`() = runTest {
        val progress = UploadProgress(50, 100, 10, 5, "backup.db")
        runState.value = UploadRunState.Running(progress)
        latest.value = record(timestampMillis = now - hour, status = UploadResultStatus.FAILED, reason = FailureReason.NETWORK)
        latestSuccess.value = record(id = 2, timestampMillis = now - 40 * hour, status = UploadResultStatus.SUCCESS)
        nextRun.value = now + 2 * hour

        val vm = viewModel()
        vm.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.setup.isComplete)
            assertEquals("Backups", state.setup.localFolderName)
            assertEquals(UploadRunState.Running(progress), state.run)
            assertEquals(FailureReason.NETWORK, state.lastResult?.reason)
            assertEquals(ResultFix.RETRY, state.lastResult?.fix)
            assertEquals(40 * hour, state.lastSuccessAgeMillis)
            assertTrue(state.isBackupOverdue)
            assertEquals(now + 2 * hour, state.nextRunMillis)
            assertFalse("upload already active", state.canUploadNow)
        }
    }

    @Test
    fun `recent success is not overdue and disabled schedule hides next run`() = runTest {
        settings.state.value = COMPLETE_SETTINGS.copy(scheduleEnabled = false)
        latestSuccess.value = record(timestampMillis = now - 10 * hour, status = UploadResultStatus.SUCCESS)
        nextRun.value = now + hour

        viewModel().uiState.test {
            val state = expectMostRecentItem()
            assertFalse(state.isBackupOverdue)
            assertNull(state.nextRunMillis)
            assertTrue(state.canUploadNow)
        }
    }

    @Test
    fun `setup checklist reflects missing steps and blocks upload`() = runTest {
        settings.state.value = DEFAULT_SETTINGS.copy(googleAccountEmail = "user@example.com")
        val vm = viewModel()
        vm.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.setup.signedIn)
            assertFalse(state.setup.driveAuthorized)
            assertFalse(state.setup.localFolderSet)
            assertFalse(state.setup.isComplete)
            assertFalse(state.canUploadNow)
            vm.uploadNow()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { manualUpload() }
    }

    @Test
    fun `needs consent failure blocks drive step and folder without backups is flagged`() = runTest {
        coEvery { folderAccess.inspect(any()) } returns LocalFolderInfo("Downloads", true, false)
        latest.value = record(timestampMillis = now, status = UploadResultStatus.FAILED, reason = FailureReason.NEEDS_CONSENT)
        viewModel().uiState.test {
            val state = expectMostRecentItem()
            assertFalse(state.setup.driveAuthorized)
            assertEquals(false, state.setup.localFolderHasBackups)
            assertEquals(ResultFix.GRANT_ACCESS, state.lastResult?.fix)
        }
    }

    @Test
    fun `grant access fix emits consent event carrying the upload action and resumes upload`() = runTest {
        val pendingIntent = mockk<PendingIntent>()
        coEvery { authorizer.requestAuthorization() } returns pendingIntent
        coEvery { authorizer.handleAuthorizationResult(any()) } returns true
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.events.test {
            vm.onFix(ResultFix.GRANT_ACCESS)
            assertEquals(HomeEvent.LaunchConsent(pendingIntent, ConsentAction.UPLOAD), awaitItem())
            assertTrue(vm.uiState.value.authorizing)

            vm.onConsentResult(null)
            cancelAndIgnoreRemainingEvents()
        }
        coVerifyOrder {
            authorizer.handleAuthorizationResult(null)
            resolveFolder()
            scheduler.applySchedule()
            manualUpload()
        }
        assertFalse(vm.uiState.value.authorizing)
    }

    @Test
    fun `clean up scan needing consent emits consent event for clean up`() = runTest {
        val pendingIntent = mockk<PendingIntent>()
        coEvery { gc.scan() } throws NeedsConsentException()
        coEvery { authorizer.requestAuthorization() } returns pendingIntent
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.events.test {
            vm.openCleanUp()
            assertEquals(HomeEvent.LaunchConsent(pendingIntent, ConsentAction.CLEAN_UP), awaitItem())
        }
        assertEquals(CleanUpState.Hidden, vm.uiState.value.cleanUp)
    }

    @Test
    fun `already authorized sign-in resolves folder and applies schedule without consent event`() = runTest {
        settings.state.value = DEFAULT_SETTINGS
        coEvery { authorizer.requestAuthorization() } returns null
        coEvery { resolveFolder() } returns DriveFolderResolution.Relocated("new", "Signal Backup", null)
        val vm = viewModel()

        vm.events.test {
            vm.onSignedIn("user@example.com")
            val event = awaitItem()
            assertTrue(event is HomeEvent.ShowMessage)
        }
        assertEquals("user@example.com", settings.state.value.googleAccountEmail)
        coVerifyOrder {
            resolveFolder()
            scheduler.applySchedule()
        }
        coVerify(exactly = 0) { manualUpload() }
    }

    @Test
    fun `notification consent fix action starts authorization for upload`() = runTest {
        val pendingIntent = mockk<PendingIntent>()
        coEvery { authorizer.requestAuthorization() } returns pendingIntent
        val vm = viewModel()
        vm.events.test {
            fixBus.post(FixAction.CONSENT)
            assertEquals(HomeEvent.LaunchConsent(pendingIntent, ConsentAction.UPLOAD), awaitItem())
        }
        assertNull(fixBus.pending.value)
    }

    @Test
    fun `dismissing clean up cancels the running scan`() = runTest {
        val never = CompletableDeferred<GcScanResult>()
        coEvery { gc.scan() } coAnswers { never.await() }
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.openCleanUp()
        assertEquals(CleanUpState.Scanning, vm.uiState.value.cleanUp)
        vm.dismissCleanUp()
        assertEquals(CleanUpState.Hidden, vm.uiState.value.cleanUp)
        // A late scan result must not reopen the dialog.
        never.complete(GcScanResult.NothingToClean)
        assertEquals(CleanUpState.Hidden, vm.uiState.value.cleanUp)
    }

    @Test
    fun `clean up ready then confirm trashes the listed items`() = runTest {
        val kept = listOf(DriveFile("k", "keep", 10))
        val trash = listOf(DriveFile("t", "old", 20))
        coEvery { gc.scan() } returns GcScanResult.Ready(kept, trash)
        val result = GcTrashResult(1, 20, emptyList())
        coEvery { gc.trash(trash) } returns result
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.openCleanUp()
        assertEquals(CleanUpState.Ready(kept, trash), vm.uiState.value.cleanUp)
        vm.confirmCleanUp()
        assertEquals(CleanUpState.Done(result), vm.uiState.value.cleanUp)
    }

    @Test
    fun `cancel upload delegates to scheduler`() = runTest {
        viewModel().cancelUpload()
        verify { scheduler.cancelRunningUploads() }
    }

    @Test
    fun `overdue threshold is 36 hours`() {
        val base = HomeUiState(nowMillis = now)
        assertFalse(base.copy(lastSuccessMillis = now - 36 * hour).isBackupOverdue)
        assertTrue(base.copy(lastSuccessMillis = now - 36 * hour - 1).isBackupOverdue)
        assertFalse(base.isBackupOverdue)
    }
}
