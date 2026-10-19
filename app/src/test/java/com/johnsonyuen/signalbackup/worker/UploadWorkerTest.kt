package com.johnsonyuen.signalbackup.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.model.FailureReason
import com.johnsonyuen.signalbackup.domain.model.MirrorProgressListener
import com.johnsonyuen.signalbackup.domain.model.UploadProgress
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadResult
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import com.johnsonyuen.signalbackup.domain.scheduling.UploadProgressTracker
import com.johnsonyuen.signalbackup.domain.scheduling.UploadScheduler
import com.johnsonyuen.signalbackup.domain.usecase.PerformUploadUseCase
import com.johnsonyuen.signalbackup.notification.BackupNotifier
import com.johnsonyuen.signalbackup.notification.UploadProgressNotifications
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UploadWorkerTest {

    private val now = Instant.parse("2026-10-19T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var context: Context
    private val performUpload = mockk<PerformUploadUseCase>()
    private val history = mockk<UploadHistoryRepository>()
    private val scheduler = mockk<UploadScheduler>(relaxed = true)
    private val tracker = UploadProgressTracker()
    private val notifier = FakeNotifier()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        setLastSuccess(now.minus(Duration.ofHours(10)))
    }

    private fun setLastSuccess(time: Instant?) {
        val record = time?.let {
            UploadRecord(
                id = 1, timestamp = it, fileName = "b", fileSizeBytes = 1, bytesUploaded = 1,
                status = UploadResultStatus.SUCCESS, failureReason = null, errorMessage = null,
                driveFolderId = null, driveFileId = null, md5Checksum = null,
            )
        }
        every { history.getLatestSuccess() } returns flowOf(record)
    }

    private suspend fun run(
        result: UploadResult,
        attempt: Int = 0,
        manual: Boolean = false,
    ): ListenableWorker.Result {
        coEvery { performUpload(any()) } returns result
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = UploadWorker(
                appContext, workerParameters, performUpload, tracker, notifier,
                UploadProgressNotifications(appContext), history, scheduler, clock,
            )
        }
        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(factory)
            .setRunAttemptCount(attempt)
            .setInputData(workDataOf(UploadWorker.KEY_MANUAL to manual))
            .build()
        return worker.doWork()
    }

    @Test
    fun success_clearsProblemsAndRepinsSchedule() = runTest {
        val result = run(UploadResult.Success("signal-backup-1", 10, 1))
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, notifier.cleared)
        coVerify(exactly = 1) { scheduler.onScheduledRunFinished() }
    }

    @Test
    fun manualSuccess_doesNotRepinSchedule() = runTest {
        run(UploadResult.Success("signal-backup-1", 10, 1), manual = true)
        coVerify(exactly = 0) { scheduler.onScheduledRunFinished() }
    }

    @Test
    fun noNewBackup_notifiesStaleAndSucceeds() = runTest {
        val result = run(UploadResult.NoNewBackup("old", 42L))
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf<Long?>(42L), notifier.staleNewest)
    }

    @Test
    fun needsConsent_notifiesAndFails() = runTest {
        val result = run(UploadResult.NeedsConsent)
        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(1, notifier.consent)
    }

    @Test
    fun waitingForWifi_retries() = runTest {
        assertEquals(ListenableWorker.Result.retry(), run(UploadResult.WaitingForWifi))
    }

    @Test
    fun alreadyRunning_succeedsQuietly() = runTest {
        assertEquals(ListenableWorker.Result.success(), run(UploadResult.AlreadyRunning))
        assertTrue(notifier.failures.isEmpty())
        assertEquals(0, notifier.cleared)
    }

    @Test
    fun retryableFailure_retriesWithoutNotifying() = runTest {
        val result = run(UploadResult.Failed(FailureReason.NETWORK, "timeout"), attempt = 3)
        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue(notifier.failures.isEmpty())
    }

    @Test
    fun retryableFailure_lastAttempt_notifiesAndFails() = runTest {
        val result = run(UploadResult.Failed(FailureReason.NETWORK, "timeout"), attempt = 4)
        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(listOf(FailureReason.NETWORK), notifier.failures)
    }

    @Test
    fun nonRetryableFailure_notifiesAndFails() = runTest {
        val result = run(UploadResult.Failed(FailureReason.LOCAL_FOLDER_INACCESSIBLE, "gone"))
        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(listOf(FailureReason.LOCAL_FOLDER_INACCESSIBLE), notifier.failures)
        assertTrue(notifier.staleLastSuccess.isEmpty())
    }

    @Test
    fun failure_withOldLastSuccess_alsoNotifiesStale() = runTest {
        val lastSuccess = now.minus(Duration.ofHours(40))
        setLastSuccess(lastSuccess)
        run(UploadResult.Failed(FailureReason.DRIVE_QUOTA_EXCEEDED, "full"))
        assertEquals(listOf<Long?>(lastSuccess.toEpochMilli()), notifier.staleLastSuccess)
    }

    @Test
    fun progress_updatesTrackerAndIsClearedAfterRun() = runTest {
        var seen: UploadProgress? = null
        coEvery { performUpload(any()) } answers {
            val listener = firstArg<MirrorProgressListener>()
            listener.onProgress(50, 100, "backup.db")
            seen = tracker.progress.value
            UploadResult.Success("signal-backup-1", 100, 1)
        }
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = UploadWorker(
                appContext, workerParameters, performUpload, tracker, notifier,
                UploadProgressNotifications(appContext), history, scheduler, clock,
            )
        }
        val worker = TestListenableWorkerBuilder<UploadWorker>(context).setWorkerFactory(factory).build()
        worker.doWork()
        assertEquals(50L, seen?.bytesUploaded)
        assertEquals("backup.db", seen?.currentFileName)
        assertNull(tracker.progress.value)
    }

    private class FakeNotifier : BackupNotifier {
        val failures = mutableListOf<FailureReason>()
        var consent = 0
        var cleared = 0
        val staleLastSuccess = mutableListOf<Long?>()
        val staleNewest = mutableListOf<Long?>()

        override fun notifyFailure(reason: FailureReason, detail: String) {
            failures += reason
        }
        override fun notifyNeedsConsent() {
            consent++
        }
        override fun notifyStale(lastSuccessMillis: Long?, newestBackupTimeMillis: Long?) {
            if (newestBackupTimeMillis != null) staleNewest += newestBackupTimeMillis
            else staleLastSuccess += lastSuccessMillis
        }
        override fun clearProblemNotifications() {
            cleared++
        }
    }
}
