package com.johnsonyuen.signalbackup.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatUtilsTest {
    @Test
    fun `formats sizes with binary units`() {
        assertEquals("512 B", formatFileSize(512, Locale.US))
        assertEquals("1 KiB", formatFileSize(1024, Locale.US))
        assertEquals("1.5 MiB", formatFileSize(1024L * 1024 * 3 / 2, Locale.US))
        assertEquals("2 GiB", formatFileSize(2L * 1024 * 1024 * 1024, Locale.US))
        assertEquals("150 MiB", formatFileSize(150L * 1024 * 1024 + 300_000, Locale.US))
    }

    @Test
    fun `uses locale decimal separator`() {
        assertEquals("1,5 MiB", formatFileSize(1024L * 1024 * 3 / 2, Locale.GERMANY))
    }

    @Test
    fun `formats speed`() {
        assertEquals("1 KiB/s", formatSpeed(1024, Locale.US))
    }
}
