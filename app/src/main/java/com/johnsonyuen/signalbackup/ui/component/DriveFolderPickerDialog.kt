package com.johnsonyuen.signalbackup.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import com.johnsonyuen.signalbackup.ui.common.asString
import com.johnsonyuen.signalbackup.ui.screen.settings.DrivePickerState

/** Browse app-visible Drive folders, create one, and pick the backup destination. */
@Composable
fun DriveFolderPickerDialog(
    state: DrivePickerState,
    onOpenFolder: (DriveFolder) -> Unit,
    onNavigateUp: () -> Unit,
    onSelect: (DriveFolder) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showNewFolder by rememberSaveable { mutableStateOf(false) }
    var newFolderName by rememberSaveable { mutableStateOf("") }
    val current = state.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current != null) {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
                Text(
                    current?.name ?: stringResource(R.string.my_drive),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                IconButton(onClick = { showNewFolder = true }, enabled = !state.creating) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.cd_new_folder))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.picker_scope_note), style = MaterialTheme.typography.bodySmall)
                Box(modifier = Modifier.height(280.dp).fillMaxWidth()) {
                    when {
                        state.loading || state.creating ->
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        state.error != null -> Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(state.error.asString(), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                        }
                        state.folders.isEmpty() ->
                            Text(stringResource(R.string.picker_empty), modifier = Modifier.align(Alignment.Center))
                        else -> LazyColumn {
                            items(state.folders, key = { it.id }) { folder ->
                                ListItem(
                                    headlineContent = { Text(folder.name) },
                                    leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                                    modifier = Modifier.clickable(role = Role.Button) { onOpenFolder(folder) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { current?.let(onSelect) }, enabled = current != null) {
                Text(stringResource(R.string.picker_select))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text(stringResource(R.string.new_folder_title)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.new_folder_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreateFolder(newFolderName)
                        newFolderName = ""
                        showNewFolder = false
                    },
                    enabled = newFolderName.isNotBlank(),
                ) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
