package com.johnsonyuen.signalbackup.domain.usecase

import android.util.Log
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.mirror.BackupFolderScanner
import com.johnsonyuen.signalbackup.domain.mirror.LocalFolderInaccessibleException
import com.johnsonyuen.signalbackup.domain.mirror.LocalReadException
import com.johnsonyuen.signalbackup.domain.mirror.MirrorStats
import com.johnsonyuen.signalbackup.domain.mirror.MirrorSyncer
import com.johnsonyuen.signalbackup.domain.mirror.Staleness
import com.johnsonyuen.signalbackup.domain.model.DriveHttpException
import com.johnsonyuen.signalbackup.domain.model.FailureReason
import com.johnsonyuen.signalbackup.domain.model.MirrorProgressListener
import com.johnsonyuen.signalbackup.domain.model.NeedsConsentException
import com.johnsonyuen.signalbackup.domain.model.NewUploadRecord
import com.johnsonyuen.signalbackup.domain.model.NotSignedInException
import com.johnsonyuen.signalbackup.domain.model.UploadResult
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import com.johnsonyuen.signalbackup.domain.model.WifiRequiredException
import com.johnsonyuen.signalbackup.domain.upload.ChecksumMismatchException
import com.johnsonyuen.signalbackup.domain.upload.FileUploader
import com.johnsonyuen.signalbackup.domain.upload.LocalFileChangedException
import com.johnsonyuen.signalbackup.domain.upload.NetworkPolicy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * One upload run: check network, resolve the Drive folder, scan the local Signal backup folder,
 * mirror it to Drive, then check staleness. Records exactly one history row per run.
 */
@Singleton
class PerformUploadUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val uploadHistoryRepository: UploadHistoryRepository,
    private val networkPolicy: NetworkPolicy,
    private val resolveDriveFolder: ResolveDriveFolderUseCase,
    private val scanner: BackupFolderScanner,
    private val mirrorSyncer: MirrorSyncer,
    private val clock: Clock,
) {

    suspend operator fun invoke(progress: MirrorProgressListener? = null): UploadResult {
        if (!runMutex.tryLock()) return UploadResult.AlreadyRunning
        try {
            val state = RunState()
            val result = try {
                execute(state, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mapFailure(e)
            }
            withContext(NonCancellable) { record(result, state) }
            return result
        } finally {
            runMutex.unlock()
        }
    }

    private class RunState {
        var backupName: String? = null
        var backupSizeBytes: Long = 0L
        var driveFolderId: String? = null
        var driveFileId: String? = null
        var md5: String? = null
        var note: String? = null
        val stats = MirrorStats()
    }

    private suspend fun execute(state: RunState, progress: MirrorProgressListener?): UploadResult {
        val settings = settingsRepository.current()
        state.driveFolderId = settings.driveFolderId
        if (settings.googleAccountEmail == null) {
            return UploadResult.Failed(FailureReason.NOT_SIGNED_IN, MSG_NOT_SIGNED_IN)
        }
        val localFolderUri = settings.localFolderUri
            ?: return UploadResult.Failed(FailureReason.LOCAL_FOLDER_NOT_SET, "Signal backup folder not selected")

        networkPolicy.ensureUploadAllowed()

        val folder = resolveDriveFolder()
        state.driveFolderId = folder.folderId
        if (folder is DriveFolderResolution.Relocated) {
            state.note = "Drive folder was recreated as \"${folder.folderName}\""
        }

        val snapshot = scanner.scan(localFolderUri)
        val newest = snapshot.newestBackupItem
            ?: return UploadResult.Failed(
                FailureReason.NO_BACKUP_FOUND,
                "No Signal backup found in the selected folder",
            )
        state.backupName = newest.name
        state.backupSizeBytes = newest.sizeBytes

        val outcome = mirrorSyncer.sync(snapshot, folder.folderId, progress, state.stats)
        val newestRecord = outcome.records[newest.name]
        state.driveFileId = newestRecord?.driveFileId
        state.md5 = if (newest.isSnapshot) null else newestRecord?.md5Checksum

        return if (Staleness.isStale(newest, clock.millis())) {
            UploadResult.NoNewBackup(newest.name, newest.sortTimeMillis)
        } else {
            UploadResult.Success(newest.name, outcome.bytesUploaded, outcome.filesUploaded)
        }
    }

    private suspend fun record(result: UploadResult, state: RunState) {
        val status: UploadResultStatus
        val reason: FailureReason?
        val message: String?
        when (result) {
            is UploadResult.Success -> {
                status = UploadResultStatus.SUCCESS; reason = null; message = state.note
            }
            is UploadResult.NoNewBackup -> {
                status = UploadResultStatus.STALE; reason = null
                message = join("No new Signal backup in the last 48 hours", state.note)
            }
            is UploadResult.Failed -> {
                status = UploadResultStatus.FAILED; reason = result.reason
                message = join(result.detail, state.note)
            }
            UploadResult.NeedsConsent -> {
                status = UploadResultStatus.FAILED; reason = FailureReason.NEEDS_CONSENT
                message = "Google Drive access needs to be granted again"
            }
            UploadResult.WaitingForWifi -> {
                status = UploadResultStatus.FAILED; reason = FailureReason.NETWORK
                message = "Waiting for Wi-Fi"
            }
            UploadResult.AlreadyRunning -> return
        }
        val success = status != UploadResultStatus.FAILED
        try {
            uploadHistoryRepository.record(
                NewUploadRecord(
                    timestamp = Instant.now(clock),
                    fileName = state.backupName,
                    fileSizeBytes = state.backupSizeBytes,
                    bytesUploaded = state.stats.bytesUploaded,
                    status = status,
                    failureReason = reason,
                    errorMessage = message,
                    driveFolderId = state.driveFolderId,
                    driveFileId = if (success) state.driveFileId else null,
                    md5Checksum = if (success) state.md5 else null,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't record upload history: ${e.javaClass.simpleName}")
        }
    }

    private fun join(a: String, b: String?): String = if (b == null) a else "$a. $b"

    companion object {
        private const val TAG = "PerformUpload"
        private const val MSG_NOT_SIGNED_IN = "Not signed in to Google"

        /** Process-wide: only one upload run at a time. */
        private val runMutex = Mutex()

        /** Maps an exception from a run to its result, with a short user-safe message. */
        internal fun mapFailure(e: Throwable): UploadResult = when (e) {
            is WifiRequiredException -> UploadResult.WaitingForWifi
            is NeedsConsentException -> UploadResult.NeedsConsent
            is NotSignedInException -> UploadResult.Failed(FailureReason.NOT_SIGNED_IN, MSG_NOT_SIGNED_IN)
            is LocalFolderInaccessibleException, is SecurityException -> UploadResult.Failed(
                FailureReason.LOCAL_FOLDER_INACCESSIBLE,
                "Can't access the Signal backup folder. Select it again in Settings",
            )
            is LocalReadException -> UploadResult.Failed(FailureReason.LOCAL_READ_ERROR, "Couldn't read the backup folder")
            is ChecksumMismatchException -> UploadResult.Failed(
                FailureReason.CHECKSUM_MISMATCH,
                "Uploaded file didn't match the local file",
            )
            is LocalFileChangedException -> UploadResult.Failed(
                FailureReason.LOCAL_READ_ERROR,
                "A backup file changed while uploading",
            )
            is FileNotFoundException -> UploadResult.Failed(FailureReason.LOCAL_READ_ERROR, "Couldn't read a backup file")
            is DriveHttpException -> when {
                e.isQuotaExceeded -> UploadResult.Failed(FailureReason.DRIVE_QUOTA_EXCEEDED, "Google Drive storage is full")
                e.statusCode == 404 -> UploadResult.Failed(
                    FailureReason.DRIVE_FOLDER_INACCESSIBLE,
                    "Drive backup folder is not accessible",
                )
                e.isRetryable -> UploadResult.Failed(
                    FailureReason.DRIVE_SERVER_ERROR,
                    "Google Drive error (HTTP ${e.statusCode})",
                )
                else -> UploadResult.Failed(FailureReason.UNKNOWN, "Google Drive error (HTTP ${e.statusCode})")
            }
            is IOException -> UploadResult.Failed(FailureReason.NETWORK, "Network error")
            else -> UploadResult.Failed(FailureReason.UNKNOWN, "Unexpected error (${e.javaClass.simpleName})")
        }
    }
}
