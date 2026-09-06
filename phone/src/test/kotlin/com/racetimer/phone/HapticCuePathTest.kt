package com.racetimer.phone

import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.CueVoice
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.withLeadIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #208 AC 1: every cue's voice reaches the haptic path as shared's own pattern, gun flag where
 * shared put it, and the buzz is asked for before the tone on every cue.
 *
 * Pure JVM, off the same seams as [CueDispatchIsScheduledTest]: a recording buzzer and a recording
 * sounder share one event log, and a scheduler the test fires by hand drives a whole race with the
 * display poll never running. What a voice FEELS like — the strength a blast buzzes at, the
 * lightness of a sync tick, the prompt's stutter, the gun's triple — is decided once in
 * `:shared-android` from `CueTiming` and measured on a wrist (#144, #187, #201); nothing a JVM can
 * hold stands in for it, and no test here pretends to. What the phone owes, and what this file
 * proves, is that it hands those definitions over **unmodified**: the expected values below are
 * read off `RaceSequence.cues` — shared's — and never re-declared here, which is the criterion's
 * own wording and the rule `ModuleBoundaryTest` enforces on the main sources.
 */
class HapticCuePathTest {

    private val clock = SteppedClock()
    private val log = mutableListOf<String>()
    private val sounder = RecordingSounder(log)
    private val buzzer = RecordingBuzzer(log)
    private val scheduler = RecordingScheduler()
    private val runner = PhoneRaceRunner(clock, sounder, scheduler, cueBuzzer = buzzer)

    /** Select [sequence], start it, and drive it to the gun off the scheduler alone. */
    private fun runToTheGun(sequence: RaceSequence) {
        runner.select(sequence)
        runner.start()
        var boundariesLeft = 1_000
        while (scheduler.armedAction != null) {
            assertTrue("dispatch loop did not converge", boundariesLeft-- > 0)
            val delay = scheduler.armedDelayMs ?: throw AssertionError("nothing armed")
            clock.nowMs += delay
            scheduler.fire()
        }
    }

    @Test
    fun `every cue reaches the buzzer as shared's own pattern, with the gun flag where shared put it`() {
        val sequence = BuiltInSequences.scholastic
        runToTheGun(sequence)

        // The whole cue list, pattern object and gun flag alike, in shared's order. A phone that
        // re-derived a pattern — or buzzed every cue with the same one — diverges here.
        assertEquals(sequence.cues.map { it.signal to it.isGun }, buzzer.buzzed)

        // Positive control on the flag: the fixture must carry exactly one gun, or an `isGun`
        // that was silently always-false would match a list that was also always-false.
        assertEquals(1, buzzer.buzzed.count { (_, isGun) -> isGun })
        assertTrue("the gun flag rides the last cue", buzzer.buzzed.last().second)
    }

    @Test
    fun `all three voices reach the buzzer with their voice intact`() {
        // The armed race-manager sequence is the one fixture that carries every voice: blasts,
        // the five sync ticks into the press moment, and the prompt itself (#207).
        val armed = withLeadIn(BuiltInSequences.scholasticRaceManager, 60)
            ?: error("the race-manager sequence must arm, or nothing below is testing anything")
        // Positive control on the fixture, so the assertion after it cannot pass on a sequence
        // that never contained a voice to lose.
        assertEquals(CueVoice.entries.toSet(), armed.cues.map { it.signal.voice }.toSet())

        runToTheGun(armed)

        assertEquals(armed.cues.map { it.signal }, buzzer.buzzed.map { (pattern, _) -> pattern })
        assertEquals(CueVoice.entries.toSet(), buzzer.buzzed.map { (pattern, _) -> pattern.voice }.toSet())
    }

    @Test
    fun `the buzz is asked for before the tone, on every cue`() {
        val sequence = BuiltInSequences.scholastic
        runToTheGun(sequence)

        // One interleaved log, read as pairs: for each cue, its buzz then its play, and nothing
        // of either kind between them. A runner that sounded first, or buzzed on a different
        // callback from the one that sounds, breaks the pairing rather than merely reordering it.
        val cueEvents = log.filter { it.startsWith("buzz:") || it.startsWith("play:") }
        val expected = sequence.cues.flatMap { listOf("buzz:${it.signal.label}", "play:${it.signal.label}") }
        assertEquals(expected, cueEvents)
    }

    @Test
    fun `the display poll backstop buzzes a cue once, exactly as it sounds it once`() {
        runner.select(BuiltInSequences.scholastic)
        runner.start()

        // A boundary slips past unserviced and the poll finds it (the backstop's job), then the
        // re-armed dispatch fires the next one. Neither path may buzz what the other already did.
        clock.nowMs += 60_000L
        runner.tick()
        val delay = scheduler.armedDelayMs ?: throw AssertionError("nothing armed")
        clock.nowMs += delay
        scheduler.fire()

        val labels = buzzer.buzzed.map { (pattern, _) -> pattern.label }
        assertEquals(sounder.played, labels)
        assertEquals(labels, labels.distinct())
        assertEquals(3, labels.size)
    }

    @Test
    fun `releasing the runner cancels the buzzer`() {
        runner.select(BuiltInSequences.scholastic)
        runner.start()

        runner.release()
        // The service calls this from onDestroy; a gun buzz in flight must not outlive its owner.
        assertTrue("release must cancel the buzzer: $log", log.contains("buzz-cancel"))
        assertTrue(
            "the cancel comes after the last buzz, not before it: $log",
            log.lastIndexOf("buzz-cancel") > log.lastIndexOf(log.last { it.startsWith("buzz:") }),
        )
    }
}
