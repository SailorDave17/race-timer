package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        NOTICE_CUE_VOLUME_REFUSED,
    )

    /**
     * Tier 1 cue-loss copy (#161), deliberately **not** folded into [allConstants].
     *
     * That list is the set `startNotice` is allowed to produce, and the exhaustive mask test asserts
     * membership in it. Adding two strings that rule can never emit would widen the thing that test
     * checks against, which is the opposite of what it is for.
     */
    private val cueLossConstants = listOf(
        NOTICE_CUE_DROPPED,
        NOTICE_CUE_TRUNCATED,
    )

    /** Every string a sailor can read, for the guards that are about the copy rather than the rule. */
    private val everyCopy = allConstants + cueLossConstants

    /**
     * Every string paired with the surface it is drawn on, **derived by driving the rules** (#231).
     *
     * The pairing is the point. `everyCopy` is a flat list, and a flat list is exactly what let one
     * character ceiling stand in for three plates of different widths — the defect #231 is. Here the
     * surface comes off `NoticeTier` as the rule returned it, so a notice that changes tier is
     * automatically re-checked against the plate it moved to, and a fourth surface cannot be added
     * without `MessageSurface` gaining a member.
     *
     * The Tier 1 cue-loss copy has no [NoticeTier] to read — `cueLossNotice` returns a bare string,
     * because a banner is news rather than a standing condition — so it is the one surface named
     * here rather than derived. That asymmetry is real and stated instead of smoothed over.
     */
    private fun everySurfacedCopy(): List<Pair<MessageSurface, String>> = buildList {
        for (mask in 0 until 32) {
            val readiness = DeviceReadiness(
                foregroundServiceRefused = mask and 1 != 0,
                audioUnavailable = mask and 2 != 0,
                notificationsBlocked = mask and 4 != 0,
                vibratorAbsent = mask and 8 != 0,
                batterySaverActive = mask and 16 != 0,
            )
            for (accepted in listOf(false, true)) {
                startNotice(readiness, accepted)?.let { add(it.tier.surface to it.text) }
            }
        }
        for (state in TimerState.values()) {
            for (refused in listOf(false, true)) {
                armedNotice(state, refused)?.let { add(it.tier.surface to it.text) }
            }
        }
        for (loss in CueLoss.values()) {
            cueLossNotice(loss)?.let { add(MessageSurface.BANNER to it) }
        }
    }.distinct()

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
        // re-measures. The shared ceiling, which holds for every surface and is coarse for all of
        // them; the sharp, per-surface check is the test below.
        for (copy in everyCopy) {
            assertTrue(
                "\"$copy\" is ${copy.length} characters, over the $NOTICE_MAX_CHARS the panel holds",
                copy.length <= NOTICE_MAX_CHARS,
            )
        }
    }

    @Test fun `every notice fits the surface it actually renders on`() {
        // #231. The ceiling above was derived from the Tier 1 banner's geometry and applied to all
        // three surfaces, so it is a ceiling that happens to hold rather than a fit — which is how
        // #96's 49-character notice landed comfortably under 60 and drew on three lines.
        //
        // Driven rather than restated, the way MessageContrastTest derives its backgrounds: the
        // surface comes off the tier the rule itself returned, so a notice that changes tier is
        // re-checked against the plate it moved to. A hand-written map would have gone on asserting
        // the old one.
        for ((surface, copy) in everySurfacedCopy()) {
            assertTrue(
                "\"$copy\" takes ${surface.linesFor(copy)} lines on $surface, " +
                    "which holds ${surface.maxLines} at ${surface.charsPerLine} characters a line",
                surface.holds(copy),
            )
        }
    }

    @Test fun `the surface check reaches all three surfaces`() {
        // Anti-vacuity for the test above, and it is not a formality: the pairs are collected by
        // driving the rules, so a rule that stopped returning anything would empty the loop and the
        // assertion would pass having checked nothing. That is the failure this workspace has
        // measured more than once — an absent result reading exactly like a clean one.
        val reached = everySurfacedCopy().map { it.first }.toSet()
        assertEquals(MessageSurface.values().toSet(), reached)
        assertTrue(everySurfacedCopy().size >= everyCopy.size)
    }

    @Test fun `the surface check would fail a notice that overran its plate`() {
        // The negative control. Without it the two tests above pass just as happily against a
        // `holds` that returned true unconditionally — and they would have passed against the state
        // of the world #231 was filed to describe, because the copy that exposed the gap does fit.
        // So the proof has to come from a string that does not.
        val overlong = "Do Not Disturb — every cue on this leg will be silent from here to the gun"
        assertFalse("a four-line notice must not pass a three-line plate", MessageSurface.STATUS_LINE.holds(overlong))
        assertTrue(
            "the same surface must still hold the notice it ships, or this refuses everything",
            MessageSurface.STATUS_LINE.holds(NOTICE_CUE_VOLUME_REFUSED),
        )
    }

    @Test fun `no two conditions share a message`() {
        // A sailor reading "Battery saver — sound may be cut" must be able to conclude which
        // condition fired. Distinct copy is what makes the notice diagnostic rather than decorative.
        assertEquals(everyCopy.size, everyCopy.toSet().size)
    }

    // --- Negative control -----------------------------------------------------------------------

    @Test fun `the suite can tell a blocking notice from a warning`() {
        // Without this the four assertFalse(blocksStart) calls above would pass just as happily
        // against a rule that never blocks anything at all.
        assertTrue(startNotice(ready.copy(foregroundServiceRefused = true))!!.blocksStart)
        assertFalse(startNotice(ready.copy(batterySaverActive = true))!!.blocksStart)
    }


    // --- armedNotice: the warning that only exists once a race is running (#96) ------------------

    @Test fun `a refused volume raise warns for the length of a running race`() {
        val notice = armedNotice(TimerState.RUNNING, cueVolumeRefused = true)
        assertNotNull(notice)
        assertEquals(NoticeTier.WARNING, notice!!.tier)
        assertEquals(NOTICE_CUE_VOLUME_REFUSED, notice.text)
        assertEquals(StartRemedy.NONE, notice.remedy)
        assertFalse("a running race can never be blocked — rule 3", notice.blocksStart)
    }

    @Test fun `a race whose cues were made audible is told nothing`() {
        // AC 5: the ordinary race — including the common case where the volume was already high
        // enough that no raise was attempted at all — is byte-for-byte the screen it always was.
        for (state in TimerState.values()) {
            assertNull(
                "state $state must stay silent when the raise was not refused",
                armedNotice(state, cueVolumeRefused = false),
            )
        }
    }

    @Test fun `the warning belongs to the countdown and to no other screen`() {
        // Before the gun there are cues left to be silent; after it there are none. RACE_ENDED
        // matters most here — a race committee reads finish times off that screen, and a warning
        // about cues that have already finished sounding would be furniture.
        for (state in TimerState.values().filter { it != TimerState.RUNNING }) {
            assertNull(
                "$state must not carry the cue-volume warning",
                armedNotice(state, cueVolumeRefused = true),
            )
        }
    }

    // --- Negative controls for the armed warning ------------------------------------------------

    @Test fun `both inputs to the armed rule are load-bearing`() {
        // Without this, the two tests above would pass just as happily against a rule that ignored
        // the measurement and warned on every running race, or against one that never warned at
        // all. Each input is asserted to change the answer on its own.
        assertNotNull(armedNotice(TimerState.RUNNING, cueVolumeRefused = true))
        assertNull(armedNotice(TimerState.RUNNING, cueVolumeRefused = false))
        assertNull(armedNotice(TimerState.IDLE, cueVolumeRefused = true))
    }

    @Test fun `the pre-start rule can never produce the armed-race copy`() {
        // The two rules answer disjoint screens, and this is the half of that `shared` can see: no
        // combination of the five pre-start conditions may emit the Do Not Disturb line. A sailor
        // reading it before Start would be reading the *previous* race's verdict, which is exactly
        // the prediction #96 was rewritten to avoid.
        //
        // What this cannot see — stated rather than implied — is that `MainActivity` calls the two
        // rules from mutually exclusive branches of `refreshUiState`. That is `wear/`, and the
        // hardware criterion is what discharges it.
        val everyPreStartText = buildList {
            for (readiness in listOf(
                ready,
                ready.copy(foregroundServiceRefused = true),
                ready.copy(audioUnavailable = true),
                ready.copy(notificationsBlocked = true),
                ready.copy(vibratorAbsent = true),
                ready.copy(batterySaverActive = true),
            )) {
                for (silent in listOf(false, true)) {
                    startNotice(readiness, silent)?.let { add(it.text) }
                }
            }
        }
        assertFalse(
            "the pre-start rule must never produce the armed-race copy",
            everyPreStartText.contains(NOTICE_CUE_VOLUME_REFUSED),
        )
        // And the list is non-empty, or the assertion above would hold against a rule that never
        // produced anything at all.
        assertTrue("no pre-start copy was collected, so the check proves nothing", everyPreStartText.size >= 5)
    }

    // --- cueLossNotice: a cue the audio path lost mid-race (#161) --------------------------------

    @Test fun `a cue that did not sound is announced as silent`() {
        assertEquals(NOTICE_CUE_DROPPED, cueLossNotice(CueLoss.DROPPED))
    }

    @Test fun `a cue that stopped early is announced as cut short`() {
        assertEquals(NOTICE_CUE_TRUNCATED, cueLossNotice(CueLoss.TRUNCATED))
    }

    @Test fun `nothing lost is nothing said`() {
        // The null-in/null-out half of the contract. `MainActivity` hands this the read-and-clear
        // result directly, so the ordinary race — every cue sounding — passes null through here on
        // every one of its refreshes and must produce no banner.
        assertNull(cueLossNotice(null))
    }

    @Test fun `every way of losing a cue has copy, including any added later`() {
        // Driven off the enum rather than off a list written by hand, so a third CueLoss case added
        // without copy fails here instead of reaching a sailor as a silent loss — which is the exact
        // defect #161 exists to close, reintroduced one enum case at a time.
        for (loss in CueLoss.values()) {
            assertNotNull("$loss has no copy", cueLossNotice(loss))
        }
        assertTrue("the enum is empty, so the loop above proves nothing", CueLoss.values().isNotEmpty())
    }

    @Test fun `a dropped cue and a truncated cue are distinguishable`() {
        // AC 4. The decision this encodes: a dropped cue is absent and a truncated one is present
        // and wrong — a three-second gun cut short sounds like a short blast, which is a different
        // mark in every sequence this app ships. Collapsing them to one string would pass every
        // other test in this section.
        val texts = CueLoss.values().map { cueLossNotice(it) }
        assertEquals("two losses must not share one message", texts.size, texts.toSet().size)
    }

    @Test fun `the pre-start rule can never produce a cue-loss banner`() {
        // The mirror of the armed-notice test above, and the same reasoning: a sailor reading "Cue
        // silent" before Start would be reading about a race that has already finished. The two
        // rules answer disjoint screens and this is the half `shared` can see.
        val everyPreStartText = buildList {
            for (readiness in listOf(
                ready,
                ready.copy(foregroundServiceRefused = true),
                ready.copy(audioUnavailable = true),
                ready.copy(notificationsBlocked = true),
                ready.copy(vibratorAbsent = true),
                ready.copy(batterySaverActive = true),
            )) {
                for (silent in listOf(false, true)) {
                    startNotice(readiness, silent)?.let { add(it.text) }
                }
            }
        }
        for (copy in cueLossConstants) {
            assertFalse("the pre-start rule produced $copy", everyPreStartText.contains(copy))
        }
        assertTrue("no pre-start copy was collected, so the check proves nothing", everyPreStartText.size >= 5)
    }

    @Test fun `the armed warning and the cue-loss banner are different messages`() {
        // Both are about cues a sailor cannot hear, and they are deliberately separate surfaces:
        // #96's is a standing condition for the length of the countdown (Tier 3), this is one event
        // that has already happened (Tier 1). Sharing copy would make the tier the only difference,
        // and a sailor cannot see a tier.
        for (copy in cueLossConstants) {
            assertNotEquals(NOTICE_CUE_VOLUME_REFUSED, copy)
        }
    }

    // --- ForegroundRefusalLatch: the refusal's expiry rule (#165) --------------------------------

    @Test fun `the Settings round trip clears a latched refusal and re-offers Start`() {
        val latch = ForegroundRefusalLatch()
        latch.dispatchRefused()
        // Asserted mid-test rather than assumed: the clearing below proves nothing unless the
        // latch demonstrably latched first — an expected "no notice" is exactly the answer a latch
        // that never worked would also give.
        assertTrue(startNotice(DeviceReadiness(foregroundServiceRefused = latch.refused))!!.blocksStart)
        latch.returnedToForeground()
        assertNull(startNotice(DeviceReadiness(foregroundServiceRefused = latch.refused)))
    }

    @Test fun `a successful dispatch clears the latch`() {
        val latch = ForegroundRefusalLatch()
        latch.dispatchRefused()
        assertTrue(latch.refused)
        latch.dispatchSucceeded()
        assertFalse(latch.refused)
    }

    @Test fun `a refusal that survives the round trip re-latches on the next dispatch`() {
        // The expiry rule is optimism, not amnesia about the condition: clearing the latch is only
        // safe because a dispatch that still fails puts the panel straight back.
        val latch = ForegroundRefusalLatch()
        latch.dispatchRefused()
        latch.returnedToForeground()
        latch.dispatchRefused()
        assertTrue(startNotice(DeviceReadiness(foregroundServiceRefused = latch.refused))!!.blocksStart)
    }

    @Test fun `a fresh latch reports nothing wrong`() {
        assertFalse(ForegroundRefusalLatch().refused)
    }
}
