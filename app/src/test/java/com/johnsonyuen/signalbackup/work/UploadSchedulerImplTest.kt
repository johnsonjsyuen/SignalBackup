package com.johnsonyuen.signalbackup.work

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.AppSettings
import com.johnsonyuen.signalbackup.domain.scheduling.UploadProgressTracker
import com.johnsonyuen.signalbackup.domain.scheduling.UploadRunState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UploadSchedulerImplTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: UploadSchedulerImpl

    // 01:00 EST on 2026-01-15.
    private val clock = Clock.fixed(Instant.parse("2026-01-15T06:00:00Z"), ZoneId.of("America/New_York"))
    private val settingsRepository = mockk<SettingsRepository>()
    private var settings = AppSettings(
        localFolderUri = "content://folder",
        driveFolderId = "drive",
        driveFolderName = "Signal Backup",
        scheduleEnabled = true,
        scheduleHour = 3,
        scheduleMinute = 0,
        googleAccountEmail = "user@example.com",
        themeMode = "system",
        wifiOnly = true,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        coEvery { settingsRepository.current() } answers { settings }
        scheduler = UploadSchedulerImpl(workManager, settingsRepository, UploadProgressTracker(), clock)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun periodic(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(UploadSchedulerImpl.PERIODIC_WORK_NAME).get()
            .filter { !it.state.isFinished }

    @Test
    fun applySchedule_enqueuesPeriodicWorkPinnedToChosenTime() = runTest {
        scheduler.applySchedule()

        val work = periodic().single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertTrue(work.tags.contains("schedule:03:00@America/New_York"))
        assertEquals(Instant.parse("2026-01-15T08:00:00Z").toEpochMilli(), work.nextScheduleTimeMillis)
        assertEquals(
            Instant.parse("2026-01-15T08:00:00Z").toEpochMilli(),
            scheduler.observeNextScheduledRunMillis().first(),
        )
    }

    @Test
    fun applySchedule_sameSettings_updatesExistingWork() = runTest {
        scheduler.applySchedule()
        val first = periodic().single().id
        settings = settings.copy(wifiOnly = false)

        scheduler.applySchedule()

        assertEquals(first, periodic().single().id)
    }

    @Test
    fun applySchedule_timeChanged_reenqueuesAtNewTime() = runTest {
        scheduler.applySchedule()
        val first = periodic().single().id
        settings = settings.copy(scheduleHour = 5)

        scheduler.applySchedule()

        val work = periodic().single()
        assertNotEquals(first, work.id)
        assertEquals(Instant.parse("2026-01-15T10:00:00Z").toEpochMilli(), work.nextScheduleTimeMillis)
    }

    @Test
    fun applySchedule_disabled_cancelsPeriodicWork() = runTest {
        scheduler.applySchedule()
        settings = settings.copy(scheduleEnabled = false)

        scheduler.applySchedule()

        assertTrue(periodic().isEmpty())
    }

    @Test
    fun applySchedule_setupIncomplete_schedulesNothing() = runTest {
        settings = settings.copy(localFolderUri = null)

        scheduler.applySchedule()

        assertTrue(periodic().isEmpty())
        assertEquals(null, scheduler.observeNextScheduledRunMillis().first())
    }

    @Test
    fun enqueueManualUpload_keepsExistingRequest() = runTest {
        assertEquals(UploadRunState.Idle, scheduler.observeRunState().first())

        scheduler.enqueueManualUpload()
        scheduler.enqueueManualUpload()

        val manual = workManager.getWorkInfosForUniqueWork(UploadSchedulerImpl.MANUAL_WORK_NAME).get()
        assertEquals(1, manual.size)
        assertEquals(WorkInfo.State.ENQUEUED, manual.single().state)
        assertTrue(manual.single().tags.contains(UploadSchedulerImpl.MANUAL_TAG))
        assertEquals(UploadRunState.Queued(manual = true), scheduler.observeRunState().first())
    }

    @Test
    fun cancelAll_removesScheduleAndManualWork() = runTest {
        scheduler.applySchedule()
        scheduler.enqueueManualUpload()

        scheduler.cancelAll()

        assertTrue(periodic().isEmpty())
        val manual = workManager.getWorkInfosForUniqueWork(UploadSchedulerImpl.MANUAL_WORK_NAME).get()
        assertTrue(manual.all { it.state == WorkInfo.State.CANCELLED })
    }
}
