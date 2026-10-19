package com.johnsonyuen.signalbackup.domain.mirror

import com.johnsonyuen.signalbackup.domain.mirror.FakeDocumentTreeSource.Companion.ROOT
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class BackupFolderScannerTest {
    private val source = FakeDocumentTreeSource()
    private val scanner = BackupFolderScanner(source, TEST_CLOCK)

    @Test
    fun legacyOnlyFolder() = runTest {
        source.file(ROOT, "signal-2026-10-17-03-00-00.backup", size = 10)
        source.file(ROOT, "signal-2026-10-18-03-00-00.backup", size = 20)
        source.file(ROOT, "notes.txt")

        val snap = scanner.scan(TREE_URI)

        assertEquals(3, snap.files.size)
        assertEquals(
            listOf("signal-2026-10-17-03-00-00.backup", "signal-2026-10-18-03-00-00.backup"),
            snap.backupItems.map { it.name },
        )
        val newest = snap.newestBackupItem!!
        assertFalse(newest.isSnapshot)
        assertEquals(20L, newest.sizeBytes)
        val expected = LocalDateTime.of(2026, 10, 18, 3, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expected, newest.timestampMillis)
    }

    @Test
    fun snapshotLayout() = runTest {
        val snap1 = source.dir(ROOT, "signal-backup-2026-10-18-03-00-00")
        source.file(snap1, "backup.db", size = 1000)
        source.file(snap1, "manifest.json", size = 5)
        val att = source.dir(snap1, "attachments")
        source.file(att, "a1", size = 50)
        source.file(att, "a2", size = 70)

        val snap = scanner.scan(TREE_URI)

        assertEquals(
            listOf("signal-backup-2026-10-18-03-00-00", "signal-backup-2026-10-18-03-00-00/attachments"),
            snap.directories.map { it.relativePath },
        )
        assertEquals(4, snap.files.size)
        assertTrue(snap.files.any { it.relativePath == "signal-backup-2026-10-18-03-00-00/attachments/a2" })
        val item = snap.backupItems.single()
        assertTrue(item.isSnapshot)
        assertEquals(1125L, item.sizeBytes)
    }

    @Test
    fun mixedLayoutOrdersByNameTimestamp() = runTest {
        source.file(ROOT, "signal-2026-10-10-03-00-00.backup", lastModified = OLD)
        val s = source.dir(ROOT, "signal-backup-2026-10-18-03-00-00", lastModified = 0)
        source.file(s, "backup.db", lastModified = OLD - 30 * DAY) // lastModified doesn't win over name
        source.dir(ROOT, "signal-backup-empty")

        val snap = scanner.scan(TREE_URI)

        assertEquals(
            listOf("signal-2026-10-10-03-00-00.backup", "signal-backup-2026-10-18-03-00-00"),
            snap.backupItems.map { it.name },
        )
        assertEquals("signal-backup-2026-10-18-03-00-00", snap.newestBackupItem!!.name)
    }

    @Test
    fun skipRules() = runTest {
        source.file(ROOT, ".hidden")
        val hiddenDir = source.dir(ROOT, ".thumbs")
        source.file(hiddenDir, "x")
        source.file(ROOT, "signal-2026-10-18-03-00-00.backup.tmp")
        source.file(ROOT, "part.PARTIAL")
        source.file(ROOT, "signal-2026-10-19-11-59-00.backup", lastModified = NOW.toEpochMilli() - 60_000)
        source.file(ROOT, "signal-2026-10-18-03-00-00.backup")

        val snap = scanner.scan(TREE_URI)

        assertEquals(listOf("signal-2026-10-18-03-00-00.backup"), snap.files.map { it.relativePath })
        assertTrue(snap.directories.isEmpty())
        // Skipped-but-present files still count as present locally (for Clean Up safety).
        assertTrue("signal-2026-10-19-11-59-00.backup" in snap.presentPaths)
        assertFalse(".hidden" in snap.presentPaths)
    }

    @Test
    fun emptyFolderHasNoBackupItems() = runTest {
        val snap = scanner.scan(TREE_URI)
        assertNull(snap.newestBackupItem)
    }

    @Test
    fun lostPermissionIsInaccessible() = runTest {
        source.hasPermission = false
        expect<LocalFolderInaccessibleException> { scanner.scan(TREE_URI) }
    }

    @Test
    fun securityExceptionIsInaccessible() = runTest {
        source.failures[ROOT] = SecurityException("revoked")
        expect<LocalFolderInaccessibleException> { scanner.scan(TREE_URI) }
    }

    @Test
    fun subfolderErrorIsReadError() = runTest {
        val d = source.dir(ROOT, "signal-backup-2026-10-18-03-00-00")
        source.failures[d] = IllegalStateException("provider crashed")
        expect<LocalReadException> { scanner.scan(TREE_URI) }
    }

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }
}
