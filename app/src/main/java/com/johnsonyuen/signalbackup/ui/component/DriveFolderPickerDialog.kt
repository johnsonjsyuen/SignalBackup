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
import com.johnsonyuen.signalbackup.data.repository.DriveFolder

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
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentFolder != null) {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(currentFolderName ?: "My Drive", modifier = Modifier.weight(1f))
                IconButton(onClick = { showNewFolderDialog = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
                }
            }
        },
        text = {
            Box(modifier = Modifier.height(300.dp).fillMaxWidth()) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (folders.isEmpty()) {
                    Text("No folders", modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn {
                        items(folders) { folder ->
                            ListItem(
                                headlineContent = { Text(folder.name) },
                                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                                modifier = Modifier.clickable { onNavigateToFolder(folder) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
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
                            newFolderName = ""
                            showNewFolderDialog = false
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
