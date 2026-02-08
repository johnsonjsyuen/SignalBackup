/**
 * UploadProgressCard.kt - A card component that displays detailed upload progress statistics.
 *
 * This composable renders a Material3 Card showing real-time upload metrics during an
 * active file upload. It displays:
 * - A determinate linear progress bar showing completion percentage
 * - Bytes uploaded vs. total bytes (e.g., "45.2 MB / 128.0 MB")
 * - Upload speed (e.g., "2.3 MB/s")
 * - Estimated time remaining (e.g., "~36 sec remaining" or "~2 min remaining")
 *
 * The card is designed to visually integrate with the existing [StatusCard] on the Home
 * screen. It uses the same secondaryContainer color scheme as the "Uploading" status to
 * maintain visual consistency.
 *
 * This component is only displayed when an upload is actively running AND progress data
 * is available from the worker. Before the first progress callback (while the upload is
 * initializing), the regular StatusCard with an indeterminate spinner is shown instead.
 *
 * @see domain.model.UploadProgress for the data model
 * @see ui.component.StatusCard for the companion status display
 * @see ui.screen.home.HomeScreen for where this card is placed
 */
package com.johnsonyuen.signalbackup.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.johnsonyuen.signalbackup.domain.model.UploadProgress
import com.johnsonyuen.signalbackup.util.formatFileSize

/**
 * Displays detailed upload progress as a styled card with a progress bar and statistics.
 *
 * @param progress The current [UploadProgress] snapshot from the ViewModel.
 * @param modifier Optional Modifier for layout customization by the caller.
 */
@Composable
fun UploadProgressCard(progress: UploadProgress, modifier: Modifier = Modifier) {
    // Animate the progress bar fraction for smooth visual transitions between chunks.
    val animatedFraction by animateFloatAsState(
        targetValue = progress.fraction,
        label = "upload_progress",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title row with percentage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Uploading backup...",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "${progress.percentComplete}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Determinate progress bar showing upload completion.
            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            // Bytes transferred: "45.2 MB / 128.0 MB"
            Text(
                text = "${formatFileSize(progress.bytesUploaded)} / ${formatFileSize(progress.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium
            )

            // Speed and ETA row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Upload speed: "2.3 MB/s"
                if (progress.speedBytesPerSec > 0) {
                    Text(
                        text = "${formatFileSize(progress.speedBytesPerSec)}/s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Estimated time remaining: "~36 sec remaining" or "~2 min remaining"
                if (progress.estimatedSecondsRemaining > 0) {
                    Text(
                        text = formatTimeRemaining(progress.estimatedSecondsRemaining),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Formats a duration in seconds into a human-readable "time remaining" string.
 *
 * Uses the most appropriate unit:
 * - Under 60 seconds: "~X sec remaining"
 * - 60 seconds to 59 minutes: "~X min remaining"
 * - 60 minutes and above: "~X hr Y min remaining"
 *
 * @param seconds The estimated seconds remaining.
 * @return A formatted string like "~36 sec remaining" or "~2 min remaining".
 */
private fun formatTimeRemaining(seconds: Long): String {
    return when {
        seconds < 60 -> "~${seconds} sec remaining"
        seconds < 3600 -> {
            val minutes = seconds / 60
            "~${minutes} min remaining"
        }
        else -> {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            if (minutes > 0) "~${hours} hr ${minutes} min remaining"
            else "~${hours} hr remaining"
        }
    }
}
