package com.racetimer.shared

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [snapToMinute].
 *
 * The rule under test (#150): a sync tap comes from an officer watching for the committee's signal,
 * so it lands on the signal or a fraction late — never early. An up-correction of
 * [LATE_TAP_WINDOW_MS] or less is that late tap and rounds up; anything else means the watch is
 * carrying time the sequence has already spent, and floors.
 */
class SyncManagerTest {

    // --- The late-tap window (rounds up) --------------------------------------

    @Test fun `exactly on minute boundary stays unchanged`() {
        assertEquals(4 * 60_000L, snapToMinute(4 * 60_000L))
    }

    @Test fun `3m52s rounds up to 4m00s`() {
        // An 8 s up-correction: in step, thumb a hair late.
        val remaining = 3 * 60_000L + 52_000L  // 3:52 remaining = 232_000 ms
        assertEquals(4 * 60_000L, snapToMinute(remaining))
    }

    @Test fun `1ms below a minute rounds up`() {
        val remaining = 3 * 60_000L - 1L  // 2:59.999
        assertEquals(3 * 60_000L, snapToMinute(remaining))
    }

    @Test fun `very large value works correctly`() {
        // 59:59 is a 1 s up-correction → 60:00
        val remaining = 59 * 60_000L + 59_000L
        assertEquals(60 * 60_000L, snapToMinute(remaining))
    }

    // --- The boundary ---------------------------------------------------------
    //
    // The one number in the rule, and it does not follow from the arithmetic: an up-correction of
    // exactly LATE_TAP_WINDOW_MS resolves UP. Both sides are asserted because a `<` / `<=` slip is
    // invisible from either one alone.

    @Test fun `an up-correction of exactly the late-tap window rounds up`() {
        val remaining = 4 * 60_000L + 50_000L  // 4:50 — exactly 10 s below 5:00
        assertEquals(10_000L, 5 * 60_000L - remaining)  // the correction really is the boundary
        assertEquals(5 * 60_000L, snapToMinute(remaining))
    }

    @Test fun `one millisecond past the late-tap window floors`() {
        val remaining = 4 * 60_000L + 49_999L  // 4:49.999 — a 10.001 s up-correction
        assertEquals(10_001L, 5 * 60_000L - remaining)
        assertEquals(4 * 60_000L, snapToMinute(remaining))
    }

    // --- Beyond the window (floors) -------------------------------------------

    @Test fun `4m49s floors to 4m00s`() {
        // The case that separates this rule from round-to-nearest: nearest would have added 11 s on
        // the assumption of an early tap. A 49 s deletion is the whole point — the watch is behind.
        val remaining = 4 * 60_000L + 49_000L
        assertEquals(4 * 60_000L, snapToMinute(remaining))
    }

    @Test fun `4m32s floors to 4m00s`() {
        // 28 s from 5:00, so round-to-nearest went up here. Past the late-tap window, so it floors.
        val remaining = 4 * 60_000L + 32_000L  // 272_000 ms
        assertEquals(4 * 60_000L, snapToMinute(remaining))
    }

    @Test fun `4m05s floors to 4m00s`() {
        val remaining = 4 * 60_000L + 5_000L  // 245_000 ms
        assertEquals(4 * 60_000L, snapToMinute(remaining))
    }

    @Test fun `0m40s floors to zero`() {
        assertEquals(0L, snapToMinute(40_000L))
    }

    @Test fun `0m31s floors to zero`() {
        assertEquals(0L, snapToMinute(31_000L))
    }

    @Test fun `0m30s floors to zero`() {
        // Was the round-to-nearest tie-break case. Under the late-tap rule 30 s is simply past the
        // window, and there is no tie to break.
        assertEquals(0L, snapToMinute(30_000L))
    }

    @Test fun `0m29s floors to zero`() {
        assertEquals(0L, snapToMinute(29_000L))
    }

    // --- Edge cases -----------------------------------------------------------

    @Test fun `zero or negative returns zero`() {
        assertEquals(0L, snapToMinute(0L))
        assertEquals(0L, snapToMinute(-1_000L))
    }
}
