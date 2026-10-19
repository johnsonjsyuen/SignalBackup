package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.domain.model.ChunkUploadResult
import com.johnsonyuen.signalbackup.domain.model.DriveFileMeta
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import com.johnsonyuen.signalbackup.domain.model.SessionProgress

typealias UploadProgressListener = (bytesUploaded: Long, totalBytes: Long) -> Unit

/**
 * Google Drive access using the `drive.file` scope (only items this app created or the user
 * opened with it are visible).
 *
 * Every file and folder this app creates is tagged with appProperties `{"signalBackup": "1"}`,
 * reported back as [DriveFileMeta.uploadedByApp].
 *
 * Errors: throws [com.johnsonyuen.signalbackup.domain.model.NeedsConsentException] when
 * authorization needs the user, [com.johnsonyuen.signalbackup.domain.model.NotSignedInException]
 * when no account is set, [com.johnsonyuen.signalbackup.domain.model.DriveHttpException] for
 * non-success HTTP responses, and plain IOException for network failures.
 * All methods are main-safe.
 */
interface DriveRepository {
    /** Folders directly under [parentId] (null = My Drive root), sorted by name. */
    suspend fun listFolders(parentId: String?): List<DriveFolder>

    /** Creates a tagged folder under [parentId] (null = My Drive root). */
    suspend fun createFolder(name: String, parentId: String?): DriveFolder

    /** Metadata for one item, or null if it doesn't exist, is trashed, or isn't accessible (404). */
    suspend fun getFile(fileId: String): DriveFileMeta?

    /** All non-trashed children (files and folders) of [parentId], newest first; follows paging. */
    suspend fun listChildren(parentId: String): List<DriveFileMeta>

    /** Starts a resumable upload of a tagged file; returns the session URI. */
    suspend fun initiateResumableUpload(
        parentId: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
    ): String

    /**
     * Sends bytes `data[0 until length]` as range [offset, offset+length) of [totalBytes].
     * [length] must be a multiple of 256 KiB unless it is the final chunk.
     */
    suspend fun uploadChunk(
        sessionUri: String,
        data: ByteArray,
        length: Int,
        offset: Long,
        totalBytes: Long,
    ): ChunkUploadResult

    suspend fun querySessionProgress(sessionUri: String, totalBytes: Long): SessionProgress

    /** One-request multipart upload of a small tagged file (use for files under ~5 MiB). */
    suspend fun uploadSmallFile(
        parentId: String,
        fileName: String,
        mimeType: String,
        data: ByteArray,
    ): DriveFileMeta

    /** Moves an item to Drive's trash (recoverable for 30 days). Never permanently deletes. */
    suspend fun trashFile(fileId: String)
}
