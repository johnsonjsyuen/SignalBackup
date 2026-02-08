package com.johnsonyuen.signalbackup.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.johnsonyuen.signalbackup.domain.model.UploadStatus
import com.johnsonyuen.signalbackup.util.formatFileSize

@Composable
fun StatusCard(status: UploadStatus, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                is UploadStatus.Success -> MaterialTheme.colorScheme.primaryContainer
                is UploadStatus.Failed -> MaterialTheme.colorScheme.errorContainer
                is UploadStatus.Uploading -> MaterialTheme.colorScheme.secondaryContainer
                is UploadStatus.Idle -> MaterialTheme.colorScheme.surfaceVariant
                is UploadStatus.NeedsConsent -> MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (status) {
                is UploadStatus.Idle -> {
                    Icon(Icons.Default.HourglassEmpty, contentDescription = null)
                    Text("No upload in progress", style = MaterialTheme.typography.bodyLarge)
                }

                is UploadStatus.Uploading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Text("Uploading backup...", style = MaterialTheme.typography.bodyLarge)
                }

                is UploadStatus.Success -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text("Upload successful", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${status.fileName} (${formatFileSize(status.fileSizeBytes)})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                is UploadStatus.Failed -> {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Column {
                        Text("Upload failed", style = MaterialTheme.typography.bodyLarge)
                        Text(status.error, style = MaterialTheme.typography.bodySmall)
                    }
                }

                is UploadStatus.NeedsConsent -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Text("Requesting Drive permission...", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

