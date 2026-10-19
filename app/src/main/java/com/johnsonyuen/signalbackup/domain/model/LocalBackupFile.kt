package com.johnsonyuen.signalbackup.domain.model

/** A regular file inside the user-selected Signal backup folder (read via SAF). */
data class LocalBackupFile(
    /** SAF document URI string. */
    val uri: String,
    /** '/'-separated path relative to the selected root, e.g. "signal-2026-10-01-03-00-00.backup". */
    val relativePath: String,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)
