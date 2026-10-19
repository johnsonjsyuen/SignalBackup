package com.johnsonyuen.signalbackup.domain.model

/**
 * Outcome of one upload run. Produced by PerformUploadUseCase, consumed by UploadWorker.
 * Every outcome except [AlreadyRunning] is recorded in upload history by PerformUploadUseCase.
 */
sealed interface UploadResult {
    /**
     * The local backup folder is fully mirrored on Drive and verified.
     * @param backupName Name of the newest backup item (legacy .backup file or snapshot folder).
     * @param bytesUploaded Bytes actually sent during this run (0 when nothing changed).
     * @param filesUploaded Number of files uploaded during this run.
     */
    data class Success(
        val backupName: String,
        val bytesUploaded: Long,
        val filesUploaded: Int,
    ) : UploadResult

    /** Mirror pass completed, but the newest local backup is older than the staleness threshold. */
    data class NoNewBackup(
        val newestBackupName: String?,
        val newestBackupTimeMillis: Long?,
    ) : UploadResult

    data class Failed(val reason: FailureReason, val detail: String) : UploadResult {
        val retryable: Boolean get() = reason.retryable
    }

    /** Drive authorization needs user interaction (notification -> app -> consent screen). */
    data object NeedsConsent : UploadResult

    /** Wi-Fi-only is on and the device is on a metered network. */
    data object WaitingForWifi : UploadResult

    /** Another upload is already running in this process; nothing was done or recorded. */
    data object AlreadyRunning : UploadResult
}

/** Why a run failed. [retryable] decides whether WorkManager should retry with backoff. */
enum class FailureReason(val retryable: Boolean) {
    NOT_SIGNED_IN(false),
    LOCAL_FOLDER_NOT_SET(false),
    LOCAL_FOLDER_INACCESSIBLE(false),
    NO_BACKUP_FOUND(false),
    NEEDS_CONSENT(false),
    DRIVE_FOLDER_INACCESSIBLE(false),
    DRIVE_QUOTA_EXCEEDED(false),
    NETWORK(true),
    DRIVE_SERVER_ERROR(true),
    CHECKSUM_MISMATCH(true),
    LOCAL_READ_ERROR(true),
    UNKNOWN(true),
}
