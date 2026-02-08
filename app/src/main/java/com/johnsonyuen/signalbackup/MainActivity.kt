package com.johnsonyuen.signalbackup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.johnsonyuen.signalbackup.ui.theme.SignalBackupTheme
import com.johnsonyuen.signalbackup.ui.navigation.AppNavGraph
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SignalBackupTheme {
                AppNavGraph()
            }
        }
    }
}
