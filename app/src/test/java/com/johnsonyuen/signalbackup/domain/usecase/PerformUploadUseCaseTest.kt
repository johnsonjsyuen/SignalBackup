package com.johnsonyuen.signalbackup.domain.usecase

import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.mirror.BackupFolderScanner
import com.johnsonyuen.signalbackup.domain.mirror.FakeDocumentTreeSource
import com.johnsonyuen.signalbackup.domain.mirror.FakeDocumentTreeSource.Companion.ROOT
import com.johnsonyuen.signalbackup.domain.mirror.FakeDriveRepository
import com.johnsonyuen.signalbackup.domain.mirror.FakeSyncedFileRepository
import com.johnsonyuen.signalbackup.domain.mirror.FakeUploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.mirror.MirrorSyncer
import com.johnsonyuen.signalbackup.domain.mirror.OLD
import com.johnsonyuen.signalbackup.domain.mirror.TEST_CLOCK
import com.johnsonyuen.signalbackup.domain.mirror.TREE_URI
import com.johnsonyuen.signalbackup.domain.mirror.fakeUploader
import com.johnsonyuen.signalbackup.domain.mirror.md5Of
import com.johnsonyuen.signalbackup.domain.model.AppSettings
import com.johnsonyuen.signalbackup.domain.model.DriveHttpException
import com.johnsonyuen.signalbackup.domain.model.FailureReason
import com.johnsonyuen.signalbackup.domain.model.LocalBackupFile
import com.johnsonyuen.signalbackup.domain.model.NeedsConsentException
import com.johnsonyuen.signalbackup.domain.model.NotSignedInException
import com.johnsonyuen.signalbackup.domain.model.UploadResult
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import com.johnsonyuen.signalbackup.domain.model.WifiRequiredException
import com.johnsonyuen.signalbackup.domain.upload.ChecksumMismatchException
import com.johnsonyuen.signalbackup.domain.upload.LocalFileChangedException
import com.johnsonyuen.signalbackup.domain.upload.NetworkPolicy
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class PerformUploadUseCaseTest {
    private var settings = AppSettings(
        localFolderUri = TREE_URI,
        driveFolderId = "driveRoot",
        driveFolderName = "Signal Backup",
        scheduleEnabled = true,
        scheduleHour = 3,
        scheduleMinute = 0,
        googleAccountEmail = "user@example.com",
        themeMode = "SYSTEM",
        wifiOnly = false,
    )
    private val settingsRepo = mockk<SettingsRepository>().also {
        coEvery { it.current() } answers { settings }
    }
    private val source = FakeDocumentTreeSource()
    private val drive = FakeDriveRepository()
    private val driveRoot = drive.addFolder("myDrive", "Signal Backup").id
    private val synced = FakeSyncedFileRepository()
    private val history = FakeUploadHistoryRepository()
    private val uploader = fakeUploader(drive)
    private val network = mockk<NetworkPolicy>().also { coEvery { it.ensureUploadAllowed() } just runs }
    private val resolve = mockk<ResolveDriveFolderUseCase>().also {
        coEvery { it.invoke() } returns DriveFolderResolution.Ok(driveRoot, "Signal Backup")
    }
    private val useCase = PerformUploadUseCase(
        settingsRepo,
        history,
        network,
        resolve,
        BackupFolderScanner(source, TEST_CLOCK),
        MirrorSyncer(drive, uploader, synced, TEST_CLOCK),
        TEST_CLOCK,
    )

    private fun legacyBackup(name: String = "signal-2026-10-19-03-00-00.backup", size: Long = 300) =
        source.file(ROOT, name, size = size)

    @Test
    fun successRecordsOneRowWithDriveIdAndMd5() = runTest {
        legacyBackup()

        val result = useCase()

        assertEquals(UploadResult.Success("signal-2026-10-19-03-00-00.backup", 300L, 1), result)
        val row = history.records.single()
        assertEquals(UploadResultStatus.SUCCESS, row.status)
        assertEquals("signal-2026-10-19-03-00-00.backup", row.fileName)
        assertEquals(300L, row.fileSizeBytes)
        assertEquals(300L, row.bytesUploaded)
        assertEquals(driveRoot, row.driveFolderId)
        assertEquals(synced.map["signal-2026-10-19-03-00-00.backup"]!!.driveFileId, row.driveFileId)
        val local = LocalBackupFile("content://doc/root/signal-2026-10-19-03-00-00.backup",
            "signal-2026-10-19-03-00-00.backup", "signal-2026-10-19-03-00-00.backup", 300L, OLD)
        assertEquals(md5Of(local), row.md5Checksum)
    }

    @Test
    fun secondRunUploadsNothingButStillSucceeds() = runTest {
        legacyBackup()
        useCase()

        val result = useCase()

        assertEquals(UploadResult.Success("signal-2026-10-19-03-00-00.backup", 0L, 0), result)
        assertEquals(2, history.records.size)
    }

    @Test
    fun snapshotSuccessHasNoMd5() = runTest {
        val d = source.dir(ROOT, "signal-backup-2026-10-19-03-00-00")
        source.file(d, "backup.db", size = 10)
        source.file(d, "manifest.json", size = 1)

        val result = useCase()

        assertTrue(result is UploadResult.Success)
        val row = history.records.single()
        assertEquals(11L, row.fileSizeBytes)
        assertEquals(synced.map["signal-backup-2026-10-19-03-00-00"]!!.driveFileId, row.driveFileId)
        assertNull(row.md5Checksum)
    }

    @Test
    fun staleBackupIsNoNewBackupAfterMirroring() = runTest {
        legacyBackup("signal-2026-10-15-03-00-00.backup")

        val result = useCase()

        assertTrue(result is UploadResult.NoNewBackup)
        assertEquals("signal-2026-10-15-03-00-00.backup", (result as UploadResult.NoNewBackup).newestBackupName)
        assertEquals(UploadResultStatus.STALE, history.records.single().status)
        assertTrue(synced.map.containsKey("signal-2026-10-15-03-00-00.backup"))
    }

    @Test
    fun relocationIsNotedInHistory() = runTest {
        legacyBackup()
        coEvery { resolve.invoke() } returns DriveFolderResolution.Relocated(driveRoot, "Signal Backup", "Old")

        useCase()

        assertTrue(history.records.single().errorMessage!!.contains("recreated"))
    }

    @Test
    fun notSignedIn() = runTest {
        settings = settings.copy(googleAccountEmail = null)
        assertFailed(FailureReason.NOT_SIGNED_IN, useCase())
    }

    @Test
    fun localFolderNotSet() = runTest {
        settings = settings.copy(localFolderUri = null)
        assertFailed(FailureReason.LOCAL_FOLDER_NOT_SET, useCase())
    }

    @Test
    fun noBackupFound() = runTest {
        source.file(ROOT, "readme.txt")
        assertFailed(FailureReason.NO_BACKUP_FOUND, useCase())
    }

    @Test
    fun lostFolderPermission() = runTest {
        legacyBackup()
        source.hasPermission = false
        assertFailed(FailureReason.LOCAL_FOLDER_INACCESSIBLE, useCase())
    }

    @Test
    fun waitingForWifiIsRecorded() = runTest {
        coEvery { network.ensureUploadAllowed() } throws WifiRequiredException()
        assertEquals(UploadResult.WaitingForWifi, useCase())
        assertEquals(1, history.records.size)
    }

    @Test
    fun needsConsent() = runTest {
        coEvery { resolve.invoke() } throws NeedsConsentException()
        assertEquals(UploadResult.NeedsConsent, useCase())
        assertEquals(FailureReason.NEEDS_CONSENT, history.records.single().failureReason)
    }

    @Test
    fun uploadFailureKeepsBackupName() = runTest {
        legacyBackup()
        coEvery { uploader.upload(any(), any(), any()) } throws ChecksumMismatchException("mismatch")

        assertFailed(FailureReason.CHECKSUM_MISMATCH, useCase())
        assertEquals("signal-2026-10-19-03-00-00.backup", history.records.single().fileName)
    }

    @Test
    fun cancellationIsRethrownAndNotRecorded() = runTest {
        legacyBackup()
        coEvery { uploader.upload(any(), any(), any()) } throws CancellationException("stop")
        try {
            useCase()
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue(history.records.isEmpty())
        // The mutex was released.
        coEvery { network.ensureUploadAllowed() } throws WifiRequiredException()
        assertEquals(UploadResult.WaitingForWifi, useCase())
    }

    @Test
    fun overlappingRunGetsAlreadyRunningAndIsNotRecorded() = runTest {
        legacyBackup()
        val gate = CompletableDeferred<Unit>()
        coEvery { network.ensureUploadAllowed() } coAnswers { gate.await() }

        val first = async { useCase() }
        runCurrent()
        assertEquals(UploadResult.AlreadyRunning, useCase())
        gate.complete(Unit)

        assertTrue(first.await() is UploadResult.Success)
        assertEquals(1, history.records.size)
    }

    @Test
    fun exceptionMapping() {
        fun reason(e: Throwable) = (PerformUploadUseCase.mapFailure(e) as UploadResult.Failed).reason
        assertEquals(FailureReason.NOT_SIGNED_IN, reason(NotSignedInException()))
        assertEquals(FailureReason.DRIVE_QUOTA_EXCEEDED, reason(DriveHttpException(403, "storageQuotaExceeded", "x")))
        assertEquals(FailureReason.DRIVE_FOLDER_INACCESSIBLE, reason(DriveHttpException(404, "notFound", "x")))
        assertEquals(FailureReason.DRIVE_SERVER_ERROR, reason(DriveHttpException(503, null, "x")))
        assertEquals(FailureReason.DRIVE_SERVER_ERROR, reason(DriveHttpException(403, "rateLimitExceeded", "x")))
        assertEquals(FailureReason.UNKNOWN, reason(DriveHttpException(400, "badRequest", "x")))
        assertEquals(FailureReason.NETWORK, reason(IOException("reset")))
        assertEquals(FailureReason.CHECKSUM_MISMATCH, reason(ChecksumMismatchException("x")))
        assertEquals(FailureReason.LOCAL_READ_ERROR, reason(LocalFileChangedException("x")))
        assertEquals(FailureReason.UNKNOWN, reason(IllegalStateException("x")))
        assertEquals(UploadResult.NeedsConsent, PerformUploadUseCase.mapFailure(NeedsConsentException()))
        assertEquals(UploadResult.WaitingForWifi, PerformUploadUseCase.mapFailure(WifiRequiredException()))
        // Messages never echo exception text (which may contain URLs or bodies).
        val failed = PerformUploadUseCase.mapFailure(IOException("https://secret")) as UploadResult.Failed
        assertTrue("https" !in failed.detail)
    }

    private fun assertFailed(reason: FailureReason, result: UploadResult) {
        assertEquals(reason, (result as UploadResult.Failed).reason)
        val row = history.records.single()
        assertEquals(UploadResultStatus.FAILED, row.status)
        assertEquals(reason, row.failureReason)
    }
}
