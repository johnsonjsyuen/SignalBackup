package com.johnsonyuen.signalbackup.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.johnsonyuen.signalbackup.data.local.db.AppDatabase
import com.johnsonyuen.signalbackup.data.local.db.DatabaseMigrations
import com.johnsonyuen.signalbackup.data.local.entity.SyncedFileEntity
import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity
import com.johnsonyuen.signalbackup.data.local.entity.UploadSessionEntity
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

/**
 * Builds raw SQLite databases with the DDL each historical version actually produced, then opens
 * them with Room + [DatabaseMigrations]. Room validates the v5 schema on open and throws if a
 * migration left it wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var room: AppDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        room?.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun v1_migratesToV5_keepingHistory() = runTest {
        createRaw(version = 1) { db ->
            db.execSQL(HISTORY_V1)
            insertLegacyHistory(db)
        }
        val db = openRoom()

        assertLegacyHistoryKept(db)
        assertTimestampIndexExists(db)
        assertDefaultSettings(db)
        assertNewTablesUsable(db)
    }

    @Test
    fun v2_migratesToV5_withoutDuplicateColumnCrash() = runTest {
        createRaw(version = 2) { db ->
            db.execSQL(HISTORY_V1)
            db.execSQL(HISTORY_TIMESTAMP_INDEX)
            insertLegacyHistory(db)
        }
        val db = openRoom()

        assertLegacyHistoryKept(db)
        assertTimestampIndexExists(db)
        assertDefaultSettings(db)
        assertNewTablesUsable(db)
    }

    @Test
    fun v3_roomCreated_migratesToV5_keepingSettings() = runTest {
        createRaw(version = 3) { db ->
            db.execSQL(HISTORY_V1)
            db.execSQL(HISTORY_TIMESTAMP_INDEX)
            db.execSQL(SETTINGS_V3_ROOM)
            insertLegacyHistory(db)
            db.execSQL(
                "INSERT INTO app_settings (id, localFolderUri, driveFolderId, driveFolderName, scheduleHour, " +
                    "scheduleMinute, googleAccountEmail, themeMode, wifiOnly, resumeSessionUri, resumeFileName) " +
                    "VALUES (1, 'content://folder', 'drive-folder', 'Backups', 4, 15, 'a@b.c', 'DARK', 1, " +
                    "'https://session', 'x.backup')",
            )
        }
        val db = openRoom()

        assertLegacyHistoryKept(db)
        assertTimestampIndexExists(db)
        assertCustomSettingsKept(db)
        assertNewTablesUsable(db)
    }

    @Test
    fun v3_createdByMigration_migratesToV5() = runTest {
        createRaw(version = 3) { db ->
            db.execSQL(HISTORY_V1)
            db.execSQL(HISTORY_TIMESTAMP_INDEX)
            db.execSQL(SETTINGS_V3_MIGRATION)
            db.execSQL(
                "INSERT OR IGNORE INTO app_settings (id, scheduleHour, scheduleMinute, themeMode, wifiOnly) " +
                    "VALUES (1, 3, 0, 'SYSTEM', 0)",
            )
            insertLegacyHistory(db)
        }
        val db = openRoom()

        assertLegacyHistoryKept(db)
        assertDefaultSettings(db)
    }

    @Test
    fun v3_withRetryColumnsAlreadyPresent_migratesToV5() = runTest {
        createRaw(version = 3) { db ->
            db.execSQL(HISTORY_V1)
            db.execSQL(HISTORY_TIMESTAMP_INDEX)
            db.execSQL(SETTINGS_V4_ROOM) // a v3 DB that already has retry columns
            insertLegacyHistory(db)
            insertCustomV4Settings(db)
        }
        val db = openRoom()

        assertLegacyHistoryKept(db)
        assertCustomSettingsKept(db)
    }

    @Test
    fun v4_roomCreated_migratesToV5_keepingSettingsAndHistory() = runTest {
        createRaw(version = 4) { db ->
            db.execSQL(HISTORY_V1)
            db.execSQL(HISTORY_TIMESTAMP_INDEX)
            db.execSQL(SETTINGS_V4_ROOM)
            insertLegacyHistory(db)
            insertCustomV4Settings(db)
        }
        val db = openRoom()

        assertLegacyHistoryKept(db)
        assertTimestampIndexExists(db)
        assertCustomSettingsKept(db)
        assertNewTablesUsable(db)
    }

    @Test
    fun v4_withoutSettingsRow_getsDefaultRow() = runTest {
        createRaw(version = 4) { db ->
            db.execSQL(HISTORY_V1)
            db.execSQL(HISTORY_TIMESTAMP_INDEX)
            db.execSQL(SETTINGS_V4_ROOM)
        }
        val db = openRoom()

        assertDefaultSettings(db)
        assertTrue(db.uploadHistoryDao().getSuccessfulDriveFileIds().isEmpty())
    }

    @Test
    fun migratedDatabase_reopensWithoutFurtherMigration() = runTest {
        createRaw(version = 2) { db ->
            db.execSQL(HISTORY_V1)
            db.execSQL(HISTORY_TIMESTAMP_INDEX)
            insertLegacyHistory(db)
        }
        openRoom().close()
        room = null

        val reopened = openRoom()
        assertLegacyHistoryKept(reopened)
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun createRaw(version: Int, block: (SQLiteDatabase) -> Unit) {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'old-hash-v$version')")
            block(db)
            db.version = version
        } finally {
            db.close()
        }
    }

    private fun openRoom(): AppDatabase {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(*DatabaseMigrations.ALL)
            .addCallback(DatabaseMigrations.DEFAULT_SETTINGS_CALLBACK)
            .allowMainThreadQueries()
            .build()
        // Forces open + migration + schema validation.
        db.openHelper.writableDatabase
        room = db
        return db
    }

    private fun insertLegacyHistory(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT INTO upload_history (id, timestamp, fileName, fileSizeBytes, status, errorMessage, " +
                "driveFolderId, driveFileId) VALUES (1, 1000, 'signal-2024-01-01-00-00-00.backup', 500, " +
                "'SUCCESS', NULL, 'folder-1', 'file-1')",
        )
        db.execSQL(
            "INSERT INTO upload_history (id, timestamp, fileName, fileSizeBytes, status, errorMessage, " +
                "driveFolderId, driveFileId) VALUES (2, 2000, 'signal-2024-01-02-00-00-00.backup', 600, " +
                "'FAILED', 'Network error', 'folder-1', NULL)",
        )
    }

    private fun insertCustomV4Settings(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT INTO app_settings (id, localFolderUri, driveFolderId, driveFolderName, scheduleHour, " +
                "scheduleMinute, googleAccountEmail, themeMode, wifiOnly, retryAtMillis, retryError, " +
                "resumeSessionUri, resumeBytesUploaded) VALUES (1, 'content://folder', 'drive-folder', " +
                "'Backups', 4, 15, 'a@b.c', 'DARK', 1, 123, 'boom', 'https://session', 10)",
        )
    }

    private suspend fun assertLegacyHistoryKept(db: AppDatabase) {
        val dao = db.uploadHistoryDao()
        assertEquals(setOf("file-1"), dao.getSuccessfulDriveFileIds().toSet())

        val rows = db.query("SELECT id, fileName, fileSizeBytes, bytesUploaded, status, errorMessage, " +
            "driveFolderId, driveFileId, failureReason, md5Checksum FROM upload_history ORDER BY id", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        listOf(
                            c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4),
                            c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9),
                        ),
                    )
                }
            }
        }
        assertEquals(
            listOf(
                listOf(1L, "signal-2024-01-01-00-00-00.backup", 500L, 500L, "SUCCESS", null, "folder-1", "file-1", null, null),
                listOf(2L, "signal-2024-01-02-00-00-00.backup", 600L, 0L, "FAILED", "Network error", "folder-1", null, null, null),
            ),
            rows,
        )

        // AUTOINCREMENT continues after the copied ids; nullable columns now accept NULL.
        val newId = dao.insert(
            UploadHistoryEntity(timestamp = 3000, fileName = null, fileSizeBytes = 0, status = "STALE"),
        )
        assertEquals(3L, newId)
    }

    private fun assertTimestampIndexExists(db: AppDatabase) {
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_upload_history_timestamp'",
            null,
        ).use { assertTrue(it.moveToFirst()) }
    }

    private suspend fun assertDefaultSettings(db: AppDatabase) {
        val s = db.settingsDao().get()!!
        assertNull(s.localFolderUri)
        assertNull(s.googleAccountEmail)
        assertTrue(s.scheduleEnabled)
        assertEquals(3, s.scheduleHour)
        assertEquals(0, s.scheduleMinute)
        assertEquals("SYSTEM", s.themeMode)
        assertFalse(s.wifiOnly)
    }

    private suspend fun assertCustomSettingsKept(db: AppDatabase) {
        val s = db.settingsDao().get()!!
        assertEquals("content://folder", s.localFolderUri)
        assertEquals("drive-folder", s.driveFolderId)
        assertEquals("Backups", s.driveFolderName)
        assertTrue(s.scheduleEnabled)
        assertEquals(4, s.scheduleHour)
        assertEquals(15, s.scheduleMinute)
        assertEquals("a@b.c", s.googleAccountEmail)
        assertEquals("DARK", s.themeMode)
        assertTrue(s.wifiOnly)

        val columns = db.query("PRAGMA table_info(app_settings)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        assertFalse(columns.any { it.startsWith("retry") || it.startsWith("resume") })
    }

    private suspend fun assertNewTablesUsable(db: AppDatabase) {
        val sessions = db.uploadSessionDao()
        assertNull(sessions.get())
        sessions.upsert(
            UploadSessionEntity(
                sessionUri = "s", localFileUri = "l", relativePath = "r", fileName = "f", totalBytes = 1,
                localLastModifiedMillis = 2, bytesUploaded = 0, driveParentId = "p", createdAtMillis = 3,
                driveFileId = null, remoteMd5 = null, failureCount = 0,
            ),
        )
        assertEquals("r", sessions.get()?.relativePath)

        val synced = db.syncedFileDao()
        assertTrue(synced.getAll().isEmpty())
        synced.upsert(SyncedFileEntity("a/b", false, 1, 2, "id", null, 3))
        assertEquals(1, synced.getAll().size)
    }

    private companion object {
        const val DB_NAME = "migration-test.db"

        // Exactly what Room generated for UploadHistoryEntity at v1–v4.
        const val HISTORY_V1 =
            "CREATE TABLE IF NOT EXISTS `upload_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `fileSizeBytes` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, `errorMessage` TEXT, `driveFolderId` TEXT NOT NULL, `driveFileId` TEXT)"
        const val HISTORY_TIMESTAMP_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_upload_history_timestamp` ON `upload_history` (`timestamp`)"

        // v3 fresh install (Room-generated from SettingsEntity at 6fdf18b).
        const val SETTINGS_V3_ROOM =
            "CREATE TABLE IF NOT EXISTS `app_settings` (`id` INTEGER NOT NULL, `localFolderUri` TEXT, " +
                "`driveFolderId` TEXT, `driveFolderName` TEXT, `scheduleHour` INTEGER NOT NULL, " +
                "`scheduleMinute` INTEGER NOT NULL, `googleAccountEmail` TEXT, `themeMode` TEXT NOT NULL, " +
                "`wifiOnly` INTEGER NOT NULL, `resumeSessionUri` TEXT, `resumeLocalFileUri` TEXT, " +
                "`resumeFileName` TEXT, `resumeBytesUploaded` INTEGER, `resumeTotalBytes` INTEGER, " +
                "`resumeDriveFolderId` TEXT, `resumeCreatedAt` INTEGER, `resumeDriveFileId` TEXT, PRIMARY KEY(`id`))"

        // v3 reached via the shipped 2→3 migration.
        const val SETTINGS_V3_MIGRATION =
            "CREATE TABLE IF NOT EXISTS app_settings (id INTEGER NOT NULL PRIMARY KEY DEFAULT 1, " +
                "localFolderUri TEXT, driveFolderId TEXT, driveFolderName TEXT, " +
                "scheduleHour INTEGER NOT NULL DEFAULT 3, scheduleMinute INTEGER NOT NULL DEFAULT 0, " +
                "googleAccountEmail TEXT, themeMode TEXT NOT NULL DEFAULT 'SYSTEM', " +
                "wifiOnly INTEGER NOT NULL DEFAULT 0, resumeSessionUri TEXT, resumeLocalFileUri TEXT, " +
                "resumeFileName TEXT, resumeBytesUploaded INTEGER, resumeTotalBytes INTEGER, " +
                "resumeDriveFolderId TEXT, resumeCreatedAt INTEGER, resumeDriveFileId TEXT)"

        // v4 fresh install (Room-generated from SettingsEntity at f08aafe).
        const val SETTINGS_V4_ROOM =
            "CREATE TABLE IF NOT EXISTS `app_settings` (`id` INTEGER NOT NULL, `localFolderUri` TEXT, " +
                "`driveFolderId` TEXT, `driveFolderName` TEXT, `scheduleHour` INTEGER NOT NULL, " +
                "`scheduleMinute` INTEGER NOT NULL, `googleAccountEmail` TEXT, `themeMode` TEXT NOT NULL, " +
                "`wifiOnly` INTEGER NOT NULL, `retryAtMillis` INTEGER, `retryError` TEXT, " +
                "`resumeSessionUri` TEXT, `resumeLocalFileUri` TEXT, `resumeFileName` TEXT, " +
                "`resumeBytesUploaded` INTEGER, `resumeTotalBytes` INTEGER, `resumeDriveFolderId` TEXT, " +
                "`resumeCreatedAt` INTEGER, `resumeDriveFileId` TEXT, PRIMARY KEY(`id`))"
    }
}
