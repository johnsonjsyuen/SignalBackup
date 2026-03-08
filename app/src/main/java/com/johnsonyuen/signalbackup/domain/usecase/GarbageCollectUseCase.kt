package com.johnsonyuen.signalbackup.domain.usecase

import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.DriveFile
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Use case for garbage collecting (deleting) old backup files from Google Drive.
 *
 * This use case provides two operations:
 * 1. [scanOldBackups] -- Lists all files in the configured Drive folder and identifies
 *    which are old (all except the most recent by creation time).
 * 2. [deleteFiles] -- Deletes a list of files by their Drive file IDs.
 *
 * The "latest" file is determined by Google Drive's `createdTime` ordering (the Drive
 * API returns files sorted by createdTime desc, so the first file is the newest).
 */
class GarbageCollectUseCase @Inject constructor(
    private val driveRepository: DriveRepository,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Result of scanning the Drive folder for old backups.
     *
     * @param latestFile The most recent backup file (will be kept).
     * @param oldFiles The older backup files (candidates for deletion).
     */
    data class ScanResult(
        val latestFile: DriveFile,
        val oldFiles: List<DriveFile>,
    )

    /**
     * Scans the configured Drive folder for backup files and identifies old ones.
     *
     * @return A [ScanResult] with the latest file and the old files, or null if there
     *         are 0 or 1 files (nothing to delete).
     * @throws IllegalStateException if no Drive folder is configured.
     */
    suspend fun scanOldBackups(): ScanResult? {
        val folderId = settingsRepository.driveFolderId.first()
            ?: throw IllegalStateException("No Drive folder configured")

        val files = driveRepository.listFiles(folderId)
            .filter { it.name.startsWith("signal-") && it.name.endsWith(".backup") }
            .map {
                DriveFile(
                    id = it.id,
                    name = it.name,
                    sizeBytes = it.sizeBytes ?: 0L,
                )
            }

        if (files.size <= 1) return null

        return ScanResult(
            latestFile = files.first(),
            oldFiles = files.drop(1),
        )
    }

    /**
     * Deletes files from Google Drive by their file IDs.
     *
     * @param fileIds The list of Drive file IDs to delete.
     */
    suspend fun deleteFiles(fileIds: List<String>) {
        for (id in fileIds) {
            driveRepository.deleteFile(id)
        }
    }
}
