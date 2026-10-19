package com.johnsonyuen.signalbackup.domain.mirror

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId

/** Recognises Signal backup items by name and parses the timestamp Signal puts in them. */
object BackupNames {
    const val MANIFEST_FILE_NAME = "manifest.json"

    private val TIMESTAMP = Regex("""(\d{4})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})""")

    /** Legacy single-file backup: `signal-YYYY-MM-DD-HH-MM-SS.backup`. */
    fun isLegacyBackupFile(name: String): Boolean =
        name.startsWith("signal-") && name.endsWith(".backup")

    /** Signal 8.30+ snapshot folder: `signal-backup-YYYY-MM-DD-HH-MM-SS`. */
    fun isSnapshotFolder(name: String): Boolean = name.startsWith("signal-backup-")

    fun isBackupItem(name: String, isDirectory: Boolean): Boolean =
        if (isDirectory) isSnapshotFolder(name) else isLegacyBackupFile(name)

    /** Epoch millis of the timestamp in [name] (Signal uses device-local time), or null. */
    fun timestampMillis(name: String, zone: ZoneId): Long? {
        val m = TIMESTAMP.find(name) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        return try {
            LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
                .atZone(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeException) {
            null
        }
    }
}
