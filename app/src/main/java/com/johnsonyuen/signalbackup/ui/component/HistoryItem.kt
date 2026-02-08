package com.johnsonyuen.signalbackup.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import com.johnsonyuen.signalbackup.util.formatFileSize
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryItem(record: UploadRecord, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (record.status == UploadResultStatus.SUCCESS) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    Icons.Default.Error,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(record.fileName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    record.timestamp.atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (record.errorMessage != null) {
                    Text(
                        record.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                val sizeText = formatFileSize(record.fileSizeBytes)
                Text(sizeText, style = MaterialTheme.typography.labelMedium)
                Surface(
                    color = if (record.status == UploadResultStatus.SUCCESS)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        record.status.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

