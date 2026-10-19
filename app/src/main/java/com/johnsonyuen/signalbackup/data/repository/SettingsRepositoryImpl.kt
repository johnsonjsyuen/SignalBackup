package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.local.db.SettingsDao
import com.johnsonyuen.signalbackup.data.local.entity.SettingsEntity
import com.johnsonyuen.signalbackup.data.local.entity.toAppSettings
import com.johnsonyuen.signalbackup.domain.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed settings. All flows derive from one shared, distinct-until-changed source. */
@Singleton
class SettingsRepositoryImpl internal constructor(
    private val dao: SettingsDao,
    scope: CoroutineScope,
) : SettingsRepository {

    @Inject
    constructor(dao: SettingsDao) : this(dao, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    override val settings: Flow<AppSettings> = dao.observe()
        .map { (it ?: SettingsEntity()).toAppSettings() }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    override suspend fun current(): AppSettings = (dao.get() ?: SettingsEntity()).toAppSettings()

    override val localFolderUri: Flow<String?> = select { it.localFolderUri }
    override val driveFolderId: Flow<String?> = select { it.driveFolderId }
    override val driveFolderName: Flow<String?> = select { it.driveFolderName }
    override val scheduleEnabled: Flow<Boolean> = select { it.scheduleEnabled }
    override val scheduleHour: Flow<Int> = select { it.scheduleHour }
    override val scheduleMinute: Flow<Int> = select { it.scheduleMinute }
    override val googleAccountEmail: Flow<String?> = select { it.googleAccountEmail }
    override val themeMode: Flow<String> = select { it.themeMode }
    override val wifiOnly: Flow<Boolean> = select { it.wifiOnly }

    override suspend fun setLocalFolderUri(uri: String?) = update { dao.updateLocalFolderUri(uri) }

    override suspend fun setDriveFolder(id: String?, name: String?) = update { dao.updateDriveFolder(id, name) }

    override suspend fun setScheduleEnabled(enabled: Boolean) = update { dao.updateScheduleEnabled(enabled) }

    override suspend fun setScheduleTime(hour: Int, minute: Int) {
        require(hour in 0..23 && minute in 0..59) { "Invalid schedule time" }
        update { dao.updateScheduleTime(hour, minute) }
    }

    override suspend fun setGoogleAccountEmail(email: String?) = update { dao.updateGoogleAccountEmail(email) }

    override suspend fun setThemeMode(mode: String) = update { dao.updateThemeMode(mode) }

    override suspend fun setWifiOnly(enabled: Boolean) = update { dao.updateWifiOnly(enabled) }

    override suspend fun clearAccountData() = update { dao.clearAccountData() }

    private fun <T> select(selector: (AppSettings) -> T): Flow<T> =
        settings.map(selector).distinctUntilChanged()

    /** Updates are UPDATE statements, so make sure the single row exists first. */
    private suspend inline fun update(block: () -> Unit) {
        dao.insertIfAbsent(SettingsEntity())
        block()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
