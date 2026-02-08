/**
 * SettingsDataStore.kt - Preferences DataStore wrapper for app settings.
 *
 * This class provides a clean, type-safe API over Android's Preferences DataStore for
 * reading and writing user settings. DataStore is the modern replacement for SharedPreferences,
 * offering several advantages:
 * - **Asynchronous**: All reads are exposed as Kotlin Flows; all writes are suspend functions.
 * - **Thread-safe**: No risk of the inconsistent reads that plague SharedPreferences.
 * - **Type-safe**: Uses typed preference keys (stringPreferencesKey, intPreferencesKey).
 * - **Transactional**: Writes happen atomically via the `edit` function.
 *
 * Settings stored here:
 * - Local backup folder URI (SAF tree URI from the document picker)
 * - Google Drive folder ID and display name
 * - Upload schedule time (hour and minute)
 * - Signed-in Google account email
 * - Theme mode preference (System/Light/Dark)
 *
 * Architecture context:
 * - Part of the **data layer** (data/local/datastore package).
 * - Wrapped by [SettingsRepositoryImpl] to expose settings through a repository interface.
 * - Instantiated as a singleton by Hilt in AppModule.provideSettingsDataStore().
 * - Also injected directly into [MainActivity] for theme mode observation at the top level.
 * - Also injected into [GoogleDriveService] to read the account email before API calls.
 *
 * Important design decision: This class does NOT use @Inject constructor. Instead, it is
 * manually constructed in AppModule via @Provides. This avoids a common Hilt pitfall where
 * using both @Inject and @Provides for the same type causes duplicate binding errors.
 *
 * @see data.repository.SettingsRepository for the interface consumed by ViewModels
 * @see di.AppModule for how this is provided to the dependency graph
 */
package com.johnsonyuen.signalbackup.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.johnsonyuen.signalbackup.domain.model.ResumableUploadSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map


/**
 * Type-safe wrapper around Preferences DataStore for all user settings.
 *
 * @param dataStore The underlying Preferences DataStore instance, provided by Hilt.
 *        There should only ever be one DataStore instance per file, which is enforced
 *        by creating it as a Kotlin property delegate at the module level (see AppModule).
 */
class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
) {

    // ---- Keys ----
    // Each key corresponds to one setting stored in the DataStore file.
    // Using a private object keeps these keys encapsulated and prevents external access.

    private object Keys {
        /** SAF (Storage Access Framework) tree URI for the local backup folder. */
        val LOCAL_FOLDER_URI = stringPreferencesKey("local_folder_uri")

        /** Google Drive folder ID where backups are uploaded. */
        val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")

        /** Human-readable name of the selected Google Drive folder for display in the UI. */
        val DRIVE_FOLDER_NAME = stringPreferencesKey("drive_folder_name")

        /** Hour component (0-23) of the daily scheduled upload time. */
        val SCHEDULE_HOUR = intPreferencesKey("schedule_hour")

        /** Minute component (0-59) of the daily scheduled upload time. */
        val SCHEDULE_MINUTE = intPreferencesKey("schedule_minute")

        /** Email of the signed-in Google account, used for Google Drive API authentication. */
        val GOOGLE_ACCOUNT_EMAIL = stringPreferencesKey("google_account_email")

        /** Theme mode string ("SYSTEM", "LIGHT", or "DARK") for user appearance preference. */
        val THEME_MODE = stringPreferencesKey("theme_mode")

        // ---- Resumable upload session keys ----
        // These keys persist the state of an in-progress resumable upload so it can
        // be resumed after the app is killed and restarted.

        /** The resumable session URI issued by Google Drive for chunk uploads. */
        val RESUME_SESSION_URI = stringPreferencesKey("resume_session_uri")

        /** The SAF content URI of the local file being uploaded. */
        val RESUME_LOCAL_FILE_URI = stringPreferencesKey("resume_local_file_uri")

        /** The display name of the file being uploaded. */
        val RESUME_FILE_NAME = stringPreferencesKey("resume_file_name")

        /** Number of bytes confirmed received by Google Drive so far. */
        val RESUME_BYTES_UPLOADED = longPreferencesKey("resume_bytes_uploaded")

        /** Total size of the file being uploaded in bytes. */
        val RESUME_TOTAL_BYTES = longPreferencesKey("resume_total_bytes")

        /** The Google Drive folder ID the file is being uploaded to. */
        val RESUME_DRIVE_FOLDER_ID = stringPreferencesKey("resume_drive_folder_id")

        /** Epoch millis when the resumable session was initiated. */
        val RESUME_CREATED_AT = longPreferencesKey("resume_created_at")

        /** The Google Drive file ID, set when upload completed but session not yet cleared. */
        val RESUME_DRIVE_FILE_ID = stringPreferencesKey("resume_drive_file_id")
    }

    // ---- Defaults ----
    // Default values for settings that should never be null (schedule time).

    companion object {
        /** Default upload hour: 3 AM -- chosen because most users are asleep and
         *  the device is likely charging and on Wi-Fi. */
        const val DEFAULT_SCHEDULE_HOUR = 3

        /** Default upload minute: 0 (on the hour). */
        const val DEFAULT_SCHEDULE_MINUTE = 0
    }

    // ---- Flow getters ----
    // Each getter returns a Flow that emits the current value and re-emits whenever it changes.
    // This enables reactive UI updates -- when a setting changes, any Composable collecting
    // the Flow automatically recomposes with the new value.

    /** The local backup folder URI as a SAF content:// URI string, or null if not set. */
    val localFolderUri: Flow<String?>
        get() = dataStore.data.map { it[Keys.LOCAL_FOLDER_URI] }

    /** The Google Drive folder ID to upload backups to, or null if not set. */
    val driveFolderId: Flow<String?>
        get() = dataStore.data.map { it[Keys.DRIVE_FOLDER_ID] }

    /** The display name of the selected Drive folder, or null if not set. */
    val driveFolderName: Flow<String?>
        get() = dataStore.data.map { it[Keys.DRIVE_FOLDER_NAME] }

    /** The scheduled upload hour (0-23), defaulting to 3 AM. */
    val scheduleHour: Flow<Int>
        get() = dataStore.data.map { it[Keys.SCHEDULE_HOUR] ?: DEFAULT_SCHEDULE_HOUR }

    /** The scheduled upload minute (0-59), defaulting to 0. */
    val scheduleMinute: Flow<Int>
        get() = dataStore.data.map { it[Keys.SCHEDULE_MINUTE] ?: DEFAULT_SCHEDULE_MINUTE }

    /** The signed-in Google account email, or null if not signed in. */
    val googleAccountEmail: Flow<String?>
        get() = dataStore.data.map { it[Keys.GOOGLE_ACCOUNT_EMAIL] }

    /** The theme mode preference string ("SYSTEM", "LIGHT", or "DARK"), defaulting to "SYSTEM". */
    val themeMode: Flow<String>
        get() = dataStore.data.map { it[Keys.THEME_MODE] ?: "SYSTEM" }

    // ---- Suspend setters ----
    // Each setter is a suspend function because DataStore writes are asynchronous I/O operations.
    // The `edit` function provides a transactional context -- if the lambda throws, the write
    // is rolled back. Passing null removes the key from the store (resetting to "not set").

    /**
     * Sets or clears the local backup folder URI.
     * @param uri The SAF content URI string, or null to clear.
     */
    suspend fun setLocalFolderUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri != null) prefs[Keys.LOCAL_FOLDER_URI] = uri
            else prefs.remove(Keys.LOCAL_FOLDER_URI)
        }
    }

    /**
     * Sets or clears the Google Drive folder ID.
     * @param id The Drive folder ID string, or null to clear.
     */
    suspend fun setDriveFolderId(id: String?) {
        dataStore.edit { prefs ->
            if (id != null) prefs[Keys.DRIVE_FOLDER_ID] = id
            else prefs.remove(Keys.DRIVE_FOLDER_ID)
        }
    }

    /**
     * Sets or clears the Google Drive folder display name.
     * @param name The folder name for UI display, or null to clear.
     */
    suspend fun setDriveFolderName(name: String?) {
        dataStore.edit { prefs ->
            if (name != null) prefs[Keys.DRIVE_FOLDER_NAME] = name
            else prefs.remove(Keys.DRIVE_FOLDER_NAME)
        }
    }

    /**
     * Sets the scheduled upload hour.
     * @param hour The hour in 24-hour format (0-23).
     */
    suspend fun setScheduleHour(hour: Int) {
        dataStore.edit { prefs -> prefs[Keys.SCHEDULE_HOUR] = hour }
    }

    /**
     * Sets the scheduled upload minute.
     * @param minute The minute (0-59).
     */
    suspend fun setScheduleMinute(minute: Int) {
        dataStore.edit { prefs -> prefs[Keys.SCHEDULE_MINUTE] = minute }
    }

    /**
     * Atomically sets both hour and minute in a single DataStore transaction,
     * avoiding inconsistent intermediate states where only one value has been updated.
     *
     * @param hour The hour in 24-hour format (0-23).
     * @param minute The minute (0-59).
     */
    suspend fun setScheduleTime(hour: Int, minute: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.SCHEDULE_HOUR] = hour
            prefs[Keys.SCHEDULE_MINUTE] = minute
        }
    }

    /**
     * Atomically clears all account-related settings (email, Drive folder ID, Drive folder name)
     * in a single DataStore transaction.
     *
     * Used during sign-out to ensure all account data is removed together, preventing
     * inconsistent state where the Drive folder is set but the account is cleared.
     */
    suspend fun clearAccountData() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.GOOGLE_ACCOUNT_EMAIL)
            prefs.remove(Keys.DRIVE_FOLDER_ID)
            prefs.remove(Keys.DRIVE_FOLDER_NAME)
        }
    }

    /**
     * Sets or clears the Google account email.
     * Clearing this effectively signs the user out (no account = no API calls).
     * @param email The Google account email, or null to sign out.
     */
    suspend fun setGoogleAccountEmail(email: String?) {
        dataStore.edit { prefs ->
            if (email != null) prefs[Keys.GOOGLE_ACCOUNT_EMAIL] = email
            else prefs.remove(Keys.GOOGLE_ACCOUNT_EMAIL)
        }
    }

    /**
     * Sets the theme mode preference.
     * @param mode One of "SYSTEM", "LIGHT", or "DARK".
     */
    suspend fun setThemeMode(mode: String) {
        dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = mode }
    }

    // ---- Resumable upload session persistence ----
    // These methods save and restore the state of an in-progress resumable upload.
    // All session fields are written/cleared atomically to prevent inconsistent state.

    /**
     * Reads the saved resumable upload session, or null if none exists.
     *
     * This is a one-shot read (not a Flow) because the session is only checked at the
     * start of an upload attempt, not observed reactively.
     *
     * @return The saved [ResumableUploadSession], or null if no session is saved.
     */
    suspend fun getResumableSession(): ResumableUploadSession? {
        val prefs = dataStore.data.first()
        val sessionUri = prefs[Keys.RESUME_SESSION_URI] ?: return null
        val localFileUri = prefs[Keys.RESUME_LOCAL_FILE_URI] ?: return null
        val fileName = prefs[Keys.RESUME_FILE_NAME] ?: return null
        val bytesUploaded = prefs[Keys.RESUME_BYTES_UPLOADED] ?: return null
        val totalBytes = prefs[Keys.RESUME_TOTAL_BYTES] ?: return null
        val driveFolderId = prefs[Keys.RESUME_DRIVE_FOLDER_ID] ?: return null
        val createdAt = prefs[Keys.RESUME_CREATED_AT] ?: return null

        return ResumableUploadSession(
            sessionUri = sessionUri,
            localFileUri = localFileUri,
            fileName = fileName,
            bytesUploaded = bytesUploaded,
            totalBytes = totalBytes,
            driveFolderId = driveFolderId,
            createdAtMillis = createdAt,
            driveFileId = prefs[Keys.RESUME_DRIVE_FILE_ID],
        )
    }

    /**
     * Atomically saves all fields of a resumable upload session.
     *
     * Called after initiating a new resumable upload and after each successful chunk
     * upload to persist the latest bytes-uploaded count.
     *
     * @param session The session state to persist.
     */
    suspend fun saveResumableSession(session: ResumableUploadSession) {
        dataStore.edit { prefs ->
            prefs[Keys.RESUME_SESSION_URI] = session.sessionUri
            prefs[Keys.RESUME_LOCAL_FILE_URI] = session.localFileUri
            prefs[Keys.RESUME_FILE_NAME] = session.fileName
            prefs[Keys.RESUME_BYTES_UPLOADED] = session.bytesUploaded
            prefs[Keys.RESUME_TOTAL_BYTES] = session.totalBytes
            prefs[Keys.RESUME_DRIVE_FOLDER_ID] = session.driveFolderId
            prefs[Keys.RESUME_CREATED_AT] = session.createdAtMillis
            if (session.driveFileId != null) {
                prefs[Keys.RESUME_DRIVE_FILE_ID] = session.driveFileId
            } else {
                prefs.remove(Keys.RESUME_DRIVE_FILE_ID)
            }
        }
    }

    /**
     * Updates only the bytes-uploaded count in a saved session.
     *
     * This is more efficient than [saveResumableSession] when only the progress
     * changes (i.e., after each chunk upload).
     *
     * @param bytesUploaded The new confirmed byte count.
     */
    suspend fun updateResumableBytesUploaded(bytesUploaded: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.RESUME_BYTES_UPLOADED] = bytesUploaded
        }
    }

    /**
     * Updates only the Drive file ID in a saved session.
     *
     * Called immediately after the upload completes (before clearing the session)
     * to shrink the crash window where the file ID could be lost.
     *
     * @param driveFileId The Google Drive file ID of the completed upload.
     */
    suspend fun updateResumableDriveFileId(driveFileId: String) {
        dataStore.edit { prefs ->
            prefs[Keys.RESUME_DRIVE_FILE_ID] = driveFileId
        }
    }

    /**
     * Atomically clears all resumable session fields.
     *
     * Called when the upload completes successfully, fails permanently, or the
     * session URI is detected as expired.
     */
    suspend fun clearResumableSession() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.RESUME_SESSION_URI)
            prefs.remove(Keys.RESUME_LOCAL_FILE_URI)
            prefs.remove(Keys.RESUME_FILE_NAME)
            prefs.remove(Keys.RESUME_BYTES_UPLOADED)
            prefs.remove(Keys.RESUME_TOTAL_BYTES)
            prefs.remove(Keys.RESUME_DRIVE_FOLDER_ID)
            prefs.remove(Keys.RESUME_CREATED_AT)
            prefs.remove(Keys.RESUME_DRIVE_FILE_ID)
        }
    }
}
