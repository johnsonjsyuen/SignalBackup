package com.johnsonyuen.signalbackup.domain.mirror

import com.johnsonyuen.signalbackup.domain.mirror.FakeDocumentTreeSource.Companion.ROOT
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorSyncerTest {
    private val source = FakeDocumentTreeSource()
    private val scanner = BackupFolderScanner(source, TEST_CLOCK)
    private val drive = FakeDriveRepository()
    private val synced = FakeSyncedFileRepository()
    private val uploaded = mutableListOf<String>()
    private val uploader = fakeUploader(drive, uploaded)
    private val syncer = MirrorSyncer(drive, uploader, synced, TEST_CLOCK)
    private val driveRoot = drive.addFolder("myDrive", "Signal Backup").id

    private fun mixedTree() {
        val newer = source.dir(ROOT, "signal-backup-2026-10-18-03-00-00")
        source.file(newer, "manifest.json", size = 5)
        source.file(newer, "backup.db", size = 1000)
        source.file(source.dir(newer, "attachments"), "a1", size = 50)
        val older = source.dir(ROOT, "signal-backup-2026-10-17-03-00-00")
        source.file(older, "manifest.json", size = 4)
        source.file(older, "backup.db", size = 900)
        source.file(ROOT, "signal-2026-10-01-03-00-00.backup", size = 300)
    }

    @Test
    fun mirrorsTreeInOrderAndRecordsEverything() = runTest {
        mixedTree()
        val progress = mutableListOf<Triple<Long, Long, String?>>()

        val outcome = syncer.sync(scanner.scan(TREE_URI), driveRoot, { b, t, n -> progress += Triple(b, t, n) })

        assertEquals(
            listOf(
                "signal-2026-10-01-03-00-00.backup",
                "signal-backup-2026-10-17-03-00-00/backup.db",
                "signal-backup-2026-10-17-03-00-00/manifest.json",
                "signal-backup-2026-10-18-03-00-00/attachments/a1",
                "signal-backup-2026-10-18-03-00-00/backup.db",
                "signal-backup-2026-10-18-03-00-00/manifest.json",
            ),
            uploaded,
        )
        assertEquals(6, outcome.filesUploaded)
        assertEquals(2259L, outcome.bytesUploaded)
        assertEquals(3, drive.createdFolders)
        assertTrue(synced.map["signal-backup-2026-10-18-03-00-00/attachments"]!!.isDirectory)
        assertEquals(9, synced.map.size) // 6 files + 3 dirs
        assertEquals(Triple(2259L, 2259L, null), progress.last())
        assertTrue(progress.all { it.second == 2259L })
        assertEquals(
            listOf("signal-backup-2026-10-18-03-00-00", "signal-backup-2026-10-17-03-00-00", "signal-2026-10-01-03-00-00.backup")
                .toSet(),
            drive.childrenNames(driveRoot).toSet(),
        )
    }

    @Test
    fun alreadySyncedFilesAreSkipped() = runTest {
        mixedTree()
        syncer.sync(scanner.scan(TREE_URI), driveRoot)
        uploaded.clear()

        val outcome = syncer.sync(scanner.scan(TREE_URI), driveRoot)

        assertEquals(0, outcome.filesUploaded)
        assertEquals(0L, outcome.bytesUploaded)
        assertTrue(uploaded.isEmpty())
        assertEquals(3, drive.createdFolders)
    }

    @Test
    fun changedFileIsUploadedAgain() = runTest {
        source.file(ROOT, "notes.txt", size = 10)
        syncer.sync(scanner.scan(TREE_URI), driveRoot)
        val fresh = FakeDocumentTreeSource().apply { file(ROOT, "notes.txt", size = 11) }

        val outcome = syncer.sync(BackupFolderScanner(fresh, TEST_CLOCK).scan(TREE_URI), driveRoot)

        assertEquals(1, outcome.filesUploaded)
        assertEquals(11L, synced.map["notes.txt"]!!.sizeBytes)
    }

    @Test
    fun adoptsExistingDriveFileWithMatchingMd5() = runTest {
        source.file(ROOT, "signal-2026-10-01-03-00-00.backup", size = 300)
        val snap = scanner.scan(TREE_URI)
        val legacy = drive.addFile(driveRoot, "signal-2026-10-01-03-00-00.backup", 300, md5Of(snap.files.single()))

        val outcome = syncer.sync(snap, driveRoot)

        assertEquals(0, outcome.filesUploaded)
        assertEquals(1, outcome.filesAdopted)
        assertEquals(legacy.id, synced.map["signal-2026-10-01-03-00-00.backup"]!!.driveFileId)
        coVerify(exactly = 0) { uploader.upload(any(), any(), any()) }
    }

    @Test
    fun doesNotAdoptOnMd5Mismatch() = runTest {
        source.file(ROOT, "signal-2026-10-01-03-00-00.backup", size = 300)
        drive.addFile(driveRoot, "signal-2026-10-01-03-00-00.backup", 300, "other")

        val outcome = syncer.sync(scanner.scan(TREE_URI), driveRoot)

        assertEquals(1, outcome.filesUploaded)
    }

    @Test
    fun reusesExistingDriveFolder() = runTest {
        val d = source.dir(ROOT, "signal-backup-2026-10-18-03-00-00")
        source.file(d, "backup.db")
        val existing = drive.addFolder(driveRoot, "signal-backup-2026-10-18-03-00-00")

        syncer.sync(scanner.scan(TREE_URI), driveRoot)

        assertEquals(0, drive.createdFolders)
        assertEquals(existing.id, synced.map["signal-backup-2026-10-18-03-00-00"]!!.driveFileId)
    }

    @Test
    fun topLevelItemDeletedOnDriveIsMirroredAgain() = runTest {
        mixedTree()
        syncer.sync(scanner.scan(TREE_URI), driveRoot)
        val folderId = synced.map["signal-backup-2026-10-17-03-00-00"]!!.driveFileId
        drive.nodes.remove(folderId)
        uploaded.clear()

        syncer.sync(scanner.scan(TREE_URI), driveRoot)

        assertEquals(
            listOf("signal-backup-2026-10-17-03-00-00/backup.db", "signal-backup-2026-10-17-03-00-00/manifest.json"),
            uploaded,
        )
    }
}
