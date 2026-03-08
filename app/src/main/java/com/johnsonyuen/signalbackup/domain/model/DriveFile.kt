package com.johnsonyuen.signalbackup.domain.model

/**
 * Domain model representing a file stored on Google Drive.
 *
 * Used by the garbage collection feature to display backup files
 * and their sizes to the user for deletion confirmation.
 *
 * @param id The Google Drive file ID.
 * @param name The file display name (e.g., "signal-2024-03-08-12-00-00.backup").
 * @param sizeBytes The file size in bytes.
 */
data class DriveFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
)
