package com.johnsonyuen.signalbackup.domain.mirror

import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.SyncedFileRepository
import com.johnsonyuen.signalbackup.domain.model.DriveFileMeta
import com.johnsonyuen.signalbackup.domain.model.LocalBackupFile
import com.johnsonyuen.signalbackup.domain.model.MirrorProgressListener
import com.johnsonyuen.signalbackup.domain.model.SyncedFile
import com.johnsonyuen.signalbackup.domain.upload.FileUploader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock
import javax.inject.Inject

/** Counters updated as a mirror pass progresses (readable even if the pass throws). */
class MirrorStats {
    var bytesUploaded: Long = 0L
    var filesUploaded: Int = 0
    var filesAdopted: Int = 0
}

/** Result of a completed mirror pass. [records] are the synced_file rows after the pass. */
data class MirrorOutcome(
    val bytesUploaded: Long,
    val filesUploaded: Int,
    val filesAdopted: Int,
    val records: Map<String, SyncedFile>,
)

/**
 * Mirrors a [LocalFolderSnapshot] into a Drive folder: same relative paths, directories as tagged
 * Drive folders, each changed file uploaded (or adopted when an identical copy exists) and recorded.
 */
class MirrorSyncer @Inject constructor(
    private val driveRepository: DriveRepository,
    private val fileUploader: FileUploader,
    private val syncedFileRepository: SyncedFileRepository,
    private val clock: Clock,
) {

    suspend fun sync(
        snapshot: LocalFolderSnapshot,
        driveRootId: String,
        progress: MirrorProgressListener? = null,
        stats: MirrorStats = MirrorStats(),
    ): MirrorOutcome {
        val records = syncedFileRepository.getAll().associateBy { it.relativePath }.toMutableMap()
        val run = RunState(driveRootId)
        val rootChildren = driveRepository.listChildren(driveRootId)
        run.children[driveRootId] = rootChildren
        invalidateMissingTopLevel(records, rootChildren)

        // Directories: parents come before children in the snapshot.
        for (dir in snapshot.directories) {
            currentCoroutineContext().ensureActive()
            ensureFolder(dir, records, run)
        }

        val pending = snapshot.files.filter { f ->
            val r = records[f.relativePath]
            r == null || r.isDirectory || r.sizeBytes != f.sizeBytes ||
                r.lastModifiedMillis != f.lastModifiedMillis
        }
        val ordered = orderForUpload(pending, snapshot.backupItems)
        val total = ordered.sumOf { it.sizeBytes }
        var done = 0L
        if (ordered.isNotEmpty()) progress?.onProgress(0L, total, null)

        for (file in ordered) {
            currentCoroutineContext().ensureActive()
            val parentPath = file.relativePath.substringBeforeLast('/', "")
            val parentId = run.folderIds[parentPath]
                ?: throw LocalReadException("Backup subfolder changed during scan")

            val adopted = findAdoptable(file, parentId, run)
            val driveId: String
            val md5: String
            if (adopted != null) {
                driveId = adopted.first
                md5 = adopted.second
                stats.filesAdopted++
            } else {
                val base = done
                val verified = fileUploader.upload(file, parentId) { sent ->
                    progress?.onProgress(base + sent.coerceIn(0L, file.sizeBytes), total, file.name)
                }
                driveId = verified.file.id
                md5 = verified.md5
                stats.bytesUploaded += verified.bytesSent
                stats.filesUploaded++
            }
            done += file.sizeBytes
            val record = SyncedFile(
                relativePath = file.relativePath,
                isDirectory = false,
                sizeBytes = file.sizeBytes,
                lastModifiedMillis = file.lastModifiedMillis,
                driveFileId = driveId,
                md5Checksum = md5,
                syncedAtMillis = clock.millis(),
            )
            syncedFileRepository.upsert(record)
            records[file.relativePath] = record
            progress?.onProgress(done, total, null)
        }

        return MirrorOutcome(stats.bytesUploaded, stats.filesUploaded, stats.filesAdopted, records.toMap())
    }

    private class RunState(rootId: String) {
        val folderIds = mutableMapOf("" to rootId)
        val children = mutableMapOf<String, List<DriveFileMeta>>()
        val createdFolderIds = mutableSetOf<String>()
    }

    /** Top-level items deleted from Drive by the user lose their records so they're re-mirrored. */
    private suspend fun invalidateMissingTopLevel(
        records: MutableMap<String, SyncedFile>,
        rootChildren: List<DriveFileMeta>,
    ) {
        val rootIds = rootChildren.mapTo(HashSet()) { it.id }
        val missingTop = records.values
            .filter { '/' !in it.relativePath && it.driveFileId !in rootIds }
            .map { it.relativePath }
        for (top in missingTop) {
            val prefix = "$top/"
            val doomed = records.keys.filter { it == top || it.startsWith(prefix) }
            for (path in doomed) {
                syncedFileRepository.delete(path)
                records.remove(path)
            }
        }
    }

    private suspend fun ensureFolder(
        dir: LocalDirectory,
        records: MutableMap<String, SyncedFile>,
        run: RunState,
    ) {
        val existing = records[dir.relativePath]
        if (existing != null && existing.isDirectory) {
            run.folderIds[dir.relativePath] = existing.driveFileId
            return
        }
        val parentPath = dir.relativePath.substringBeforeLast('/', "")
        val parentId = run.folderIds[parentPath] ?: return
        val reusable = childrenOf(parentId, run)
            .filter { it.isFolder && it.name == dir.name }
            .sortedByDescending { it.uploadedByApp }
            .firstOrNull()
        val folderId = if (reusable != null) {
            reusable.id
        } else {
            driveRepository.createFolder(dir.name, parentId).id.also { run.createdFolderIds += it }
        }
        run.folderIds[dir.relativePath] = folderId
        val record = SyncedFile(
            relativePath = dir.relativePath,
            isDirectory = true,
            sizeBytes = 0L,
            lastModifiedMillis = dir.lastModifiedMillis,
            driveFileId = folderId,
            md5Checksum = null,
            syncedAtMillis = clock.millis(),
        )
        syncedFileRepository.upsert(record)
        records[dir.relativePath] = record
    }

    /** Returns (driveId, md5) of an existing Drive file identical to [file], or null. */
    private suspend fun findAdoptable(
        file: LocalBackupFile,
        parentId: String,
        run: RunState,
    ): Pair<String, String>? {
        val candidates = childrenOf(parentId, run).filter {
            !it.isFolder && it.name == file.name && it.sizeBytes == file.sizeBytes && it.md5Checksum != null
        }
        if (candidates.isEmpty()) return null
        val localMd5 = fileUploader.md5(file)
        val match = candidates.firstOrNull { it.md5Checksum.equals(localMd5, ignoreCase = true) }
            ?: return null
        return match.id to localMd5
    }

    private suspend fun childrenOf(parentId: String, run: RunState): List<DriveFileMeta> {
        if (parentId in run.createdFolderIds) return emptyList()
        return run.children.getOrPut(parentId) { driveRepository.listChildren(parentId) }
    }

    companion object {
        /**
         * Upload order: files outside backup items first, then backup items oldest first; inside an
         * item, manifest.json goes last so a snapshot only looks complete once everything else is up.
         */
        fun orderForUpload(files: List<LocalBackupFile>, items: List<BackupItem>): List<LocalBackupFile> {
            val itemTime = items.associate { it.name to it.sortTimeMillis }
            fun top(f: LocalBackupFile) = f.relativePath.substringBefore('/')
            return files.sortedWith(
                compareBy<LocalBackupFile>(
                    { itemTime[top(it)] ?: Long.MIN_VALUE },
                    { top(it) },
                    { if (it.name == BackupNames.MANIFEST_FILE_NAME) 1 else 0 },
                    { it.relativePath },
                ),
            )
        }
    }
}
