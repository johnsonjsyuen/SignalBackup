package com.johnsonyuen.signalbackup.util

import android.content.Context
import android.text.format.DateFormat
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

private val SIZE_UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB")

/** The app's single size formatter: binary units (1 KiB = 1024 B), locale-aware digits. */
fun formatFileSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < 1024) {
        return "${NumberFormat.getIntegerInstance(locale).format(bytes.coerceAtLeast(0))} B"
    }
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < SIZE_UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    val format = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = if (value >= 100) 0 else 1
        minimumFractionDigits = 0
    }
    return "${format.format(value)} ${SIZE_UNITS[unit]}"
}

/** Transfer speed, e.g. "1.5 MiB/s". */
fun formatSpeed(bytesPerSecond: Long, locale: Locale = Locale.getDefault()): String =
    "${formatFileSize(bytesPerSecond, locale)}/s"

/** Time of day in the user's locale, honouring the system 12/24-hour setting. */
fun formatTimeOfDay(context: Context, hour: Int, minute: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return DateFormat.getTimeFormat(context).format(calendar.time)
}
