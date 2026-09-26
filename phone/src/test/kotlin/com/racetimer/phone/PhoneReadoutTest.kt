package com.racetimer.phone

import com.racetimer.phone.ui.PhoneReadout
import com.racetimer.phone.ui.displayedElapsedMs
import com.racetimer.phone.ui.displayedRemainingMs
import com.racetimer.shared.BG_FINAL_TEN_ARGB
import com.racetimer.shared.BG_FINISHED_ARGB
import com.racetimer.shared.BG_NORMAL_ARGB
import com.racetimer.shared.BG_ONE_MINUTE_ARGB
import com.racetimer.shared.TimerState
import com.racetimer.shared.backgroundArgbFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The readout mapper on its own — what the screen says, and what colour it says it on.
 *
 * The colour assertions name the shared constants rather than hex values on purpose: the point of
 * #197 AC 3 is that this module holds no colour of its own, and a test carrying its own copy of
 * `0xFF7B0000` would be the very duplication the criterion forbids, dressed as a check.
 */
class PhoneReadoutTest {

    @Test
    fun `idle shows the full countdown on the normal background`() {
        val readout = PhoneReadout.of(TimerState.IDLE, remainingMs = 300_000L, elapsedMs = 0L)
        assertEquals("5:00", readout.text)
        assertEquals(BG_NORMAL_ARGB, readout.backgroundArgb)
    }

    @Test
    fun `the background follows the shared rule at every threshold`() {
        // Driven through backgroundArgbFor rather than restating its branches — the same discipline
        // MessageContrastTest applies on the watch, and what keeps this honest if a threshold moves.
        val cases = listOf(
            61_000L to BG_NORMAL_ARGB,
            60_000L to BG_ONE_MINUTE_ARGB,
            11_000L to BG_ONE_MINUTE_ARGB,
            10_000L to BG_FINAL_TEN_ARGB,
            1_000L to BG_FINAL_TEN_ARGB,
        )
        for ((remaining, expected) in cases) {
            val readout = PhoneReadout.of(TimerState.RUNNING, remaining, elapsedMs = 0L)
            assertEquals("at $remaining ms", expected, readout.backgroundArgb)
            assertEquals(
                "at $remaining ms",
                backgroundArgbFor(remaining, TimerState.RUNNING),
                readout.backgroundArgb,
            )
        }
    }

    @Test
    fun `the gun replaces the clock with GO`() {
        val readout = PhoneReadout.of(TimerState.FINISHED, remainingMs = 0L, elapsedMs = 0L)
        assertEquals(PhoneReadout.GUN_LABEL, readout.text)
        assertEquals(BG_FINISHED_ARGB, readout.backgroundArgb)
    }

    @Test
    fun `the race-manager states read as elapsed time, not as a countdown`() {
        // Written before any sequence this module offered could reach these states, on the
        // argument that the engine can produce them and a readout answering by falling through
        // would draw a countdown over a running race. #206 made them reachable — the race-manager
        // pair is on the picker — and this test needed no edit, which is the argument paying out.
        val counting = PhoneReadout.of(TimerState.COUNTING_UP, remainingMs = -95_000L, elapsedMs = 95_000L)
        assertEquals("1:35", counting.text)

        val ended = PhoneReadout.of(TimerState.RACE_ENDED, remainingMs = -3_725_000L, elapsedMs = 3_725_000L)
        assertEquals("1:02:05", ended.text)
        assertEquals(BG_FINISHED_ARGB, ended.backgroundArgb)
    }

    @Test
    fun `a countdown rounds up to the second it still has and elapsed rounds down`() {
        assertEquals(60_000L, displayedRemainingMs(59_980L))
        assertEquals(1_000L, displayedRemainingMs(1L))
        assertEquals(0L, displayedRemainingMs(0L))
        assertEquals(0L, displayedRemainingMs(-500L))

        assertEquals(59_000L, displayedElapsedMs(59_980L))
        assertEquals(0L, displayedElapsedMs(999L))
        assertEquals(0L, displayedElapsedMs(-500L))
    }
}
