/**
 * DriveRepository.kt - Repository interface for Google Drive operations.
 *
 * This interface defines the contract for interacting with Google Drive. It abstracts
 * the Google Drive API behind a clean interface following the Repository pattern.
 *
 * Architecture context:
 * - Part of the **data layer** (data/repository package), defining the boundary
 *   between data sources and consumers (use cases, ViewModels).
 * - Implemented by [DriveRepositoryImpl], which delegates to [GoogleDriveService].
 * - Bound to its implementation by Hilt in [RepositoryModule] via @Binds.
 * - Consumed by [PerformUploadUseCase] (for uploads) and [SettingsViewModel] (for
 *   folder browsing and creation).
 *
 * Why not use GoogleDriveService directly?
 * - **Testability**: Use cases can be tested with a fake/mock DriveRepository.
 * - **Abstraction**: If we switch from Google Drive to another cloud provider,
 *   only the implementation changes.
 *
 * @see data.repository.DriveRepositoryImpl for the implementation
 * @see data.remote.GoogleDriveService for the actual Drive API calls
 * @see di.RepositoryModule for the Hilt binding
 */
package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.remote.GoogleDriveService
import com.johnsonyuen.signalbackup.domain.model.DriveFolder

/**
 * Callback invoked by the upload layer to report progress.
 *
 * @param bytesUploaded Number of bytes uploaded so far.
 * @param totalBytes Total file size in bytes.
 */
typealias UploadProgressListener = (bytesUploaded: Long, totalBytes: Long) -> Unit

/**
 * Contract for Google Drive file and folder operations.
 */
interface DriveRepository {

    /**
     * Lists subfolders in a Drive folder.
     *
     * @param parentId The parent folder ID, or null for root.
     * @return List of [DriveFolder] objects sorted by name.
     */
    suspend fun listFolders(parentId: String?): List<DriveFolder>

    /**
     * Creates a new folder in Drive.
     *
     * @param name The folder display name.
     * @param parentId The parent folder ID, or null for root.
     * @return The newly created [DriveFolder].
     */
    suspend fun createFolder(name: String, parentId: String?): DriveFolder

    // ---- Resumable upload protocol methods ----

    /**
     * Initiates a resumable upload session and returns the session URI.
     *
     * @param folderId The Drive folder ID to upload into.
     * @param fileName The display name for the file in Drive.
     * @param mimeType The MIME type of the file content.
     * @param totalBytes The total file size in bytes.
     * @return The resumable session URI.
     */
    suspend fun initiateResumableUpload(
        folderId: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
    ): String

    /**
     * Uploads a chunk of data to a resumable session.
     *
     * @param sessionUri The resumable session URI.
     * @param chunkData The raw bytes of this chunk.
     * @param offset The starting byte offset of this chunk.
     * @param totalBytes The total file size in bytes.
     * @return A [GoogleDriveService.ChunkUploadResult] indicating progress or completion.
     */
    suspend fun uploadChunk(
        sessionUri: String,
        chunkData: ByteArray,
        offset: Long,
        totalBytes: Long,
    ): GoogleDriveService.ChunkUploadResult

    /**
     * Queries the server for the progress of a resumable session.
     *
     * @param sessionUri The resumable session URI.
     * @param totalBytes The total file size in bytes.
     * @return A [GoogleDriveService.SessionProgressResult] indicating progress, completion, or expiry.
     */
    suspend fun querySessionProgress(
        sessionUri: String,
        totalBytes: Long,
    ): GoogleDriveService.SessionProgressResult

    /**
     * Searches for a file by name within a specific Drive folder.
     *
     * @param folderId The Drive folder ID to search in.
     * @param fileName The file name to search for.
     * @return A [GoogleDriveService.DriveFileInfo] with the file's metadata, or null if not found.
     */
    suspend fun findFileByName(
        folderId: String,
        fileName: String,
    ): GoogleDriveService.DriveFileInfo?

}
