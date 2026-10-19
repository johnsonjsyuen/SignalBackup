package com.johnsonyuen.signalbackup.work

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DailyScheduleTest {

    private val newYork = ZoneId.of("America/New_York")

    private fun clockAt(local: String, offset: String): Clock {
        val instant = LocalDateTime.parse(local).atOffset(ZoneOffset.of(offset)).toInstant()
        return Clock.fixed(instant, newYork)
    }

    @Test
    fun beforeTodaysTime_runsToday() {
        val clock = clockAt("2026-01-15T01:00", "-05:00")
        assertEquals(Duration.ofHours(2), DailySchedule.initialDelay(clock, 3, 0))
    }

    @Test
    fun afterTodaysTime_runsTomorrow() {
        val clock = clockAt("2026-01-15T04:00", "-05:00")
        assertEquals(Duration.ofHours(23), DailySchedule.initialDelay(clock, 3, 0))
    }

    @Test
    fun exactlyAtTime_runsTomorrow() {
        val clock = clockAt("2026-01-15T03:00", "-05:00")
        assertEquals(Duration.ofHours(24), DailySchedule.initialDelay(clock, 3, 0))
    }

    @Test
    fun springForward_sameDay_usesRealElapsedTime() {
        // 2026-03-08: clocks jump from 02:00 EST to 03:00 EDT.
        val clock = clockAt("2026-03-08T00:30", "-05:00")
        assertEquals(Duration.ofMinutes(90), DailySchedule.initialDelay(clock, 3, 0))
    }

    @Test
    fun springForward_timeInGap_shiftsForward() {
        val clock = clockAt("2026-03-08T00:30", "-05:00")
        val next = DailySchedule.nextRun(ZonedDateTime.now(clock), 2, 30)
        assertEquals(LocalDateTime.parse("2026-03-08T03:30"), next.toLocalDateTime())
        assertEquals(Duration.ofHours(2), DailySchedule.initialDelay(clock, 2, 30))
    }

    @Test
    fun springForward_dayBefore_is23Hours() {
        val clock = clockAt("2026-03-07T03:00", "-05:00")
        assertEquals(Duration.ofHours(23), DailySchedule.initialDelay(clock, 3, 0))
    }

    @Test
    fun fallBack_dayBefore_is25Hours() {
        // 2026-11-01: clocks fall back from 02:00 EDT to 01:00 EST.
        val clock = clockAt("2026-10-31T03:00", "-04:00")
        assertEquals(Duration.ofHours(25), DailySchedule.initialDelay(clock, 3, 0))
    }

    @Test
    fun fallBack_ambiguousTime_usesEarlierOffset() {
        val clock = clockAt("2026-11-01T00:00", "-04:00")
        val next = DailySchedule.nextRun(ZonedDateTime.now(clock), 1, 30)
        assertEquals(ZoneOffset.of("-04:00"), next.offset)
        assertEquals(Duration.ofMinutes(90), DailySchedule.initialDelay(clock, 1, 30))
    }
}
