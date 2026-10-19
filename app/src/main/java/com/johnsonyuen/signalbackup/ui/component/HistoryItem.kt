package com.johnsonyuen.signalbackup.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import com.johnsonyuen.signalbackup.ui.common.dateTimeText
import com.johnsonyuen.signalbackup.ui.common.messageRes
import com.johnsonyuen.signalbackup.util.formatFileSize

/** One upload history row: status, time, item, reason, bytes uploaded and size. */
@Composable
fun HistoryItem(record: UploadRecord, modifier: Modifier = Modifier) {
    val statusLabel = stringResource(
        when (record.status) {
            UploadResultStatus.SUCCESS -> R.string.status_success
            UploadResultStatus.FAILED -> R.string.status_failed
            UploadResultStatus.STALE -> R.string.status_stale
        },
    )
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (record.status) {
                UploadResultStatus.SUCCESS ->
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                UploadResultStatus.FAILED ->
                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                UploadResultStatus.STALE ->
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    record.fileName ?: stringResource(R.string.history_no_item),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    dateTimeText(record.timestamp.toEpochMilli()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (record.status) {
                    UploadResultStatus.FAILED -> {
                        record.failureReason?.let {
                            Text(stringResource(it.messageRes()), style = MaterialTheme.typography.bodySmall)
                        }
                        record.errorMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    UploadResultStatus.STALE ->
                        Text(stringResource(R.string.stale_explanation), style = MaterialTheme.typography.bodySmall)
                    UploadResultStatus.SUCCESS -> Unit
                }
                Text(
                    if (record.bytesUploaded > 0) {
                        stringResource(R.string.history_uploaded, formatFileSize(record.bytesUploaded))
                    } else {
                        stringResource(R.string.last_result_nothing_new)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (record.fileSizeBytes > 0) {
                    Text(
                        stringResource(R.string.history_size, formatFileSize(record.fileSizeBytes)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Surface(
                    color = if (record.status == UploadResultStatus.SUCCESS) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        statusLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
