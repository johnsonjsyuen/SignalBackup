package com.johnsonyuen.signalbackup.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import com.johnsonyuen.signalbackup.ui.common.dateTimeText
import com.johnsonyuen.signalbackup.ui.common.durationText
import com.johnsonyuen.signalbackup.ui.common.messageRes
import com.johnsonyuen.signalbackup.ui.common.relativeAgeText
import com.johnsonyuen.signalbackup.ui.common.upcomingTimeText
import com.johnsonyuen.signalbackup.ui.screen.home.LastResultUi
import com.johnsonyuen.signalbackup.ui.screen.home.ResultFix
import com.johnsonyuen.signalbackup.ui.screen.home.SetupChecklist
import com.johnsonyuen.signalbackup.util.formatFileSize

/** Outcome of the most recent run with a plain-language reason and a fix button. */
@Composable
fun LastResultCard(result: LastResultUi, onFix: (ResultFix) -> Unit, modifier: Modifier = Modifier) {
    val failed = result.status == UploadResultStatus.FAILED
    val stale = result.status == UploadResultStatus.STALE
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (failed) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                failed -> Icon(Icons.Default.Error, contentDescription = stringResource(R.string.status_failed))
                stale -> Icon(
                    Icons.Default.Warning,
                    contentDescription = stringResource(R.string.status_stale),
                    tint = MaterialTheme.colorScheme.error,
                )
                else -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.status_success),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        when {
                            failed -> R.string.last_result_failed
                            stale -> R.string.last_result_stale
                            else -> R.string.last_result_success
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(dateTimeText(result.timestampMillis), style = MaterialTheme.typography.bodySmall)
                when {
                    failed -> {
                        result.reason?.let {
                            Text(stringResource(it.messageRes()), style = MaterialTheme.typography.bodyMedium)
                        }
                        result.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    stale -> Text(stringResource(R.string.stale_explanation), style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        result.backupName?.let {
                            Text(stringResource(R.string.last_result_backup_name, it), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            if (result.bytesUploaded > 0) {
                                stringResource(R.string.last_result_uploaded, formatFileSize(result.bytesUploaded))
                            } else {
                                stringResource(R.string.last_result_nothing_new)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                result.fix?.let { fix ->
                    FilledTonalButton(onClick = { onFix(fix) }) { Text(stringResource(fix.labelRes())) }
                }
            }
        }
    }
}

private fun ResultFix.labelRes(): Int = when (this) {
    ResultFix.SIGN_IN -> R.string.fix_sign_in
    ResultFix.GRANT_ACCESS -> R.string.fix_grant_access
    ResultFix.FIX_DRIVE_FOLDER -> R.string.fix_drive_folder
    ResultFix.CHOOSE_FOLDER -> R.string.fix_choose_folder
    ResultFix.RETRY -> R.string.fix_retry
    ResultFix.CLEAN_UP -> R.string.fix_clean_up
}

/** "Last successful backup: N days ago", in the error colour when overdue. */
@Composable
fun LastSuccessText(ageMillis: Long?, overdue: Boolean, modifier: Modifier = Modifier) {
    val text = if (ageMillis == null) {
        stringResource(R.string.last_success_never)
    } else {
        stringResource(R.string.last_success, relativeAgeText(ageMillis))
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (overdue) {
            Icon(
                Icons.Default.Warning,
                contentDescription = stringResource(R.string.cd_backup_overdue),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Next scheduled run with a minute-resolution countdown. */
@Composable
fun NextRunCard(nextRunMillis: Long?, nowMillis: Long, scheduleEnabled: Boolean, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Schedule, contentDescription = null)
            Column {
                Text(stringResource(R.string.next_backup), style = MaterialTheme.typography.labelMedium)
                when {
                    !scheduleEnabled -> Text(stringResource(R.string.next_backup_off), style = MaterialTheme.typography.titleMedium)
                    nextRunMillis == null -> Text(stringResource(R.string.next_backup_not_scheduled), style = MaterialTheme.typography.titleMedium)
                    else -> {
                        val remainingSec = ((nextRunMillis - nowMillis) / 1000L).coerceAtLeast(0L)
                        // Round up to whole minutes since the countdown ticks once a minute.
                        val roundedSec = ((remainingSec + 59) / 60) * 60
                        Text(upcomingTimeText(nextRunMillis, nowMillis), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (remainingSec < 60) stringResource(R.string.next_backup_due)
                            else stringResource(R.string.next_backup_in, durationText(roundedSec)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/** Steps still needed before backups can run. */
@Composable
fun SetupChecklistCard(
    setup: SetupChecklist,
    authorizing: Boolean,
    onSignIn: () -> Unit,
    onGrantAccess: () -> Unit,
    onChooseFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.setup_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            ChecklistRow(
                done = setup.signedIn,
                title = stringResource(R.string.setup_sign_in),
                actionLabel = stringResource(R.string.action_sign_in),
                onAction = onSignIn,
            )
            ChecklistRow(
                done = setup.driveAuthorized,
                title = stringResource(R.string.setup_drive_access),
                actionLabel = stringResource(R.string.action_grant_access),
                onAction = onGrantAccess,
                actionEnabled = setup.signedIn && !authorizing,
            )
            val folderDone = setup.localFolderSet && setup.localFolderAccessible
            ChecklistRow(
                done = folderDone,
                title = stringResource(R.string.setup_local_folder),
                detail = when {
                    !setup.localFolderSet -> null
                    !setup.localFolderAccessible -> stringResource(R.string.local_folder_inaccessible)
                    else -> setup.localFolderName
                },
                actionLabel = stringResource(if (setup.localFolderSet) R.string.action_change else R.string.action_choose),
                onAction = onChooseFolder,
                showActionWhenDone = false,
            )
            if (folderDone && setup.localFolderHasBackups == false) {
                NoBackupsWarning()
            }
        }
    }
}

/** Warning shown when the chosen folder holds no Signal backups. */
@Composable
fun NoBackupsWarning(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = stringResource(R.string.cd_warning),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(R.string.local_folder_no_backups),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ChecklistRow(
    done: Boolean,
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    detail: String? = null,
    actionEnabled: Boolean = true,
    showActionWhenDone: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = stringResource(if (done) R.string.cd_step_done else R.string.cd_step_todo),
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (!done || showActionWhenDone) {
            TextButton(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
        }
    }
}
