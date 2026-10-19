package com.johnsonyuen.signalbackup.domain.upload

import com.johnsonyuen.signalbackup.domain.model.DriveHttpException
import java.io.IOException
import javax.inject.Inject
import kotlin.math.min
import kotlin.random.Random

/** Exponential backoff with jitter for per-chunk retries (1s, 2s, 4s, … capped at 60s). */
class UploadRetryPolicy internal constructor(private val random: Random) {

    @Inject constructor() : this(Random.Default)

    /** Delay before retry number [failedAttempts] (1-based). */
    fun backoffMillis(failedAttempts: Int): Long {
        val exponent = (failedAttempts - 1).coerceIn(0, 16)
        val base = min(MAX_DELAY_MS, INITIAL_DELAY_MS shl exponent)
        val jitter = (base * JITTER_FRACTION * random.nextDouble()).toLong()
        return min(MAX_DELAY_MS, base + jitter)
    }

    /** Network errors and retryable Drive HTTP errors are worth another try. */
    fun isRetryable(e: IOException): Boolean = e !is DriveHttpException || e.isRetryable

    companion object {
        const val MAX_ATTEMPTS = 6
        const val INITIAL_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 60_000L
        private const val JITTER_FRACTION = 0.25
    }
}
