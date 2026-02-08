/**
 * PerformUploadUseCase.kt - Core business logic for uploading a backup file to Google Drive.
 *
 * This use case orchestrates the entire upload flow:
 * 1. Read settings (local folder URI, Drive folder ID) from the repository.
 * 2. Use SAF (Storage Access Framework) to find the most recent .backup file.
 * 3. Open an InputStream to the file via ContentResolver.
 * 4. Upload the file to Google Drive via the DriveRepository.
 * 5. Record the result (success or failure) in the upload history database.
 *
 * The use case is invoked as a function via Kotlin's `operator fun invoke()` convention,
 * which is a common pattern for single-purpose use case classes in clean architecture.
 *
 * Error handling:
 * - [UserRecoverableAuthIOException]: The Google Drive SDK throws this when OAuth consent
 *   is needed. We catch it and return [UploadStatus.NeedsConsent] so the UI can launch
 *   the consent dialog.
 * - General exceptions: Caught, logged, recorded in history as failed, and returned as
 *   [UploadStatus.Failed].
 *
 * Threading: The entire operation runs on [Dispatchers.IO] because it involves file I/O
 * (SAF DocumentFile operations), network I/O (Drive upload), and database I/O (history insert).
 * Running on IO prevents ANR (Application Not Responding) errors.
 *
 * Architecture context:
 * - Part of the **domain layer** (domain/usecase package).
 * - Called by [HomeViewModel.uploadNow()] for manual uploads and [UploadWorker.doWork()]
 *   for scheduled background uploads.
 *
 * @see domain.model.UploadStatus for the possible return states
 * @see worker.UploadWorker for the background execution context
 * @see ui.screen.home.HomeViewModel for the manual execution context
 */
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

/**
 * Use case that finds the latest .backup file in the configured local folder
 * and uploads it to the configured Google Drive folder.
 *
 * @param settingsRepository For reading the local folder URI and Drive folder ID.
 * @param driveRepository For uploading the file to Google Drive.
 * @param uploadHistoryRepository For recording the upload result in the local database.
 */
class PerformUploadUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val driveRepository: DriveRepository,
    private val uploadHistoryRepository: UploadHistoryRepository
) {
    /**
     * Performs the upload operation.
     *
     * This is an `operator fun invoke` so it can be called as `performUploadUseCase(context)`.
     *
     * @param context Android Context needed for SAF (DocumentFile.fromTreeUri) and
     *        ContentResolver (opening file InputStreams). Uses ApplicationContext to
     *        avoid Activity lifecycle issues.
     * @return The resulting [UploadStatus] indicating success, failure, or consent needed.
     */
    suspend operator fun invoke(context: Context): UploadStatus = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting upload")

        // Step 1: Read the configured folders from settings.
        // .first() suspends until the DataStore emits its first value, then returns it.
        val localFolderUri = settingsRepository.localFolderUri.first()
        val driveFolderId = settingsRepository.driveFolderId.first()

        // Validate that both folders are configured before attempting the upload.
        if (localFolderUri == null || driveFolderId == null) {
            return@withContext UploadStatus.Failed("Configuration incomplete — set local folder and Drive folder")
        }

        try {
            // Step 2: Access the local folder via SAF (Storage Access Framework).
            // DocumentFile.fromTreeUri creates a virtual file system view of the
            // directory the user selected via the folder picker.
            val uri = Uri.parse(localFolderUri)
            val folder = DocumentFile.fromTreeUri(context, uri)
                ?: return@withContext UploadStatus.Failed("Cannot access local folder")

            // Step 3: Find the most recent .backup file by modification time.
            // Signal creates timestamped backup files, so we want the latest one.
            val latestBackup = folder.listFiles()
                .filter { it.isFile && it.name?.endsWith(".backup") == true }
                .maxByOrNull { it.lastModified() }
                ?: return@withContext UploadStatus.Failed("No backup file found")

            val fileName = latestBackup.name ?: "unknown.backup"
            val fileSize = latestBackup.length()

            // Step 4: Open an InputStream to read the file content.
            // ContentResolver is needed because SAF uses content:// URIs, not direct file paths.
            val inputStream = context.contentResolver.openInputStream(latestBackup.uri)
                ?: return@withContext UploadStatus.Failed("Cannot open backup file")

            // Step 5: Upload to Google Drive. The InputStream is consumed by this call.
            // .use {} ensures the stream is closed even if the upload throws an exception.
            val driveFileId = inputStream.use { stream ->
                driveRepository.uploadFile(driveFolderId, fileName, "application/octet-stream", stream)
            }

            // Step 6: Record the successful upload in the history database.
            uploadHistoryRepository.insert(
                UploadHistoryEntity(
                    timestamp = System.currentTimeMillis(),
                    fileName = fileName,
                    fileSizeBytes = fileSize,
                    status = UploadHistoryEntity.STATUS_SUCCESS,
                    errorMessage = null,
                    driveFolderId = driveFolderId,
                    driveFileId = driveFileId
                )
            )

            UploadStatus.Success(fileName = fileName, fileSizeBytes = fileSize)
        } catch (e: UserRecoverableAuthIOException) {
            // The Drive SDK throws this when the OAuth token is expired or the
            // DRIVE_FILE scope hasn't been granted. Return NeedsConsent so the UI
            // can launch the consent Intent for the user to approve.
            Log.w(TAG, "Drive consent needed", e)
            UploadStatus.NeedsConsent(e.intent)
        } catch (e: Exception) {
            // Any other error (network failure, Drive quota exceeded, etc.)
            Log.e(TAG, "Upload failed", e)
            val errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "no details"}"

            // Record the failed upload in history for the user to see.
            uploadHistoryRepository.insert(
                UploadHistoryEntity(
                    timestamp = System.currentTimeMillis(),
                    fileName = "unknown",
                    fileSizeBytes = 0,
                    status = UploadHistoryEntity.STATUS_FAILED,
                    errorMessage = errorMsg,
                    driveFolderId = driveFolderId,
                    driveFileId = null
                )
            )
            UploadStatus.Failed(errorMsg)
        }
    }

    companion object {
        private const val TAG = "PerformUpload"
    }
}
