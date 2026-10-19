package com.johnsonyuen.signalbackup

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.ThemeMode
import com.johnsonyuen.signalbackup.notification.NotificationIntents
import com.johnsonyuen.signalbackup.ui.navigation.AppNavGraph
import com.johnsonyuen.signalbackup.ui.navigation.FixAction
import com.johnsonyuen.signalbackup.ui.navigation.FixActionBus
import com.johnsonyuen.signalbackup.ui.theme.SignalBackupTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Single activity hosting the Compose UI; routes notification fix actions. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var fixActionBus: FixActionBus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On recreation the extra was already handled (and removed) before.
        if (savedInstanceState == null) handleFixAction(intent)
        setContent {
            val themeModeString by settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM.name)
            SignalBackupTheme(themeMode = ThemeMode.fromString(themeModeString)) {
                AppNavGraph(fixActionBus = fixActionBus)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFixAction(intent)
    }

    private fun handleFixAction(intent: Intent?) {
        val action = FixAction.fromExtra(intent?.getStringExtra(NotificationIntents.EXTRA_FIX_ACTION)) ?: return
        intent?.removeExtra(NotificationIntents.EXTRA_FIX_ACTION)
        fixActionBus.post(action)
    }
}
