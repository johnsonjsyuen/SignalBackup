package com.johnsonyuen.signalbackup.ui.common

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.model.FailureReason
import com.johnsonyuen.signalbackup.domain.model.ThemeMode
import com.johnsonyuen.signalbackup.util.formatTimeOfDay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Plain-language explanation of a failure reason. */
@StringRes
fun FailureReason.messageRes(): Int = when (this) {
    FailureReason.NOT_SIGNED_IN -> R.string.reason_not_signed_in
    FailureReason.LOCAL_FOLDER_NOT_SET -> R.string.reason_local_folder_not_set
    FailureReason.LOCAL_FOLDER_INACCESSIBLE -> R.string.reason_local_folder_inaccessible
    FailureReason.NO_BACKUP_FOUND -> R.string.reason_no_backup_found
    FailureReason.NEEDS_CONSENT -> R.string.reason_needs_consent
    FailureReason.DRIVE_FOLDER_INACCESSIBLE -> R.string.reason_drive_folder_inaccessible
    FailureReason.DRIVE_QUOTA_EXCEEDED -> R.string.reason_drive_quota_exceeded
    FailureReason.NETWORK -> R.string.reason_network
    FailureReason.DRIVE_SERVER_ERROR -> R.string.reason_drive_server_error
    FailureReason.CHECKSUM_MISMATCH -> R.string.reason_checksum_mismatch
    FailureReason.LOCAL_READ_ERROR -> R.string.reason_local_read_error
    FailureReason.UNKNOWN -> R.string.reason_unknown
}

@StringRes
fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

private fun Context.currentLocale(): Locale =
    resources.configuration.locales[0] ?: Locale.getDefault()

/** "5 minutes ago" / "3 hours ago" / "2 days ago". */
@Composable
fun relativeAgeText(ageMillis: Long): String {
    val minutes = ageMillis / 60_000L
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> stringResource(R.string.time_just_now)
        hours < 1 -> pluralStringResource(R.plurals.minutes_ago, minutes.toInt(), minutes.toInt())
        days < 1 -> pluralStringResource(R.plurals.hours_ago, hours.toInt(), hours.toInt())
        else -> pluralStringResource(R.plurals.days_ago, days.toInt(), days.toInt())
    }
}

/** A short duration such as "2 h 5 min", "12 min" or "40 s". */
@Composable
fun durationText(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    return when {
        hours > 0 -> stringResource(R.string.duration_hours_minutes, hours, minutes)
        minutes > 0 -> stringResource(R.string.duration_minutes, minutes)
        else -> stringResource(R.string.duration_seconds, s)
    }
}

/** Date and time in the user's locale and time zone, honouring the 12/24-hour setting. */
@Composable
fun dateTimeText(epochMillis: Long): String {
    val context = LocalContext.current
    val locale = context.currentLocale()
    val zoned = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val date = zoned.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    val time = formatTimeOfDay(context, zoned.hour, zoned.minute)
    return stringResource(R.string.date_at_time, date, time)
}

/** "Today at 3:00 AM", "Tomorrow at 03:00" or "Wed at 3:00 AM" for an upcoming time. */
@Composable
fun upcomingTimeText(epochMillis: Long, nowMillis: Long): String {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val target: ZonedDateTime = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val time = formatTimeOfDay(context, target.hour, target.minute)
    return when (ChronoUnit.DAYS.between(today, target.toLocalDate())) {
        0L -> stringResource(R.string.today_at, time)
        1L -> stringResource(R.string.tomorrow_at, time)
        else -> {
            val locale = context.currentLocale()
            val date = try {
                target.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale))
            } catch (e: IllegalArgumentException) {
                target.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
            }
            stringResource(R.string.date_at_time, date, time)
        }
    }
}
