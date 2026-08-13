package com.racetimer.phone

import com.racetimer.shared.BG_FINAL_TEN_ARGB
import com.racetimer.shared.BG_FINISHED_ARGB
import com.racetimer.shared.BG_NORMAL_ARGB
import com.racetimer.shared.BG_ONE_MINUTE_ARGB
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.MonotonicClock
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A monotonic clock the test moves by hand — the whole reason the engine takes one (#197 AC 2). */
private class FakeClock(var nowMs: Long = 0L) : MonotonicClock {
    override fun elapsedMs(): Long = nowMs
}

/**
 * The screen is a view of the engine, not a second clock.
 *
 * This is the criterion the phone module exists to keep honest: the readout must be a function of
 * where the engine's monotonic anchor says the race is, so that anything which moves that anchor —
 * a sync (#204), a restore (#205), a correction from the paired watch (#220) — moves the screen with
 * it, and so that nothing the *screen* does can move the race.
 *
 * The jumps are what make that provable. A readout counting its own frames survives a steady
 * advance perfectly well and cannot survive a clock that leaps two minutes between polls.
 */
class CountdownFollowsTheEngineTest {

    private val clock = FakeClock()
    private val viewModel = PhoneTimerViewModel(clock)

    private fun advanceTo(elapsedMs: Long) {
        clock.nowMs = elapsedMs
        viewModel.tick()
    }

    @Test
    fun `the console offers exactly the three sailor sequences`() {
        // The race-manager variants are BuiltInSequences.all's other two entries and are absent by
        // design: their defining behaviour is the count-up after the gun, which is #206.
        assertEquals(
            listOf("US Sailing 5-4-1-Go", "Scholastic (ICSA)", "Club 3-2-1-Go"),
            viewModel.sequences.map { it.name },
        )
        assertTrue(viewModel.sequences.none { it.countUpAfterFinish })
    }

    @Test
    fun `an unstarted race shows the selected sequence's full duration`() {
        assertEquals("5:00", viewModel.readout().text)

        viewModel.select(BuiltInSequences.club)
        assertEquals("3:00", viewModel.readout().text)
        assertEquals(TimerState.IDLE, viewModel.engine.currentState)
    }

    @Test
    fun `the readout tracks the engine's anchor through a whole race`() {
        viewModel.start()
        assertEquals("5:00", viewModel.readout().text)

        advanceTo(30_000L)
        assertEquals("4:30", viewModel.readout().text)
        assertEquals(BG_NORMAL_ARGB, viewModel.readout().backgroundArgb)

        // A two-and-a-half-minute leap between polls. A screen doing its own arithmetic reads 4:29.
        advanceTo(180_000L)
        assertEquals("2:00", viewModel.readout().text)

        advanceTo(250_000L)
        assertEquals("0:50", viewModel.readout().text)
        assertEquals(BG_ONE_MINUTE_ARGB, viewModel.readout().backgroundArgb)

        advanceTo(291_000L)
        assertEquals("0:09", viewModel.readout().text)
        assertEquals(BG_FINAL_TEN_ARGB, viewModel.readout().backgroundArgb)

        advanceTo(300_000L)
        assertEquals(TimerState.FINISHED, viewModel.engine.currentState)
        assertEquals("GO!", viewModel.readout().text)
        assertEquals(BG_FINISHED_ARGB, viewModel.readout().backgroundArgb)
    }

    @Test
    fun `a sub-second poll never claims a second the sailor still has`() {
        viewModel.start()
        // 20 ms into the race: 4:59.98 remains, and a sailor who has not yet lost a whole second
        // must not be shown 4:59. The countdown rounds up; this is the rule cue timing rests on.
        advanceTo(20L)
        assertEquals("5:00", viewModel.readout().text)

        advanceTo(999L)
        assertEquals("5:00", viewModel.readout().text)

        // The whole second is gone now, and only now.
        advanceTo(1_000L)
        assertEquals("4:59", viewModel.readout().text)
    }

    @Test
    fun `stop returns the screen to the top of the same sequence`() {
        viewModel.select(BuiltInSequences.scholastic)
        viewModel.start()
        advanceTo(60_000L)
        assertEquals("2:00", viewModel.readout().text)

        viewModel.stop()
        assertEquals(TimerState.IDLE, viewModel.engine.currentState)
        assertEquals("3:00", viewModel.readout().text)
        assertEquals(BG_NORMAL_ARGB, viewModel.readout().backgroundArgb)
    }

    @Test
    fun `stop after the gun returns the screen to the top too`() {
        // Deliberately a SECOND stop case, and the fixture is the whole point: stopping mid-race
        // reads correctly whether or not the sequence is reloaded, because `TimerEngine.stop` resets
        // its own remaining time on the way out of RUNNING. From FINISHED it does not — `stop` is a
        // no-op there — so this is the only fixture on which the reload is load-bearing. Found by
        // mutation: dropping the reload reddened nothing at all with only the mid-race case here.
        viewModel.start()
        advanceTo(300_000L)
        assertEquals(TimerState.FINISHED, viewModel.engine.currentState)
        assertEquals("GO!", viewModel.readout().text)

        viewModel.stop()
        assertEquals(TimerState.IDLE, viewModel.engine.currentState)
        assertEquals("5:00", viewModel.readout().text)
        assertEquals(BG_NORMAL_ARGB, viewModel.readout().backgroundArgb)
    }

    @Test
    fun `the engine is anchored to the injected clock and not to wall time`() {
        viewModel.start()
        // The clock never moves, so neither does the race — however long the test takes to run.
        Thread.sleep(30L)
        viewModel.tick()
        assertEquals("5:00", viewModel.readout().text)
        assertEquals(TimerState.RUNNING, viewModel.engine.currentState)
    }
}
