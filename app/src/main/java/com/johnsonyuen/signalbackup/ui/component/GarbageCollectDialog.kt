package com.johnsonyuen.signalbackup.ui.component

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.johnsonyuen.signalbackup.domain.model.DriveFile
import com.johnsonyuen.signalbackup.domain.model.GcUiState

/**
 * Dialog that handles all garbage collection UI states:
 * - Scanning: shows a loading spinner
 * - Confirm: shows list of old files to delete with sizes, asks for confirmation
 * - Deleting: shows a progress spinner
 * - Done: shows how many files were deleted and how much space was freed
 * - NothingToDelete: informs the user no old backups were found
 * - Error: shows error message
 *
 * The dialog auto-dismisses only when the user explicitly dismisses it.
 */
@Composable
fun GarbageCollectDialog(
    state: GcUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is GcUiState.Idle -> { /* No dialog shown */ }

        is GcUiState.Scanning -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Scanning Drive") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Looking for old backups...")
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                },
            )
        }

        is GcUiState.Confirm -> {
            ConfirmDeleteDialog(
                latestFile = state.latestFile,
                filesToDelete = state.filesToDelete,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        }

        is GcUiState.Deleting -> {
            AlertDialog(
                onDismissRequest = { /* non-dismissible while deleting */ },
                title = { Text("Deleting") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Deleting old backups...")
                    }
                },
                confirmButton = {},
            )
        }

        is GcUiState.Done -> {
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                },
                title = { Text("Cleanup Complete") },
                text = {
                    Column {
                        Text("Deleted ${state.deletedCount} old backup${if (state.deletedCount != 1) "s" else ""}.")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Freed ${Formatter.formatShortFileSize(context, state.freedBytes)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("OK")
                    }
                },
            )
        }

        is GcUiState.NothingToDelete -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Nothing to Clean Up") },
                text = { Text("There are no old backups to delete. Only the latest backup was found.") },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("OK")
                    }
                },
            )
        }

        is GcUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Error") },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("OK")
                    }
                },
            )
        }
    }
}

/**
 * Confirmation dialog showing the list of old backup files to delete.
 *
 * Displays:
 * - Which file will be kept (the latest)
 * - A scrollable list of files to delete with individual sizes
 * - The total size to be freed
 * - Confirm (Delete) and Cancel buttons
 */
@Composable
private fun ConfirmDeleteDialog(
    latestFile: DriveFile,
    filesToDelete: List<DriveFile>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val totalBytes = filesToDelete.sumOf { it.sizeBytes }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp),
            )
        },
        title = { Text("Delete Old Backups?") },
        text = {
            Column {
                Text(
                    text = "Keeping:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = latestFile.name,
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Will delete ${filesToDelete.size} file${if (filesToDelete.size != 1) "s" else ""}:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(4.dp))

                LazyColumn(
                    modifier = Modifier.height((filesToDelete.size.coerceAtMost(5) * 40).dp),
                ) {
                    items(filesToDelete) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                text = Formatter.formatShortFileSize(context, file.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Total: ${Formatter.formatShortFileSize(context, totalBytes)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    "Delete",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
