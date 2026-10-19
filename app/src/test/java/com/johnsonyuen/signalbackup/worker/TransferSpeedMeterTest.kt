package com.johnsonyuen.signalbackup.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferSpeedMeterTest {

    @Test
    fun resumedOffset_isNotCountedAsSpeed() {
        val meter = TransferSpeedMeter()
        // First callback of a resumed upload already reports 500 MB confirmed.
        assertEquals(0L, meter.record(0, 500_000_000))
        meter.record(1_000, 501_000_000)
        assertEquals(1_000_000L, meter.record(2_000, 502_000_000))
    }

    @Test
    fun implausibleJumpMidRun_isIgnored() {
        val meter = TransferSpeedMeter()
        meter.record(0, 0)
        meter.record(1_000, 1_000_000)
        // Next file resumes at a large offset almost instantly.
        meter.record(1_100, 400_000_000)
        // Only the 2 MB really sent over 2.1 s count.
        assertEquals(2_000_000L * 1_000 / 2_100, meter.record(2_100, 401_000_000))
    }

    @Test
    fun slidingWindow_dropsOldSamples() {
        val meter = TransferSpeedMeter(windowMillis = 10_000)
        meter.record(0, 0)
        meter.record(10_000, 100_000_000) // 10 MB/s for the first 10 s
        meter.record(20_000, 110_000_000) // then 1 MB/s
        assertEquals(1_000_000L, meter.record(30_000, 120_000_000))
    }
}
