package com.johnsonyuen.signalbackup.data.remote

import android.app.PendingIntent
import android.content.Intent
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.johnsonyuen.signalbackup.data.auth.DriveAuthorizer
import com.johnsonyuen.signalbackup.data.repository.DriveRepositoryImpl
import com.johnsonyuen.signalbackup.domain.model.ChunkUploadResult
import com.johnsonyuen.signalbackup.domain.model.DriveHttpException
import com.johnsonyuen.signalbackup.domain.model.SessionProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DriveRepositoryImplTest {

    private class Recorded(val method: String, val url: String, val request: MockLowLevelHttpRequest) {
        fun header(name: String): String? = request.getFirstHeaderValue(name)
        fun param(name: String): Any? = GenericUrl(url).getFirst(name)
        val body: String get() = request.contentAsString
    }

    private class QueueTransport : MockHttpTransport() {
        val requests = mutableListOf<Recorded>()
        val responses = ArrayDeque<MockLowLevelHttpResponse>()

        override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
            object : MockLowLevelHttpRequest(url) {
                override fun execute(): LowLevelHttpResponse {
                    requests += Recorded(method, url, this)
                    return responses.removeFirst()
                }
            }
    }

    private class FakeAuthorizer : DriveAuthorizer {
        private val tokens = ArrayDeque(listOf("token-1", "token-2", "token-3"))
        val invalidated = mutableListOf<String>()
        override suspend fun getAccessToken(): String = tokens.first()
        override suspend fun invalidateToken(token: String) {
            invalidated += token
            tokens.remove(token)
        }
        override suspend fun requestAuthorization(): PendingIntent? = null
        override suspend fun handleAuthorizationResult(data: Intent?): Boolean = true
        override suspend fun revokeAccess() = Unit
    }

    private val transport = QueueTransport()
    private val authorizer = FakeAuthorizer()
    private val repo = DriveRepositoryImpl(
        transport,
        GsonFactory.getDefaultInstance(),
        authorizer,
        Dispatchers.Unconfined,
    )

    private fun respond(status: Int, body: String? = null, headers: Map<String, String> = emptyMap()) {
        val response = MockLowLevelHttpResponse().setStatusCode(status)
        if (body != null) {
            response.setContentType("application/json; charset=UTF-8")
            response.setContent(body)
        }
        headers.forEach { (k, v) -> response.addHeader(k, v) }
        transport.responses.addLast(response)
    }

    private val fileJson = """
        {"id":"file1","name":"backup.db","size":"1048576","md5Checksum":"abc123",
         "createdTime":"2026-10-01T03:00:00.000Z","mimeType":"application/octet-stream",
         "appProperties":{"signalBackup":"1"},"ownedByMe":true}
    """.trimIndent()

    private fun errorJson(code: Int, reason: String) =
        """{"error":{"code":$code,"message":"secret detail","errors":[{"domain":"usageLimits","reason":"$reason","message":"secret detail"}]}}"""

    @Test
    fun uploadChunk_sendsContentRangeAndOnlyLengthBytes_and308WithRangeIsInProgress() = runTest {
        respond(308, headers = mapOf("Range" to "bytes=0-262143"))
        val data = ByteArray(10) { it.toByte() }

        val result = repo.uploadChunk("https://upload.test/session", data, length = 4, offset = 100, totalBytes = 1000)

        assertEquals(ChunkUploadResult.InProgress(262_144), result)
        val req = transport.requests.single()
        assertEquals("PUT", req.method)
        assertEquals("bytes 100-103/1000", req.header("Content-Range"))
        assertEquals("Bearer token-1", req.header("Authorization"))
        assertEquals(4L, req.request.contentLength)
    }

    @Test
    fun uploadChunk_308WithoutRange_confirmsZero() = runTest {
        respond(308)
        val result = repo.uploadChunk("https://upload.test/s", ByteArray(8), 8, 0, 8)
        assertEquals(ChunkUploadResult.InProgress(0), result)
    }

    @Test
    fun uploadChunk_completion_parsesMetadata() = runTest {
        respond(200, fileJson)
        val result = repo.uploadChunk("https://upload.test/s", ByteArray(8), 8, 0, 8)

        val file = (result as ChunkUploadResult.Complete).file
        assertEquals("file1", file.id)
        assertEquals("backup.db", file.name)
        assertEquals(1_048_576L, file.sizeBytes)
        assertEquals("abc123", file.md5Checksum)
        assertEquals(java.time.Instant.parse("2026-10-01T03:00:00Z").toEpochMilli(), file.createdTimeMillis)
        assertFalse(file.isFolder)
        assertTrue(file.uploadedByApp)
        assertTrue(file.ownedByMe)
    }

    @Test
    fun uploadChunk_201AlsoComplete() = runTest {
        respond(201, fileJson)
        val result = repo.uploadChunk("https://upload.test/s", ByteArray(8), 8, 0, 8)
        assertTrue(result is ChunkUploadResult.Complete)
    }

    @Test
    fun querySessionProgress_sendsStarRange_and308() = runTest {
        respond(308, headers = mapOf("Range" to "bytes=0-524287"))
        val result = repo.querySessionProgress("https://upload.test/s", 1000)
        assertEquals(SessionProgress.InProgress(524_288), result)
        assertEquals("bytes */1000", transport.requests.single().header("Content-Range"))
    }

    @Test
    fun querySessionProgress_404And410AreExpired() = runTest {
        respond(404, errorJson(404, "notFound"))
        respond(410)
        assertEquals(SessionProgress.Expired, repo.querySessionProgress("https://upload.test/s", 10))
        assertEquals(SessionProgress.Expired, repo.querySessionProgress("https://upload.test/s", 10))
    }

    @Test
    fun querySessionProgress_completeParsesFile() = runTest {
        respond(200, fileJson)
        val result = repo.querySessionProgress("https://upload.test/s", 10)
        assertEquals("file1", (result as SessionProgress.Complete).file.id)
    }

    @Test
    fun querySessionProgress_serverErrorThrows() = runTest {
        respond(503)
        try {
            repo.querySessionProgress("https://upload.test/s", 10)
            fail("expected DriveHttpException")
        } catch (e: DriveHttpException) {
            assertEquals(503, e.statusCode)
            assertTrue(e.isRetryable)
        }
    }

    @Test
    fun unauthorized_invalidatesTokenAndRetriesOnce() = runTest {
        respond(401, errorJson(401, "authError"))
        respond(308, headers = mapOf("Range" to "bytes=0-9"))

        val result = repo.uploadChunk("https://upload.test/s", ByteArray(10), 10, 0, 20)

        assertEquals(ChunkUploadResult.InProgress(10), result)
        assertEquals(listOf("token-1"), authorizer.invalidated)
        assertEquals("Bearer token-1", transport.requests[0].header("Authorization"))
        assertEquals("Bearer token-2", transport.requests[1].header("Authorization"))
        assertEquals("bytes 0-9/20", transport.requests[1].header("Content-Range"))
    }

    @Test
    fun unauthorizedTwice_throws401() = runTest {
        respond(401)
        respond(401)
        try {
            repo.getFile("abc")
            fail("expected DriveHttpException")
        } catch (e: DriveHttpException) {
            assertEquals(401, e.statusCode)
            assertEquals(2, transport.requests.size)
        }
    }

    @Test
    fun errorReasonParsed_andMessageHasNoUrlOrBody() = runTest {
        respond(403, errorJson(403, "storageQuotaExceeded"))
        try {
            repo.uploadChunk("https://upload.test/secret-session", ByteArray(4), 4, 0, 4)
            fail("expected DriveHttpException")
        } catch (e: DriveHttpException) {
            assertEquals(403, e.statusCode)
            assertEquals("storageQuotaExceeded", e.reason)
            assertTrue(e.isQuotaExceeded)
            val message = e.message.orEmpty()
            assertFalse(message.contains("secret"))
            assertFalse(message.contains("upload.test"))
            assertFalse(message.contains("token"))
        }
    }

    @Test
    fun rateLimitReason_isRetryable() = runTest {
        respond(403, errorJson(403, "userRateLimitExceeded"))
        try {
            repo.listChildren("parent")
            fail("expected DriveHttpException")
        } catch (e: DriveHttpException) {
            assertTrue(e.isRetryable)
        }
    }

    @Test
    fun nonJsonErrorBody_givesNullReason() = runTest {
        val response = MockLowLevelHttpResponse().setStatusCode(500).setContent("<html>oops</html>")
        transport.responses.addLast(response)
        try {
            repo.getFile("x")
            fail("expected DriveHttpException")
        } catch (e: DriveHttpException) {
            assertEquals(500, e.statusCode)
            assertNull(e.reason)
        }
    }

    @Test
    fun listChildren_followsPaging() = runTest {
        respond(200, """{"nextPageToken":"p2","files":[$fileJson]}""")
        respond(200, """{"files":[{"id":"dir1","name":"attachments","mimeType":"application/vnd.google-apps.folder","ownedByMe":true}]}""")

        val children = repo.listChildren("parent1")

        assertEquals(listOf("file1", "dir1"), children.map { it.id })
        assertTrue(children[1].isFolder)
        assertFalse(children[1].uploadedByApp)
        val first = transport.requests[0]
        assertNull(first.param("pageToken"))
        assertTrue((first.param("q") as String).contains("'parent1' in parents"))
        assertTrue((first.param("q") as String).contains("trashed = false"))
        assertTrue((first.param("fields") as String).contains(DriveRepositoryImpl.FILE_FIELDS))
        assertEquals("p2", transport.requests[1].param("pageToken"))
    }

    @Test
    fun listFolders_queriesOnlyFolders_rootWhenNoParent() = runTest {
        respond(200, """{"files":[{"id":"b","name":"beta","mimeType":"application/vnd.google-apps.folder"},{"id":"a","name":"Alpha","mimeType":"application/vnd.google-apps.folder"}]}""")
        val folders = repo.listFolders(null)
        assertEquals(listOf("Alpha", "beta"), folders.map { it.name })
        val q = transport.requests.single().param("q") as String
        assertTrue(q.contains("mimeType = 'application/vnd.google-apps.folder'"))
        assertTrue(q.contains("'root' in parents"))
    }

    @Test
    fun initiateResumableUpload_tagsFileAndRequestsFields() = runTest {
        respond(200, headers = mapOf("Location" to "https://upload.test/session-xyz"))

        val uri = repo.initiateResumableUpload("parent1", "backup.db", "application/octet-stream", 5000)

        assertEquals("https://upload.test/session-xyz", uri)
        val req = transport.requests.single()
        assertEquals("POST", req.method)
        assertEquals("resumable", req.param("uploadType"))
        assertEquals(DriveRepositoryImpl.FILE_FIELDS, req.param("fields"))
        assertEquals("5000", req.header("X-Upload-Content-Length"))
        assertEquals("application/octet-stream", req.header("X-Upload-Content-Type"))
        assertTrue(req.body.contains("\"appProperties\":{\"signalBackup\":\"1\"}"))
        assertTrue(req.body.contains("\"parents\":[\"parent1\"]"))
    }

    @Test
    fun createFolder_isTagged() = runTest {
        respond(200, """{"id":"f1","name":"Signal Backup","mimeType":"application/vnd.google-apps.folder"}""")
        val folder = repo.createFolder("Signal Backup", null)
        assertEquals("f1", folder.id)
        val body = transport.requests.single().body
        assertTrue(body.contains("\"appProperties\":{\"signalBackup\":\"1\"}"))
        assertTrue(body.contains("application/vnd.google-apps.folder"))
        assertFalse(body.contains("parents"))
    }

    @Test
    fun uploadSmallFile_isOneTaggedMultipartRequest() = runTest {
        respond(200, fileJson)
        val meta = repo.uploadSmallFile("parent1", "manifest.json", "application/json", "HELLO-DATA".toByteArray())

        assertEquals("file1", meta.id)
        val req = transport.requests.single()
        assertEquals("POST", req.method)
        assertEquals("multipart", req.param("uploadType"))
        assertEquals(DriveRepositoryImpl.FILE_FIELDS, req.param("fields"))
        assertTrue(req.request.contentType.startsWith("multipart/related"))
        assertTrue(req.body.contains("\"signalBackup\":\"1\""))
        assertTrue(req.body.contains("HELLO-DATA"))
    }

    @Test
    fun trashFile_patchesTrashedTrue_neverDeletes() = runTest {
        respond(200, """{"id":"file1"}""")
        repo.trashFile("file1")

        val req = transport.requests.single()
        assertFalse(req.method == "DELETE")
        assertEquals("POST", req.method)
        assertEquals("PATCH", req.header("X-HTTP-Method-Override"))
        assertTrue(req.url.contains("/drive/v3/files/file1"))
        assertTrue(req.body.contains("\"trashed\":true"))
    }

    @Test
    fun getFile_404IsNull_trashedIsNull_otherwiseMeta() = runTest {
        respond(404, errorJson(404, "notFound"))
        respond(200, """{"id":"f","name":"n","trashed":true}""")
        respond(200, fileJson)

        assertNull(repo.getFile("missing"))
        assertNull(repo.getFile("trashed"))
        assertEquals("file1", repo.getFile("file1")?.id)
        assertTrue((transport.requests[0].param("fields") as String).contains("trashed"))
    }
}
