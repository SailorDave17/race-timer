package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for when the time of day shows at the top rim (#303): in every state, giving way to any
 * Tier 3 line and to a Tier 2 panel.
 *
 * The notices are **produced by driving [startNotice] and [armedNotice]**, not built by hand here.
 * A notice the catalogue gains later is then checked against this rule on its own, whichever tier
 * it lands on, rather than being missed by a hand-written list that stopped growing.
 */
class TimeOfDayTest {

    /** Every notice [startNotice] can return, over all 32 readiness combinations, with and without a silent start. */
    private val everyStartNotice: Set<StartNotice> =
        (0 until 32).flatMap { mask ->
            val readiness = DeviceReadiness(
                foregroundServiceRefused = mask and 1 != 0,
                audioUnavailable = mask and 2 != 0,
                notificationsBlocked = mask and 4 != 0,
                vibratorAbsent = mask and 8 != 0,
                batterySaverActive = mask and 16 != 0,
            )
            listOf(startNotice(readiness), startNotice(readiness, silentStartAccepted = true))
        }.filterNotNull().toSet()

    /** The one notice a running race can carry (#96). */
    private val armed: StartNotice = armedNotice(TimerState.RUNNING, cueVolumeRefused = true)!!

    @Test fun `the clock shows when no message is up`() {
        assertTrue(showsTimeOfDay(showResyncPrompt = false, discardWarningUp = false, startNotice = null))
    }

    @Test fun `the clock hides while the re-sync prompt is up`() {
        assertFalse(showsTimeOfDay(showResyncPrompt = true, discardWarningUp = false, startNotice = null))
    }

    @Test fun `the clock hides while the discard warning is up`() {
        assertFalse(showsTimeOfDay(showResyncPrompt = false, discardWarningUp = true, startNotice = null))
    }

    @Test fun `the clock hides under every Tier 3 notice the pre-start screen can carry`() {
        val warnings = everyStartNotice.filter { it.tier == NoticeTier.WARNING }
        // Four today: silent audio, notifications, vibrator, battery saver. Asserted so a rule that
        // stopped producing warnings could not pass this vacuously over an empty list.
        assertEquals(4, warnings.map { it.text }.toSet().size)
        for (notice in warnings) {
            assertFalse(
                "\"${notice.text}\" is a Tier 3 line, so the clock must give way to it",
                showsTimeOfDay(showResyncPrompt = false, discardWarningUp = false, startNotice = notice),
            )
        }
    }

    @Test fun `the clock hides under the Tier 3 notice a running race can carry`() {
        assertEquals(NoticeTier.WARNING, armed.tier)
        assertFalse(showsTimeOfDay(showResyncPrompt = false, discardWarningUp = false, startNotice = armed))
    }

    @Test fun `the clock hides under every Tier 2 blocking notice`() {
        // Not in the issue's criteria, which named Tier 3 only. The Tier 2 panel is taller than
        // Start, so it pushes the column to the top as a Tier 3 line does, and the clock then
        // touched the sequence name on the emulator at the SM-R925U's metrics. The owner chose to
        // hide it on #303.
        val blocking = everyStartNotice.filter { it.blocksStart }
        assertEquals(2, blocking.map { it.text }.toSet().size)
        for (notice in blocking) {
            assertFalse(
                "\"${notice.text}\" is a Tier 2 panel, so the clock must give way to it",
                showsTimeOfDay(showResyncPrompt = false, discardWarningUp = false, startNotice = notice),
            )
        }
    }

    @Test fun `the clock returns when the line clears`() {
        // The rule holds no state of its own: the same screen, one frame later with the line gone,
        // shows the clock again.
        val notice = everyStartNotice.first { it.tier == NoticeTier.WARNING }
        assertFalse(showsTimeOfDay(showResyncPrompt = false, discardWarningUp = false, startNotice = notice))
        assertTrue(showsTimeOfDay(showResyncPrompt = false, discardWarningUp = false, startNotice = null))
    }
}
