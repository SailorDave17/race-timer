package com.racetimer.phone

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wake-lock sizing arithmetic (#203 AC 2).
 *
 * Pure and small on purpose — this is the arithmetic whose inline watch copy carried #126, where a
 * lock sized once at Start expired silently after sync moved the gun later. The *re-compute on
 * sync* is the service's job and is asserted in `PhoneTimerServiceTest`; this file pins what any
 * single computation must say.
 */
class PhoneWakeLockTest {

    @Test
    fun `the timeout is the remaining race plus the margin`() {
        assertEquals(240_000L + PhoneWakeLock.MARGIN_MS, PhoneWakeLock.timeoutMs(240_000L))
        assertEquals(PhoneWakeLock.MARGIN_MS + 1L, PhoneWakeLock.timeoutMs(1L))
    }

    @Test
    fun `a negative remaining clamps to the bare margin rather than eating into it`() {
        // The engine reads negative once the gun is due; a margin shortened by however late the
        // caller sampled the clock would be #126 arrived at from the other side.
        assertEquals(PhoneWakeLock.MARGIN_MS, PhoneWakeLock.timeoutMs(-5_000L))
        assertEquals(PhoneWakeLock.MARGIN_MS, PhoneWakeLock.timeoutMs(0L))
    }

    @Test
    fun `the margin outlasts the longest gun cue and the teardown linger together`() {
        // The margin's job: the gun (3 s sustained) plus GUN_LINGER_MS must fit inside it, or the
        // lock can expire while the final cue is still sounding. Asserted against the service's
        // own constants so a future retune of either side has to face this line.
        val gunAndLinger = 3_000L + PhoneTimerService.GUN_LINGER_MS
        org.junit.Assert.assertTrue(PhoneWakeLock.MARGIN_MS >= gunAndLinger * 2)
    }
}
