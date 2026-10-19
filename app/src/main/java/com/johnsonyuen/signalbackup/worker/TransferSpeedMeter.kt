package com.johnsonyuen.signalbackup.worker

/**
 * Upload speed over a sliding window, counting only bytes sent during this run. Jumps that are
 * physically implausible (e.g. a resumed session reporting its already-confirmed offset) are ignored.
 */
class TransferSpeedMeter(
    private val windowMillis: Long = 10_000L,
    private val maxPlausibleBytesPerSec: Long = 100L * 1024 * 1024,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var countedBytes = 0L
    private var lastBytes = -1L
    private var lastTime = 0L

    /** Records a progress callback and returns the current speed in bytes/s (0 when unknown). */
    fun record(nowMillis: Long, bytesUploaded: Long): Long {
        if (lastBytes >= 0) {
            val delta = bytesUploaded - lastBytes
            val dt = nowMillis - lastTime
            val plausible = delta > 0 && dt > 0 && delta * 1000 / dt <= maxPlausibleBytesPerSec
            if (plausible) countedBytes += delta
        }
        lastBytes = bytesUploaded
        lastTime = nowMillis
        samples.addLast(nowMillis to countedBytes)
        // Keep one sample at or beyond the window edge so sparse callbacks still yield a span.
        while (samples.size > 2 && nowMillis - samples[1].first >= windowMillis) samples.removeFirst()
        val (startTime, startBytes) = samples.first()
        val span = nowMillis - startTime
        return if (span < MIN_SPAN_MILLIS) 0L else (countedBytes - startBytes) * 1000 / span
    }

    private companion object {
        const val MIN_SPAN_MILLIS = 500L
    }
}
