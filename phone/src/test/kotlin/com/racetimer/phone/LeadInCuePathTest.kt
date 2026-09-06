package com.racetimer.phone

import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.CueVoice
import com.racetimer.shared.LEAD_IN_PREP_SECONDS
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.withLeadIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #207 AC 1 and AC 3: shared `LeadIn` drives the two-stage run-up through the phone's cue path,
 * and the handover to the sequence proper lands on the same anchor with no gap and no double cue.
 *
 * Pure JVM, off the same seams as [CueDispatchIsScheduledTest] — the recording sounder stands in
 * for the audio path, and a scheduler the test fires by hand is what lets a 250-second race be
 * driven boundary by boundary with the display poll never running. The logic under test is
 * shared's and is already proven there at every alert (`LeadInTest`); what this file owns is the
 * **glue**: that the phone runner hands an armed sequence to the engine unmodified, that every cue
 * shared put in the run-up reaches the sounder on its own boundary, and that the phone's own
 * additions — the lead-in flag the screen reads, the drop back to the base sequence — do what they
 * say.
 *
 * Times below are read off the test's own clock, so *when* a cue sounded is asserted as a number
 * rather than inferred from order. The ordering half alone would pass on a run-up whose cues were
 * all fired at the anchor instant.
 */
class LeadInCuePathTest {

    private val clock = SteppedClock()
    private val sounder = RecordingSounder()
    private val scheduler = RecordingScheduler()
    private val runner = PhoneRaceRunner(clock, sounder, scheduler)

    private val base = BuiltInSequences.scholasticRaceManager

    /** The Rule 26 preset on the scholastic committee sequence: 10 s prep + 60 s alert + 3:00. */
    private val armed: RaceSequence = withLeadIn(base, 60)
        ?: error("the race-manager sequence must arm, or nothing below is testing anything")

    /** Every cue that sounded, as (clock time, label), in order. */
    private val timeline = mutableListOf<Pair<Long, String>>()

    /** Advance the clock to the armed boundary and let the dispatch fire, recording what played. */
    private fun fireNextBoundary() {
        val delay = scheduler.armedDelayMs ?: throw AssertionError("nothing armed")
        val before = sounder.played.size
        clock.nowMs += delay
        scheduler.fire()
        sounder.played.drop(before).forEach { timeline += clock.nowMs to it }
    }

    /** Select the armed race, start it, and drive it to the gun off the scheduler alone. */
    private fun runArmedRaceToTheGun() {
        runner.select(armed)
        runner.start()
        sounder.played.forEach { timeline += clock.nowMs to it }
        var boundariesLeft = 1_000
        while (scheduler.armedAction != null) {
            assertTrue("dispatch loop did not converge", boundariesLeft-- > 0)
            fireNextBoundary()
        }
    }

    @Test
    fun `every armed cue lands through the scheduler, in shared's order, with the poll never running`() {
        runArmedRaceToTheGun()

        // The whole armed cue list, run-up and sequence alike, and nothing the phone added or
        // dropped. `armed.cues` is shared's object; a phone that re-derived the run-up would
        // diverge here.
        assertEquals(armed.cues.map { it.signal.label }, sounder.played)
        assertNull("nothing may be left armed after the gun", scheduler.armedDelayMs)
    }

    @Test
    fun `stage 1 ticks into the press prompt, stage 2 is silent, and the sequence opens on its own signal`() {
        runArmedRaceToTheGun()
        val prepMs = LEAD_IN_PREP_SECONDS * 1_000L

        // Stage 1: the last five seconds of prep tick, once a second, and nothing before them.
        // Found by voice rather than by label — the voice is the property that keeps a tick from
        // being heard as a signal, and the label is shared's to word.
        val tickLabels = armed.cues.filter { it.signal.voice == CueVoice.SYNC }.map { it.signal.label }.toSet()
        assertEquals(5, tickLabels.size)
        val ticks = timeline.filter { (_, label) -> label in tickLabels }.map { it.first }
        assertEquals((5 downTo 1).map { prepMs - it * 1_000L }, ticks)
        assertTrue("nothing sounds before the run-in", timeline.none { it.first < ticks.first() })

        // The press moment: the one cue that must be acted on, exactly at the end of prep.
        val prompt = timeline.filter { (_, label) -> label == "Press the signal box" }
        assertEquals(listOf(prepMs to "Press the signal box"), prompt)

        // Stage 2: the box's alert window. The phone is silent for all 60 s of it, because the box
        // is doing the talking — a tick or a signal here would be the phone competing with the
        // alert it is waiting for.
        val handoverMs = armed.leadInMs
        assertEquals(prepMs + 60_000L, handoverMs)
        assertTrue(
            "stage 2 must be silent, got ${timeline.filter { it.first in (prepMs + 1) until handoverMs }}",
            timeline.none { it.first in (prepMs + 1) until handoverMs },
        )

        // The handover: the sequence's own first signal, at the instant the lead ends.
        assertEquals(handoverMs to "3 long", timeline.first { it.first >= handoverMs })
    }

    @Test
    fun `the handover lands on one anchor, once, with the gun where prep plus alert plus the sequence put it`() {
        runArmedRaceToTheGun()

        // AC 3, as numbers. The run-up and the sequence are one cue list on one gun anchor, so the
        // 3-long fires at exactly `leadInMs` from the tap and the gun at exactly `totalMs` — a
        // lead-in implemented as a separate countdown chained onto the sequence would show a gap
        // here (the second anchor set when the first expired) or a double cue (both anchors firing
        // the boundary they share).
        val handover = timeline.filter { (_, label) -> label == "3 long" }
        assertEquals(listOf(armed.leadInMs to "3 long"), handover)
        assertEquals(armed.totalMs, timeline.last().first)
        assertEquals(base.sequenceMs, armed.sequenceMs)

        // No instant carries two cues, and no cue sounds twice: (time, label) pairs are unique.
        assertEquals(timeline, timeline.distinct())
        assertEquals(timeline.map { it.first }, timeline.map { it.first }.distinct())
    }

    @Test
    fun `the lead-in flag is true through both stages and false from the sequence's first signal`() {
        runner.select(armed)
        runner.start()
        // Stage 1, mid-prep.
        clock.nowMs = 4_000L
        runner.tick()
        assertTrue("in prep", runner.inLeadIn)
        // Stage 2, inside the alert window.
        clock.nowMs = 40_000L
        runner.tick()
        assertTrue("in the alert window", runner.inLeadIn)
        // The tick the 3-long fires on: the lead is over, and so is the flag — this is what brings
        // the Sync control back on the same tick as the sequence's own first signal.
        clock.nowMs = armed.leadInMs
        runner.tick()
        assertFalse("at the handover", runner.inLeadIn)
        assertEquals(armed.sequenceMs, runner.engine.remainingMs)
    }

    @Test
    fun `sync is refused inside the lead-in and accepted once the sequence proper is under way`() {
        runner.select(armed)
        runner.start()

        // 4:07 remaining, 3 s into prep. A nearest-minute snap would land on 4:00 and delete seven
        // seconds of the lead — the engine refuses on its own terms, and this proves the phone's
        // sync route reaches that refusal rather than a re-implementation of it.
        clock.nowMs = 3_000L
        runner.sync()
        assertEquals(armed.totalMs - 3_000L, runner.engine.remainingMs)

        // 2:57 remaining, 3 s into the sequence proper: the same tap now snaps up to 3:00, inside
        // the late-tap window. A refusal that had been implemented as "never during a race with
        // a lead" would fail here.
        clock.nowMs = armed.leadInMs + 3_000L
        runner.sync()
        assertEquals(armed.sequenceMs, runner.engine.remainingMs)
    }

    @Test
    fun `stopping a lead-in race drops the selection back to the base sequence`() {
        runner.select(armed)
        runner.start()
        assertEquals(armed.id, runner.selected.id)

        runner.stop()
        // A lead-in is a per-race choice. After the race the selection is the plain sequence at
        // its own duration, so the next Start runs a clean 3:00 rather than an alert nobody
        // re-chose — the state the watch measured its two-tap picker exists to rule out.
        assertEquals(base.id, runner.selected.id)
        assertEquals(base.totalMs, runner.engine.remainingMs)
    }

    @Test
    fun `stopping an unarmed race leaves its selection exactly as it was`() {
        // The negative control for the drop above: `leadInBaseOf` passes a plain sequence through,
        // so every other race's Stop is byte-for-byte what it was.
        runner.select(BuiltInSequences.usSailing)
        runner.start()
        runner.stop()
        assertEquals(BuiltInSequences.usSailing.id, runner.selected.id)
        assertEquals(BuiltInSequences.usSailing.totalMs, runner.engine.remainingMs)
        assertFalse(runner.inLeadIn)
    }

    @Test
    fun `an unarmed race is never in a lead-in`() {
        runner.select(base)
        runner.start()
        clock.nowMs = 4_000L
        runner.tick()
        assertFalse(runner.inLeadIn)
    }

    @Test
    fun `the press prompt reaches the sounder in its own voice, not as a blast`() {
        runArmedRaceToTheGun()
        // The prompt is the one cue in the run-up that must be acted on, and its voice is what
        // keeps a five-pulse stutter from being heard as `5 short`. The phone's sounder receives
        // the pattern shared built, voice intact — asserted on the object the runner handed over.
        val prompt = armed.cues.single { it.signal.label == "Press the signal box" }.signal
        assertEquals(CueVoice.PROMPT, prompt.voice)
        assertTrue(sounder.events.contains("play:${prompt.label}"))
    }
}
