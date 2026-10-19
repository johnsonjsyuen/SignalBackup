package com.johnsonyuen.signalbackup.ui.screen.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.johnsonyuen.signalbackup.BuildConfig
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.model.ThemeMode
import com.johnsonyuen.signalbackup.ui.common.labelRes
import com.johnsonyuen.signalbackup.ui.common.resolve
import com.johnsonyuen.signalbackup.ui.component.DriveFolderPickerDialog
import com.johnsonyuen.signalbackup.ui.component.NoBackupsWarning
import com.johnsonyuen.signalbackup.ui.component.TimePickerDialog
import com.johnsonyuen.signalbackup.util.formatTimeOfDay
import kotlinx.coroutines.launch

private const val TAG = "SettingsScreen"

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showSignOutDialog by rememberSaveable { mutableStateOf(false) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onConsentResult(result.data) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.LaunchConsent -> try {
                    consentLauncher.launch(IntentSenderRequest.Builder(event.pendingIntent).build())
                } catch (e: Exception) {
                    Log.w(TAG, "Could not launch consent screen: ${e.javaClass.simpleName}")
                    viewModel.onConsentLaunchFailed()
                }
                is SettingsEvent.ShowMessage -> {
                    val text = event.text.resolve(context)
                    launch { snackbarHostState.showSnackbar(text) }
                }
            }
        }
    }

    // Permission state, refreshed whenever the screen resumes.
    var notificationsGranted by remember { mutableStateOf(true) }
    var ignoringBatteryOptimizations by remember { mutableStateOf(true) }
    var hibernationStatus by remember { mutableIntStateOf(UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE) }
    var refreshPermissions by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshLocalFolder()
        refreshPermissions++
    }
    LaunchedEffect(refreshPermissions) {
        notificationsGranted = hasNotificationPermission(context)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName)
        val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(context)
        future.addListener(
            {
                hibernationStatus = try {
                    future.get()
                } catch (e: Exception) {
                    UnusedAppRestrictionsConstants.ERROR
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted
        if (!granted) {
            // Permanently denied or dismissed: send the user to the app's notification settings.
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }
    val hibernationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshPermissions++ }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                viewModel.setLocalFolder(uri.toString())
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not persist folder permission")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_section_backup))

            SettingCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_local_folder)) },
                    supportingContent = {
                        Column {
                            Text(
                                when {
                                    !state.localFolderSet -> stringResource(R.string.not_set)
                                    !state.localFolderAccessible -> stringResource(R.string.local_folder_inaccessible)
                                    else -> state.localFolderName ?: stringResource(R.string.local_folder_selected)
                                },
                            )
                            if (state.localFolderSet && state.localFolderAccessible && state.localFolderHasBackups == false) {
                                NoBackupsWarning()
                            }
                        }
                    },
                    leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                    trailingContent = {
                        TextButton(onClick = { folderPickerLauncher.launch(null) }) {
                            Text(stringResource(R.string.action_select))
                        }
                    },
                )
            }

            SettingCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_drive_folder)) },
                    supportingContent = { Text(state.driveFolderName ?: stringResource(R.string.not_set)) },
                    leadingContent = { Icon(Icons.Default.CloudQueue, contentDescription = null) },
                    trailingContent = {
                        TextButton(onClick = viewModel::openDrivePicker, enabled = state.signedIn) {
                            Text(stringResource(R.string.action_select))
                        }
                    },
                )
            }

            SettingCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_schedule_enabled)) },
                    supportingContent = { Text(stringResource(R.string.settings_schedule_enabled_detail)) },
                    leadingContent = { Icon(Icons.Default.EventRepeat, contentDescription = null) },
                    trailingContent = { Switch(checked = state.scheduleEnabled, onCheckedChange = null) },
                    modifier = Modifier.toggleable(
                        value = state.scheduleEnabled,
                        role = Role.Switch,
                        onValueChange = viewModel::setScheduleEnabled,
                    ),
                )
            }

            SettingCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_schedule_time)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.settings_daily_at,
                                formatTimeOfDay(context, state.scheduleHour, state.scheduleMinute),
                            ),
                        )
                    },
                    leadingContent = { Icon(Icons.Default.Schedule, contentDescription = null) },
                    trailingContent = {
                        TextButton(onClick = { showTimePicker = true }, enabled = state.scheduleEnabled) {
                            Text(stringResource(R.string.action_change))
                        }
                    },
                )
            }

            SettingCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_wifi_only)) },
                    supportingContent = { Text(stringResource(R.string.settings_wifi_only_detail)) },
                    leadingContent = { Icon(Icons.Default.Wifi, contentDescription = null) },
                    trailingContent = { Switch(checked = state.wifiOnly, onCheckedChange = null) },
                    modifier = Modifier.toggleable(
                        value = state.wifiOnly,
                        role = Role.Switch,
                        onValueChange = viewModel::setWifiOnly,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.settings_section_permissions))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionCard(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.perm_notifications),
                    detail = stringResource(R.string.perm_notifications_detail),
                    ok = notificationsGranted,
                    actionLabel = stringResource(R.string.action_allow),
                    onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
            PermissionCard(
                icon = Icons.Default.BatteryAlert,
                title = stringResource(R.string.perm_battery),
                detail = stringResource(R.string.perm_battery_detail),
                ok = ignoringBatteryOptimizations,
                actionLabel = stringResource(R.string.action_allow),
                onAction = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${context.packageName}")),
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Battery optimization settings unavailable")
                    }
                },
            )
            val hibernationApplies = hibernationStatus == UnusedAppRestrictionsConstants.API_30_BACKPORT ||
                hibernationStatus == UnusedAppRestrictionsConstants.API_30 ||
                hibernationStatus == UnusedAppRestrictionsConstants.API_31
            if (hibernationApplies || hibernationStatus == UnusedAppRestrictionsConstants.DISABLED) {
                PermissionCard(
                    icon = Icons.Default.Hotel,
                    title = stringResource(R.string.perm_hibernation),
                    detail = stringResource(R.string.perm_hibernation_detail),
                    ok = !hibernationApplies,
                    actionLabel = stringResource(R.string.action_turn_off),
                    onAction = {
                        try {
                            hibernationLauncher.launch(
                                IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName),
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Unused app settings unavailable")
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.settings_section_appearance))
            SettingCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_theme)) },
                    supportingContent = { Text(stringResource(state.themeMode.labelRes())) },
                    leadingContent = { Icon(state.themeMode.icon(), contentDescription = null) },
                    trailingContent = {
                        TextButton(onClick = { showThemeDialog = true }) { Text(stringResource(R.string.action_change)) }
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.settings_section_account))
            SettingCard {
                ListItem(
                    headlineContent = { Text(state.accountEmail ?: stringResource(R.string.not_signed_in)) },
                    supportingContent = if (state.signedIn) {
                        { Text(stringResource(R.string.settings_account_detail)) }
                    } else {
                        null
                    },
                    leadingContent = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                    trailingContent = {
                        if (state.signedIn) {
                            TextButton(onClick = { showSignOutDialog = true }, enabled = !state.signingOut) {
                                Text(stringResource(R.string.action_sign_out))
                            }
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.settings_section_about))
            SettingCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_build_time)) },
                    supportingContent = { Text(BuildConfig.BUILD_TIME) },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                )
            }
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialHour = state.scheduleHour,
            initialMinute = state.scheduleMinute,
            onConfirm = { h, m ->
                viewModel.setScheduleTime(h, m)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }

    if (state.picker.visible) {
        DriveFolderPickerDialog(
            state = state.picker,
            onOpenFolder = viewModel::openFolder,
            onNavigateUp = viewModel::navigateUp,
            onSelect = viewModel::selectDriveFolder,
            onCreateFolder = viewModel::createFolder,
            onRetry = viewModel::retryLoad,
            onDismiss = viewModel::dismissDrivePicker,
        )
    }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentMode = state.themeMode,
            onSelectMode = { mode ->
                viewModel.setThemeMode(mode)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text(stringResource(R.string.sign_out_title)) },
            text = { Text(stringResource(R.string.sign_out_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.action_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun ThemeMode.icon(): ImageVector = when (this) {
    ThemeMode.LIGHT -> Icons.Default.LightMode
    ThemeMode.DARK -> Icons.Default.DarkMode
    ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    detail: String,
    ok: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (ok) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = {
                Text(if (ok) stringResource(R.string.perm_ok) else detail)
            },
            leadingContent = { Icon(icon, contentDescription = null) },
            trailingContent = {
                if (!ok) TextButton(onClick = onAction) { Text(actionLabel) }
            },
            colors = if (ok) {
                androidx.compose.material3.ListItemDefaults.colors()
            } else {
                androidx.compose.material3.ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    headlineColor = MaterialTheme.colorScheme.onErrorContainer,
                    supportingColor = MaterialTheme.colorScheme.onErrorContainer,
                    leadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            },
        )
    }
}

@Composable
private fun ThemeSelectionDialog(
    currentMode: ThemeMode,
    onSelectMode: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_dialog_title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = mode == currentMode,
                                onClick = { onSelectMode(mode) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == currentMode, onClick = null)
                        Text(
                            stringResource(mode.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
