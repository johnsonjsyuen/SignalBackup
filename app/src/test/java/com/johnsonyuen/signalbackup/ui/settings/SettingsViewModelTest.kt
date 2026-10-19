package com.johnsonyuen.signalbackup.ui.settings

import android.app.PendingIntent
import android.util.Log
import app.cash.turbine.test
import com.johnsonyuen.signalbackup.data.auth.DriveAuthorizer
import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.SyncedFileRepository
import com.johnsonyuen.signalbackup.data.repository.UploadSessionRepository
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import com.johnsonyuen.signalbackup.domain.model.NeedsConsentException
import com.johnsonyuen.signalbackup.domain.scheduling.UploadScheduler
import com.johnsonyuen.signalbackup.domain.usecase.ResolveDriveFolderUseCase
import com.johnsonyuen.signalbackup.ui.COMPLETE_SETTINGS
import com.johnsonyuen.signalbackup.ui.FakeSettingsRepository
import com.johnsonyuen.signalbackup.ui.MainDispatcherRule
import com.johnsonyuen.signalbackup.ui.common.LocalFolderAccess
import com.johnsonyuen.signalbackup.ui.common.LocalFolderInfo
import com.johnsonyuen.signalbackup.ui.consent.DriveAuthorizationFlow
import com.johnsonyuen.signalbackup.ui.screen.settings.SettingsEvent
import com.johnsonyuen.signalbackup.ui.screen.settings.SettingsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
class SettingsViewModelTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private lateinit var settings: FakeSettingsRepository
    private val drive = mockk<DriveRepository>()
    private val scheduler = mockk<UploadScheduler>(relaxed = true)
    private val authorizer = mockk<DriveAuthorizer>(relaxed = true)
    private val synced = mockk<SyncedFileRepository>(relaxed = true)
    private val sessions = mockk<UploadSessionRepository>(relaxed = true)
    private val folderAccess = mockk<LocalFolderAccess>(relaxed = true)
    private val resolveFolder = mockk<ResolveDriveFolderUseCase>()

    private val root = listOf(DriveFolder("a", "Alpha"), DriveFolder("b", "Beta"))
    private val inAlpha = listOf(DriveFolder("c", "Child"))

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        settings = FakeSettingsRepository(COMPLETE_SETTINGS)
        coEvery { folderAccess.inspect(any()) } returns LocalFolderInfo("Backups", true, true)
        coEvery { drive.listFolders(null) } returns root
        coEvery { drive.listFolders("a") } returns inAlpha
    }

    @After
    fun tearDown() = unmockkAll()

    private fun viewModel() = SettingsViewModel(
        settingsRepository = settings,
        driveRepository = drive,
        uploadScheduler = scheduler,
        driveAuthorizer = authorizer,
        syncedFileRepository = synced,
        uploadSessionRepository = sessions,
        localFolderAccess = folderAccess,
        authorizationFlow = DriveAuthorizationFlow(authorizer, resolveFolder, scheduler),
    )

    @Test
    fun `picker resets its stack and list every time it opens`() = runTest {
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }

        vm.openDrivePicker()
        assertEquals(root, vm.uiState.value.picker.folders)
        vm.openFolder(root[0])
        assertEquals(listOf(root[0]), vm.uiState.value.picker.stack)
        assertEquals(inAlpha, vm.uiState.value.picker.folders)
        vm.dismissDrivePicker()
        assertFalse(vm.uiState.value.picker.visible)

        vm.openDrivePicker()
        val picker = vm.uiState.value.picker
        assertTrue(picker.visible)
        assertTrue(picker.stack.isEmpty())
        assertEquals(root, picker.folders)
        coVerify(exactly = 2) { drive.listFolders(null) }
    }

    @Test
    fun `a stale load does not overwrite the current folder`() = runTest {
        val slowRoot = CompletableDeferred<List<DriveFolder>>()
        coEvery { drive.listFolders(null) } coAnswers { slowRoot.await() }
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }

        vm.openDrivePicker()
        assertTrue(vm.uiState.value.picker.loading)
        vm.openFolder(DriveFolder("a", "Alpha"))
        slowRoot.complete(root)
        assertEquals(inAlpha, vm.uiState.value.picker.folders)
        assertFalse(vm.uiState.value.picker.loading)
    }

    @Test
    fun `load error shows message and retry reloads`() = runTest {
        coEvery { drive.listFolders(null) } throws java.io.IOException("offline") andThen root
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }

        vm.openDrivePicker()
        assertTrue(vm.uiState.value.picker.error != null)
        vm.retryLoad()
        assertNull(vm.uiState.value.picker.error)
        assertEquals(root, vm.uiState.value.picker.folders)
    }

    @Test
    fun `needs consent while browsing routes to the consent flow`() = runTest {
        val pendingIntent = mockk<PendingIntent>()
        coEvery { drive.listFolders(null) } throws NeedsConsentException()
        coEvery { authorizer.requestAuthorization() } returns pendingIntent
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }

        vm.events.test {
            vm.openDrivePicker()
            assertEquals(SettingsEvent.LaunchConsent(pendingIntent), awaitItem())
        }
        assertFalse(vm.uiState.value.picker.visible)
    }

    @Test
    fun `selecting a new drive folder saves it, clears sync state and reapplies schedule`() = runTest {
        val vm = viewModel()
        vm.selectDriveFolder(DriveFolder("new", "New"))
        assertEquals("new", settings.state.value.driveFolderId)
        coVerifyOrder {
            sessions.clear()
            synced.clear()
            scheduler.applySchedule()
        }
    }

    @Test
    fun `sign out runs the full sequence`() = runTest {
        val vm = viewModel()
        vm.signOut()

        coVerifyOrder {
            authorizer.revokeAccess()
            scheduler.cancelAll()
            sessions.clear()
            synced.clear()
            folderAccess.releasePermission(COMPLETE_SETTINGS.localFolderUri!!)
        }
        val s = settings.state.value
        assertNull(s.googleAccountEmail)
        assertNull(s.driveFolderId)
        assertNull(s.localFolderUri)
    }

    @Test
    fun `schedule switch saves the setting then applies the schedule`() = runTest {
        val vm = viewModel()
        vm.setScheduleEnabled(false)
        assertFalse(settings.state.value.scheduleEnabled)
        coVerify(exactly = 1) { scheduler.applySchedule() }
    }

    @Test
    fun `time and wifi changes apply the schedule`() = runTest {
        val vm = viewModel()
        vm.setScheduleTime(4, 30)
        vm.setWifiOnly(true)
        assertEquals(4, settings.state.value.scheduleHour)
        assertTrue(settings.state.value.wifiOnly)
        coVerify(exactly = 2) { scheduler.applySchedule() }
    }

    @Test
    fun `state exposes the local folder name`() = runTest {
        viewModel().uiState.test {
            val state = expectMostRecentItem()
            assertEquals("Backups", state.localFolderName)
            assertEquals("Signal Backup", state.driveFolderName)
            assertTrue(state.signedIn)
        }
    }
}
