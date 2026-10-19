package com.johnsonyuen.signalbackup.domain.mirror

import com.johnsonyuen.signalbackup.domain.model.LocalBackupFile

/** A directory inside the selected backup folder ('/'-separated relative path). */
data class LocalDirectory(
    val relativePath: String,
    val name: String,
    val lastModifiedMillis: Long,
)

/** A top-level backup made by Signal: a legacy `.backup` file or a snapshot folder. */
data class BackupItem(
    val name: String,
    val isSnapshot: Boolean,
    /** Time parsed from the name, or null when the name has no timestamp. */
    val timestampMillis: Long?,
    val lastModifiedMillis: Long,
    /** File size, or the total size of a snapshot's eligible files. */
    val sizeBytes: Long,
) {
    /** Time used to order items: name timestamp, else last-modified. */
    val sortTimeMillis: Long get() = timestampMillis ?: lastModifiedMillis
}

/** Result of scanning the selected local folder. */
data class LocalFolderSnapshot(
    /** Files eligible for upload (skip rules applied). */
    val files: List<LocalBackupFile>,
    /** Non-hidden directories, parents before children. */
    val directories: List<LocalDirectory>,
    /** Backup items, oldest first. */
    val backupItems: List<BackupItem>,
    /** Every non-hidden path present locally, including files skipped this run. */
    val presentPaths: Set<String>,
) {
    val newestBackupItem: BackupItem? get() = backupItems.lastOrNull()
}

/** The persisted folder permission is gone or the folder no longer exists. */
class LocalFolderInaccessibleException(message: String = "Backup folder is not accessible") :
    Exception(message)

/** A subfolder of the backup folder couldn't be listed. */
class LocalReadException(message: String) : Exception(message)
