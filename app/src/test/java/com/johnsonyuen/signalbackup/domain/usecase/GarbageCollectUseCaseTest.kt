package com.johnsonyuen.signalbackup.domain.usecase

import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.mirror.BackupFolderScanner
import com.johnsonyuen.signalbackup.domain.mirror.FakeDocumentTreeSource
import com.johnsonyuen.signalbackup.domain.mirror.FakeDocumentTreeSource.Companion.ROOT
import com.johnsonyuen.signalbackup.domain.mirror.FakeDriveRepository
import com.johnsonyuen.signalbackup.domain.mirror.FakeSyncedFileRepository
import com.johnsonyuen.signalbackup.domain.mirror.FakeUploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.mirror.OLD
import com.johnsonyuen.signalbackup.domain.mirror.TEST_CLOCK
import com.johnsonyuen.signalbackup.domain.mirror.TREE_URI
import com.johnsonyuen.signalbackup.domain.model.AppSettings
import com.johnsonyuen.signalbackup.domain.model.DriveFileMeta
import com.johnsonyuen.signalbackup.domain.model.GcScanResult
import com.johnsonyuen.signalbackup.domain.model.SyncedFile
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GarbageCollectUseCaseTest {
    private val drive = FakeDriveRepository()
    private val driveRoot = drive.addFolder("myDrive", "Signal Backup").id
    private val settings = AppSettings(TREE_URI, driveRoot, "Signal Backup", true, 3, 0, "user@example.com", "SYSTEM", false)
    private val settingsRepo = mockk<SettingsRepository>().also { coEvery { it.current() } returns settings }
    private val source = FakeDocumentTreeSource()
    private val synced = FakeSyncedFileRepository()
    private val history = FakeUploadHistoryRepository()
    private val gc = GarbageCollectUseCase(
        settingsRepo, drive, synced, history, BackupFolderScanner(source, TEST_CLOCK), TEST_CLOCK,
    )

    private fun legacy(day: Int, tagged: Boolean = true): DriveFileMeta =
        drive.addFile(driveRoot, "signal-2026-10-%02d-03-00-00.backup".format(day), 100L * day, "m$day", tagged)

    private fun fiveLegacyWithVerifiedNewest(): List<DriveFileMeta> {
        val files = (1..5).map { legacy(it) }
        history.extraSuccessIds += files.last().id
        return files
    }

    @Test
    fun keepsThreeNewestAndTrashesOlderAppUploads() = runTest {
        val files = fiveLegacyWithVerifiedNewest()

        val result = gc.scan() as GcScanResult.Ready

        assertEquals(listOf(files[4].id, files[3].id, files[2].id), result.kept.map { it.id })
        assertEquals(setOf(files[0].id, files[1].id), result.toTrash.map { it.id }.toSet())
    }

    @Test
    fun neverTouchesItemsStillPresentLocally() = runTest {
        val files = fiveLegacyWithVerifiedNewest()
        source.file(ROOT, files[0].name)

        val result = gc.scan() as GcScanResult.Ready

        assertEquals(listOf(files[1].id), result.toTrash.map { it.id })
    }

    @Test
    fun blockedWhenNewestNotVerified() = runTest {
        (1..5).map { legacy(it) }
        assertTrue(gc.scan() is GcScanResult.Blocked)
    }

    @Test
    fun blockedWhenLocalFolderUnreadable() = runTest {
        fiveLegacyWithVerifiedNewest()
        source.hasPermission = false
        assertTrue(gc.scan() is GcScanResult.Blocked)
    }

    @Test
    fun unverifiedItemsAreNotTouched() = runTest {
        legacy(1, tagged = false)
        legacy(2, tagged = true)
        val newer = (3..5).map { legacy(it) }
        history.extraSuccessIds += newer.last().id

        val result = gc.scan() as GcScanResult.Ready

        assertEquals(listOf("signal-2026-10-02-03-00-00.backup"), result.toTrash.map { it.name })
    }

    @Test
    fun nothingToCleanWithThreeOrFewer() = runTest {
        val files = (1..3).map { legacy(it) }
        history.extraSuccessIds += files.last().id
        assertEquals(GcScanResult.NothingToClean, gc.scan())
    }

    @Test
    fun snapshotDeletedLocallyIsTrashedAsOneFolderAndRecordsRemoved() = runTest {
        // Old snapshot mirrored earlier, since deleted by Signal.
        val old = snapshot("signal-backup-2026-10-10-03-00-00", present = false)
        // Three newer snapshots still present locally.
        listOf(16, 17, 18).forEach { snapshot("signal-backup-2026-10-%02d-03-00-00".format(it), present = true) }

        val result = gc.scan() as GcScanResult.Ready

        val item = result.toTrash.single()
        assertEquals(old.id, item.id)
        assertTrue(item.isFolder)
        assertEquals(30L, item.sizeBytes)

        val trash = gc.trash(result.toTrash)

        assertEquals(1, trash.trashedCount)
        assertEquals(30L, trash.freedBytes)
        assertTrue(synced.map.keys.none { it.startsWith("signal-backup-2026-10-10") })
        assertEquals(listOf(old.id), drive.trashed)
    }

    @Test
    fun trashContinuesPastFailures() = runTest {
        val files = fiveLegacyWithVerifiedNewest()
        val result = gc.scan() as GcScanResult.Ready
        drive.trashFailures += files[0].id

        val trash = gc.trash(result.toTrash)

        assertEquals(1, trash.trashedCount)
        assertEquals(listOf(files[0].name), trash.failedNames)
        assertEquals(listOf(files[1].id), drive.trashed)
    }

    /** Creates a mirrored snapshot (Drive folder + synced records), optionally present locally. */
    private suspend fun snapshot(name: String, present: Boolean): DriveFileMeta {
        val folder = drive.addFolder(driveRoot, name)
        val db = drive.addFile(folder.id, "backup.db", 20, "a")
        val manifest = drive.addFile(folder.id, "manifest.json", 10, "b")
        synced.upsert(SyncedFile(name, true, 0, OLD, folder.id, null, OLD))
        synced.upsert(SyncedFile("$name/backup.db", false, 20, OLD, db.id, "a", OLD))
        synced.upsert(SyncedFile("$name/manifest.json", false, 10, OLD, manifest.id, "b", OLD))
        if (present) {
            val d = source.dir(ROOT, name)
            source.file(d, "backup.db", size = 20)
            source.file(d, "manifest.json", size = 10)
        }
        return folder
    }
}
