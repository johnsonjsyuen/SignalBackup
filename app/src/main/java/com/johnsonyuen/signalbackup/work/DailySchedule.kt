package com.johnsonyuen.signalbackup.work

import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime

/** DST-aware computation of the next daily run at a wall-clock time. */
object DailySchedule {

    /**
     * Next occurrence of [hour]:[minute] strictly after [now], in [now]'s zone. A time inside a
     * spring-forward gap resolves to the shifted time; an ambiguous fall-back time uses the earlier offset.
     */
    fun nextRun(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        val today = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
        if (today.isAfter(now)) return today
        return now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(now.zone)
    }

    /** Delay from the clock's current instant to the next run. */
    fun initialDelay(clock: Clock, hour: Int, minute: Int): Duration {
        val now = ZonedDateTime.now(clock)
        return Duration.between(now, nextRun(now, hour, minute))
    }
}
