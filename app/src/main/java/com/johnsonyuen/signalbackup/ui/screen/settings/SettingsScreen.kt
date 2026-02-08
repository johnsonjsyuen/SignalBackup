/**
 * SettingsScreen.kt - The Settings screen for configuring app preferences.
 *
 * This screen provides the user interface for all app configuration:
 * - **Local Backup Folder**: Uses Android's Storage Access Framework (SAF) to let the
 *   user pick a directory where Signal stores its .backup files.
 * - **Google Drive Folder**: Opens a custom DriveFolderPickerDialog for browsing and
 *   selecting the Drive destination folder.
 * - **Upload Schedule**: Opens a TimePickerDialog for choosing the daily backup time.
 * - **Theme**: Opens a ThemeSelectionDialog with Light/Dark/System options.
 * - **Account**: Shows the signed-in Google email with a sign-out button.
 *
 * Key Android/Compose concepts:
 * - **ActivityResultContracts.OpenDocumentTree()**: Launches the system folder picker
 *   (SAF). Returns a content URI that can be used to read files in that directory.
 *   `takePersistableUriPermission` ensures the URI survives app restarts.
 * - **Scaffold with SnackbarHost**: The Scaffold provides a slot for a SnackbarHost,
 *   which displays error messages from Drive API calls (driveError state).
 * - **LaunchedEffect(driveError)**: Watches the driveError state and shows a snackbar
 *   whenever a new error occurs. After showing, clears the error in the ViewModel.
 * - **rememberScrollState() + verticalScroll()**: Makes the settings list scrollable
 *   if content exceeds the screen height.
 * - **collectAsStateWithLifecycle()**: Lifecycle-aware Flow collection for all settings.
 *
 * Dialog management:
 * Three boolean state variables (showTimePicker, showDrivePicker, showThemeDialog)
 * control which dialog is currently visible. Only one dialog can be open at a time
 * because the user must dismiss a dialog before interacting with the screen again.
 *
 * @see ui.screen.settings.SettingsViewModel for the state management
 * @see ui.component.DriveFolderPickerDialog for the Drive folder browser
 * @see ui.component.TimePickerDialog for the schedule time picker
 */
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

/**
 * The Settings screen composable.
 *
 * @param viewModel The Hilt-injected SettingsViewModel.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    // -----------------------------------------------------------------------
    // Collect all ViewModel state flows as lifecycle-aware Compose State.
    // -----------------------------------------------------------------------
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

    // Show Drive API errors as a snackbar, then clear the error.
    LaunchedEffect(driveError) {
        driveError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearDriveError()
        }
    }

    // -----------------------------------------------------------------------
    // Dialog visibility state -- local to this composable.
    // -----------------------------------------------------------------------
    var showTimePicker by remember { mutableStateOf(false) }
    var showDrivePicker by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    // -----------------------------------------------------------------------
    // SAF folder picker launcher.
    // OpenDocumentTree launches the system "choose directory" UI.
    // On result, we take a persistable URI permission so the app can access
    // the folder across reboots without re-asking the user.
    // -----------------------------------------------------------------------
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take a persistable read permission so the URI survives app restarts.
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.setLocalFolderUri(it.toString())
        }
    }

    // -----------------------------------------------------------------------
    // Screen layout: Scaffold with snackbar + scrollable Column of settings cards.
    // -----------------------------------------------------------------------
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())  // Scrollable for small screens.
                .padding(scaffoldPadding)                // Respect scaffold insets.
                .padding(16.dp),                         // Content padding.
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // --- Configuration section ---
            Text("Configuration", style = MaterialTheme.typography.titleLarge)

            Spacer(modifier = Modifier.height(8.dp))

            // Local backup folder card -- shows status and "Select" button.
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

            // Google Drive folder card -- "Select" button disabled until signed in.
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Google Drive Folder") },
                    supportingContent = { Text(driveFolderName ?: "Not set") },
                    leadingContent = { Icon(Icons.Default.CloudQueue, contentDescription = null) },
                    trailingContent = {
                        TextButton(
                            onClick = {
                                viewModel.loadDriveFolders()  // Load root-level folders.
                                showDrivePicker = true
                            },
                            enabled = googleEmail != null  // Must be signed in first.
                        ) {
                            Text("Select")
                        }
                    }
                )
            }

            // Upload schedule card -- shows formatted time and "Change" button.
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

            // --- Appearance section ---
            Text("Appearance", style = MaterialTheme.typography.titleLarge)

            // Theme card -- shows current mode with a contextual icon.
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Theme") },
                    supportingContent = { Text(themeMode.displayName) },
                    leadingContent = {
                        // Show a different icon for each theme mode.
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

            // --- Account section ---
            Text("Account", style = MaterialTheme.typography.titleLarge)

            // Account info card -- shows email or "Not signed in", with sign-out button.
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

    // -----------------------------------------------------------------------
    // Dialogs -- rendered conditionally based on boolean state flags.
    // -----------------------------------------------------------------------

    // Time picker dialog for changing the upload schedule.
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

    // Drive folder picker dialog for browsing and selecting a Drive folder.
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

    // Theme selection dialog with radio buttons for Light/Dark/System.
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

/**
 * A dialog with radio buttons for choosing the app's theme mode.
 *
 * Iterates over all [ThemeMode.entries] and renders a RadioButton + label for each.
 * Selecting a mode immediately calls [onSelectMode] and closes the dialog.
 *
 * @param currentMode The currently active theme mode (determines which radio is selected).
 * @param onSelectMode Callback when a mode is selected.
 * @param onDismiss Callback when the dialog is dismissed (Cancel button or back press).
 */
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
                // Render one radio button row for each ThemeMode enum entry.
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
