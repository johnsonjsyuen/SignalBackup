/**
 * GoogleDriveService.kt - Google Drive API wrapper for file and folder operations.
 *
 * This class encapsulates all direct interactions with the Google Drive REST API v3.
 * It provides folder operations (list and create) and a manual resumable upload protocol
 * (initiate, upload chunk, query progress) for cross-process upload resumption.
 *
 * Architecture context:
 * - Part of the **data layer** (data/remote package) -- this is the "remote data source."
 * - Used exclusively by [DriveRepositoryImpl], which wraps it behind the [DriveRepository]
 *   interface for clean architecture separation.
 * - Depends on [GoogleAccountCredential] (provided by Hilt via DriveModule) for OAuth2
 *   authentication, and [SettingsRepository] to read the signed-in account email.
 *
 * Authentication pattern:
 * - Before every API call, [ensureAccount] reads the stored email from DataStore and sets
 *   it on the [GoogleAccountCredential]. This is necessary because GoogleAccountCredential
 *   is a singleton that may not yet know which account to authenticate as.
 * - If no email is stored, it throws IllegalStateException -- callers must ensure the user
 *   has signed in before attempting Drive operations.
 * - If the user's OAuth token has expired or the required scope hasn't been granted,
 *   the Drive API throws UserRecoverableAuthIOException, which is caught upstream in
 *   [PerformUploadUseCase] and surfaces as [UploadStatus.NeedsConsent].
 *
 * Threading: All API methods use `withContext(Dispatchers.IO)` because Google's Drive
 * client library makes synchronous HTTP calls that would block the main thread.
 *
 * @see data.repository.DriveRepository for the clean interface consumed by use cases
 * @see di.DriveModule for how GoogleAccountCredential is provided
 * @see domain.usecase.PerformUploadUseCase for the upload flow that calls this service
 */
package com.johnsonyuen.signalbackup.data.remote

import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.http.json.JsonHttpContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import javax.inject.Inject

/**
 * Service class that wraps the Google Drive API v3 client.
 *
 * Injected by Hilt with a [GoogleAccountCredential] for OAuth2 and [SettingsRepository]
 * for reading the signed-in account. All methods are suspend functions that run on
 * [Dispatchers.IO] to avoid blocking the main thread.
 *
 * @param credential Google OAuth2 credential object. Its `selectedAccountName` is set
 *        dynamically before each API call via [ensureAccount].
 * @param settingsRepository Used to read the stored Google account email.
 */
class GoogleDriveService @Inject constructor(
    private val credential: GoogleAccountCredential,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Lazily initialized Google Drive API client.
     *
     * We use `by lazy` to defer construction until the first API call, because the
     * Drive client requires the credential to be configured. The Drive.Builder pattern
     * is Google's standard way to construct API clients:
     * - [NetHttpTransport]: HTTP layer for making network requests.
     * - [GsonFactory]: JSON parser for serializing/deserializing API responses.
     * - [credential]: OAuth2 credential that attaches the access token to each request.
     *
     * The credential is wrapped in a [HttpRequestInitializer] that also sets connect and
     * read timeouts on every HTTP request. This prevents indefinite hangs on stalled
     * connections (Java's default socket timeout is 0 / infinite).
     */
    private val drive: Drive by lazy {
        // Wrap the credential initializer to also set timeouts on every request.
        val timeoutInitializer = HttpRequestInitializer { request ->
            credential.initialize(request)
            request.connectTimeout = HTTP_TIMEOUT_MS
            request.readTimeout = HTTP_TIMEOUT_MS
        }

        Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            timeoutInitializer,
        )
            .setApplicationName("SignalBackup")
            .build()
    }

    /**
     * Ensures the credential has a valid account name set before making API calls.
     *
     * GoogleAccountCredential requires `selectedAccountName` to be set so it knows
     * which Google account's OAuth token to use. We read the email from DataStore
     * (where it was saved during sign-in) and set it on the credential.
     *
     * This must be called before every API operation because the credential is a
     * shared singleton -- if the user signs out and signs in with a different account,
     * we need to pick up the new email.
     *
     * @throws IllegalStateException if no Google account email is stored (user not signed in).
     */
    private suspend fun ensureAccount() {
        val email = settingsRepository.googleAccountEmail.first()
            ?: throw IllegalStateException("No Google account configured. Please sign in first.")
        credential.selectedAccountName = email
    }

    /**
     * Lists all subfolders within a given Drive folder.
     *
     * Used by the Drive Folder Picker dialog to let users navigate the Drive folder
     * hierarchy and select a destination for uploads.
     *
     * @param parentId The folder ID to list children of, or null for the root ("My Drive").
     * @return A list of [DriveFolder] objects (id + name), sorted alphabetically by name.
     */
    suspend fun listFolders(parentId: String?): List<DriveFolder> = withContext(Dispatchers.IO) {
        ensureAccount()

        // Build the search query to find only folders (not files) that are not in trash.
        // If parentId is provided, filter to that parent; otherwise show root-level folders.
        val query = buildString {
            append("mimeType = 'application/vnd.google-apps.folder' and trashed = false")
            if (parentId != null) {
                append(" and '$parentId' in parents")
            } else {
                append(" and 'root' in parents")
            }
        }

        // Execute the query. setSpaces("drive") limits search to the user's Drive
        // (as opposed to appDataFolder or photos). setFields limits the response to
        // only the fields we need, reducing data transfer.
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .setOrderBy("name")
            .execute()

        // Map Google's File objects to our simpler DriveFolder data class.
        result.files?.map { DriveFolder(it.id, it.name) } ?: emptyList()
    }

    /**
     * Creates a new folder in Google Drive.
     *
     * Used by the Drive Folder Picker dialog's "New Folder" button to let users
     * create destination folders without leaving the app.
     *
     * @param name The display name for the new folder.
     * @param parentId The parent folder ID, or null to create at the root level.
     * @return A [DriveFolder] with the ID and name of the newly created folder.
     */
    suspend fun createFolder(name: String, parentId: String?): DriveFolder =
        withContext(Dispatchers.IO) {
            ensureAccount()

            // In Google Drive, folders are just files with a special MIME type.
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                this.name = name
                this.mimeType = "application/vnd.google-apps.folder"
                if (parentId != null) {
                    parents = listOf(parentId)
                }
            }

            val file = drive.files().create(fileMetadata)
                .setFields("id, name")
                .execute()
            DriveFolder(file.id, file.name)
        }

    // -----------------------------------------------------------------------
    // Manual resumable upload protocol
    //
    // The Google API client's MediaHttpUploader handles resumable uploads within
    // a single process lifecycle, but does NOT expose the session URI for persistence
    // across process kills. These methods implement the Google Drive resumable upload
    // REST protocol directly so we can save and restore session state.
    //
    // Protocol reference: https://developers.google.com/drive/api/guides/manage-uploads
    // -----------------------------------------------------------------------

    /**
     * The HTTP transport used for manual resumable upload requests.
     * Shared with the Drive client for connection pooling.
     */
    private val httpTransport: NetHttpTransport by lazy { NetHttpTransport() }

    /**
     * The JSON factory used for serializing metadata in initiation requests.
     */
    private val jsonFactory: GsonFactory by lazy { GsonFactory.getDefaultInstance() }

    /**
     * Initiates a resumable upload session with Google Drive.
     *
     * Sends a POST request to the Drive upload endpoint with `uploadType=resumable` and
     * the file metadata as JSON. Google responds with a 200 status and a `Location` header
     * containing the resumable session URI. This URI is used for all subsequent chunk uploads.
     *
     * The session URI remains valid for approximately one week, allowing the upload to be
     * resumed even after the app is killed and restarted days later.
     *
     * @param folderId The Google Drive folder ID to upload the file into.
     * @param fileName The display name for the file in Drive.
     * @param mimeType The MIME type of the file content.
     * @param totalBytes The total file size in bytes. Google uses this to validate completeness.
     * @return The resumable session URI from the Location header.
     * @throws HttpResponseException if the initiation request fails.
     * @throws IllegalStateException if the response does not contain a Location header.
     */
    suspend fun initiateResumableUpload(
        folderId: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
    ): String = withContext(Dispatchers.IO) {
        ensureAccount()

        // Build file metadata as a Map to serialize as JSON.
        val metadata = mapOf(
            "name" to fileName,
            "parents" to listOf(folderId),
        )

        // The initiation URL follows the Drive v3 upload endpoint format.
        // Include fields=id,md5Checksum so the completion response includes the checksum.
        val initiationUrl = GenericUrl(DRIVE_UPLOAD_URL).apply {
            put("uploadType", "resumable")
            put("fields", "id,md5Checksum")
        }

        // Build the POST request with the metadata as JSON body.
        val jsonContent = JsonHttpContent(jsonFactory, metadata)

        val request = httpTransport.createRequestFactory { httpRequest ->
            credential.initialize(httpRequest)
            httpRequest.connectTimeout = HTTP_TIMEOUT_MS
            httpRequest.readTimeout = HTTP_TIMEOUT_MS
        }.buildPostRequest(initiationUrl, jsonContent)

        // Set headers required by the resumable upload protocol.
        request.headers.apply {
            contentType = "application/json; charset=UTF-8"
            // X-Upload-Content-Type tells Google the MIME type of the actual file content.
            set("X-Upload-Content-Type", mimeType)
            // X-Upload-Content-Length tells Google the total file size for validation.
            set("X-Upload-Content-Length", totalBytes)
        }

        coroutineContext.ensureActive()
        val response = request.execute()
        try {
            val sessionUri = response.headers.location
                ?: throw IllegalStateException("No Location header in resumable upload initiation response")
            Log.d(TAG, "Resumable upload session initiated: $sessionUri")
            sessionUri
        } finally {
            response.disconnect()
        }
    }

    /**
     * Uploads a single chunk of data to a resumable upload session.
     *
     * Sends a PUT request to the session URI with the chunk data and a Content-Range header
     * indicating the byte range. Google responds with:
     * - **308 Resume Incomplete**: The chunk was received; more data expected. The response
     *   contains a `Range` header indicating bytes received so far.
     * - **200 OK**: The upload is complete. The response body contains the created file metadata.
     *
     * @param sessionUri The resumable session URI obtained from [initiateResumableUpload].
     * @param chunkData The raw bytes of this chunk.
     * @param offset The byte offset of the first byte in this chunk (0-indexed).
     * @param totalBytes The total file size in bytes.
     * @return A [ChunkUploadResult] indicating either the confirmed byte offset (more chunks
     *         needed) or the Drive file ID (upload complete).
     * @throws HttpResponseException if the server returns an unexpected error.
     */
    suspend fun uploadChunk(
        sessionUri: String,
        chunkData: ByteArray,
        offset: Long,
        totalBytes: Long,
    ): ChunkUploadResult = withContext(Dispatchers.IO) {
        ensureAccount()

        val chunkEnd = offset + chunkData.size - 1
        val contentRange = "bytes $offset-$chunkEnd/$totalBytes"

        val content = ByteArrayContent("application/octet-stream", chunkData)
        val request = httpTransport.createRequestFactory { httpRequest ->
            credential.initialize(httpRequest)
            httpRequest.connectTimeout = HTTP_TIMEOUT_MS
            httpRequest.readTimeout = HTTP_TIMEOUT_MS
        }.buildPutRequest(GenericUrl(sessionUri), content)

        request.headers.contentRange = contentRange
        // Disable throwing on non-success status codes so we can handle 308 ourselves.
        request.throwExceptionOnExecuteError = false

        Log.d(TAG, "Uploading chunk: $contentRange")
        coroutineContext.ensureActive()
        val response = request.execute()
        try {
            when (response.statusCode) {
                200, 201 -> {
                    // Upload complete -- parse the file ID and MD5 from the JSON response.
                    val responseBody = response.parseAsString()
                    val fileId = parseFileIdFromResponse(responseBody)
                    val md5 = parseMd5FromResponse(responseBody)
                    Log.d(TAG, "Upload complete, file ID: $fileId, md5: $md5")
                    ChunkUploadResult.Complete(fileId, md5)
                }
                308 -> {
                    // Resume Incomplete -- parse how many bytes Google has confirmed.
                    // Use the direct property accessor for reliability; the generic
                    // get("Range") can return a non-String type in some client versions.
                    val rangeHeader = response.headers.range
                    Log.d(TAG, "Chunk 308 Range header: '$rangeHeader'")
                    val confirmedBytes = parseConfirmedBytes(rangeHeader)
                    Log.d(TAG, "Chunk uploaded, confirmed bytes: $confirmedBytes")
                    ChunkUploadResult.InProgress(confirmedBytes)
                }
                else -> {
                    throw HttpResponseException(response)
                }
            }
        } finally {
            response.disconnect()
        }
    }

    /**
     * Queries the Google Drive server for how many bytes of a resumable upload have been received.
     *
     * Sends a PUT request with an empty body and a Content-Range header of `bytes star/total`.
     * Google responds with:
     * - **308 Resume Incomplete**: The `Range` header shows bytes received.
     * - **200/201 OK**: The upload was already completed; the response body contains the file ID.
     * - **404 Not Found**: The session URI has expired or is invalid.
     *
     * @param sessionUri The resumable session URI to query.
     * @param totalBytes The total file size in bytes.
     * @return A [SessionProgressResult] indicating progress, completion (with file ID), or expiry.
     */
    suspend fun querySessionProgress(
        sessionUri: String,
        totalBytes: Long,
    ): SessionProgressResult = withContext(Dispatchers.IO) {
        ensureAccount()

        val content = ByteArrayContent("application/octet-stream", ByteArray(0))
        val request = httpTransport.createRequestFactory { httpRequest ->
            credential.initialize(httpRequest)
            httpRequest.connectTimeout = HTTP_TIMEOUT_MS
            httpRequest.readTimeout = HTTP_TIMEOUT_MS
        }.buildPutRequest(GenericUrl(sessionUri), content)

        request.headers.contentRange = "bytes */$totalBytes"
        request.throwExceptionOnExecuteError = false

        Log.d(TAG, "Querying session progress: $sessionUri")
        coroutineContext.ensureActive()
        val response = request.execute()
        try {
            when (response.statusCode) {
                200, 201 -> {
                    // The upload was already completed. Parse the file ID from the response body.
                    val responseBody = response.parseAsString()
                    val fileId = parseFileIdFromResponse(responseBody)
                    Log.d(TAG, "Session query: upload already complete, file ID: $fileId")
                    SessionProgressResult.AlreadyComplete(fileId)
                }
                308 -> {
                    val rangeHeader = response.headers.range
                    Log.d(TAG, "Session query Range header: '$rangeHeader'")
                    val confirmedBytes = parseConfirmedBytes(rangeHeader)
                    Log.d(TAG, "Session query: $confirmedBytes bytes confirmed")
                    SessionProgressResult.InProgress(confirmedBytes)
                }
                404 -> {
                    // Session URI expired or invalid.
                    Log.w(TAG, "Session URI expired or not found")
                    SessionProgressResult.Expired
                }
                else -> {
                    // Throw on unexpected status (including 5xx) so the exception propagates
                    // to PerformUploadUseCase's catch block, which preserves the session for retry.
                    // Returning Expired here would permanently discard a valid session.
                    throw HttpResponseException(response)
                }
            }
        } finally {
            response.disconnect()
        }
    }

    /**
     * Searches for a file by name within a specific Drive folder.
     *
     * Used as a deduplication check before starting a fresh upload — if a file with
     * the same name already exists in the target folder, we skip uploading.
     * Returns the file ID, size, and MD5 checksum for verification.
     *
     * @param folderId The Google Drive folder ID to search in.
     * @param fileName The file name to search for.
     * @return A [DriveFileInfo] with the file's ID, size, and MD5, or null if not found.
     */
    suspend fun findFileByName(
        folderId: String,
        fileName: String,
    ): DriveFileInfo? = withContext(Dispatchers.IO) {
        ensureAccount()

        val escapedName = fileName.replace("\\", "\\\\").replace("'", "\\'")
        val query = "name = '$escapedName' and '$folderId' in parents and trashed = false"
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, size)")
            .setOrderBy("createdTime desc")
            .setPageSize(1)
            .execute()

        val file = result.files?.firstOrNull() ?: return@withContext null
        DriveFileInfo(
            id = file.id,
            sizeBytes = file.getSize()?.toLong(),
        )
    }

    /**
     * Information about a file found on Google Drive.
     */
    data class DriveFileInfo(
        val id: String,
        val sizeBytes: Long?,
    )

    /**
     * Parses the confirmed byte count from a Range header value.
     *
     * The Range header format is "bytes=0-N" where N is the last byte received (inclusive).
     * The confirmed byte count is N + 1 (the number of bytes, not the offset).
     *
     * @param rangeHeader The Range header value, e.g., "bytes=0-42".
     * @return The number of bytes confirmed, or 0 if the header is null/unparseable.
     */
    private fun parseConfirmedBytes(rangeHeader: String?): Long {
        if (rangeHeader == null) return 0L
        // Expected format: "bytes=0-42" -> confirmed = 43
        val dashIndex = rangeHeader.lastIndexOf('-')
        if (dashIndex < 0) return 0L
        val lastByteStr = rangeHeader.substring(dashIndex + 1)
        return try {
            lastByteStr.toLong() + 1
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Failed to parse Range header: $rangeHeader", e)
            0L
        }
    }

    /**
     * Extracts the file ID from a Google Drive JSON response body.
     *
     * The response format is `{"kind":"drive#file","id":"abc123","name":"file.backup",...}`.
     * We use a simple string search to avoid pulling in a full JSON parser dependency just
     * for this one field.
     *
     * @param responseBody The raw JSON response string.
     * @return The extracted file ID.
     * @throws IllegalStateException if the ID cannot be found in the response.
     */
    private fun parseFileIdFromResponse(responseBody: String): String {
        // Use GsonFactory to parse the response properly.
        val jsonParser = jsonFactory.createJsonParser(responseBody)
        val fileMetadata = jsonParser.parse(com.google.api.services.drive.model.File::class.java)
        return fileMetadata.id?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("No file ID in upload completion response")
    }

    /**
     * Extracts the MD5 checksum from a Google Drive JSON response body.
     *
     * @param responseBody The raw JSON response string.
     * @return The MD5 checksum hex string, or null if not present.
     */
    private fun parseMd5FromResponse(responseBody: String): String? {
        val jsonParser = jsonFactory.createJsonParser(responseBody)
        val fileMetadata = jsonParser.parse(com.google.api.services.drive.model.File::class.java)
        return fileMetadata.md5Checksum
    }

    /**
     * Result of querying the progress of a resumable upload session.
     */
    sealed interface SessionProgressResult {
        /** The upload is still in progress. */
        data class InProgress(val confirmedBytes: Long) : SessionProgressResult

        /** The upload was already completed in a previous attempt. */
        data class AlreadyComplete(val driveFileId: String) : SessionProgressResult

        /** The session URI is expired or invalid. */
        data object Expired : SessionProgressResult
    }

    /**
     * Result of uploading a single chunk to a resumable upload session.
     */
    sealed interface ChunkUploadResult {
        /**
         * The chunk was accepted but more data is expected.
         * @property confirmedBytes Total bytes confirmed as received so far.
         */
        data class InProgress(val confirmedBytes: Long) : ChunkUploadResult

        /**
         * The upload is complete.
         * @property driveFileId The Google Drive file ID of the created file.
         * @property md5Checksum The MD5 checksum of the file as computed by Google Drive, or null.
         */
        data class Complete(val driveFileId: String, val md5Checksum: String? = null) : ChunkUploadResult
    }

    companion object {
        private const val TAG = "GoogleDriveService"

        /**
         * Chunk size for resumable uploads (5 MB). Smaller chunks improve resilience to
         * transient network failures on mobile connections. Must be a multiple of 256 KB
         * as required by the Google API client library.
         */
        const val UPLOAD_CHUNK_SIZE = 5 * 1024 * 1024

        /**
         * HTTP connect and read timeout in milliseconds (2 minutes). Prevents indefinite
         * hangs on stalled connections while allowing enough time for each chunk upload
         * on slow mobile networks.
         */
        private const val HTTP_TIMEOUT_MS = 2 * 60 * 1000

        /**
         * Google Drive v3 upload endpoint for creating files.
         * Used by the manual resumable upload protocol.
         */
        private const val DRIVE_UPLOAD_URL =
            "https://www.googleapis.com/upload/drive/v3/files"
    }
}
