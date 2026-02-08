/**
 * CountdownTimer.kt - A card component that shows a live countdown to the next scheduled upload.
 *
 * This composable calculates the time remaining until the next scheduled backup and
 * updates the display every 60 seconds. It uses [LaunchedEffect] to run a coroutine
 * that re-calculates the countdown on each tick.
 *
 * How the countdown works:
 * 1. Given the user's scheduled hour and minute (e.g., 3:00 AM), compute
 *    a target LocalDateTime for today at that time.
 * 2. If the target has already passed today, move it to tomorrow.
 * 3. Calculate the Duration between now and the target.
 * 4. Format as "Xh Ym" and display.
 * 5. Wait 60 seconds, then repeat from step 1.
 *
 * Key Compose concepts:
 * - **LaunchedEffect(scheduleHour, scheduleMinute)**: Launches a coroutine that is
 *   cancelled and restarted whenever the schedule parameters change. This ensures
 *   the countdown resets when the user changes the schedule time in Settings.
 * - **mutableStateOf + remember**: Holds the formatted countdown string as Compose state.
 *   When the coroutine updates this value, the Text composable recomposes.
 * - **delay(60_000)**: Suspends the coroutine for 60 seconds between updates.
 *   This is efficient -- no thread is blocked during the wait.
 *
 * Note: This countdown is purely a UI timer. The actual scheduled upload is managed
 * by WorkManager (see [ScheduleUploadUseCase]), which runs independently of whether
 * the app is open.
 *
 * @see domain.usecase.ScheduleUploadUseCase for the actual WorkManager scheduling
 * @see ui.screen.home.HomeScreen where this countdown is displayed
 */
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

/**
 * Displays a card with a live countdown to the next scheduled upload.
 *
 * @param scheduleHour The scheduled upload hour (0-23).
 * @param scheduleMinute The scheduled upload minute (0-59).
 * @param modifier Optional Modifier for layout customization.
 */
@Composable
fun CountdownTimer(
    scheduleHour: Int,
    scheduleMinute: Int,
    modifier: Modifier = Modifier
) {
    // Compose state holding the formatted remaining time string (e.g., "14h 30m").
    var remainingText by remember { mutableStateOf("") }

    // LaunchedEffect keyed on the schedule parameters.
    // Restarts the countdown loop whenever the user changes the schedule time.
    LaunchedEffect(scheduleHour, scheduleMinute) {
        while (true) {
            val now = LocalDateTime.now()
            var target = now.with(LocalTime.of(scheduleHour, scheduleMinute))

            // If the target time has already passed today, the next occurrence is tomorrow.
            if (target.isBefore(now) || target.isEqual(now)) {
                target = target.plusDays(1)
            }

            // Calculate hours and minutes remaining.
            val duration = Duration.between(now, target)
            val hours = duration.toHours()
            val minutes = duration.toMinutes() % 60
            remainingText = "${hours}h ${minutes}m"

            // Suspend for 60 seconds before recalculating.
            // Using delay() (a coroutine suspend function) -- no thread is blocked.
            delay(60_000)
        }
    }

    // Render a simple card with a schedule icon and the countdown text.
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
