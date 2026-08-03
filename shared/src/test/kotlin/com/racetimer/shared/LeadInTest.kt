package com.racetimer.shared

import org.junit.Assert.*
import org.junit.Test

/**
 * The two-stage lead-in that lets a race manager start an external signal box on the mark (#104).
 *
 * The rules under test all live in `LeadIn.kt` as data rather than as branches in `TimerScreen`,
 * which is what makes them reachable from here at all — and is the point of AC 12.
 */
class LeadInTest {

    private val raceManager = BuiltInSequences.scholasticRaceManager

    /**
     * Every mode a race manager runs, in `all` order — named outright, on purpose.
     *
     * The tempting version of this is `all.filter { it.countUpAfterFinish }`, and it is worthless:
     * `offersLeadIn` *is* `countUpAfterFinish`, so the eligibility test below would compare the
     * implementation against itself and pass whatever either one said. Naming the sequences is what
     * gives the assertion an independent answer to check against, and the cost — this list must be
     * edited when a mode is added — is the assertion doing its job, not a defect in it.
     */
    private val raceManagers = listOf(
        BuiltInSequences.usSailingRaceManager,
        BuiltInSequences.scholasticRaceManager,
    )

    /** Every box alert a race can legally be armed for: the presets, both dialled bounds, and one odd. */
    private val allAlerts =
        (BOX_ALERT_PRESET_SECONDS + BOX_ALERT_MIN_SECONDS + BOX_ALERT_MAX_SECONDS + 47).distinct()

    /** The alerts that produce a non-zero stage 2, which is the only case with a press prompt. */
    private val alertsWithWindow = allAlerts.filter { it != BOX_ALERT_NONE }

    /** Where stage 1 ends — the press moment — for [alert] on the race-manager sequence. */
    private fun pressOffsetFor(alert: Int) = raceManager.totalMs + alert * 1_000L

    // --- The offered values (AC 2, AC 3, AC 4) --------------------------------

    @Test fun `the box alert presets are off, 15 s and 60 s`() {
        // Named after the *box's* setting rather than a total lead, because that is the fact the race
        // manager holds. 15 s is the iStart Dinghy alert, 60 s the Rule 26 one, per the manual.
        assertEquals(listOf(0, 15, 60), BOX_ALERT_PRESET_SECONDS)
        assertEquals(BOX_ALERT_NONE, BOX_ALERT_PRESET_SECONDS.first())
    }

    @Test fun `a dialled alert runs from 5 seconds to 2 minutes, whole seconds`() {
        assertEquals(5, BOX_ALERT_MIN_SECONDS)
        assertEquals(120, BOX_ALERT_MAX_SECONDS)
        assertEquals(5..120, BOX_ALERT_DIALLED_RANGE)
    }

    @Test fun `zero is a valid alert but is not reachable by dialling`() {
        // "Off" is a distinct answer to a question about the box, not the bottom of a stepper: a
        // stepper that runs to zero invites arriving there by accident, and zero is the one value
        // whose cue structure is different (no press prompt).
        assertTrue(isValidBoxAlert(BOX_ALERT_NONE))
        assertFalse(BOX_ALERT_NONE in BOX_ALERT_DIALLED_RANGE)
    }

    @Test fun `every preset is a valid alert, and the dialled ones are dialable`() {
        for (seconds in BOX_ALERT_PRESET_SECONDS) {
            assertTrue("$seconds s must be armable", isValidBoxAlert(seconds))
            if (seconds != BOX_ALERT_NONE) {
                assertTrue("$seconds s must be dialable too", seconds in BOX_ALERT_DIALLED_RANGE)
            }
        }
    }

    @Test fun `isValidBoxAlert refuses everything outside the range and zero`() {
        for (seconds in listOf(1, 4, -1, -60, 121, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertFalse("$seconds s must be refused", isValidBoxAlert(seconds))
        }
    }

    @Test fun `the picker's default is one of the presets`() {
        assertTrue(DEFAULT_BOX_ALERT_SECONDS in BOX_ALERT_PRESET_SECONDS)
    }

    @Test fun `prep is ten seconds and the presets produce the leads they always did`() {
        // The three original single-stage presets, recovered as prep + alert. Nobody had noticed they
        // decomposed; this is the assertion that says so out loud, so a change to either number has
        // to face what it does to the other.
        assertEquals(10, LEAD_IN_PREP_SECONDS)
        assertEquals(10, leadInSecondsFor(0))
        assertEquals(25, leadInSecondsFor(15))
        assertEquals(70, leadInSecondsFor(60))
    }

    // --- Which sequences offer it (AC 1) --------------------------------------

    @Test fun `only race-manager sequences offer a lead-in`() {
        // Syncing to a signal box is committee work: a sailor has no box to sync to, and a sailor
        // who triggers this by accident starts their race late. Asserted over `all` as a set rather
        // than by naming the sequences, so a sequence added later either opts in through
        // countUpAfterFinish — which is what makes it a race-manager mode — or does not opt in at all.
        //
        // #105 is the first time this test could fail for a reason that matters. With one committee
        // sequence in existence, "offer the lead-in for race-manager modes" and "offer it for
        // scholastic_race_manager" produced identical results, so the rule was untestable *as a
        // rule*: a hardcoded id would have passed just as well. A second mode that was never named
        // in `offersLeadIn` and arms anyway is what makes it a rule rather than a coincidence.
        val offering = BuiltInSequences.all.filter { offersLeadIn(it) }
        assertEquals(raceManagers, offering)
    }

    @Test fun `no sailor sequence can be armed with a lead-in`() {
        for (seq in BuiltInSequences.all - raceManagers.toSet()) {
            assertNull("${seq.id} must not arm", withLeadIn(seq, 60))
        }
        assertNull("custom must not arm", withLeadIn(BuiltInSequences.custom(5), 60))
    }

    @Test fun `every race-manager mode arms without being named anywhere`() {
        // The other half of the rule above, and the AC #105 exists to prove: the lead-in reached the
        // new sequence through offersLeadIn alone. Nothing in LeadIn.kt mentions
        // us_sailing_race_manager, and this arms it end to end — id, cues, stages and all — at every
        // preset, on a 5:00 sequence rather than the 3:00 one every other test in this file uses.
        for (seq in raceManagers) {
            for (alert in allAlerts) {
                val armed = withLeadIn(seq, alert)
                assertNotNull("${seq.id} at $alert s", armed)
                assertEquals(
                    "${seq.id} at $alert s",
                    leadInSecondsFor(alert) * 1_000L + seq.totalMs,
                    armed!!.totalMs,
                )
                assertEquals("${seq.id} at $alert s", seq.totalMs, armed.sequenceMs)
                assertEquals("${seq.id} at $alert s", leadInId(seq.id, alert), armed.id)
                assertTrue("${seq.id} at $alert s", armed.countUpAfterFinish)
            }
        }
    }

    @Test fun `the longest lead on the longest sequence puts the gun 6-10 away`() {
        // The largest remaining time the app can now produce, and it only became reachable with
        // #105 — #104's worst case was a 70 s lead on a 3:00 sequence, or 4:10. Anything reading a
        // threshold off remaining time (backgroundColorFor's 60 s and 10 s bands, the resume-offer
        // guards) now sees a maximum half again as large, so the number is worth pinning where a
        // future sequence that grows it again has to face it.
        val armed = withLeadIn(BuiltInSequences.usSailingRaceManager, BOX_ALERT_PRESET_SECONDS.max())!!
        assertEquals(6 * 60_000L + 10_000L, armed.totalMs)
        assertEquals("6:10", formatCountdown(armed.totalMs))
    }

    // --- Where the gun lands (AC 5) -------------------------------------------

    @Test fun `the gun lands at prep plus alert plus the sequence's own duration`() {
        // The headline number: a 60 s box alert on a 3:00 sequence puts the gun 4:10 away, and
        // TimerEngine.start anchors on exactly this value.
        for (alert in allAlerts) {
            val armed = withLeadIn(raceManager, alert)!!
            assertEquals(
                "$alert s alert",
                leadInSecondsFor(alert) * 1_000L + raceManager.totalMs,
                armed.totalMs,
            )
        }
        assertEquals(4 * 60_000L + 10_000L, withLeadIn(raceManager, 60)!!.totalMs)
        assertEquals(3 * 60_000L + 25_000L, withLeadIn(raceManager, 15)!!.totalMs)
        assertEquals(3 * 60_000L + 10_000L, withLeadIn(raceManager, BOX_ALERT_NONE)!!.totalMs)
    }

    @Test fun `arming does not change the sequence's own duration`() {
        for (alert in allAlerts) {
            val armed = withLeadIn(raceManager, alert)!!
            assertEquals("$alert s alert", raceManager.totalMs, armed.sequenceMs)
        }
    }

    @Test fun `the largest cue offset is never the total, at any alert`() {
        // The trap this whole model exists to avoid. Stage 1's ticks sit five seconds above the press
        // moment whatever the stages are, so a duration read off the largest cue offset is short by
        // the prep stage every single time — silently, and the gun lands early by exactly that much.
        for (alert in allAlerts) {
            val armed = withLeadIn(raceManager, alert)!!
            assertEquals(
                "$alert s alert: top cue is five seconds into the prep stage",
                armed.totalMs - 5_000L,
                armed.cues.maxOf { it.offsetMs },
            )
            assertNotEquals(
                "$alert s alert",
                armed.totalMs,
                armed.cues.maxOf { it.offsetMs },
            )
        }
    }

    // --- Stage 1: the run-in and the press prompt (AC 7, AC 8) ----------------

    @Test fun `the last five seconds of stage 1 tick once per second, and only those five`() {
        // Five at the end of the prep stage, whatever the alert: the run-in matters most closest to
        // the act, and one cadence at every setting is one cadence to learn. Ticking a whole 60 s
        // alert window would be unusable and would drown the box's own alert.
        for (alert in allAlerts) {
            val armed = withLeadIn(raceManager, alert)!!
            val ticks = armed.cues.filter { it.signal.voice == CueVoice.SYNC }
            assertEquals("$alert s alert", 5, ticks.size)
            assertEquals(
                "$alert s alert",
                (5L downTo 1L).map { pressOffsetFor(alert) + it * 1_000L },
                ticks.map { it.offsetMs },
            )
            for (cue in ticks) {
                assertTrue("$alert s alert at ${cue.offsetMs}", cue.isLeadIn)
                assertEquals("$alert s alert at ${cue.offsetMs}", 1, cue.signal.shortBlasts)
                assertEquals("$alert s alert at ${cue.offsetMs}", 0, cue.signal.longBlasts)
                assertEquals("$alert s alert at ${cue.offsetMs}", 0L, cue.signal.sustainedMs)
                assertFalse("a run-in tick is not the gun", cue.isGun)
            }
        }
    }

    @Test fun `the press prompt lands exactly where stage 1 ends`() {
        // One alert-window before the sequence's own first signal, which is where the box must be
        // pressed for its warning and the watch's to land together.
        for (alert in alertsWithWindow) {
            val armed = withLeadIn(raceManager, alert)!!
            val prompt = armed.cues.filter { it.signal.voice == CueVoice.PROMPT }
            assertEquals("$alert s alert", 1, prompt.size)
            assertEquals("$alert s alert", pressOffsetFor(alert), prompt.single().offsetMs)
            assertTrue("$alert s alert", prompt.single().isLeadIn)
            assertFalse("the prompt is not the gun", prompt.single().isGun)
        }
    }

    @Test fun `an alert of zero has no press prompt, because the sequence's own signal is one`() {
        // The single exception, and the case the first draft of this feature assumed universally: with
        // no alert window the press moment IS the sequence's first signal, and two cues cannot share
        // one offset. The 3-long is already unmissable and already means "the box should be going".
        val armed = withLeadIn(raceManager, BOX_ALERT_NONE)!!
        assertEquals(emptyList<SequenceCue>(), armed.cues.filter { it.signal.voice == CueVoice.PROMPT })
        assertEquals(5, armed.cues.count { it.isLeadIn })
        // ...and the last tick still runs into the 3-long, one second later.
        assertEquals(raceManager.totalMs + 1_000L, armed.cues.filter { it.isLeadIn }.last().offsetMs)
    }

    @Test fun `stage 2 is silent, however long it is`() {
        // The box is sounding its own five short horns through the alert window. Anything the watch
        // added there would put a wrist signal against a horn that means something else.
        for (alert in alertsWithWindow) {
            val armed = withLeadIn(raceManager, alert)!!
            val inStageTwo = armed.cues.filter {
                it.offsetMs < pressOffsetFor(alert) && it.offsetMs > raceManager.totalMs
            }
            assertEquals("$alert s alert", emptyList<SequenceCue>(), inStageTwo)
        }
    }

    @Test fun `a run-in tick carries the sync voice, so it feels unlike a signal`() {
        // AC 7's "at the SYNC haptic amplitude, not the race-signal one" is delivered by the voice
        // rather than by anything set at the call site: CueTiming reports the tick's own shorter
        // length and lighter amplitude off it, and HapticManager reads both from there.
        val tick = withLeadIn(raceManager, 15)!!.cues.first { it.signal.voice == CueVoice.SYNC }
        assertEquals(CueTiming.SYNC_ON + CueTiming.SYNC_OFF, CueTiming.durationMs(tick.signal))
        assertEquals(CueTiming.SYNC_AMPLITUDE, CueTiming.amplitude(tick.signal, long = false))
        assertNotEquals(
            CueTiming.durationMs(SignalPattern(shortBlasts = 1)),
            CueTiming.durationMs(tick.signal),
        )
    }

    @Test fun `the press prompt is unlike both a tick and a race signal on both channels`() {
        // The whole justification for a third voice. It must not be mistakable for the 3 short at
        // 0:30 of this very sequence, nor for the ticks that lead into it one second apart.
        val prompt = withLeadIn(raceManager, 15)!!.cues.single { it.signal.voice == CueVoice.PROMPT }
        val tick = withLeadIn(raceManager, 15)!!.cues.first { it.signal.voice == CueVoice.SYNC }
        val threeShort = SignalPattern(shortBlasts = 3)

        // Full strength, where a tick is deliberately light: this is the one cue that must be acted on.
        assertEquals(CueTiming.PROMPT_AMPLITUDE, CueTiming.amplitude(prompt.signal, long = false))
        assertNotEquals(
            CueTiming.amplitude(tick.signal, long = false),
            CueTiming.amplitude(prompt.signal, long = false),
        )
        // Faster than a tick and far faster than a blast, which is what makes it read as one event
        // rather than as a count.
        assertTrue(CueTiming.PROMPT_ON < CueTiming.SYNC_ON)
        assertTrue(CueTiming.PROMPT_ON < CueTiming.SHORT_ON)
        // And so it cannot be confused with 3 short by length either.
        assertNotEquals(
            CueTiming.durationMs(threeShort),
            CueTiming.durationMs(prompt.signal),
        )
    }

    @Test fun `no lead-in cue outruns the gap to the next one`() {
        // The same guard `no cue in any sequence outruns the gap to the next one` applies to the
        // built-ins, extended to the armed variants: the last tick one second above the press prompt
        // is the tightest pairing this feature creates.
        for (alert in allAlerts) {
            val armed = withLeadIn(raceManager, alert)!!
            for ((cue, next) in armed.cues.zipWithNext()) {
                val patternMs = CueTiming.durationMs(cue.signal, cue.isGun)
                val gapMs = cue.offsetMs - next.offsetMs
                assertTrue(
                    "${armed.id} cue at ${cue.offsetMs} ms runs $patternMs ms into a $gapMs ms gap",
                    patternMs <= gapMs,
                )
            }
        }
    }

    @Test fun `armed cues stay sorted descending and unique`() {
        for (alert in allAlerts) {
            val offsets = withLeadIn(raceManager, alert)!!.cues.map { it.offsetMs }
            assertEquals("$alert s alert", offsets.sortedDescending(), offsets)
            assertEquals("$alert s alert", offsets.size, offsets.distinct().size)
        }
    }

    // --- The sequence's own cues (AC 10) --------------------------------------

    @Test fun `arming leaves the sequence's own cues untouched, object for object`() {
        // The 3-long still fires at 3:00, the tail is bit-for-bit raceManagerTail, and the gun is
        // still sustainedGun — asserted by identity, so a future edit cannot substitute an equal
        // copy and quietly re-voice the race-manager sequence through this path.
        for (alert in allAlerts) {
            val armed = withLeadIn(raceManager, alert)!!
            val own = armed.cues.filterNot { it.isLeadIn }
            assertEquals("$alert s alert", raceManager.cues.size, own.size)
            for ((original, carried) in raceManager.cues.zip(own)) {
                assertSame("$alert s alert", original, carried)
            }
        }
    }

    @Test fun `an armed sequence keeps its name, its count-up and its gun`() {
        val armed = withLeadIn(raceManager, 60)!!
        assertEquals(raceManager.name, armed.name)
        assertTrue("a race manager's job does not end at the gun", armed.countUpAfterFinish)
        assertSame(raceManager.cues.last { it.isGun }, armed.cues.last { it.isGun })
    }

    @Test fun `arming changes nothing about the sequence it was built from`() {
        withLeadIn(raceManager, 60)
        assertEquals(3 * 60_000L, raceManager.totalMs)
        assertEquals(13, raceManager.cues.size)
        assertEquals(0L, raceManager.leadInMs)
        assertTrue(raceManager.cues.none { it.isLeadIn })
    }

    // --- Surviving a process kill (AC 13) -------------------------------------

    @Test fun `resolve rebuilds an armed sequence from its id alone, at any alert`() {
        // The sequence id is the entire persisted state — TimerEngine.Snapshot stores nothing else —
        // so this is the whole of "a process death anywhere in either stage restores to the correct
        // remaining time". If it does not reproduce the race, the watch comes back short by the lead.
        for (alert in allAlerts) {
            val armed = withLeadIn(raceManager, alert)!!
            assertEquals("$alert s alert", armed, BuiltInSequences.resolve(armed.id))
        }
    }

    @Test fun `an armed id names its box alert and its base sequence`() {
        val armed = withLeadIn(raceManager, 60)!!
        assertEquals("scholastic_race_manager_alert60s", armed.id)
        assertEquals(60, boxAlertSeconds(armed.id))
        assertEquals(raceManager.id, leadInBaseId(armed.id))
        // Zero is encoded like any other value, so "off" survives a kill as itself rather than
        // resolving to a plain sequence with no prep stage at all.
        assertEquals("scholastic_race_manager_alert0s", withLeadIn(raceManager, 0)!!.id)
        assertEquals(0, boxAlertSeconds("scholastic_race_manager_alert0s"))
    }

    @Test fun `a plain sequence id carries no box alert`() {
        for (seq in BuiltInSequences.all + BuiltInSequences.custom(8)) {
            assertNull("${seq.id} carries no alert", boxAlertSeconds(seq.id))
            assertEquals(seq.id, leadInBaseId(seq.id))
        }
    }

    @Test fun `resolve returns null rather than substituting for a malformed alert id`() {
        // Null rather than a clamp or a fallback, exactly as an out-of-range custom duration is
        // refused: substituting *something* is the "wrong duration, wrong cues, no error" failure
        // this codebase has already paid for once.
        val bad = listOf(
            "scholastic_race_manager_alert4s",       // below the dialled minimum
            "scholastic_race_manager_alert1s",       // ditto, and not zero
            "scholastic_race_manager_alert121s",     // above the maximum by one
            "scholastic_race_manager_alert-60s",     // negative
            "scholastic_race_manager_alerts",        // no number
            "scholastic_race_manager_alert60",       // no unit
            "scholastic_race_manager_alert60m",      // wrong unit
            "scholastic_alert60s",                   // a sailor sequence cannot be armed
            "club_3_2_1_alert60s",                   // nor can club
            "custom_5m_alert60s",                    // nor can a custom race
            "nothing_alert60s",                      // no such base
            "_alert60s",                             // no base at all
            "scholastic_race_manager_alert5s_alert9s", // armed twice
        )
        for (id in bad) {
            assertNull("'$id' must not resolve", BuiltInSequences.resolve(id))
        }
    }

    @Test fun `arming refuses an alert outside the bounds`() {
        for (seconds in listOf(1, 4, -60, 121, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertNull("$seconds s must be refused", withLeadIn(raceManager, seconds))
        }
    }

    @Test fun `re-arming replaces the alert rather than stacking a second lead`() {
        // Reachable from the pre-start screen after a process kill, where the selection is the saved
        // armed race and the lead-in control is tapped again. A stacked lead would put the gun a
        // minute and a half from where the picker said it would be.
        val armed = withLeadIn(raceManager, 60)!!
        val rearmed = withLeadIn(armed, 15)!!
        assertEquals(withLeadIn(raceManager, 15), rearmed)
        assertEquals(25_000L, rearmed.leadInMs)
        assertEquals(6, rearmed.cues.count { it.isLeadIn })
    }

    @Test fun `leadInBaseOf strips a lead and passes a plain sequence through`() {
        assertSame(raceManager, leadInBaseOf(raceManager))
        assertEquals(raceManager, leadInBaseOf(withLeadIn(raceManager, 60)!!))
    }

    // --- Where the lead-in ends (the Sync rule) --------------------------------

    @Test fun `a race is in its lead-in until the sequence's own first signal fires`() {
        val armed = withLeadIn(raceManager, 60)!!
        assertTrue("at the top", isInLeadIn(armed, armed.totalMs))
        assertTrue("in the prep stage", isInLeadIn(armed, pressOffsetFor(60) + 1))
        assertTrue("at the press moment", isInLeadIn(armed, pressOffsetFor(60)))
        assertTrue("inside the alert window", isInLeadIn(armed, raceManager.totalMs + 1))
        // Strict: at exactly 3:00 the sequence's own first signal is firing, and that signal is the
        // *end* of the lead-in, not part of it.
        assertFalse("on the mark", isInLeadIn(armed, raceManager.totalMs))
        assertFalse("into the sequence", isInLeadIn(armed, raceManager.totalMs - 1))
        assertFalse("past the gun", isInLeadIn(armed, -1_000L))
    }

    @Test fun `an unarmed sequence is never in a lead-in`() {
        for (seq in BuiltInSequences.all) {
            for (remaining in listOf(Long.MAX_VALUE, seq.totalMs + 1, seq.totalMs, 0L, -1L)) {
                assertFalse("${seq.id} at $remaining", isInLeadIn(seq, remaining))
            }
        }
    }
}
