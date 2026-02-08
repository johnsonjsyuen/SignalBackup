package com.johnsonyuen.signalbackup.domain.usecase

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity
import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.model.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PerformUploadUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val driveRepository: DriveRepository,
    private val uploadHistoryRepository: UploadHistoryRepository
) {
    suspend operator fun invoke(context: Context): UploadStatus = withContext(Dispatchers.IO) {
        val localFolderUri = settingsRepository.localFolderUri.first()
        val driveFolderId = settingsRepository.driveFolderId.first()

        if (localFolderUri == null || driveFolderId == null) {
            return@withContext UploadStatus.Failed("Configuration incomplete — set local folder and Drive folder")
        }

        try {
            val uri = Uri.parse(localFolderUri)
            val folder = DocumentFile.fromTreeUri(context, uri)
                ?: return@withContext UploadStatus.Failed("Cannot access local folder")

            val latestBackup = folder.listFiles()
                .filter { it.isFile && it.name?.endsWith(".backup") == true }
                .maxByOrNull { it.lastModified() }
                ?: return@withContext UploadStatus.Failed("No backup file found")

            val fileName = latestBackup.name ?: "unknown.backup"
            val fileSize = latestBackup.length()

            val inputStream = context.contentResolver.openInputStream(latestBackup.uri)
                ?: return@withContext UploadStatus.Failed("Cannot open backup file")

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
        } catch (e: UserRecoverableAuthIOException) {
            Log.w("PerformUpload", "Drive consent needed", e)
            UploadStatus.NeedsConsent(e.intent)
        } catch (e: Exception) {
            Log.e("PerformUpload", "Upload failed", e)
            val errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "no details"}"
            uploadHistoryRepository.insert(
                UploadHistoryEntity(
                    timestamp = System.currentTimeMillis(),
                    fileName = "unknown",
                    fileSizeBytes = 0,
                    status = "FAILED",
                    errorMessage = errorMsg,
                    driveFolderId = driveFolderId,
                    driveFileId = null
                )
            )
            UploadStatus.Failed(errorMsg)
        }
    }
}
