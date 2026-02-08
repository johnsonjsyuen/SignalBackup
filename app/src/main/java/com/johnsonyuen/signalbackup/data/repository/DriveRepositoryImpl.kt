/**
 * DriveRepositoryImpl.kt - Implementation of [DriveRepository] using Google Drive API.
 *
 * This class implements the [DriveRepository] interface by delegating all operations
 * to [GoogleDriveService]. Like [SettingsRepositoryImpl], it is a thin wrapper that
 * provides the abstraction layer needed for clean architecture.
 *
 * Architecture context:
 * - Part of the **data layer** (data/repository package).
 * - Uses [@Inject] constructor for Hilt dependency injection.
 * - Bound to [DriveRepository] interface via @Binds in [RepositoryModule].
 * - Singleton scope (one instance shared across the entire app).
 *
 * @see data.repository.DriveRepository for the interface contract
 * @see data.remote.GoogleDriveService for the actual Google Drive API wrapper
 * @see di.RepositoryModule for the Hilt binding
 */
package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.remote.GoogleDriveService
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import java.io.InputStream
import javax.inject.Inject

/**
 * Concrete implementation of [DriveRepository] that delegates to [GoogleDriveService].
 *
 * @param driveService The Google Drive API wrapper that handles actual network calls.
 */
class DriveRepositoryImpl @Inject constructor(
    private val driveService: GoogleDriveService,
) : DriveRepository {

    /** Delegates single-process file upload to [GoogleDriveService.uploadFile]. Currently unused. */
    override suspend fun uploadFile(
        folderId: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream,
        fileSize: Long,
        progressListener: UploadProgressListener?,
    ): String {
        return driveService.uploadFile(folderId, fileName, mimeType, inputStream, fileSize, progressListener)
    }

    /** Delegates folder listing to [GoogleDriveService.listFolders]. */
    override suspend fun listFolders(parentId: String?): List<DriveFolder> {
        return driveService.listFolders(parentId)
    }

    /** Delegates folder creation to [GoogleDriveService.createFolder]. */
    override suspend fun createFolder(name: String, parentId: String?): DriveFolder {
        return driveService.createFolder(name, parentId)
    }

    /** Delegates resumable upload initiation to [GoogleDriveService.initiateResumableUpload]. */
    override suspend fun initiateResumableUpload(
        folderId: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
    ): String {
        return driveService.initiateResumableUpload(folderId, fileName, mimeType, totalBytes)
    }

    /** Delegates chunk upload to [GoogleDriveService.uploadChunk]. */
    override suspend fun uploadChunk(
        sessionUri: String,
        chunkData: ByteArray,
        offset: Long,
        totalBytes: Long,
    ): GoogleDriveService.ChunkUploadResult {
        return driveService.uploadChunk(sessionUri, chunkData, offset, totalBytes)
    }

    /** Delegates session progress query to [GoogleDriveService.querySessionProgress]. */
    override suspend fun querySessionProgress(
        sessionUri: String,
        totalBytes: Long,
    ): Long {
        return driveService.querySessionProgress(sessionUri, totalBytes)
    }
}
