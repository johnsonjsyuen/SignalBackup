package com.johnsonyuen.signalbackup.domain.mirror

/** Decision 5: a backup older than 48 h means Signal has stopped writing new backups. */
object Staleness {
    const val THRESHOLD_MILLIS = 48 * 60 * 60 * 1000L

    fun isStale(newest: BackupItem, nowMillis: Long): Boolean =
        nowMillis - newest.sortTimeMillis > THRESHOLD_MILLIS
}
