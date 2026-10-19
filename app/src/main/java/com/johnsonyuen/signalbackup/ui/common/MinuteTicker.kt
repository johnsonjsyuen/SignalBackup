package com.johnsonyuen.signalbackup.ui.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import javax.inject.Inject

/** Emits the current time now and then at the start of every wall-clock minute. */
class MinuteTicker @Inject constructor(private val clock: Clock) {
    fun ticks(): Flow<Long> = flow {
        while (true) {
            val now = clock.millis()
            emit(now)
            delay(MINUTE_MS - now % MINUTE_MS)
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
