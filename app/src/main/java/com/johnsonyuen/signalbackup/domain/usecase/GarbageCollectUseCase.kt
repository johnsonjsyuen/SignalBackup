package com.johnsonyuen.signalbackup.domain.usecase

import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.data.repository.SyncedFileRepository
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.mirror.BackupFolderScanner
import com.johnsonyuen.signalbackup.domain.mirror.BackupNames
import com.johnsonyuen.signalbackup.domain.mirror.LocalFolderInaccessibleException
import com.johnsonyuen.signalbackup.domain.mirror.LocalReadException
import com.johnsonyuen.signalbackup.domain.model.DriveFile
import com.johnsonyuen.signalbackup.domain.model.DriveFileMeta
import com.johnsonyuen.signalbackup.domain.model.DriveHttpException
import com.johnsonyuen.signalbackup.domain.model.GcScanResult
import com.johnsonyuen.signalbackup.domain.model.GcTrashResult
import com.johnsonyuen.signalbackup.domain.model.SyncedFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Manual "Clean Up" (decision 4): finds Drive items whose local counterpart Signal deleted, plus
 * older app-uploaded legacy backups, keeping the newest [KEEP_COUNT] backup items. Trash only.
 */
class GarbageCollectUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val driveRepository: DriveRepository,
    private val syncedFileRepository: SyncedFileRepository,
    private val uploadHistoryRepository: UploadHistoryRepository,
    private val scanner: BackupFolderScanner,
    private val clock: Clock,
) {

    /** May throw NeedsConsentException, NotSignedInException, DriveHttpException, IOException. */
    suspend fun scan(): GcScanResult {
        val settings = settingsRepository.current()
        val rootId = settings.driveFolderId ?: return GcScanResult.NothingToClean
        val localUri = settings.localFolderUri
            ?: return GcScanResult.Blocked("Select the Signal backup folder first")
        val local = try {
            scanner.scan(localUri)
        } catch (e: LocalFolderInaccessibleException) {
            return GcScanResult.Blocked(MSG_LOCAL_UNREADABLE)
        } catch (e: LocalReadException) {
            return GcScanResult.Blocked(MSG_LOCAL_UNREADABLE)
        }
        val present = local.presentPaths

        val synced = syncedFileRepository.getAll()
        val syncedIds = synced.mapTo(HashSet()) { it.driveFileId }
        val successIds = uploadHistoryRepository.getSuccessfulDriveFileIds()
        val rootChildren = driveRepository.listChildren(rootId)
        val rootIds = rootChildren.mapTo(HashSet()) { it.id }

        val zone = clock.zone
        val items = rootChildren
            .filter { BackupNames.isBackupItem(it.name, it.isFolder) }
            .sortedByDescending { BackupNames.timestampMillis(it.name, zone) ?: it.createdTimeMillis ?: 0L }
        if (items.isEmpty()) return GcScanResult.NothingToClean
        val newest = items.first()
        if (newest.id !in syncedIds && newest.id !in successIds) {
            return GcScanResult.Blocked("The newest backup on Drive isn't verified yet. Run a backup first.")
        }
        val kept = items.take(KEEP_COUNT)
        val keptIds = kept.mapTo(HashSet()) { it.id }
        val keptNames = kept.mapTo(HashSet()) { it.name }

        val candidates = LinkedHashMap<String, DriveFile>()

        // (a) Mirrored items whose local counterpart is gone.
        val missing = synced.filter { r ->
            r.relativePath !in present &&
                r.driveFileId !in keptIds &&
                topLevel(r.relativePath) !in keptNames &&
                ('/' in r.relativePath || r.driveFileId in rootIds)
        }
        val missingDirs = missing.filter { it.isDirectory }.mapTo(HashSet()) { it.relativePath }
        for (r in missing) {
            if (hasAncestorIn(r.relativePath, missingDirs)) continue // trashed with its folder
            val meta = rootChildren.firstOrNull { it.id == r.driveFileId }
            candidates[r.driveFileId] = DriveFile(
                id = r.driveFileId,
                name = r.relativePath,
                sizeBytes = if (r.isDirectory) sizeUnder(r.relativePath, synced) else r.sizeBytes,
                isFolder = r.isDirectory,
                createdTimeMillis = meta?.createdTimeMillis ?: r.syncedAtMillis,
            )
        }

        // (b) Older legacy .backup files at the top level that this app uploaded.
        for (m in items.drop(KEEP_COUNT)) {
            if (m.isFolder || m.id in candidates || m.name in present) continue
            if (!(m.uploadedByApp || m.id in successIds || m.id in syncedIds)) continue
            candidates[m.id] = m.toDriveFile(synced)
        }

        if (candidates.isEmpty()) return GcScanResult.NothingToClean
        return GcScanResult.Ready(kept = kept.map { it.toDriveFile(synced) }, toTrash = candidates.values.toList())
    }

    /** Moves [items] to Drive's trash, continuing past individual failures. */
    suspend fun trash(items: List<DriveFile>): GcTrashResult {
        var trashed = 0
        var freed = 0L
        val failed = mutableListOf<String>()
        for (item in items) {
            currentCoroutineContext().ensureActive()
            try {
                var alreadyGone = false
                try {
                    driveRepository.trashFile(item.id)
                } catch (e: DriveHttpException) {
                    if (e.statusCode != 404) throw e
                    alreadyGone = true
                }
                removeRecords(item.id)
                trashed++
                if (!alreadyGone) freed += item.sizeBytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += item.name
            }
        }
        return GcTrashResult(trashedCount = trashed, freedBytes = freed, failedNames = failed)
    }

    private suspend fun removeRecords(driveId: String) {
        val all = syncedFileRepository.getAll()
        val roots = all.filter { it.driveFileId == driveId }.map { it.relativePath }
        for (root in roots) {
            val prefix = "$root/"
            all.filter { it.relativePath == root || it.relativePath.startsWith(prefix) }
                .forEach { syncedFileRepository.delete(it.relativePath) }
        }
    }

    private fun DriveFileMeta.toDriveFile(synced: List<SyncedFile>) = DriveFile(
        id = id,
        name = name,
        sizeBytes = if (isFolder) sizeUnder(name, synced) else sizeBytes ?: 0L,
        isFolder = isFolder,
        createdTimeMillis = createdTimeMillis,
    )

    private fun sizeUnder(path: String, synced: List<SyncedFile>): Long {
        val prefix = "$path/"
        return synced.filter { !it.isDirectory && it.relativePath.startsWith(prefix) }.sumOf { it.sizeBytes }
    }

    private fun topLevel(path: String) = path.substringBefore('/')

    private fun hasAncestorIn(path: String, dirs: Set<String>): Boolean {
        var p = path
        while ('/' in p) {
            p = p.substringBeforeLast('/')
            if (p in dirs) return true
        }
        return false
    }

    companion object {
        const val KEEP_COUNT = 3
        private const val MSG_LOCAL_UNREADABLE =
            "Can't read the local backup folder, so nothing can be safely cleaned up"
    }
}
