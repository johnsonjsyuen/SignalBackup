package com.johnsonyuen.signalbackup.domain.mirror

import com.johnsonyuen.signalbackup.domain.model.LocalBackupFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Recursively lists the selected SAF tree (one query per directory), applies the skip rules and
 * identifies Signal backup items at the top level.
 */
class BackupFolderScanner @Inject constructor(
    private val source: DocumentTreeSource,
    private val clock: Clock,
) {

    /**
     * @throws LocalFolderInaccessibleException when the folder permission is gone or the root can't be read.
     * @throws LocalReadException when a subfolder can't be listed.
     */
    suspend fun scan(treeUri: String): LocalFolderSnapshot {
        if (!source.hasPersistedReadPermission(treeUri)) throw LocalFolderInaccessibleException()
        val rootId = try {
            source.rootDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            throw LocalFolderInaccessibleException()
        }

        val now = clock.millis()
        val files = mutableListOf<LocalBackupFile>()
        val directories = mutableListOf<LocalDirectory>()
        val present = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>() // documentId to relativePath ("" = root)
        queue.addLast(rootId to "")

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (docId, path) = queue.removeFirst()
            val isRoot = path.isEmpty()
            val children = listOrThrow(treeUri, docId, isRoot)
            for (entry in children.sortedBy { it.name }) {
                if (entry.name.isEmpty() || entry.name.startsWith(".")) continue
                val rel = if (isRoot) entry.name else "$path/${entry.name}"
                present += rel
                if (entry.isDirectory) {
                    directories += LocalDirectory(rel, entry.name, entry.lastModifiedMillis)
                    queue.addLast(entry.documentId to rel)
                } else if (isEligible(entry, now)) {
                    files += LocalBackupFile(
                        uri = entry.uri,
                        relativePath = rel,
                        name = entry.name,
                        sizeBytes = entry.sizeBytes,
                        lastModifiedMillis = entry.lastModifiedMillis,
                    )
                }
            }
        }

        return LocalFolderSnapshot(
            files = files,
            directories = directories,
            backupItems = identifyBackupItems(files, directories),
            presentPaths = present,
        )
    }

    private suspend fun listOrThrow(treeUri: String, docId: String, isRoot: Boolean): List<DocumentEntry> {
        val children = try {
            source.listChildren(treeUri, docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw LocalFolderInaccessibleException()
        } catch (e: Exception) {
            if (isRoot) throw LocalFolderInaccessibleException()
            throw LocalReadException("Couldn't list a backup subfolder")
        }
        return children ?: if (isRoot) {
            throw LocalFolderInaccessibleException()
        } else {
            throw LocalReadException("Couldn't list a backup subfolder")
        }
    }

    private fun isEligible(entry: DocumentEntry, now: Long): Boolean {
        val name = entry.name.lowercase()
        if (name.endsWith(".tmp") || name.endsWith(".partial")) return false
        // Still being written by Signal (0 = unknown, don't skip).
        if (entry.lastModifiedMillis > 0 && now - entry.lastModifiedMillis < MIN_FILE_AGE_MILLIS) return false
        return true
    }

    private fun identifyBackupItems(
        files: List<LocalBackupFile>,
        directories: List<LocalDirectory>,
    ): List<BackupItem> {
        val zone = clock.zone
        val items = mutableListOf<BackupItem>()
        files.filter { '/' !in it.relativePath && BackupNames.isLegacyBackupFile(it.name) }.forEach {
            items += BackupItem(
                name = it.name,
                isSnapshot = false,
                timestampMillis = BackupNames.timestampMillis(it.name, zone),
                lastModifiedMillis = it.lastModifiedMillis,
                sizeBytes = it.sizeBytes,
            )
        }
        directories.filter { '/' !in it.relativePath && BackupNames.isSnapshotFolder(it.name) }.forEach { dir ->
            val prefix = dir.relativePath + "/"
            val contents = files.filter { it.relativePath.startsWith(prefix) }
            if (contents.isEmpty()) return@forEach
            items += BackupItem(
                name = dir.name,
                isSnapshot = true,
                timestampMillis = BackupNames.timestampMillis(dir.name, zone),
                lastModifiedMillis = maxOf(dir.lastModifiedMillis, contents.maxOf { it.lastModifiedMillis }),
                sizeBytes = contents.sumOf { it.sizeBytes },
            )
        }
        return items.sortedWith(compareBy({ it.sortTimeMillis }, { it.name }))
    }

    companion object {
        const val MIN_FILE_AGE_MILLIS = 2 * 60 * 1000L
    }
}
