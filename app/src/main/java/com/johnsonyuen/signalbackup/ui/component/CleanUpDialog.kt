package com.johnsonyuen.signalbackup.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.model.DriveFile
import com.johnsonyuen.signalbackup.ui.common.asString
import com.johnsonyuen.signalbackup.ui.screen.home.CleanUpState
import com.johnsonyuen.signalbackup.util.formatFileSize

/** Clean Up: scan, review kept/to-trash items, move to Drive trash, show the result. */
@Composable
fun CleanUpDialog(
    state: CleanUpState,
    driveFolderName: String?,
    accountEmail: String?,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == CleanUpState.Hidden) return
    val busy = state is CleanUpState.Trashing
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.cleanup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.cleanup_location,
                        driveFolderName ?: stringResource(R.string.not_set),
                        accountEmail ?: stringResource(R.string.not_signed_in),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                when (state) {
                    CleanUpState.Hidden -> Unit
                    CleanUpState.Scanning -> Progress(stringResource(R.string.cleanup_scanning))
                    is CleanUpState.Trashing ->
                        Progress(pluralStringResource(R.plurals.cleanup_trashing, state.count, state.count))
                    CleanUpState.NothingToClean -> Text(stringResource(R.string.cleanup_nothing))
                    is CleanUpState.Blocked -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Text(stringResource(R.string.cleanup_blocked))
                        }
                        Text(state.message, style = MaterialTheme.typography.bodySmall)
                    }
                    is CleanUpState.Error -> Text(state.message.asString(), color = MaterialTheme.colorScheme.error)
                    is CleanUpState.Ready -> ReadyContent(state)
                    is CleanUpState.Done -> {
                        val r = state.result
                        Text(
                            pluralStringResource(
                                R.plurals.cleanup_done,
                                r.trashedCount,
                                r.trashedCount,
                                formatFileSize(r.freedBytes),
                            ),
                        )
                        Text(stringResource(R.string.cleanup_trash_note), style = MaterialTheme.typography.bodySmall)
                        if (r.failedNames.isNotEmpty()) {
                            Text(
                                pluralStringResource(R.plurals.cleanup_failed, r.failedNames.size, r.failedNames.size),
                                color = MaterialTheme.colorScheme.error,
                            )
                            r.failedNames.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is CleanUpState.Ready -> TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.cleanup_confirm))
                }
                is CleanUpState.Error -> TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                is CleanUpState.Done, CleanUpState.NothingToClean, is CleanUpState.Blocked ->
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                else -> Unit
            }
        },
        dismissButton = {
            if (state is CleanUpState.Ready || state is CleanUpState.Scanning || state is CleanUpState.Error) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun Progress(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(label)
    }
}

@Composable
private fun ReadyContent(state: CleanUpState.Ready) {
    Text(
        pluralStringResource(
            R.plurals.cleanup_summary,
            state.toTrash.size,
            state.toTrash.size,
            formatFileSize(state.trashBytes),
        ),
    )
    Text(stringResource(R.string.cleanup_trash_note), style = MaterialTheme.typography.bodySmall)
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
        item { SectionHeader(stringResource(R.string.cleanup_to_trash)) }
        items(state.toTrash, key = { "t_" + it.id }) { ItemRow(it) }
        if (state.kept.isNotEmpty()) {
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SectionHeader(stringResource(R.string.cleanup_kept))
            }
            items(state.kept, key = { "k_" + it.id }) { ItemRow(it) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(vertical = 4.dp).semantics { heading() },
    )
}

@Composable
private fun ItemRow(file: DriveFile) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (file.isFolder) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = stringResource(if (file.isFolder) R.string.cd_folder else R.string.cd_file),
            modifier = Modifier.size(18.dp),
        )
        Text(file.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            if (file.sizeBytes > 0) formatFileSize(file.sizeBytes) else stringResource(R.string.size_unknown),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
