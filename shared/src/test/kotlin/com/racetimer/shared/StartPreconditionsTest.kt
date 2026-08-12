package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the rule deciding what a sailor is told before a race, and whether Start survives it.
 *
 * Built like [MessageContrastTest]: the tests worth having are the ones that assert something must
 * **not** happen. Three of them below encode decisions that were argued and could plausibly be
 * reverted by someone reading only `docs/message-surface.md`'s original catalogue —
 *
 * - a denied notification permission must **never** block Start (#13's first acceptance criterion,
 *   and the owner decision of 2026-08-11 that changed the catalogue rather than the criterion);
 * - a blocking notice must always carry a remedy, or the sailor is in a dead end with no control;
 * - "Start silent" must not unblock anything except audio.
 *
 * Take any of those three out and this suite goes red, which is the only reason to believe it is
 * measuring anything.
 *
 * **What this file cannot see.** `shared` has no view of `wear`, so these tests prove the *rule*.
 * They cannot prove `MainActivity` populates [DeviceReadiness] from the real platform, nor that
 * `TimerScreen` removes the Start button when [StartNotice.blocksStart] is true. That gap is the
 * same one [MessageContrastTest] documents for colours, and it is discharged the same way — on the
 * watch, in this issue's verification section.
 */
class StartPreconditionsTest {

    /** Every condition false: the watch this app was designed for. */
    private val ready = DeviceReadiness()

    private val allConstants = listOf(
        NOTICE_FOREGROUND_SERVICE_REFUSED,
        NOTICE_AUDIO_UNAVAILABLE,
        NOTICE_NOTIFICATIONS_BLOCKED,
        NOTICE_VIBRATOR_ABSENT,
        NOTICE_BATTERY_SAVER,
    )

    // --- The ordinary case ----------------------------------------------------------------------

    @Test fun `a watch with nothing wrong is told nothing`() {
        assertNull(startNotice(ready))
    }

    @Test fun `a ready watch stays silent even once a silent start has been accepted`() {
        // The escape hatch is about a condition, not a mood: accepting it must not manufacture a
        // notice on a watch whose audio is fine.
        assertNull(startNotice(ready, silentStartAccepted = true))
    }

    // --- Each condition on its own --------------------------------------------------------------

    @Test fun `a refused foreground service blocks the start and offers Settings`() {
        val notice = startNotice(ready.copy(foregroundServiceRefused = true))
        assertNotNull(notice)
        assertEquals(NoticeTier.BLOCKING, notice!!.tier)
        assertEquals(NOTICE_FOREGROUND_SERVICE_REFUSED, notice.text)
        assertEquals(StartRemedy.APP_SETTINGS, notice.remedy)
        assertTrue(notice.blocksStart)
    }

    @Test fun `unavailable audio blocks softly and offers to start silent`() {
        val notice = startNotice(ready.copy(audioUnavailable = true))
        assertEquals(NoticeTier.BLOCKING, notice!!.tier)
        assertEquals(StartRemedy.START_SILENT, notice.remedy)
        assertEquals("Start silent", notice.remedy.label)
    }

    @Test fun `accepting a silent start demotes the audio notice and gives Start back`() {
        // docs/message-surface.md: the warning is "demoted to Tier 3 for the duration" — it does not
        // disappear, or the sailor forgets mid-race why the gun made no sound.
        val notice = startNotice(ready.copy(audioUnavailable = true), silentStartAccepted = true)
        assertEquals(NoticeTier.WARNING, notice!!.tier)
        assertEquals(NOTICE_AUDIO_UNAVAILABLE, notice.text)
        assertEquals(StartRemedy.NONE, notice.remedy)
        assertFalse(notice.blocksStart)
    }

    @Test fun `a missing vibrator warns and leaves Start alone`() {
        val notice = startNotice(ready.copy(vibratorAbsent = true))
        assertEquals(NoticeTier.WARNING, notice!!.tier)
        assertEquals(NOTICE_VIBRATOR_ABSENT, notice.text)
        assertFalse(notice.blocksStart)
    }

    @Test fun `battery saver warns and leaves Start alone`() {
        val notice = startNotice(ready.copy(batterySaverActive = true))
        assertEquals(NoticeTier.WARNING, notice!!.tier)
        assertEquals(NOTICE_BATTERY_SAVER, notice.text)
        assertFalse(notice.blocksStart)
    }

    // --- The decision this story turned on ------------------------------------------------------

    @Test fun `a denied notification permission warns but never blocks the start`() {
        // #13 AC1: "sequence still starts and runs; user is informed once with a clear next step".
        // The catalogue in docs/message-surface.md put this at Tier 2 when it was written, which
        // would remove Start. On Android 13+ a foreground service starts fine with POST_NOTIFICATIONS
        // denied — only the notification is suppressed — so blocking would refuse a race that would
        // have worked. This is the assertion that stops the catalogue's original being restored.
        val notice = startNotice(ready.copy(notificationsBlocked = true))
        assertEquals(NoticeTier.WARNING, notice!!.tier)
        assertEquals(NOTICE_NOTIFICATIONS_BLOCKED, notice.text)
        assertFalse("a denied notification permission must never remove Start", notice.blocksStart)
        // The "clear next step" half of the criterion: warning alone does not discharge it.
        assertEquals(StartRemedy.NOTIFICATION_SETTINGS, notice.remedy)
        assertEquals("Settings", notice.remedy.label)
    }

    @Test fun `notifications stay non-blocking however many other conditions are also true`() {
        // Guards the weaker version of the same regression: a rewrite that blocks whenever "enough"
        // is wrong. Notifications plus both harmless warnings must still leave Start on screen.
        val notice = startNotice(
            ready.copy(notificationsBlocked = true, vibratorAbsent = true, batterySaverActive = true),
        )
        assertFalse(notice!!.blocksStart)
    }

    // --- Precedence, and the one-message rule ---------------------------------------------------

    @Test fun `a refused foreground service outranks every other condition`() {
        val everythingWrong = DeviceReadiness(
            foregroundServiceRefused = true,
            audioUnavailable = true,
            notificationsBlocked = true,
            vibratorAbsent = true,
            batterySaverActive = true,
        )
        assertEquals(NOTICE_FOREGROUND_SERVICE_REFUSED, startNotice(everythingWrong)!!.text)
    }

    @Test fun `audio outranks the three warnings`() {
        val notice = startNotice(
            ready.copy(
                audioUnavailable = true,
                notificationsBlocked = true,
                vibratorAbsent = true,
                batterySaverActive = true,
            ),
        )
        assertEquals(NOTICE_AUDIO_UNAVAILABLE, notice!!.text)
    }

    @Test fun `notifications outrank a missing vibrator which outranks battery saver`() {
        assertEquals(
            NOTICE_NOTIFICATIONS_BLOCKED,
            startNotice(ready.copy(notificationsBlocked = true, vibratorAbsent = true))!!.text,
        )
        assertEquals(
            NOTICE_VIBRATOR_ABSENT,
            startNotice(ready.copy(vibratorAbsent = true, batterySaverActive = true))!!.text,
        )
    }

    @Test fun `a demoted audio notice still outranks the warnings beneath it`() {
        // Accepting a silent start changes the audio notice's tier, not its rank. Getting this
        // wrong would replace the reason the gun is silent with "Battery saver" mid-session.
        //
        // Every lower condition is switched on, and that is the point rather than thoroughness for
        // its own sake: this test named battery saver in its comment while leaving the field false,
        // so a mutation hoisting battery saver to the top of the precedence chain left it green.
        // Found by that exact mutation — predicted 4 red, got 3, and this was the missing one.
        val notice = startNotice(
            ready.copy(
                audioUnavailable = true,
                notificationsBlocked = true,
                vibratorAbsent = true,
                batterySaverActive = true,
            ),
            silentStartAccepted = true,
        )
        assertEquals(NOTICE_AUDIO_UNAVAILABLE, notice!!.text)
    }

    @Test fun `every combination of conditions yields at most one notice`() {
        // Rule 6 of docs/message-surface.md, asserted over all 32 combinations rather than argued:
        // the return type is a single nullable, so what this really proves is that no combination
        // throws or falls through the `when` to an unintended branch.
        var produced = 0
        for (mask in 0 until 32) {
            val readiness = DeviceReadiness(
                foregroundServiceRefused = mask and 1 != 0,
                audioUnavailable = mask and 2 != 0,
                notificationsBlocked = mask and 4 != 0,
                vibratorAbsent = mask and 8 != 0,
                batterySaverActive = mask and 16 != 0,
            )
            for (accepted in listOf(false, true)) {
                val notice = startNotice(readiness, accepted)
                if (mask == 0) {
                    assertNull("nothing wrong must produce nothing", notice)
                } else {
                    assertNotNull("mask $mask produced no notice", notice)
                    assertTrue(
                        "mask $mask produced copy that is not one of the constants",
                        notice!!.text in allConstants,
                    )
                    produced++
                }
            }
        }
        assertEquals("31 non-empty masks x 2 acceptance states", 62, produced)
    }

    // --- Invariants that hold whatever the conditions -------------------------------------------

    @Test fun `a blocking notice always offers a way out`() {
        // A block with no button is a watch the sailor cannot start a race on and cannot fix. Both
        // blocking conditions must name a remedy; this asserts it over the whole input space rather
        // than over the two cases that happen to exist today.
        for (mask in 1 until 32) {
            val readiness = DeviceReadiness(
                foregroundServiceRefused = mask and 1 != 0,
                audioUnavailable = mask and 2 != 0,
                notificationsBlocked = mask and 4 != 0,
                vibratorAbsent = mask and 8 != 0,
                batterySaverActive = mask and 16 != 0,
            )
            for (accepted in listOf(false, true)) {
                val notice = startNotice(readiness, accepted) ?: continue
                if (notice.blocksStart) {
                    assertTrue(
                        "blocking notice '${notice.text}' offers no remedy",
                        notice.remedy != StartRemedy.NONE,
                    )
                    assertNotNull("remedy ${notice.remedy} has no button label", notice.remedy.label)
                }
            }
        }
    }

    @Test fun `only audio can be got past by accepting a silent start`() {
        // The escape hatch is scoped to one condition on purpose: a foreground service that cannot
        // start has no usable degraded mode, so no amount of accepting should arm that race.
        val stillBlocked = startNotice(
            ready.copy(foregroundServiceRefused = true),
            silentStartAccepted = true,
        )
        assertTrue("a silent start must not arm a race the service cannot run", stillBlocked!!.blocksStart)
    }

    @Test fun `every notice fits the screen it has to render on`() {
        // The geometry is docs/message-surface.md's, and a copy edit is exactly the change nobody
        // re-measures. Two lines of caption1 inside the width cap is the budget.
        for (copy in allConstants) {
            assertTrue(
                "\"$copy\" is ${copy.length} characters, over the $NOTICE_MAX_CHARS the panel holds",
                copy.length <= NOTICE_MAX_CHARS,
            )
        }
    }

    @Test fun `no two conditions share a message`() {
        // A sailor reading "Battery saver — sound may be cut" must be able to conclude which
        // condition fired. Distinct copy is what makes the notice diagnostic rather than decorative.
        assertEquals(allConstants.size, allConstants.toSet().size)
    }

    // --- Negative control -----------------------------------------------------------------------

    @Test fun `the suite can tell a blocking notice from a warning`() {
        // Without this the four assertFalse(blocksStart) calls above would pass just as happily
        // against a rule that never blocks anything at all.
        assertTrue(startNotice(ready.copy(foregroundServiceRefused = true))!!.blocksStart)
        assertFalse(startNotice(ready.copy(batterySaverActive = true))!!.blocksStart)
    }
}
