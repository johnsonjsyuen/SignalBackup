package com.johnsonyuen.signalbackup.domain.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class UploadHelpersTest {

    @Test
    fun streamingMd5HandlesResentBytes() {
        val data = Random(7).nextBytes(1000)
        val md5 = StreamingMd5()
        md5.update(data.copyOfRange(0, 600), 600, 0)
        // Drive confirmed only 300; bytes 300..900 are sent again.
        md5.update(data.copyOfRange(300, 900), 600, 300)
        md5.update(data.copyOfRange(900, 1000), 100, 900)
        assertEquals(md5Hex(data), md5.hexIfComplete(1000))
    }

    @Test
    fun streamingMd5GapIsUnavailable() {
        val data = Random(7).nextBytes(100)
        val md5 = StreamingMd5()
        md5.update(data.copyOfRange(50, 100), 50, 50)
        assertNull(md5.hexIfComplete(100))
    }

    @Test
    fun backoffIsExponentialAndCapped() {
        val policy = UploadRetryPolicy(Random(3))
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L)
        expected.forEachIndexed { i, base ->
            val d = policy.backoffMillis(i + 1)
            assertTrue("attempt ${i + 1}: $d", d in base..(base + base / 4))
        }
        assertEquals(UploadRetryPolicy.MAX_DELAY_MS, policy.backoffMillis(10))
    }
}
