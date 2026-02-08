/**
 * FormatUtils.kt - Shared formatting utility functions.
 *
 * This file contains pure formatting functions used across the app for displaying
 * file sizes and schedule times in human-readable formats. These are extracted as
 * top-level functions (rather than being duplicated in UI components) to ensure
 * consistent formatting and to follow the DRY principle.
 *
 * Architecture context:
 * - Part of the **util** package -- shared utilities with no dependencies on Android
 *   framework classes, making them easy to unit test.
 * - Used by UI components: StatusCard, HistoryItem, SettingsScreen.
 */
package com.johnsonyuen.signalbackup.util

/**
 * Formats a byte count into a human-readable size string (KB, MB, or GB).
 *
 * Uses floating-point division for accurate display with one decimal place
 * for GB and MB, and no decimal places for KB.
 *
 * @param bytes The file size in bytes.
 * @return A formatted string like "1.5 GB", "256.3 MB", or "512 KB".
 */
fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.1f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}

/**
 * Formats a 24-hour time value into a 12-hour display string with AM/PM.
 *
 * Handles the edge cases of midnight (0 -> 12 AM) and noon (12 -> 12 PM).
 *
 * @param hour The hour in 24-hour format (0-23).
 * @param minute The minute (0-59).
 * @return A formatted string like "3:00 AM" or "11:30 PM".
 */
fun formatScheduleTime(hour: Int, minute: Int): String {
    val displayHour = when {
        hour == 0 -> 12          // Midnight: 0 -> 12 AM
        hour > 12 -> hour - 12   // Afternoon/evening: 13-23 -> 1-11 PM
        else -> hour             // Morning: 1-12 stays as-is
    }
    val amPm = if (hour < 12) "AM" else "PM"
    return "%d:%02d %s".format(displayHour, minute, amPm)
}
