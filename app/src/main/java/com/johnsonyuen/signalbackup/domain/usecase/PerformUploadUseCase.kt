package com.johnsonyuen.signalbackup.domain.usecase

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity
import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.model.UploadStatus
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class PerformUploadUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val driveRepository: DriveRepository,
    private val uploadHistoryRepository: UploadHistoryRepository
) {
    suspend operator fun invoke(context: Context): UploadStatus {
        val localFolderUri = settingsRepository.localFolderUri.first()
        val driveFolderId = settingsRepository.driveFolderId.first()

        if (localFolderUri == null || driveFolderId == null) {
            return UploadStatus.Failed("Configuration incomplete — set local folder and Drive folder")
        }

        return try {
            val uri = Uri.parse(localFolderUri)
            val folder = DocumentFile.fromTreeUri(context, uri)
                ?: return UploadStatus.Failed("Cannot access local folder")

            val latestBackup = folder.listFiles()
                .filter { it.isFile && it.name?.endsWith(".backup") == true }
                .maxByOrNull { it.lastModified() }
                ?: return UploadStatus.Failed("No backup file found")

            val fileName = latestBackup.name ?: "unknown.backup"
            val fileSize = latestBackup.length()

            val inputStream = context.contentResolver.openInputStream(latestBackup.uri)
                ?: return UploadStatus.Failed("Cannot open backup file")

            val driveFileId = inputStream.use { stream ->
                driveRepository.uploadFile(driveFolderId, fileName, "application/octet-stream", stream)
            }

            uploadHistoryRepository.insert(
                UploadHistoryEntity(
                    timestamp = System.currentTimeMillis(),
                    fileName = fileName,
                    fileSizeBytes = fileSize,
                    status = "SUCCESS",
                    errorMessage = null,
                    driveFolderId = driveFolderId,
                    driveFileId = driveFileId
                )
            )

            UploadStatus.Success(fileName = fileName, fileSizeBytes = fileSize)
        } catch (e: Exception) {
            val driveFolderIdForRecord = driveFolderId ?: ""
            uploadHistoryRepository.insert(
                UploadHistoryEntity(
                    timestamp = System.currentTimeMillis(),
                    fileName = "unknown",
                    fileSizeBytes = 0,
                    status = "FAILED",
                    errorMessage = e.message,
                    driveFolderId = driveFolderIdForRecord,
                    driveFileId = null
                )
            )
            UploadStatus.Failed(e.message ?: "Unknown error")
        }
    }
}
