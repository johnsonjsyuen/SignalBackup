package com.johnsonyuen.signalbackup.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.model.UploadProgress
import com.johnsonyuen.signalbackup.domain.scheduling.UploadRunState
import com.johnsonyuen.signalbackup.ui.common.durationText
import com.johnsonyuen.signalbackup.util.formatFileSize
import com.johnsonyuen.signalbackup.util.formatSpeed

/** Current run: queued, or uploading with progress, current file, speed and ETA. */
@Composable
fun UploadRunCard(run: UploadRunState, modifier: Modifier = Modifier) {
    when (run) {
        UploadRunState.Idle -> Unit
        is UploadRunState.Queued -> QueuedCard(manual = run.manual, modifier = modifier)
        is UploadRunState.Running -> RunningCard(progress = run.progress, modifier = modifier)
    }
}

@Composable
private fun QueuedCard(manual: Boolean, modifier: Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.HourglassTop, contentDescription = null)
            Column {
                Text(
                    stringResource(if (manual) R.string.run_queued_manual else R.string.run_queued_scheduled),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.run_queued_detail), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RunningCard(progress: UploadProgress?, modifier: Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (progress == null || progress.totalBytes <= 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.run_preparing), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                ProgressDetails(progress)
            }
        }
    }
}

@Composable
private fun ProgressDetails(progress: UploadProgress) {
    val animated by animateFloatAsState(targetValue = progress.fraction, label = "upload_progress")
    val percentText = stringResource(R.string.run_percent, progress.percentComplete)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.run_uploading), style = MaterialTheme.typography.titleMedium)
        Text(percentText, style = MaterialTheme.typography.titleMedium)
    }
    LinearProgressIndicator(
        progress = { animated },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = percentText },
    )
    progress.currentFileName?.let {
        Text(
            stringResource(R.string.run_current_file, it),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }
    Text(
        stringResource(
            R.string.run_bytes,
            formatFileSize(progress.bytesUploaded),
            formatFileSize(progress.totalBytes),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (progress.speedBytesPerSec > 0) {
            Text(formatSpeed(progress.speedBytesPerSec), style = MaterialTheme.typography.bodySmall)
        }
        if (progress.estimatedSecondsRemaining >= 0) {
            Text(
                stringResource(R.string.run_eta, durationText(progress.estimatedSecondsRemaining)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
