package com.johnsonyuen.signalbackup.domain.model

/** Result of scanning Drive for backup items that can be moved to the trash. */
sealed interface GcScanResult {
    data object NothingToClean : GcScanResult
    /** Cleaning is unsafe right now (e.g. newest backup on Drive not verified). */
    data class Blocked(val message: String) : GcScanResult
    data class Ready(val kept: List<DriveFile>, val toTrash: List<DriveFile>) : GcScanResult
}

data class GcTrashResult(
    val trashedCount: Int,
    val freedBytes: Long,
    val failedNames: List<String>,
)
