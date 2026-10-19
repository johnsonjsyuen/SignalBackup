package com.johnsonyuen.signalbackup.domain.mirror

import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.SyncedFileRepository
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.model.ChunkUploadResult
import com.johnsonyuen.signalbackup.domain.model.DriveFileMeta
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import com.johnsonyuen.signalbackup.domain.model.DriveHttpException
import com.johnsonyuen.signalbackup.domain.model.LocalBackupFile
import com.johnsonyuen.signalbackup.domain.model.NewUploadRecord
import com.johnsonyuen.signalbackup.domain.model.SessionProgress
import com.johnsonyuen.signalbackup.domain.model.SyncedFile
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import com.johnsonyuen.signalbackup.domain.upload.FileUploader
import com.johnsonyuen.signalbackup.domain.upload.VerifiedUpload
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

val NOW: Instant = Instant.parse("2026-10-19T12:00:00Z")
val TEST_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
const val TREE_URI = "content://tree/signal"
const val DAY = 24 * 60 * 60 * 1000L

/** Old enough to pass the 2-minute rule. */
val OLD: Long = NOW.toEpochMilli() - DAY

/** In-memory SAF tree. Document ids are paths ("root/a/b"). */
class FakeDocumentTreeSource : DocumentTreeSource {
    var hasPermission = true
    val failures = mutableMapOf<String, Exception>()
    private val children = mutableMapOf<String, MutableList<DocumentEntry>>()

    fun dir(parentId: String, name: String, lastModified: Long = OLD): String {
        val id = "$parentId/$name"
        children.getOrPut(parentId) { mutableListOf() } +=
            DocumentEntry(id, "content://doc/$id", name, true, 0L, lastModified)
        children.getOrPut(id) { mutableListOf() }
        return id
    }

    fun file(parentId: String, name: String, size: Long = 100L, lastModified: Long = OLD) {
        val id = "$parentId/$name"
        children.getOrPut(parentId) { mutableListOf() } +=
            DocumentEntry(id, "content://doc/$id", name, false, size, lastModified)
    }

    override suspend fun hasPersistedReadPermission(treeUri: String) = hasPermission
    override suspend fun rootDocumentId(treeUri: String) = ROOT

    override suspend fun listChildren(treeUri: String, parentDocumentId: String): List<DocumentEntry>? {
        failures[parentDocumentId]?.let { throw it }
        return children[parentDocumentId]?.toList() ?: emptyList()
    }

    companion object {
        const val ROOT = "root"
    }
}

class FakeDriveRepository : DriveRepository {
    data class Node(val meta: DriveFileMeta, val parentId: String)

    val nodes = linkedMapOf<String, Node>()
    val trashed = mutableListOf<String>()
    val trashFailures = mutableSetOf<String>()
    var createdFolders = 0
    private var nextId = 1

    fun addFolder(parentId: String, name: String, tagged: Boolean = true): DriveFileMeta =
        add(parentId, DriveFileMeta("id${nextId++}", name, null, null, null, true, tagged, true))

    fun addFile(
        parentId: String,
        name: String,
        size: Long,
        md5: String?,
        tagged: Boolean = true,
        created: Long? = null,
    ): DriveFileMeta = add(parentId, DriveFileMeta("id${nextId++}", name, size, md5, created, false, tagged, true))

    private fun add(parentId: String, meta: DriveFileMeta): DriveFileMeta {
        nodes[meta.id] = Node(meta, parentId)
        return meta
    }

    fun childrenNames(parentId: String) = nodes.values.filter { it.parentId == parentId }.map { it.meta.name }

    override suspend fun listFolders(parentId: String?) =
        nodes.values.filter { it.parentId == (parentId ?: "root") && it.meta.isFolder }
            .map { DriveFolder(it.meta.id, it.meta.name) }

    override suspend fun createFolder(name: String, parentId: String?): DriveFolder {
        createdFolders++
        val m = addFolder(parentId ?: "root", name)
        return DriveFolder(m.id, m.name)
    }

    override suspend fun getFile(fileId: String) = nodes[fileId]?.meta
    override suspend fun listChildren(parentId: String) =
        nodes.values.filter { it.parentId == parentId }.map { it.meta }

    override suspend fun initiateResumableUpload(parentId: String, fileName: String, mimeType: String, totalBytes: Long): String =
        error("unused")

    override suspend fun uploadChunk(sessionUri: String, data: ByteArray, length: Int, offset: Long, totalBytes: Long): ChunkUploadResult =
        error("unused")

    override suspend fun querySessionProgress(sessionUri: String, totalBytes: Long): SessionProgress = error("unused")

    override suspend fun uploadSmallFile(parentId: String, fileName: String, mimeType: String, data: ByteArray): DriveFileMeta =
        error("unused")

    override suspend fun trashFile(fileId: String) {
        if (fileId in trashFailures) throw IOException("boom")
        nodes.remove(fileId) ?: throw DriveHttpException(404, "notFound", "not found")
        trashed += fileId
    }
}

class FakeSyncedFileRepository : SyncedFileRepository {
    val map = linkedMapOf<String, SyncedFile>()
    override suspend fun get(relativePath: String) = map[relativePath]
    override suspend fun getAll() = map.values.toList()
    override suspend fun upsert(file: SyncedFile) { map[file.relativePath] = file }
    override suspend fun delete(relativePath: String) { map.remove(relativePath) }
    override suspend fun clear() = map.clear()
}

class FakeUploadHistoryRepository : UploadHistoryRepository {
    val records = mutableListOf<NewUploadRecord>()
    val extraSuccessIds = mutableSetOf<String>()
    override suspend fun record(record: NewUploadRecord) { records += record }
    override fun getAll(): Flow<List<UploadRecord>> = flowOf(emptyList())
    override fun getLatest(): Flow<UploadRecord?> = flowOf(null)
    override fun getLatestSuccess(): Flow<UploadRecord?> = flowOf(null)
    override suspend fun getSuccessfulDriveFileIds(): Set<String> =
        records.filter { it.status == UploadResultStatus.SUCCESS }.mapNotNull { it.driveFileId }.toSet() +
            extraSuccessIds
}

fun md5Of(f: LocalBackupFile) = "md5-${f.relativePath}-${f.sizeBytes}"

/** A FileUploader mock that "uploads" into [drive] and reports full progress. */
fun fakeUploader(drive: FakeDriveRepository, uploaded: MutableList<String> = mutableListOf()): FileUploader {
    val uploader = mockk<FileUploader>()
    coEvery { uploader.md5(any()) } coAnswers { md5Of(firstArg()) }
    coEvery { uploader.upload(any(), any(), any()) } coAnswers {
        val f = firstArg<LocalBackupFile>()
        val parent = secondArg<String>()
        thirdArg<(Long) -> Unit>().invoke(f.sizeBytes)
        uploaded += f.relativePath
        val meta = drive.addFile(parent, f.name, f.sizeBytes, md5Of(f))
        VerifiedUpload(meta, md5Of(f), f.sizeBytes)
    }
    return uploader
}
