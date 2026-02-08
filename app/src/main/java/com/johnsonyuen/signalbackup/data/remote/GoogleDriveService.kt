/**
 * GoogleDriveService.kt - Google Drive API wrapper for file and folder operations.
 *
 * This class encapsulates all direct interactions with the Google Drive REST API v3.
 * It provides three core operations: uploading files, listing folders, and creating folders.
 *
 * Architecture context:
 * - Part of the **data layer** (data/remote package) -- this is the "remote data source."
 * - Used exclusively by [DriveRepositoryImpl], which wraps it behind the [DriveRepository]
 *   interface for clean architecture separation.
 * - Depends on [GoogleAccountCredential] (provided by Hilt via DriveModule) for OAuth2
 *   authentication, and [SettingsDataStore] to read the signed-in account email.
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
import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.http.json.JsonHttpContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.johnsonyuen.signalbackup.data.local.datastore.SettingsDataStore
import com.johnsonyuen.signalbackup.data.repository.UploadProgressListener
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject

/**
 * Service class that wraps the Google Drive API v3 client.
 *
 * Injected by Hilt with a [GoogleAccountCredential] for OAuth2 and [SettingsDataStore]
 * for reading the signed-in account. All methods are suspend functions that run on
 * [Dispatchers.IO] to avoid blocking the main thread.
 *
 * @param credential Google OAuth2 credential object. Its `selectedAccountName` is set
 *        dynamically before each API call via [ensureAccount].
 * @param settingsDataStore Used to read the stored Google account email.
 */
class GoogleDriveService @Inject constructor(
    private val credential: GoogleAccountCredential,
    private val settingsDataStore: SettingsDataStore,
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
        val email = settingsDataStore.googleAccountEmail.first()
            ?: throw IllegalStateException("No Google account configured. Please sign in first.")
        credential.selectedAccountName = email
    }

    /**
     * Uploads a file to Google Drive using resumable media upload.
     *
     * Creates a new file in the specified Drive folder with the given content.
     * The upload uses the Google API client's resumable upload protocol, which splits
     * the file into chunks and uploads them sequentially. If a chunk fails due to a
     * transient network error, the client retries that chunk without re-uploading the
     * entire file. This is critical for large Signal backup files (often 100MB+).
     *
     * Configuration:
     * - **Chunk size**: 5 MB. Smaller chunks mean more HTTP requests but better resilience
     *   to interruptions. 5 MB is a good balance for mobile networks.
     * - **Resumable upload**: Enabled via `mediaHttpUploader.isDirectUploadEnabled = false`.
     *   The Google client library handles the resumable upload protocol automatically.
     * - **Timeouts**: Connect and read timeouts are set to 2 minutes each on the HTTP
     *   request factory. This prevents indefinite hangs on stalled connections.
     *
     * @param folderId The Google Drive folder ID to upload into.
     * @param fileName The name for the file in Drive.
     * @param mimeType The MIME type of the file content (typically "application/octet-stream").
     * @param inputStream The file content to upload. This stream is consumed and should
     *        not be reused after this call.
     * @param fileSize Total size of the file in bytes, used by the progress listener to
     *        report meaningful progress. Pass 0 if unknown.
     * @param progressListener Optional callback invoked after each chunk is uploaded,
     *        receiving the bytes uploaded so far and the total file size.
     * @return The Google Drive file ID of the newly uploaded file.
     * @throws com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
     *         if the OAuth token is expired or the Drive scope hasn't been granted.
     */
    suspend fun uploadFile(
        folderId: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream,
        fileSize: Long = 0L,
        progressListener: UploadProgressListener? = null,
    ): String = withContext(Dispatchers.IO) {
        ensureAccount()

        // Create Drive file metadata specifying the name and parent folder.
        val fileMetadata = com.google.api.services.drive.model.File().apply {
            name = fileName
            parents = listOf(folderId)
        }

        // Wrap the InputStream in Drive's InputStreamContent for the upload.
        // Setting the length enables the progress listener to report accurate percentages
        // and allows the Google client to set Content-Length headers.
        val mediaContent = InputStreamContent(mimeType, inputStream).apply {
            if (fileSize > 0) {
                length = fileSize
            }
        }

        // Build the create request. setFields("id") tells Drive to only return the file ID
        // in the response, reducing bandwidth and parsing overhead.
        val createRequest = drive.files().create(fileMetadata, mediaContent)
            .setFields("id")

        // Configure the uploader to use resumable upload with smaller chunks.
        // Resumable upload splits the file into chunks and uploads them sequentially.
        // If a chunk fails, only that chunk is retried -- not the entire file.
        createRequest.mediaHttpUploader?.apply {
            // Disable direct upload to enable resumable upload protocol.
            isDirectUploadEnabled = false
            // 5 MB chunks balance between network resilience and request overhead.
            chunkSize = UPLOAD_CHUNK_SIZE
            Log.d(TAG, "Uploading $fileName with resumable upload, chunk size: ${chunkSize / 1024 / 1024} MB")

            // Wire up the progress listener to report chunk-level progress.
            // MediaHttpUploader calls this after each chunk upload and on completion.
            if (progressListener != null) {
                setProgressListener { uploader ->
                    val bytesUploaded = when (uploader.uploadState) {
                        MediaHttpUploader.UploadState.MEDIA_IN_PROGRESS ->
                            uploader.numBytesUploaded
                        MediaHttpUploader.UploadState.MEDIA_COMPLETE ->
                            fileSize
                        else -> 0L
                    }
                    progressListener(bytesUploaded, fileSize)
                }
            }
        }

        val file = createRequest.execute()
        file.id
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
        val initiationUrl = GenericUrl(DRIVE_UPLOAD_URL).apply {
            put("uploadType", "resumable")
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
        val response = request.execute()
        try {
            when (response.statusCode) {
                200, 201 -> {
                    // Upload complete -- parse the file ID from the JSON response.
                    val responseBody = response.parseAsString()
                    val fileId = parseFileIdFromResponse(responseBody)
                    Log.d(TAG, "Upload complete, file ID: $fileId")
                    ChunkUploadResult.Complete(fileId)
                }
                308 -> {
                    // Resume Incomplete -- parse how many bytes Google has confirmed.
                    val rangeHeader = response.headers["Range"] as? String
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
     * - **200 OK**: The upload was already completed.
     * - **404 Not Found**: The session URI has expired or is invalid.
     *
     * @param sessionUri The resumable session URI to query.
     * @param totalBytes The total file size in bytes.
     * @return The number of bytes confirmed as received, or -1 if the session is expired/invalid.
     */
    suspend fun querySessionProgress(
        sessionUri: String,
        totalBytes: Long,
    ): Long = withContext(Dispatchers.IO) {
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
        val response = request.execute()
        try {
            when (response.statusCode) {
                200, 201 -> {
                    // The upload was already completed. Return totalBytes to signal completion.
                    Log.d(TAG, "Session query: upload already complete")
                    totalBytes
                }
                308 -> {
                    val rangeHeader = response.headers["Range"] as? String
                    val confirmedBytes = parseConfirmedBytes(rangeHeader)
                    Log.d(TAG, "Session query: $confirmedBytes bytes confirmed")
                    confirmedBytes
                }
                404 -> {
                    // Session URI expired or invalid.
                    Log.w(TAG, "Session URI expired or not found")
                    -1L
                }
                else -> {
                    Log.w(TAG, "Unexpected status querying session: ${response.statusCode}")
                    -1L
                }
            }
        } finally {
            response.disconnect()
        }
    }

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
        return fileMetadata.id
            ?: throw IllegalStateException("No file ID in upload completion response")
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
         */
        data class Complete(val driveFileId: String) : ChunkUploadResult
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
