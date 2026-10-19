package com.johnsonyuen.signalbackup.data.local.db

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migrations v1 → v5. DDL is frozen per version: never edit an old migration's SQL, add a new
 * migration instead. Every historical version (1–4) must reach [AppDatabase.VERSION].
 */
object DatabaseMigrations {

    // ---- v2: index on upload_history.timestamp ----------------------------------------------
    private const val V2_CREATE_HISTORY_TIMESTAMP_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_upload_history_timestamp` ON `upload_history` (`timestamp`)"

    // ---- v3: app_settings table (as shipped by the v3 migration) -----------------------------
    private const val V3_CREATE_SETTINGS = """
        CREATE TABLE IF NOT EXISTS app_settings (
            id INTEGER NOT NULL PRIMARY KEY DEFAULT 1,
            localFolderUri TEXT,
            driveFolderId TEXT,
            driveFolderName TEXT,
            scheduleHour INTEGER NOT NULL DEFAULT 3,
            scheduleMinute INTEGER NOT NULL DEFAULT 0,
            googleAccountEmail TEXT,
            themeMode TEXT NOT NULL DEFAULT 'SYSTEM',
            wifiOnly INTEGER NOT NULL DEFAULT 0,
            resumeSessionUri TEXT,
            resumeLocalFileUri TEXT,
            resumeFileName TEXT,
            resumeBytesUploaded INTEGER,
            resumeTotalBytes INTEGER,
            resumeDriveFolderId TEXT,
            resumeCreatedAt INTEGER,
            resumeDriveFileId TEXT
        )
    """
    private const val V3_INSERT_DEFAULT_SETTINGS =
        "INSERT OR IGNORE INTO app_settings (id, scheduleHour, scheduleMinute, themeMode, wifiOnly) " +
            "VALUES (1, 3, 0, 'SYSTEM', 0)"

    // ---- v5 ----------------------------------------------------------------------------------
    private const val V5_CREATE_SETTINGS =
        "CREATE TABLE IF NOT EXISTS `app_settings_v5` (`id` INTEGER NOT NULL, `localFolderUri` TEXT, " +
            "`driveFolderId` TEXT, `driveFolderName` TEXT, `scheduleEnabled` INTEGER NOT NULL DEFAULT 1, " +
            "`scheduleHour` INTEGER NOT NULL, `scheduleMinute` INTEGER NOT NULL, `googleAccountEmail` TEXT, " +
            "`themeMode` TEXT NOT NULL, `wifiOnly` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    private const val V5_COPY_SETTINGS =
        "INSERT INTO `app_settings_v5` (id, localFolderUri, driveFolderId, driveFolderName, scheduleEnabled, " +
            "scheduleHour, scheduleMinute, googleAccountEmail, themeMode, wifiOnly) " +
            "SELECT id, localFolderUri, driveFolderId, driveFolderName, 1, COALESCE(scheduleHour, 3), " +
            "COALESCE(scheduleMinute, 0), googleAccountEmail, COALESCE(themeMode, 'SYSTEM'), " +
            "COALESCE(wifiOnly, 0) FROM app_settings WHERE id = 1"

    private const val V5_CREATE_HISTORY =
        "CREATE TABLE IF NOT EXISTS `upload_history_v5` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`timestamp` INTEGER NOT NULL, `fileName` TEXT, `fileSizeBytes` INTEGER NOT NULL, " +
            "`bytesUploaded` INTEGER NOT NULL DEFAULT 0, `status` TEXT NOT NULL, `failureReason` TEXT, " +
            "`errorMessage` TEXT, `driveFolderId` TEXT, `driveFileId` TEXT, `md5Checksum` TEXT)"
    private const val V5_COPY_HISTORY =
        "INSERT INTO `upload_history_v5` (id, timestamp, fileName, fileSizeBytes, bytesUploaded, status, " +
            "failureReason, errorMessage, driveFolderId, driveFileId, md5Checksum) " +
            "SELECT id, timestamp, fileName, fileSizeBytes, " +
            "CASE WHEN status = 'SUCCESS' THEN fileSizeBytes ELSE 0 END, status, NULL, errorMessage, " +
            "driveFolderId, driveFileId, NULL FROM upload_history"

    private const val V5_CREATE_UPLOAD_SESSION =
        "CREATE TABLE IF NOT EXISTS `upload_session` (`id` INTEGER NOT NULL, `sessionUri` TEXT NOT NULL, " +
            "`localFileUri` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
            "`totalBytes` INTEGER NOT NULL, `localLastModifiedMillis` INTEGER NOT NULL, " +
            "`bytesUploaded` INTEGER NOT NULL, `driveParentId` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, " +
            "`driveFileId` TEXT, `remoteMd5` TEXT, `failureCount` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    private const val V5_CREATE_SYNCED_FILE =
        "CREATE TABLE IF NOT EXISTS `synced_file` (`relativePath` TEXT NOT NULL, `isDirectory` INTEGER NOT NULL, " +
            "`sizeBytes` INTEGER NOT NULL, `lastModifiedMillis` INTEGER NOT NULL, `driveFileId` TEXT NOT NULL, " +
            "`md5Checksum` TEXT, `syncedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`relativePath`))"

    /** Ensures the single settings row exists (current schema). */
    const val INSERT_DEFAULT_SETTINGS =
        "INSERT OR IGNORE INTO app_settings (id, scheduleEnabled, scheduleHour, scheduleMinute, themeMode, wifiOnly) " +
            "VALUES (1, 1, 3, 0, 'SYSTEM', 0)"

    /** v2 added the timestamp index; v1 installs never got it. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(V2_CREATE_HISTORY_TIMESTAMP_INDEX)
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(V3_CREATE_SETTINGS)
            db.execSQL(V3_INSERT_DEFAULT_SETTINGS)
        }
    }

    /** Adds the retry columns, tolerating databases where they already exist. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val existing = columnNames(db, "app_settings")
            if ("retryAtMillis" !in existing) db.execSQL("ALTER TABLE app_settings ADD COLUMN retryAtMillis INTEGER")
            if ("retryError" !in existing) db.execSQL("ALTER TABLE app_settings ADD COLUMN retryError TEXT")
        }
    }

    /**
     * Drops retry and resume columns from app_settings (adds scheduleEnabled), makes
     * upload_history.fileName/driveFolderId nullable with new columns, and creates upload_session
     * and synced_file. History rows are kept; the old in-flight resumable session is discarded.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `app_settings_v5`")
            db.execSQL(V5_CREATE_SETTINGS)
            if (tableExists(db, "app_settings")) {
                db.execSQL(V5_COPY_SETTINGS)
                db.execSQL("DROP TABLE app_settings")
            }
            db.execSQL("ALTER TABLE `app_settings_v5` RENAME TO `app_settings`")
            db.execSQL(INSERT_DEFAULT_SETTINGS)

            db.execSQL("DROP TABLE IF EXISTS `upload_history_v5`")
            db.execSQL(V5_CREATE_HISTORY)
            db.execSQL(V5_COPY_HISTORY)
            db.execSQL("DROP TABLE upload_history")
            db.execSQL("ALTER TABLE `upload_history_v5` RENAME TO `upload_history`")
            db.execSQL(V2_CREATE_HISTORY_TIMESTAMP_INDEX)

            db.execSQL(V5_CREATE_UPLOAD_SESSION)
            db.execSQL(V5_CREATE_SYNCED_FILE)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

    /** Makes sure the default settings row exists every time the database opens. */
    val DEFAULT_SETTINGS_CALLBACK = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL(INSERT_DEFAULT_SETTINGS)
        }
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf<Any?>(table))
            .use { it.moveToFirst() }
}
