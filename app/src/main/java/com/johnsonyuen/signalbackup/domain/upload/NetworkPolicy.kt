package com.johnsonyuen.signalbackup.domain.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.WifiRequiredException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Enforces the user's "Wi-Fi only" setting. */
class NetworkPolicy @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val networkChecker: UnmeteredNetworkChecker,
) {
    /** Throws [WifiRequiredException] if Wi-Fi only is on and the active network is metered or absent. */
    suspend fun ensureUploadAllowed() {
        if (settingsRepository.current().wifiOnly && !networkChecker.isUnmetered()) {
            UploadLog.w("Wi-Fi only is enabled but the active network is metered")
            throw WifiRequiredException()
        }
    }
}

/** Reports whether the active network is unmetered. */
fun interface UnmeteredNetworkChecker {
    fun isUnmetered(): Boolean
}

/** [UnmeteredNetworkChecker] backed by ConnectivityManager. */
class AndroidUnmeteredNetworkChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : UnmeteredNetworkChecker {
    override fun isUnmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
