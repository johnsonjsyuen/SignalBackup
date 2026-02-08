package com.johnsonyuen.signalbackup.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

@Composable
fun CountdownTimer(
    scheduleHour: Int,
    scheduleMinute: Int,
    modifier: Modifier = Modifier
) {
    var remainingText by remember { mutableStateOf("") }

    LaunchedEffect(scheduleHour, scheduleMinute) {
        while (true) {
            val now = LocalDateTime.now()
            var target = now.with(LocalTime.of(scheduleHour, scheduleMinute))
            if (target.isBefore(now) || target.isEqual(now)) {
                target = target.plusDays(1)
            }
            val duration = Duration.between(now, target)
            val hours = duration.toHours()
            val minutes = duration.toMinutes() % 60
            remainingText = "${hours}h ${minutes}m"
            delay(60_000)
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Schedule, contentDescription = null)
            Column {
                Text("Next upload", style = MaterialTheme.typography.labelMedium)
                Text(remainingText, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
