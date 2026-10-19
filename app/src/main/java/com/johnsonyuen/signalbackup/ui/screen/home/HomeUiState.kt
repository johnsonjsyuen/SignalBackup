package com.johnsonyuen.signalbackup.ui.screen.home

import android.app.PendingIntent
import com.johnsonyuen.signalbackup.domain.model.DriveFile
import com.johnsonyuen.signalbackup.domain.model.FailureReason
import com.johnsonyuen.signalbackup.domain.model.GcTrashResult
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import com.johnsonyuen.signalbackup.domain.scheduling.UploadRunState
import com.johnsonyuen.signalbackup.ui.common.UiText
import com.johnsonyuen.signalbackup.ui.consent.ConsentAction

/** Everything the Home screen renders. Immutable; produced by [HomeViewModel.uiState]. */
data class HomeUiState(
    val loading: Boolean = true,
    val accountEmail: String? = null,
    val driveFolderName: String? = null,
    val setup: SetupChecklist = SetupChecklist(),
    val run: UploadRunState = UploadRunState.Idle,
    val lastResult: LastResultUi? = null,
    val lastSuccessMillis: Long? = null,
    val nowMillis: Long = 0L,
    val nextRunMillis: Long? = null,
    val scheduleEnabled: Boolean = true,
    val authorizing: Boolean = false,
    /** A Credential Manager sign-in is in flight. */
    val signingIn: Boolean = false,
    val cleanUp: CleanUpState = CleanUpState.Hidden,
) {
    val isUploadActive: Boolean get() = run !is UploadRunState.Idle
    val canUploadNow: Boolean get() = setup.isComplete && !isUploadActive && !authorizing
    val canCleanUp: Boolean get() = setup.signedIn && setup.driveAuthorized && !isUploadActive

    /** Millis since the last successful backup, or null if there has never been one. */
    val lastSuccessAgeMillis: Long?
        get() = lastSuccessMillis?.let { (nowMillis - it).coerceAtLeast(0L) }

    /** True when the last success is older than [OVERDUE_AFTER_MS]. */
    val isBackupOverdue: Boolean
        get() = (lastSuccessAgeMillis ?: 0L) > OVERDUE_AFTER_MS

    companion object {
        const val OVERDUE_AFTER_MS = 36L * 60L * 60L * 1000L
    }
}

/** Setup steps that must be done before backups can run. */
data class SetupChecklist(
    val signedIn: Boolean = false,
    /** Drive access granted and a Drive folder resolved. */
    val driveAuthorized: Boolean = false,
    val localFolderSet: Boolean = false,
    /** Name from DocumentFile.fromTreeUri(...).name; null while unknown. */
    val localFolderName: String? = null,
    /** False once inspection shows the folder can't be read (null info counts as readable). */
    val localFolderAccessible: Boolean = true,
    /** Null while unknown. */
    val localFolderHasBackups: Boolean? = null,
) {
    val isComplete: Boolean
        get() = signedIn && driveAuthorized && localFolderSet && localFolderAccessible
}

/** The fix offered next to a failed or stale result. */
enum class ResultFix { SIGN_IN, GRANT_ACCESS, FIX_DRIVE_FOLDER, CHOOSE_FOLDER, RETRY, CLEAN_UP }

/** The most recent run outcome from history. */
data class LastResultUi(
    val status: UploadResultStatus,
    val timestampMillis: Long,
    val backupName: String?,
    val bytesUploaded: Long,
    val reason: FailureReason?,
    /** Short, user-safe detail from history. */
    val detail: String?,
    val fix: ResultFix?,
)

/** Clean Up dialog state. */
sealed interface CleanUpState {
    data object Hidden : CleanUpState
    data object Scanning : CleanUpState
    data class Blocked(val message: String) : CleanUpState
    data object NothingToClean : CleanUpState
    data class Ready(val kept: List<DriveFile>, val toTrash: List<DriveFile>) : CleanUpState {
        val trashBytes: Long get() = toTrash.sumOf { it.sizeBytes }
    }
    data class Trashing(val count: Int) : CleanUpState
    data class Done(val result: GcTrashResult) : CleanUpState
    data class Error(val message: UiText) : CleanUpState
}

/** One-shot Home events. */
sealed interface HomeEvent {
    data class LaunchConsent(val pendingIntent: PendingIntent, val action: ConsentAction) : HomeEvent
    data class ShowMessage(val text: UiText) : HomeEvent
}

/** Maps a history row to the "last result" card. */
fun UploadRecord.toLastResultUi(): LastResultUi = LastResultUi(
    status = status,
    timestampMillis = timestamp.toEpochMilli(),
    backupName = fileName,
    bytesUploaded = bytesUploaded,
    reason = failureReason,
    detail = errorMessage,
    fix = when (status) {
        UploadResultStatus.SUCCESS, UploadResultStatus.STALE -> null
        UploadResultStatus.FAILED -> fixFor(failureReason)
    },
)

/** The single fix action offered for each failure reason. */
fun fixFor(reason: FailureReason?): ResultFix = when (reason) {
    FailureReason.NOT_SIGNED_IN -> ResultFix.SIGN_IN
    FailureReason.NEEDS_CONSENT -> ResultFix.GRANT_ACCESS
    FailureReason.DRIVE_FOLDER_INACCESSIBLE -> ResultFix.FIX_DRIVE_FOLDER
    FailureReason.LOCAL_FOLDER_NOT_SET,
    FailureReason.LOCAL_FOLDER_INACCESSIBLE,
    FailureReason.NO_BACKUP_FOUND -> ResultFix.CHOOSE_FOLDER
    FailureReason.DRIVE_QUOTA_EXCEEDED -> ResultFix.CLEAN_UP
    FailureReason.NETWORK,
    FailureReason.DRIVE_SERVER_ERROR,
    FailureReason.CHECKSUM_MISMATCH,
    FailureReason.LOCAL_READ_ERROR,
    FailureReason.UNKNOWN,
    null -> ResultFix.RETRY
}
