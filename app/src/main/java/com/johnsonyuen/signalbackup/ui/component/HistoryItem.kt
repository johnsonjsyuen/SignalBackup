/**
 * HistoryItem.kt - A card component that renders a single upload history record.
 *
 * This composable displays one [UploadRecord] as a Material3 Card with:
 * - A status icon (checkmark for success, error icon for failure)
 * - The file name and formatted timestamp
 * - An optional error message (shown only for failed uploads)
 * - The file size and a colored status badge
 *
 * Layout structure:
 * ```
 * [ Icon ]  [ File name              ]  [ Size text ]
 *           [ Timestamp              ]  [ STATUS    ]
 *           [ Error message (if any) ]
 * ```
 *
 * Key Compose concepts:
 * - **Modifier.weight(1f)**: The center Column takes all remaining horizontal space
 *   after the icon and size/badge column, ensuring the file name can truncate gracefully.
 * - **Surface with shape**: The status badge uses a Surface with `shapes.small` to create
 *   a rounded-rectangle pill around the status text, colored by success/failure.
 * - **DateTimeFormatter**: Converts the Instant timestamp to a human-readable format
 *   (e.g., "Jan 15, 2025 3:00 AM") using the device's local time zone.
 *
 * @see domain.model.UploadRecord for the domain model this card renders
 * @see ui.screen.history.HistoryScreen where this component is used in a LazyColumn
 */
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

/**
 * Renders a single upload history record as a styled card.
 *
 * @param record The [UploadRecord] to display.
 * @param modifier Optional Modifier for layout customization.
 */
@Composable
fun HistoryItem(record: UploadRecord, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status icon -- green checkmark for success, red error icon for failure.
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

            // Center column: file name, timestamp, and optional error message.
            // weight(1f) makes this column fill all remaining horizontal space.
            Column(modifier = Modifier.weight(1f)) {
                Text(record.fileName, style = MaterialTheme.typography.bodyLarge)

                // Convert the UTC Instant to the device's local time zone for display.
                Text(
                    record.timestamp.atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show the error message only for failed uploads.
                if (record.errorMessage != null) {
                    Text(
                        record.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Right column: file size and status badge.
            Column(horizontalAlignment = Alignment.End) {
                // Human-readable file size (e.g., "12.3 MB").
                val sizeText = formatFileSize(record.fileSizeBytes)
                Text(sizeText, style = MaterialTheme.typography.labelMedium)

                // Colored pill badge showing "SUCCESS" or "FAILED".
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
