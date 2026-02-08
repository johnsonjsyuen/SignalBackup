package com.johnsonyuen.signalbackup.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
) {

    // ---- Keys ----

    private object Keys {
        val LOCAL_FOLDER_URI = stringPreferencesKey("local_folder_uri")
        val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")
        val DRIVE_FOLDER_NAME = stringPreferencesKey("drive_folder_name")
        val SCHEDULE_HOUR = intPreferencesKey("schedule_hour")
        val SCHEDULE_MINUTE = intPreferencesKey("schedule_minute")
        val GOOGLE_ACCOUNT_EMAIL = stringPreferencesKey("google_account_email")
    }

    // ---- Defaults ----

    companion object {
        const val DEFAULT_SCHEDULE_HOUR = 3
        const val DEFAULT_SCHEDULE_MINUTE = 0
    }

    // ---- Flow getters ----

    val localFolderUri: Flow<String?>
        get() = dataStore.data.map { it[Keys.LOCAL_FOLDER_URI] }

    val driveFolderId: Flow<String?>
        get() = dataStore.data.map { it[Keys.DRIVE_FOLDER_ID] }

    val driveFolderName: Flow<String?>
        get() = dataStore.data.map { it[Keys.DRIVE_FOLDER_NAME] }

    val scheduleHour: Flow<Int>
        get() = dataStore.data.map { it[Keys.SCHEDULE_HOUR] ?: DEFAULT_SCHEDULE_HOUR }

    val scheduleMinute: Flow<Int>
        get() = dataStore.data.map { it[Keys.SCHEDULE_MINUTE] ?: DEFAULT_SCHEDULE_MINUTE }

    val googleAccountEmail: Flow<String?>
        get() = dataStore.data.map { it[Keys.GOOGLE_ACCOUNT_EMAIL] }

    // ---- Suspend setters ----

    suspend fun setLocalFolderUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri != null) prefs[Keys.LOCAL_FOLDER_URI] = uri
            else prefs.remove(Keys.LOCAL_FOLDER_URI)
        }
    }

    suspend fun setDriveFolderId(id: String?) {
        dataStore.edit { prefs ->
            if (id != null) prefs[Keys.DRIVE_FOLDER_ID] = id
            else prefs.remove(Keys.DRIVE_FOLDER_ID)
        }
    }

    suspend fun setDriveFolderName(name: String?) {
        dataStore.edit { prefs ->
            if (name != null) prefs[Keys.DRIVE_FOLDER_NAME] = name
            else prefs.remove(Keys.DRIVE_FOLDER_NAME)
        }
    }

    suspend fun setScheduleHour(hour: Int) {
        dataStore.edit { prefs -> prefs[Keys.SCHEDULE_HOUR] = hour }
    }

    suspend fun setScheduleMinute(minute: Int) {
        dataStore.edit { prefs -> prefs[Keys.SCHEDULE_MINUTE] = minute }
    }

    suspend fun setGoogleAccountEmail(email: String?) {
        dataStore.edit { prefs ->
            if (email != null) prefs[Keys.GOOGLE_ACCOUNT_EMAIL] = email
            else prefs.remove(Keys.GOOGLE_ACCOUNT_EMAIL)
        }
    }
}
