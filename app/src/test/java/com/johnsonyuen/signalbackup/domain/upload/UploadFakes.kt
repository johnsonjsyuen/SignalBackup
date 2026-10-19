package com.johnsonyuen.signalbackup.domain.upload

import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.UploadSessionRepository
import com.johnsonyuen.signalbackup.domain.model.ChunkUploadResult
import com.johnsonyuen.signalbackup.domain.model.DriveFileMeta
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import com.johnsonyuen.signalbackup.domain.model.ResumableUploadSession
import com.johnsonyuen.signalbackup.domain.model.SessionProgress
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.min

/** In-memory local files keyed by URI. */
class InMemoryLocalFileSource : LocalFileSource {
    class Entry(var content: ByteArray, var lastModified: Long)

    val files = mutableMapOf<String, Entry>()
    var opens = 0

    fun put(uri: String, content: ByteArray, lastModified: Long) {
        files[uri] = Entry(content, lastModified)
    }

    override suspend fun stat(uri: String): LocalFileStat? =
        files[uri]?.let { LocalFileStat(it.content.size.toLong(), it.lastModified) }

    override suspend fun open(uri: String): SeekableReader {
        val entry = files[uri] ?: throw FileNotFoundException(uri)
        opens++
        return object : SeekableReader {
            private var position = 0L
            override suspend fun seek(position: Long) {
                this.position = position
            }

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val data = entry.content
                if (position >= data.size) return -1
                val n = min(length.toLong(), data.size - position).toInt()
                System.arraycopy(data, position.toInt(), buffer, offset, n)
                position += n
                return n
            }

            override fun close() {}
        }
    }
}

/** Behaviour override for one uploadChunk call. */
sealed interface ChunkBehavior {
    data object Normal : ChunkBehavior
    data class Fail(val error: IOException) : ChunkBehavior
    /** Persist only [bytes] of the chunk and answer 308 with that range. */
    data class Partial(val bytes: Int) : ChunkBehavior
    /** 308 without a Range header: nothing persisted. */
    data object NoRange : ChunkBehavior
}

/** Fake Drive that stores resumable uploads in memory and enforces contiguous offsets. */
class FakeDriveRepository : DriveRepository {
    class Session(val parentId: String, val name: String, val total: Long) {
        val content = ByteArray(total.toInt())
        var persisted = 0L
        var fileId: String? = null
    }

    val sessions = mutableMapOf<String, Session>()
    val files = mutableMapOf<String, DriveFileMeta>()
    val contents = mutableMapOf<String, ByteArray>()
    val trashed = mutableListOf<String>()
    val chunkOffsets = mutableListOf<Long>()
    var initiateCount = 0
    var queryCount = 0
    var smallUploads = 0
    private var nextId = 0

    /** Called per uploadChunk with the call index (0-based). */
    var chunkBehavior: (call: Int, offset: Long) -> ChunkBehavior = { _, _ -> ChunkBehavior.Normal }
    /** Overrides the md5 Drive reports for created files. */
    var md5Override: String? = null
    var afterChunk: (call: Int) -> Unit = {}

    fun receivedContent(fileId: String): ByteArray = contents.getValue(fileId)

    override suspend fun listFolders(parentId: String?): List<DriveFolder> = emptyList()
    override suspend fun createFolder(name: String, parentId: String?): DriveFolder = error("unused")
    override suspend fun getFile(fileId: String): DriveFileMeta? = if (fileId in trashed) null else files[fileId]
    override suspend fun listChildren(parentId: String): List<DriveFileMeta> = emptyList()

    override suspend fun initiateResumableUpload(
        parentId: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
    ): String {
        initiateCount++
        val uri = "session-${nextId++}"
        sessions[uri] = Session(parentId, fileName, totalBytes)
        return uri
    }

    override suspend fun uploadChunk(
        sessionUri: String,
        data: ByteArray,
        length: Int,
        offset: Long,
        totalBytes: Long,
    ): ChunkUploadResult {
        val call = chunkOffsets.size
        chunkOffsets += offset
        val s = sessions[sessionUri] ?: throw com.johnsonyuen.signalbackup.domain.model.DriveHttpException(404, "notFound", "gone")
        check(offset == s.persisted) { "Non-contiguous chunk: offset=$offset persisted=${s.persisted}" }
        check(totalBytes == s.total)
        val accept = when (val b = chunkBehavior(call, offset)) {
            ChunkBehavior.Normal -> length
            is ChunkBehavior.Fail -> throw b.error
            is ChunkBehavior.Partial -> b.bytes
            ChunkBehavior.NoRange -> 0
        }
        System.arraycopy(data, 0, s.content, offset.toInt(), accept)
        s.persisted += accept
        afterChunk(call)
        return if (s.persisted == s.total) {
            ChunkUploadResult.Complete(complete(s))
        } else {
            ChunkUploadResult.InProgress(s.persisted)
        }
    }

    override suspend fun querySessionProgress(sessionUri: String, totalBytes: Long): SessionProgress {
        queryCount++
        val s = sessions[sessionUri] ?: return SessionProgress.Expired
        val id = s.fileId
        return if (id != null) SessionProgress.Complete(files.getValue(id)) else SessionProgress.InProgress(s.persisted)
    }

    override suspend fun uploadSmallFile(
        parentId: String,
        fileName: String,
        mimeType: String,
        data: ByteArray,
    ): DriveFileMeta {
        smallUploads++
        return addFile(fileName, data.copyOf())
    }

    override suspend fun trashFile(fileId: String) {
        trashed += fileId
    }

    fun addFile(name: String, content: ByteArray, md5: String? = md5Override ?: md5Hex(content)): DriveFileMeta {
        val id = "file-${nextId++}"
        val meta = DriveFileMeta(
            id = id,
            name = name,
            sizeBytes = content.size.toLong(),
            md5Checksum = md5,
            createdTimeMillis = 0L,
            isFolder = false,
            uploadedByApp = true,
            ownedByMe = true,
        )
        files[id] = meta
        contents[id] = content
        return meta
    }

    private fun complete(s: Session): DriveFileMeta {
        val meta = addFile(s.name, s.content.copyOf())
        s.fileId = meta.id
        return meta
    }
}

/** Single-row in-memory session store. */
class FakeUploadSessionRepository : UploadSessionRepository {
    var session: ResumableUploadSession? = null
    val markedCompleted = mutableListOf<String>()

    override suspend fun get(): ResumableUploadSession? = session
    override suspend fun save(session: ResumableUploadSession) {
        this.session = session
    }

    override suspend fun updateBytesUploaded(bytesUploaded: Long) {
        session = session?.copy(bytesUploaded = bytesUploaded)
    }

    override suspend fun markCompleted(driveFileId: String, remoteMd5: String?) {
        markedCompleted += driveFileId
        session = session?.copy(driveFileId = driveFileId, remoteMd5 = remoteMd5)
    }

    override suspend fun incrementFailureCount(): Int {
        val s = session ?: return 0
        session = s.copy(failureCount = s.failureCount + 1)
        return s.failureCount + 1
    }

    override suspend fun clear() {
        session = null
    }
}
