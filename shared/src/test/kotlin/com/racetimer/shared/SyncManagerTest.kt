package com.racetimer.shared

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [snapToMinute].
 */
class SyncManagerTest {

    // --- Nearest-minute rounding (default) ------------------------------------

    @Test fun `exactly on minute boundary stays unchanged`() {
        assertEquals(4 * 60_000L, snapToMinute(4 * 60_000L))
    }

    @Test fun `4m05s rounds down to 4m00s`() {
        // 4:05 remaining is only 5 seconds from 4:00 — rounds to the lower boundary
        val remaining = 4 * 60_000L + 5_000L  // 4:05 remaining = 245_000 ms
        assertEquals(4 * 60_000L, snapToMinute(remaining))
    }

    @Test fun `3m52s rounds up to 4m00s`() {
        val remaining = 3 * 60_000L + 52_000L  // 3:52 remaining = 232_000 ms
        assertEquals(4 * 60_000L, snapToMinute(remaining))
    }

    @Test fun `4m32s rounds up to 5m00s`() {
        // 4:32 remaining is 32 sec from 4:00 but only 28 sec from 5:00 — rounds up
        val remaining = 4 * 60_000L + 32_000L  // 4:32 remaining = 272_000 ms
        assertEquals(5 * 60_000L, snapToMinute(remaining))
    }

    @Test fun `0m40s rounds up to 1m00s`() {
        val remaining = 40_000L
        assertEquals(60_000L, snapToMinute(remaining))
    }

    @Test fun `0m29s rounds down to 0`() {
        val remaining = 29_000L
        assertEquals(0L, snapToMinute(remaining))
    }

    @Test fun `exactly 30s rounds up (tie-break to upper)`() {
        // 30 seconds is exactly midway between 0 and 1 minute;
        // remaining - lower = 30_000, upper - remaining = 30_000 → condition: <= → lower wins
        assertEquals(0L, snapToMinute(30_000L))
    }

    @Test fun `31s rounds up to 1m`() {
        assertEquals(60_000L, snapToMinute(31_000L))
    }

    @Test fun `zero or negative returns zero`() {
        assertEquals(0L, snapToMinute(0L))
        assertEquals(0L, snapToMinute(-1_000L))
    }

    // --- Round-down mode ------------------------------------------------------

    @Test fun `round-down - 4m05s rounds down to 4m00s`() {
        assertEquals(4 * 60_000L, snapToMinute(4 * 60_000L + 5_000L, roundDown = true))
    }

    @Test fun `round-down - 3m52s rounds down to 3m00s`() {
        assertEquals(3 * 60_000L, snapToMinute(3 * 60_000L + 52_000L, roundDown = true))
    }

    @Test fun `round-down - exactly on minute stays unchanged`() {
        assertEquals(3 * 60_000L, snapToMinute(3 * 60_000L, roundDown = true))
    }

    @Test fun `round-down - 0m40s rounds down to 0`() {
        assertEquals(0L, snapToMinute(40_000L, roundDown = true))
    }

    // --- Edge cases -----------------------------------------------------------

    @Test fun `very large value works correctly`() {
        // 59m59s → nearest = 60m = 3_600_000
        val remaining = 59 * 60_000L + 59_000L
        assertEquals(60 * 60_000L, snapToMinute(remaining))
    }

    @Test fun `1ms before a minute rounds down`() {
        val remaining = 3 * 60_000L - 1L  // 2:59.999
        assertEquals(3 * 60_000L, snapToMinute(remaining))
    }
}
