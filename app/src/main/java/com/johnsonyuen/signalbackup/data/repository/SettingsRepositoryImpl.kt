package com.johnsonyuen.signalbackup.data.repository

import android.util.Log
import com.johnsonyuen.signalbackup.data.local.db.SettingsDao
import com.johnsonyuen.signalbackup.data.local.entity.SettingsEntity
import com.johnsonyuen.signalbackup.domain.model.ResumableUploadSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

class SettingsRepositoryImpl @Inject constructor(
    private val settingsDao: SettingsDao,
) : SettingsRepository {

    private val settingsFlow: Flow<SettingsEntity> =
        settingsDao.getSettings()
            .onEach { Log.d(TAG, "Room emitted settings entity: $it") }
            .map { it ?: SettingsEntity() }

    override val localFolderUri: Flow<String?> =
        settingsFlow.map { it.localFolderUri }

    override val driveFolderId: Flow<String?> =
        settingsFlow.map { it.driveFolderId }

    override val driveFolderName: Flow<String?> =
        settingsFlow.map { it.driveFolderName }

    override val scheduleHour: Flow<Int> =
        settingsFlow.map { it.scheduleHour }

    override val scheduleMinute: Flow<Int> =
        settingsFlow.map { it.scheduleMinute }

    override val googleAccountEmail: Flow<String?> =
        settingsFlow.map { it.googleAccountEmail }

    override val themeMode: Flow<String> =
        settingsFlow.map { it.themeMode }

    override val wifiOnly: Flow<Boolean> =
        settingsFlow.map { it.wifiOnly }

    override suspend fun setLocalFolderUri(uri: String?) {
        Log.d(TAG, "setLocalFolderUri: $uri")
        settingsDao.updateLocalFolderUri(uri)
        Log.d(TAG, "setLocalFolderUri done, row now: ${settingsDao.getSettingsOnce()}")
    }

    override suspend fun setDriveFolderId(id: String?) {
        Log.d(TAG, "setDriveFolderId: $id")
        settingsDao.updateDriveFolderId(id)
        Log.d(TAG, "setDriveFolderId done, row now: ${settingsDao.getSettingsOnce()}")
    }

    override suspend fun setDriveFolderName(name: String?) {
        Log.d(TAG, "setDriveFolderName: $name")
        settingsDao.updateDriveFolderName(name)
    }

    override suspend fun setScheduleHour(hour: Int) {
        settingsDao.updateScheduleHour(hour)
    }

    override suspend fun setScheduleMinute(minute: Int) {
        settingsDao.updateScheduleMinute(minute)
    }

    override suspend fun setScheduleTime(hour: Int, minute: Int) {
        settingsDao.updateScheduleTime(hour, minute)
    }

    override suspend fun clearAccountData() {
        settingsDao.clearAccountData()
        settingsDao.clearResumableSession()
    }

    override suspend fun setGoogleAccountEmail(email: String?) {
        Log.d(TAG, "setGoogleAccountEmail: $email")
        settingsDao.updateGoogleAccountEmail(email)
        Log.d(TAG, "setGoogleAccountEmail done, row now: ${settingsDao.getSettingsOnce()}")
    }

    override suspend fun setThemeMode(mode: String) {
        Log.d(TAG, "setThemeMode: $mode")
        settingsDao.updateThemeMode(mode)
    }

    override suspend fun setWifiOnly(enabled: Boolean) {
        Log.d(TAG, "setWifiOnly: $enabled")
        settingsDao.updateWifiOnly(enabled)
    }

    override suspend fun getResumableSession(): ResumableUploadSession? {
        val entity = settingsDao.getSettingsOnce() ?: return null
        val sessionUri = entity.resumeSessionUri ?: return null
        return ResumableUploadSession(
            sessionUri = sessionUri,
            localFileUri = entity.resumeLocalFileUri ?: return null,
            fileName = entity.resumeFileName ?: return null,
            bytesUploaded = entity.resumeBytesUploaded ?: 0L,
            totalBytes = entity.resumeTotalBytes ?: return null,
            driveFolderId = entity.resumeDriveFolderId ?: return null,
            createdAtMillis = entity.resumeCreatedAt ?: return null,
            driveFileId = entity.resumeDriveFileId,
        )
    }

    override suspend fun saveResumableSession(session: ResumableUploadSession) {
        settingsDao.saveResumableSession(
            sessionUri = session.sessionUri,
            localFileUri = session.localFileUri,
            fileName = session.fileName,
            bytesUploaded = session.bytesUploaded,
            totalBytes = session.totalBytes,
            driveFolderId = session.driveFolderId,
            createdAt = session.createdAtMillis,
            driveFileId = session.driveFileId,
        )
    }

    override suspend fun updateResumableBytesUploaded(bytesUploaded: Long) {
        settingsDao.updateResumableBytesUploaded(bytesUploaded)
    }

    override suspend fun updateResumableDriveFileId(driveFileId: String) {
        settingsDao.updateResumableDriveFileId(driveFileId)
    }

    override suspend fun clearResumableSession() {
        settingsDao.clearResumableSession()
    }

    companion object {
        private const val TAG = "SettingsRepo"
    }
}
