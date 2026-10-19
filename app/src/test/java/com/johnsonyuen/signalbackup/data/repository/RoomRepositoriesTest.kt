package com.johnsonyuen.signalbackup.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.johnsonyuen.signalbackup.data.local.db.AppDatabase
import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity
import com.johnsonyuen.signalbackup.domain.model.FailureReason
import com.johnsonyuen.signalbackup.domain.model.NewUploadRecord
import com.johnsonyuen.signalbackup.domain.model.ResumableUploadSession
import com.johnsonyuen.signalbackup.domain.model.SyncedFile
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomRepositoriesTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // No default-row callback: the settings repository must create the row itself.
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- settings --------------------------------------------------------------------------

    @Test
    fun settings_defaultsWhenNoRow() = runTest {
        val repo = SettingsRepositoryImpl(db.settingsDao(), backgroundScope)
        val s = repo.current()
        assertNull(s.localFolderUri)
        assertTrue(s.scheduleEnabled)
        assertEquals(3, s.scheduleHour)
        assertEquals(0, s.scheduleMinute)
        assertEquals("SYSTEM", s.themeMode)
        assertFalse(s.wifiOnly)
        assertFalse(s.isSetupComplete)
        assertEquals(s, repo.settings.first())
    }

    @Test
    fun settings_settersPersist() = runTest {
        val repo = SettingsRepositoryImpl(db.settingsDao(), backgroundScope)
        repo.setLocalFolderUri("content://tree")
        repo.setDriveFolder("folder-id", "Signal Backup")
        repo.setScheduleEnabled(false)
        repo.setScheduleTime(22, 45)
        repo.setGoogleAccountEmail("me@example.com")
        repo.setThemeMode("DARK")
        repo.setWifiOnly(true)

        val s = repo.current()
        assertEquals("content://tree", s.localFolderUri)
        assertEquals("folder-id", s.driveFolderId)
        assertEquals("Signal Backup", s.driveFolderName)
        assertFalse(s.scheduleEnabled)
        assertEquals(22, s.scheduleHour)
        assertEquals(45, s.scheduleMinute)
        assertEquals("me@example.com", s.googleAccountEmail)
        assertEquals("DARK", s.themeMode)
        assertTrue(s.wifiOnly)
        assertTrue(s.isSetupComplete)
        assertEquals("folder-id", repo.driveFolderId.first())

        repo.clearAccountData()
        val cleared = repo.current()
        assertNull(cleared.googleAccountEmail)
        assertNull(cleared.driveFolderId)
        assertNull(cleared.driveFolderName)
        assertEquals("content://tree", cleared.localFolderUri)
    }

    @Test
    fun settings_fieldFlowsAreDistinct() = runTest {
        val repo = SettingsRepositoryImpl(db.settingsDao(), backgroundScope)
        repo.scheduleHour.test {
            assertEquals(3, awaitItem())
            repo.setWifiOnly(true) // unrelated change must not re-emit the hour
            repo.setScheduleTime(5, 30)
            assertEquals(5, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- upload history --------------------------------------------------------------------

    @Test
    fun history_recordAndQuery() = runTest {
        val repo = UploadHistoryRepositoryImpl(db.uploadHistoryDao())
        repo.record(
            NewUploadRecord(
                timestamp = Instant.ofEpochMilli(1_000), fileName = "a.backup", fileSizeBytes = 10,
                bytesUploaded = 10, status = UploadResultStatus.SUCCESS, driveFolderId = "f",
                driveFileId = "id-a", md5Checksum = "abc",
            ),
        )
        repo.record(
            NewUploadRecord(
                timestamp = Instant.ofEpochMilli(2_000), fileName = null, fileSizeBytes = 0,
                bytesUploaded = 0, status = UploadResultStatus.FAILED,
                failureReason = FailureReason.NETWORK, errorMessage = "offline",
            ),
        )
        repo.record(
            NewUploadRecord(
                timestamp = Instant.ofEpochMilli(3_000), fileName = "a.backup", fileSizeBytes = 10,
                bytesUploaded = 0, status = UploadResultStatus.SUCCESS, driveFileId = "id-a",
            ),
        )

        val all = repo.getAll().first()
        assertEquals(listOf(3_000L, 2_000L, 1_000L), all.map { it.timestamp.toEpochMilli() })
        val failed = all[1]
        assertEquals(UploadResultStatus.FAILED, failed.status)
        assertEquals(FailureReason.NETWORK, failed.failureReason)
        assertEquals("offline", failed.errorMessage)
        assertNull(failed.fileName)
        assertEquals("abc", all[2].md5Checksum)

        assertEquals(3_000L, repo.getLatest().first()?.timestamp?.toEpochMilli())
        assertEquals(UploadResultStatus.SUCCESS, repo.getLatestSuccess().first()?.status)
        assertEquals(setOf("id-a"), repo.getSuccessfulDriveFileIds())
    }

    @Test
    fun history_unknownStoredValuesMapSafely() = runTest {
        db.uploadHistoryDao().insert(
            UploadHistoryEntity(
                timestamp = 1, fileName = "x", fileSizeBytes = 1, status = "FROM_THE_FUTURE",
                failureReason = "NOT_A_REASON",
            ),
        )
        val record = UploadHistoryRepositoryImpl(db.uploadHistoryDao()).getLatest().first()!!
        assertEquals(UploadResultStatus.FAILED, record.status)
        assertNull(record.failureReason)
    }

    @Test
    fun history_latestSuccessIsNullWithoutSuccess() = runTest {
        val repo = UploadHistoryRepositoryImpl(db.uploadHistoryDao())
        repo.record(
            NewUploadRecord(
                timestamp = Instant.ofEpochMilli(1), fileName = null, fileSizeBytes = 0, bytesUploaded = 0,
                status = UploadResultStatus.STALE,
            ),
        )
        assertNull(repo.getLatestSuccess().first())
        assertTrue(repo.getSuccessfulDriveFileIds().isEmpty())
    }

    // ---- upload session --------------------------------------------------------------------

    @Test
    fun session_lifecycle() = runTest {
        val repo = UploadSessionRepositoryImpl(db.uploadSessionDao())
        assertNull(repo.get())
        assertEquals(0, repo.incrementFailureCount())

        val session = ResumableUploadSession(
            sessionUri = "https://upload/session", localFileUri = "content://file",
            relativePath = "snap/backup.db", fileName = "backup.db", totalBytes = 100,
            localLastModifiedMillis = 50, bytesUploaded = 0, driveParentId = "parent",
            createdAtMillis = 10,
        )
        repo.save(session)
        assertEquals(session, repo.get())

        repo.updateBytesUploaded(64)
        assertEquals(64L, repo.get()?.bytesUploaded)

        assertEquals(1, repo.incrementFailureCount())
        assertEquals(2, repo.incrementFailureCount())

        repo.markCompleted("drive-id", "md5")
        val completed = repo.get()!!
        assertEquals("drive-id", completed.driveFileId)
        assertEquals("md5", completed.remoteMd5)
        assertEquals(2, completed.failureCount)

        // Saving a new session replaces the single row.
        repo.save(session.copy(relativePath = "other"))
        assertEquals(session.copy(relativePath = "other"), repo.get())

        repo.clear()
        assertNull(repo.get())
    }

    // ---- synced files ----------------------------------------------------------------------

    @Test
    fun syncedFiles_crud() = runTest {
        val repo = SyncedFileRepositoryImpl(db.syncedFileDao())
        val dir = SyncedFile("snap", true, 0, 1, "dir-id", null, 5)
        val file = SyncedFile("snap/backup.db", false, 100, 2, "file-id", "md5", 6)
        repo.upsert(dir)
        repo.upsert(file)
        assertEquals(file, repo.get("snap/backup.db"))
        assertEquals(listOf(dir, file), repo.getAll())

        val changed = file.copy(sizeBytes = 200, md5Checksum = "md5b")
        repo.upsert(changed)
        assertEquals(changed, repo.get("snap/backup.db"))
        assertEquals(2, repo.getAll().size)

        repo.delete("snap")
        assertNull(repo.get("snap"))
        assertEquals(listOf(changed), repo.getAll())

        repo.clear()
        assertTrue(repo.getAll().isEmpty())
    }
}
