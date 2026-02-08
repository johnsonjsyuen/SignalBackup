/**
 * DriveFolderPickerDialog.kt - A dialog for browsing and selecting Google Drive folders.
 *
 * This composable provides a navigable folder browser within an AlertDialog, allowing the
 * user to browse their Google Drive folder hierarchy, navigate into subfolders, go back
 * to the parent folder, and create new folders -- all without leaving the Settings screen.
 *
 * User interaction flow:
 * 1. User taps "Select" on the Drive Folder setting -> SettingsViewModel.loadDriveFolders()
 *    is called, which fetches the root-level folders from the Drive API.
 * 2. The dialog opens, showing a list of folders in a LazyColumn.
 * 3. Tapping a folder navigates into it (pushes onto the folder stack, loads children).
 * 4. Tapping the back arrow navigates up (pops the stack, loads the parent's children).
 * 5. Tapping "Select This Folder" saves the current folder (or root if at top level).
 * 6. Tapping the "New Folder" icon opens a nested dialog to create a folder.
 *
 * Architecture:
 * - The folder navigation state (stack of visited folders, current folder list, loading
 *   state) lives in [SettingsViewModel]. This dialog is purely presentational.
 * - The nested "New Folder" dialog state (showNewFolderDialog, newFolderName) is local
 *   Compose state since it is ephemeral and only relevant to this dialog.
 *
 * Key Compose concepts:
 * - **LazyColumn with items()**: Efficiently renders the folder list with recycling.
 * - **AlertDialog with custom title**: The title Row contains a back button, the folder
 *   name, and a create-folder button -- acting as a mini toolbar.
 * - **Nested AlertDialog**: The "New Folder" dialog is a separate AlertDialog that opens
 *   on top of the folder picker dialog.
 * - **remember { mutableStateOf(...) }**: Local state for the nested dialog visibility
 *   and the new folder name input.
 *
 * @see ui.screen.settings.SettingsViewModel for the folder navigation state machine
 * @see data.repository.DriveRepository for the API calls that list/create folders
 */
package com.johnsonyuen.signalbackup.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.johnsonyuen.signalbackup.domain.model.DriveFolder

/**
 * A navigable Google Drive folder picker dialog.
 *
 * @param folders The list of subfolders in the currently viewed folder.
 * @param isLoading True while folder contents are being fetched from the Drive API.
 * @param currentFolderName The display name of the current folder (null for root = "My Drive").
 * @param onNavigateToFolder Callback when the user taps a folder to browse into it.
 * @param onNavigateUp Callback when the user taps the back arrow to go to the parent folder.
 * @param onSelectCurrentFolder Callback when the user taps "Select This Folder" -- passes
 *        the current [DriveFolder] (or null if at root, meaning "My Drive").
 * @param onCreateFolder Callback when the user creates a new folder with the given name.
 * @param onDismiss Callback when the user cancels the dialog.
 * @param currentFolder The current [DriveFolder] being viewed (null = Drive root).
 */
@Composable
fun DriveFolderPickerDialog(
    folders: List<DriveFolder>,
    isLoading: Boolean,
    currentFolderName: String?,
    onNavigateToFolder: (DriveFolder) -> Unit,
    onNavigateUp: () -> Unit,
    onSelectCurrentFolder: (DriveFolder?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDismiss: () -> Unit,
    currentFolder: DriveFolder?
) {
    // Local state for the "New Folder" nested dialog.
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // Custom title row acting as a mini toolbar:
            // [Back button (if not at root)] [Folder name] [Create folder button]
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Only show back arrow when navigated into a subfolder.
                if (currentFolder != null) {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                // Show the current folder name, or "My Drive" if at root.
                Text(currentFolderName ?: "My Drive", modifier = Modifier.weight(1f))
                // Button to open the "create new folder" nested dialog.
                IconButton(onClick = { showNewFolderDialog = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
                }
            }
        },
        text = {
            // Fixed-height box for the folder list to prevent the dialog from resizing
            // as folders load or when switching between folders with different counts.
            Box(modifier = Modifier.height(300.dp).fillMaxWidth()) {
                if (isLoading) {
                    // Show a centered spinner while fetching folder contents from Drive.
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (folders.isEmpty()) {
                    Text("No folders", modifier = Modifier.align(Alignment.Center))
                } else {
                    // LazyColumn efficiently renders potentially many folders with recycling.
                    LazyColumn {
                        items(folders) { folder ->
                            ListItem(
                                headlineContent = { Text(folder.name) },
                                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                                // Tapping a folder navigates into it (loads its children).
                                modifier = Modifier.clickable { onNavigateToFolder(folder) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            // "Select This Folder" saves whichever folder is currently being viewed.
            // If at root (currentFolder == null), the Drive root ("My Drive") is selected.
            TextButton(onClick = { onSelectCurrentFolder(currentFolder) }) {
                Text("Select This Folder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    // Nested "New Folder" dialog -- opens on top of the folder picker.
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("New Folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            onCreateFolder(newFolderName)
                            newFolderName = ""            // Reset for next use
                            showNewFolderDialog = false   // Close nested dialog
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") }
            }
        )
    }
}
