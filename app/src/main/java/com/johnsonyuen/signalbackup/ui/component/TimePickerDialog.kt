/**
 * TimePickerDialog.kt - A Material3 time picker wrapped in an AlertDialog.
 *
 * This composable shows a dialog with a full Material3 TimePicker (the clock-face picker)
 * for the user to choose their preferred daily backup time. It is used on the Settings
 * screen when the user taps "Change" on the upload schedule.
 *
 * Key Compose/Material3 concepts:
 * - **@OptIn(ExperimentalMaterial3Api::class)**: The Material3 TimePicker and
 *   TimePickerState are still marked as experimental. This opt-in is required.
 * - **rememberTimePickerState()**: Creates and remembers the TimePicker's state
 *   (selected hour and minute). Initialized with the current schedule values.
 * - **AlertDialog**: A standard Material3 dialog with title, content, and action buttons.
 *   The TimePicker is placed in the `text` slot, which works but may require
 *   scrolling on smaller screens.
 * - **is24Hour = false**: Shows a 12-hour clock with AM/PM toggle. The returned
 *   hour value is still in 24-hour format (0-23) regardless of this setting.
 *
 * Data flow:
 * 1. SettingsScreen opens this dialog with the current scheduleHour and scheduleMinute.
 * 2. User adjusts the time on the clock face.
 * 3. User taps "OK" -> onConfirm(hour, minute) is called -> SettingsViewModel updates
 *    the schedule in DataStore and reschedules the WorkManager periodic work.
 * 4. User taps "Cancel" -> onDismiss() is called -> dialog closes, no changes saved.
 *
 * @see ui.screen.settings.SettingsScreen where this dialog is shown
 * @see ui.screen.settings.SettingsViewModel.setScheduleTime for what happens on confirm
 */
package com.johnsonyuen.signalbackup.ui.component

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/**
 * Shows a time picker dialog for selecting the daily upload schedule.
 *
 * @param initialHour The initially selected hour (0-23).
 * @param initialMinute The initially selected minute (0-59).
 * @param onConfirm Callback with the selected (hour, minute) when the user taps OK.
 * @param onDismiss Callback when the user cancels or dismisses the dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Remember the time picker state across recompositions.
    // The state holds the currently selected hour and minute on the clock face.
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false  // 12-hour format with AM/PM; returned values are still 0-23.
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload Schedule") },
        text = {
            // The Material3 TimePicker renders a clock face (or input mode) in the
            // dialog's text/content area. It reads from and writes to timePickerState.
            TimePicker(state = timePickerState)
        },
        confirmButton = {
            // Read the selected hour and minute from the state and pass them upstream.
            TextButton(onClick = { onConfirm(timePickerState.hour, timePickerState.minute) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
