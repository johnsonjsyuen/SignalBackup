/**
 * StatusCard.kt - A card component that visually represents the current upload status.
 *
 * This composable renders a Material3 Card that changes its appearance (background color,
 * icon, and text) based on the current [UploadStatus]. It acts as the primary visual
 * indicator on the Home screen, giving the user immediate feedback about what the app
 * is doing.
 *
 * Status-to-appearance mapping:
 * | Status       | Background Color     | Icon / Indicator        | Text                     |
 * |--------------|---------------------|-------------------------|--------------------------|
 * | Idle         | surfaceVariant       | HourglassEmpty          | "No upload in progress"  |
 * | Uploading    | secondaryContainer   | CircularProgressIndicator | "Uploading backup..."  |
 * | Success      | primaryContainer     | CheckCircle (primary)   | File name + size         |
 * | Failed       | errorContainer       | Error (error color)     | Error message            |
 * | NeedsConsent | secondaryContainer   | CircularProgressIndicator | "Requesting permission"|
 *
 * Compose concepts used:
 * - **when expression on sealed interface**: Exhaustive matching ensures every status
 *   variant is handled. If a new status is added, the compiler will flag it.
 * - **CardDefaults.cardColors()**: Overrides the card's container color per-status.
 * - **CircularProgressIndicator**: An indeterminate progress spinner used for active states.
 *
 * @see domain.model.UploadStatus for the sealed interface defining all possible states
 * @see ui.screen.home.HomeScreen where this card is displayed
 */
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.johnsonyuen.signalbackup.domain.model.UploadStatus
import com.johnsonyuen.signalbackup.util.formatFileSize

/**
 * Displays the current upload status as a styled card.
 *
 * @param status The current [UploadStatus] from the ViewModel.
 * @param modifier Optional Modifier for layout customization by the caller.
 */
@Composable
fun StatusCard(status: UploadStatus, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        // Dynamic card background color based on status -- success is green-ish,
        // error is red-ish, active states use secondary, idle uses neutral surface.
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                is UploadStatus.Success -> MaterialTheme.colorScheme.primaryContainer
                is UploadStatus.Failed -> MaterialTheme.colorScheme.errorContainer
                is UploadStatus.Uploading -> MaterialTheme.colorScheme.secondaryContainer
                is UploadStatus.Idle -> MaterialTheme.colorScheme.surfaceVariant
                is UploadStatus.NeedsConsent -> MaterialTheme.colorScheme.secondaryContainer
                is UploadStatus.RetryScheduled -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Render different icon + text combinations for each status variant.
            when (status) {
                is UploadStatus.Idle -> {
                    Icon(Icons.Default.HourglassEmpty, contentDescription = null)
                    Text("No upload in progress", style = MaterialTheme.typography.bodyLarge)
                }

                is UploadStatus.Uploading -> {
                    // Indeterminate spinner -- upload is in progress.
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
                        // Show the uploaded file name and human-readable size (e.g., "12.3 MB").
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
                        // Display the error message from the failed upload attempt.
                        Text(status.error, style = MaterialTheme.typography.bodySmall)
                    }
                }

                is UploadStatus.NeedsConsent -> {
                    // The user needs to grant Drive permission -- show a spinner
                    // while the consent UI is launching.
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Text("Requesting Drive permission...", style = MaterialTheme.typography.bodyLarge)
                }

                is UploadStatus.RetryScheduled -> {
                    var remainingMs by remember { mutableLongStateOf(status.retryAtMillis - System.currentTimeMillis()) }

                    LaunchedEffect(status.retryAtMillis) {
                        while (true) {
                            remainingMs = (status.retryAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
                            delay(1000)
                        }
                    }

                    val minutes = (remainingMs / 60000).toInt()
                    val seconds = ((remainingMs % 60000) / 1000).toInt()

                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Column {
                        Text(
                            "Retry in ${minutes}m ${seconds}s",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(status.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
