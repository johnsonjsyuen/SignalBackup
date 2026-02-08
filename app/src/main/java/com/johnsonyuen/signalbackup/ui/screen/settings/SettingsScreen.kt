package com.johnsonyuen.signalbackup.ui.screen.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.johnsonyuen.signalbackup.domain.model.ThemeMode
import com.johnsonyuen.signalbackup.ui.component.DriveFolderPickerDialog
import com.johnsonyuen.signalbackup.ui.component.TimePickerDialog
import com.johnsonyuen.signalbackup.util.formatScheduleTime

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val localFolderUri by viewModel.localFolderUri.collectAsStateWithLifecycle()
    val driveFolderName by viewModel.driveFolderName.collectAsStateWithLifecycle()
    val scheduleHour by viewModel.scheduleHour.collectAsStateWithLifecycle()
    val scheduleMinute by viewModel.scheduleMinute.collectAsStateWithLifecycle()
    val googleEmail by viewModel.googleAccountEmail.collectAsStateWithLifecycle()
    val driveFolders by viewModel.driveFolders.collectAsStateWithLifecycle()
    val isLoadingFolders by viewModel.isLoadingFolders.collectAsStateWithLifecycle()
    val currentDriveFolder by viewModel.currentDriveFolder.collectAsStateWithLifecycle()
    val currentDriveFolderName by viewModel.currentDriveFolderName.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val driveError by viewModel.driveError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(driveError) {
        driveError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearDriveError()
        }
    }

    var showTimePicker by remember { mutableStateOf(false) }
    var showDrivePicker by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.setLocalFolderUri(it.toString())
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(scaffoldPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Configuration", style = MaterialTheme.typography.titleLarge)

            Spacer(modifier = Modifier.height(8.dp))

            // Local folder
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Local Backup Folder") },
                    supportingContent = { Text(localFolderUri?.let { "Folder selected" } ?: "Not set") },
                    leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                    trailingContent = {
                        TextButton(onClick = { folderPickerLauncher.launch(null) }) {
                            Text("Select")
                        }
                    }
                )
            }

            // Drive folder
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Google Drive Folder") },
                    supportingContent = { Text(driveFolderName ?: "Not set") },
                    leadingContent = { Icon(Icons.Default.CloudQueue, contentDescription = null) },
                    trailingContent = {
                        TextButton(
                            onClick = {
                                viewModel.loadDriveFolders()
                                showDrivePicker = true
                            },
                            enabled = googleEmail != null
                        ) {
                            Text("Select")
                        }
                    }
                )
            }

            // Schedule time
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Upload Schedule") },
                    supportingContent = {
                        Text("Daily at ${formatScheduleTime(scheduleHour, scheduleMinute)}")
                    },
                    leadingContent = { Icon(Icons.Default.Schedule, contentDescription = null) },
                    trailingContent = {
                        TextButton(onClick = { showTimePicker = true }) {
                            Text("Change")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Appearance section
            Text("Appearance", style = MaterialTheme.typography.titleLarge)

            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Theme") },
                    supportingContent = { Text(themeMode.displayName) },
                    leadingContent = {
                        Icon(
                            when (themeMode) {
                                ThemeMode.LIGHT -> Icons.Default.LightMode
                                ThemeMode.DARK -> Icons.Default.DarkMode
                                ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                            },
                            contentDescription = null
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { showThemeDialog = true }) {
                            Text("Change")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Account info
            Text("Account", style = MaterialTheme.typography.titleLarge)

            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(googleEmail ?: "Not signed in") },
                    leadingContent = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                    trailingContent = {
                        if (googleEmail != null) {
                            TextButton(onClick = { viewModel.signOut() }) {
                                Text("Sign Out")
                            }
                        }
                    }
                )
            }
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialHour = scheduleHour,
            initialMinute = scheduleMinute,
            onConfirm = { h, m ->
                viewModel.setScheduleTime(h, m)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false }
        )
    }

    if (showDrivePicker) {
        DriveFolderPickerDialog(
            folders = driveFolders,
            isLoading = isLoadingFolders,
            currentFolderName = currentDriveFolderName,
            onNavigateToFolder = { viewModel.navigateToFolder(it) },
            onNavigateUp = { viewModel.navigateUp() },
            onSelectCurrentFolder = { folder ->
                viewModel.setDriveFolder(folder)
                showDrivePicker = false
            },
            onCreateFolder = { name -> viewModel.createFolder(name) },
            onDismiss = { showDrivePicker = false },
            currentFolder = currentDriveFolder
        )
    }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentMode = themeMode,
            onSelectMode = { mode ->
                viewModel.setThemeMode(mode)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }
}

@Composable
private fun ThemeSelectionDialog(
    currentMode: ThemeMode,
    onSelectMode: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose theme") },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = mode == currentMode,
                            onClick = { onSelectMode(mode) }
                        )
                        Text(
                            text = mode.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .align(alignment = androidx.compose.ui.Alignment.CenterVertically)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
