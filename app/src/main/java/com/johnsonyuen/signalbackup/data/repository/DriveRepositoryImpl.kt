package com.johnsonyuen.signalbackup.data.repository

import com.google.api.client.googleapis.json.GoogleJsonErrorContainer
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpContent
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestFactory
import com.google.api.client.http.HttpResponse
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.MultipartContent
import com.google.api.client.http.json.JsonHttpContent
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.JsonObjectParser
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import com.johnsonyuen.signalbackup.data.auth.DriveAuthorizer
import com.johnsonyuen.signalbackup.domain.model.ChunkUploadResult
import com.johnsonyuen.signalbackup.domain.model.DriveFileMeta
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import com.johnsonyuen.signalbackup.domain.model.DriveHttpException
import com.johnsonyuen.signalbackup.domain.model.SessionProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DriveRepository] over the Drive v3 REST API using google-http-client directly.
 * Every request carries `Authorization: Bearer <token>` from [DriveAuthorizer]; a 401 invalidates
 * the token and the request is retried once with a fresh one.
 */
@Singleton
class DriveRepositoryImpl internal constructor(
    transport: HttpTransport,
    private val jsonFactory: JsonFactory,
    private val authorizer: DriveAuthorizer,
    private val ioDispatcher: CoroutineDispatcher,
) : DriveRepository {

    @Inject
    constructor(
        transport: HttpTransport,
        jsonFactory: JsonFactory,
        authorizer: DriveAuthorizer,
    ) : this(transport, jsonFactory, authorizer, Dispatchers.IO)

    private val requestFactory: HttpRequestFactory = transport.createRequestFactory { request ->
        request.connectTimeout = HTTP_TIMEOUT_MS
        request.readTimeout = HTTP_TIMEOUT_MS
        request.followRedirects = false
        request.throwExceptionOnExecuteError = false
        request.parser = JsonObjectParser(jsonFactory)
    }

    override suspend fun listFolders(parentId: String?): List<DriveFolder> {
        val parent = parentId ?: ROOT
        val query = "mimeType = '$FOLDER_MIME' and trashed = false and '${escape(parent)}' in parents"
        return listAll(query, orderBy = "name", fileFields = FILE_FIELDS)
            .map { DriveFolder(it.id, it.name.orEmpty()) }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun createFolder(name: String, parentId: String?): DriveFolder {
        val metadata = buildMap<String, Any> {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            if (parentId != null) put("parents", listOf(parentId))
            put("appProperties", APP_PROPERTIES)
        }
        val file = execute(
            build = {
                val url = filesUrl().apply { put("fields", FILE_FIELDS) }
                it.buildPostRequest(url, JsonHttpContent(jsonFactory, metadata))
            },
            handle = { it.parseAs(File::class.java) },
        )
        return DriveFolder(file.id, file.name ?: name)
    }

    override suspend fun getFile(fileId: String): DriveFileMeta? = execute(
        build = { it.buildGetRequest(fileUrl(fileId).apply { put("fields", "$FILE_FIELDS,trashed") }) },
        accept = { it in 200..299 || it == 404 },
        handle = { response ->
            if (response.statusCode == 404) {
                null
            } else {
                val file = response.parseAs(File::class.java)
                if (file.trashed == true) null else file.toMeta()
            }
        },
    )

    override suspend fun listChildren(parentId: String): List<DriveFileMeta> {
        val query = "'${escape(parentId)}' in parents and trashed = false"
        return listAll(query, orderBy = "createdTime desc", fileFields = FILE_FIELDS).map { it.toMeta() }
    }

    override suspend fun initiateResumableUpload(
        parentId: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
    ): String {
        val metadata = mapOf(
            "name" to fileName,
            "mimeType" to mimeType,
            "parents" to listOf(parentId),
            "appProperties" to APP_PROPERTIES,
        )
        return execute(
            build = { factory ->
                val url = GenericUrl(UPLOAD_URL).apply {
                    put("uploadType", "resumable")
                    put("fields", FILE_FIELDS)
                }
                factory.buildPostRequest(url, JsonHttpContent(jsonFactory, metadata)).apply {
                    headers.set("X-Upload-Content-Type", mimeType)
                    headers.set("X-Upload-Content-Length", totalBytes.toString())
                }
            },
            handle = { response ->
                response.headers.location ?: throw IOException("Drive did not return an upload session")
            },
        )
    }

    override suspend fun uploadChunk(
        sessionUri: String,
        data: ByteArray,
        length: Int,
        offset: Long,
        totalBytes: Long,
    ): ChunkUploadResult {
        require(length in 1..data.size) { "Invalid chunk length" }
        val lastByte = offset + length - 1
        return execute(
            build = { factory ->
                val content = ByteArrayContent(OCTET_STREAM, data, 0, length)
                factory.buildPutRequest(GenericUrl(sessionUri), content).apply {
                    headers.contentRange = "bytes $offset-$lastByte/$totalBytes"
                }
            },
            accept = { it == 308 || it in 200..299 },
            handle = { response ->
                if (response.statusCode == 308) {
                    ChunkUploadResult.InProgress(confirmedBytes(response))
                } else {
                    ChunkUploadResult.Complete(response.parseAs(File::class.java).toMeta())
                }
            },
        )
    }

    override suspend fun querySessionProgress(sessionUri: String, totalBytes: Long): SessionProgress =
        execute(
            build = { factory ->
                factory.buildPutRequest(GenericUrl(sessionUri), ByteArrayContent(null, ByteArray(0))).apply {
                    headers.contentRange = "bytes */$totalBytes"
                }
            },
            accept = { it == 308 || it == 404 || it == 410 || it in 200..299 },
            handle = { response ->
                when (response.statusCode) {
                    308 -> SessionProgress.InProgress(confirmedBytes(response))
                    404, 410 -> SessionProgress.Expired
                    else -> SessionProgress.Complete(response.parseAs(File::class.java).toMeta())
                }
            },
        )

    override suspend fun uploadSmallFile(
        parentId: String,
        fileName: String,
        mimeType: String,
        data: ByteArray,
    ): DriveFileMeta {
        val metadata = mapOf(
            "name" to fileName,
            "mimeType" to mimeType,
            "parents" to listOf(parentId),
            "appProperties" to APP_PROPERTIES,
        )
        return execute(
            build = { factory ->
                val url = GenericUrl(UPLOAD_URL).apply {
                    put("uploadType", "multipart")
                    put("fields", FILE_FIELDS)
                }
                val content = MultipartContent()
                    .addPart(MultipartContent.Part(JsonHttpContent(jsonFactory, metadata)))
                    .addPart(MultipartContent.Part(ByteArrayContent(mimeType, data)))
                factory.buildPostRequest(url, content)
            },
            handle = { it.parseAs(File::class.java).toMeta() },
        )
    }

    override suspend fun trashFile(fileId: String) {
        // PATCH via method override: HttpURLConnection (NetHttpTransport) can't send PATCH.
        execute(
            build = { factory ->
                val url = fileUrl(fileId).apply { put("fields", "id") }
                val body: HttpContent = JsonHttpContent(jsonFactory, mapOf("trashed" to true))
                factory.buildPostRequest(url, body).apply {
                    headers.set("X-HTTP-Method-Override", "PATCH")
                }
            },
            handle = { },
        )
    }

    private suspend fun listAll(query: String, orderBy: String, fileFields: String): List<File> {
        val files = mutableListOf<File>()
        var pageToken: String? = null
        do {
            val token = pageToken
            val page = execute(
                build = { factory ->
                    val url = filesUrl().apply {
                        put("q", query)
                        put("spaces", "drive")
                        put("orderBy", orderBy)
                        put("pageSize", PAGE_SIZE)
                        put("fields", "nextPageToken,files($fileFields)")
                        if (token != null) put("pageToken", token)
                    }
                    factory.buildGetRequest(url)
                },
                handle = { it.parseAs(FileList::class.java) },
            )
            page.files?.let(files::addAll)
            pageToken = page.nextPageToken
        } while (!pageToken.isNullOrEmpty())
        return files
    }

    /**
     * Runs a request built by [build] with bearer auth, retrying once after a 401.
     * Responses whose status matches [accept] go to [handle]; anything else becomes [DriveHttpException].
     */
    private suspend fun <T> execute(
        build: (HttpRequestFactory) -> HttpRequest,
        accept: (Int) -> Boolean = { it in 200..299 },
        handle: (HttpResponse) -> T,
    ): T = withContext(ioDispatcher) {
        var token = authorizer.getAccessToken()
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val request = build(requestFactory)
            request.headers.authorization = "Bearer $token"
            val response = runInterruptible { request.execute() }
            val status = response.statusCode
            if (status == 401 && attempt == 0) {
                response.disconnect()
                authorizer.invalidateToken(token)
                token = authorizer.getAccessToken()
                attempt++
                continue
            }
            try {
                if (!accept(status)) throw toHttpException(response)
                return@withContext handle(response)
            } finally {
                response.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun toHttpException(response: HttpResponse): DriveHttpException {
        val status = response.statusCode
        val reason = try {
            val body = response.parseAsString()
            if (body.isNullOrBlank()) {
                null
            } else {
                jsonFactory.fromString(body, GoogleJsonErrorContainer::class.java)
                    ?.error?.errors?.firstOrNull()?.reason
            }
        } catch (e: Exception) {
            null
        }
        val message = buildString {
            append("Drive request failed with HTTP ").append(status)
            if (reason != null) append(" (").append(reason).append(')')
        }
        return DriveHttpException(status, reason, message)
    }

    /** Parses the 308 `Range: bytes=0-N` header; no header means nothing was persisted yet. */
    private fun confirmedBytes(response: HttpResponse): Long {
        val range = response.headers.range ?: return 0L
        val end = range.substringAfterLast('-', missingDelimiterValue = "").trim().toLongOrNull()
            ?: return 0L
        return end + 1
    }

    private fun File.toMeta(): DriveFileMeta = DriveFileMeta(
        id = id ?: throw IOException("Drive response is missing the file id"),
        name = name.orEmpty(),
        sizeBytes = getSize(),
        md5Checksum = md5Checksum,
        createdTimeMillis = createdTime?.value,
        isFolder = mimeType == FOLDER_MIME,
        uploadedByApp = appProperties?.get(APP_PROPERTY_KEY) == APP_PROPERTY_VALUE,
        ownedByMe = ownedByMe ?: false,
    )

    private fun filesUrl() = GenericUrl(FILES_URL)

    private fun fileUrl(fileId: String) = GenericUrl(FILES_URL).apply { appendRawPath("/$fileId") }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val APP_PROPERTY_KEY = "signalBackup"
        const val APP_PROPERTY_VALUE = "1"
        const val FILE_FIELDS = "id,name,size,md5Checksum,createdTime,mimeType,appProperties,ownedByMe"

        private val APP_PROPERTIES = mapOf(APP_PROPERTY_KEY to APP_PROPERTY_VALUE)
        private const val ROOT = "root"
        private const val OCTET_STREAM = "application/octet-stream"
        private const val PAGE_SIZE = 1000
        private const val HTTP_TIMEOUT_MS = 2 * 60 * 1000
        private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
    }
}
