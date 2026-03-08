package com.johnsonyuen.signalbackup.domain.model

/**
 * UI state for the garbage collection (old backup deletion) feature.
 *
 * This sealed interface represents every possible state of the GC flow:
 * Idle -> Scanning -> Confirm -> Deleting -> Done (or Error at any point).
 */
sealed interface GcUiState {
    /** No GC operation in progress. */
    data object Idle : GcUiState

    /** Scanning the Drive folder for backup files. */
    data object Scanning : GcUiState

    /** Files found -- waiting for user confirmation to delete old backups. */
    data class Confirm(
        val latestFile: DriveFile,
        val filesToDelete: List<DriveFile>,
    ) : GcUiState

    /** Deletion in progress. */
    data object Deleting : GcUiState

    /** Deletion complete -- showing results. */
    data class Done(
        val deletedCount: Int,
        val freedBytes: Long,
    ) : GcUiState

    /** An error occurred during scan or deletion. */
    data class Error(val message: String) : GcUiState

    /** No old backups found to delete. */
    data object NothingToDelete : GcUiState
}
