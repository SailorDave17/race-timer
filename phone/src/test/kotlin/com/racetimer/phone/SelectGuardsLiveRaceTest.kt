package com.racetimer.phone

import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.MonotonicClock
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A monotonic clock the test moves by hand. Named for this file — see `CueDispatchIsScheduledTest`. */
private class GuardClock(var nowMs: Long = 0L) : MonotonicClock {
    override fun elapsedMs(): Long = nowMs
}

/** Records whether a cue dispatch is currently armed, so a disarm can be asserted rather than assumed. */
private class ArmRecordingScheduler : CueScheduler {
    var armed: Runnable? = null
        private set

    override fun armIn(delayMs: Long, action: Runnable) {
        armed = action
    }

    override fun cancel() {
        armed = null
    }
}

/**
 * `PhoneRaceRunner.select` refuses to discard a race in progress, and `endRaceAndSelect` is the one
 * way past it (#281 AC 4).
 *
 * ### What this is guarding
 *
 * `TimerEngine.load` sets the engine IDLE unconditionally, so selecting a sequence is byte-for-byte
 * the same call as destroying the race that is running. #281 measured what that cost on hardware:
 * after a recreated activity the officer's only obvious tap — the sequence already running, in the
 * picker — killed the live race and its cue queue silently.
 *
 * ### Why the guard is here and not in `TimerEngine`
 *
 * The watch loads through the same call in `TimerService`'s ACTION_START handler, twice. It is the
 * product on Play, with its cue delivery re-verified on hardware in #201, and #281 is a defect in
 * what the phone's UI does with the engine rather than in the engine. #281 AC 4 names the callers
 * as a legitimate home for exactly this reason.
 *
 * ### The four states, and why FINISHED and RACE_ENDED are not guarded
 *
 * Only RUNNING and COUNTING_UP are races that can still be lost. FINISHED is a countdown that has
 * fired its gun and RACE_ENDED is a frozen summary — both are things to read, and refusing a
 * selection on them would make the officer stop a finished race before picking the next one, which
 * is a worse product for no protection. Those two are asserted here as positive controls: without
 * them, a guard that refused every non-IDLE state would pass every other test in this class.
 */
class SelectGuardsLiveRaceTest {

    private val clock = GuardClock()
    private val scheduler = ArmRecordingScheduler()
    private val runner = PhoneRaceRunner(clock, cueScheduler = scheduler)

    private val club = BuiltInSequences.club
    private val usSailing = BuiltInSequences.usSailing
    private val raceManager = BuiltInSequences.scholasticRaceManager

    /** Put a countdown on the clock and confirm it really is running before anything is claimed. */
    private fun startRunning(sequence: com.racetimer.shared.RaceSequence = usSailing) {
        assertTrue("the arrangement's own select was refused", runner.select(sequence))
        runner.start()
        assertEquals(TimerState.RUNNING, runner.engine.currentState)
    }

    /** Run [sequence] past its gun, so the engine settles into whatever it does after one. */
    private fun runPastTheGun(sequence: com.racetimer.shared.RaceSequence) {
        clock.nowMs += sequence.totalMs + 4_000L
        runner.tick()
    }

    @Test
    fun `select is refused while a countdown is running, and nothing moves`() {
        startRunning()
        val remainingBefore = runner.engine.remainingMs

        assertFalse("select was taken on a running race", runner.select(club))

        assertEquals("the selection changed", usSailing, runner.selected)
        assertEquals("the race was ended", TimerState.RUNNING, runner.engine.currentState)
        assertEquals("the countdown moved", remainingBefore, runner.engine.remainingMs)
        assertEquals(
            "the loaded sequence changed under the running race",
            usSailing,
            runner.engine.loadedSequence,
        )
    }

    @Test
    fun `select is refused while a race-manager count-up is running`() {
        assertTrue(runner.select(raceManager))
        runner.start()
        runPastTheGun(raceManager)
        assertEquals(TimerState.COUNTING_UP, runner.engine.currentState)

        assertFalse("select was taken on a count-up", runner.select(club))

        assertEquals(raceManager, runner.selected)
        assertEquals(TimerState.COUNTING_UP, runner.engine.currentState)
    }

    @Test
    fun `select is taken on an idle engine, which is every ordinary pick`() {
        assertTrue("select was refused before any race started", runner.select(club))

        assertEquals(club, runner.selected)
        assertEquals(TimerState.IDLE, runner.engine.currentState)
        // Loading is what puts the new sequence's full duration on the idle screen.
        assertEquals(club.totalMs, runner.engine.remainingMs)
    }

    @Test
    fun `select is taken on a finished countdown — a fired gun is not a race to protect`() {
        startRunning(usSailing)
        runPastTheGun(usSailing)
        assertEquals(
            "the arrangement did not reach FINISHED",
            TimerState.FINISHED,
            runner.engine.currentState,
        )

        assertTrue("select was refused on a finished race", runner.select(club))
        assertEquals(club, runner.selected)
    }

    @Test
    fun `select is taken on a frozen race-ended summary`() {
        assertTrue(runner.select(raceManager))
        runner.start()
        runPastTheGun(raceManager)
        runner.endRace()
        assertEquals(
            "the arrangement did not reach RACE_ENDED",
            TimerState.RACE_ENDED,
            runner.engine.currentState,
        )

        assertTrue("select was refused on a summary", runner.select(club))
        assertEquals(club, runner.selected)
    }

    @Test
    fun `endRaceAndSelect abandons the running race and takes the selection`() {
        startRunning()

        runner.endRaceAndSelect(club)

        assertEquals("the new sequence was not selected", club, runner.selected)
        assertEquals("the abandoned race is still live", TimerState.IDLE, runner.engine.currentState)
        // The idle screen shows the new sequence's full duration, not the abandoned race's position.
        assertEquals(club.totalMs, runner.engine.remainingMs)
    }

    @Test
    fun `endRaceAndSelect disarms the abandoned race's pending cue`() {
        startRunning()
        // Positive control on the arrangement: a running race really does have a dispatch armed, so
        // the assertion below is about the disarm rather than about a scheduler nothing ever used.
        assertTrue("no cue was armed by the running race", scheduler.armed != null)

        runner.endRaceAndSelect(club)

        assertEquals(
            "a cue from the abandoned race was left armed and would sound into the next one",
            null,
            scheduler.armed,
        )
    }

    @Test
    fun `a select refused by the guard leaves the running race's cue armed`() {
        startRunning()
        val armedBefore = scheduler.armed

        runner.select(club)

        assertEquals(
            "the refused select disarmed the running race's next cue",
            armedBefore,
            scheduler.armed,
        )
    }
}
