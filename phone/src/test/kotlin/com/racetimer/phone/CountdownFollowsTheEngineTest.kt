package com.racetimer.phone

import com.racetimer.shared.BG_FINAL_TEN_ARGB
import com.racetimer.shared.BG_FINISHED_ARGB
import com.racetimer.shared.BG_NORMAL_ARGB
import com.racetimer.shared.BG_ONE_MINUTE_ARGB
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.MonotonicClock
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
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
    private val runner = PhoneRaceRunner(clock)

    private fun advanceTo(elapsedMs: Long) {
        clock.nowMs = elapsedMs
        runner.tick()
    }

    @Test
    fun `the console offers every shared sequence, the race-manager pair included`() {
        // Held at the three sailor sequences until #206, because a race-manager mode listed before
        // its post-gun half existed would have ended the race at the gun. That half is built, so
        // the console offers the full set — and the assertion is against `all` rather than against
        // five names written out here, so a sequence added to shared is offered on the phone
        // without this test having to be told about it.
        assertEquals(BuiltInSequences.all.map { it.name }, runner.sequences.map { it.name })
        // The half a comparison against `all` cannot make: that the pair actually arrived, named.
        // Without it this passes just as well on the day `all` itself loses the race-manager modes.
        assertEquals(
            listOf("US Sailing - Race Manager", "Scholastic - Race Manager"),
            runner.sequences.filter { it.countUpAfterFinish }.map { it.name },
        )
    }

    @Test
    fun `an unstarted race shows the selected sequence's full duration`() {
        assertEquals("5:00", runner.readout().text)

        runner.select(BuiltInSequences.club)
        assertEquals("3:00", runner.readout().text)
        assertEquals(TimerState.IDLE, runner.engine.currentState)
    }

    @Test
    fun `the readout tracks the engine's anchor through a whole race`() {
        runner.start()
        assertEquals("5:00", runner.readout().text)

        advanceTo(30_000L)
        assertEquals("4:30", runner.readout().text)
        assertEquals(BG_NORMAL_ARGB, runner.readout().backgroundArgb)

        // A two-and-a-half-minute leap between polls. A screen doing its own arithmetic reads 4:29.
        advanceTo(180_000L)
        assertEquals("2:00", runner.readout().text)

        advanceTo(250_000L)
        assertEquals("0:50", runner.readout().text)
        assertEquals(BG_ONE_MINUTE_ARGB, runner.readout().backgroundArgb)

        advanceTo(291_000L)
        assertEquals("0:09", runner.readout().text)
        assertEquals(BG_FINAL_TEN_ARGB, runner.readout().backgroundArgb)

        advanceTo(300_000L)
        assertEquals(TimerState.FINISHED, runner.engine.currentState)
        assertEquals("GO!", runner.readout().text)
        assertEquals(BG_FINISHED_ARGB, runner.readout().backgroundArgb)
    }

    @Test
    fun `a sub-second poll never claims a second the sailor still has`() {
        runner.start()
        // 20 ms into the race: 4:59.98 remains, and a sailor who has not yet lost a whole second
        // must not be shown 4:59. The countdown rounds up; this is the rule cue timing rests on.
        advanceTo(20L)
        assertEquals("5:00", runner.readout().text)

        advanceTo(999L)
        assertEquals("5:00", runner.readout().text)

        // The whole second is gone now, and only now.
        advanceTo(1_000L)
        assertEquals("4:59", runner.readout().text)
    }

    @Test
    fun `stop returns the screen to the top of the same sequence`() {
        runner.select(BuiltInSequences.scholastic)
        runner.start()
        advanceTo(60_000L)
        assertEquals("2:00", runner.readout().text)

        runner.stop()
        assertEquals(TimerState.IDLE, runner.engine.currentState)
        assertEquals("3:00", runner.readout().text)
        assertEquals(BG_NORMAL_ARGB, runner.readout().backgroundArgb)
    }

    @Test
    fun `stop after the gun returns the screen to the top too`() {
        // Deliberately a SECOND stop case, and the fixture is the whole point: stopping mid-race
        // reads correctly whether or not the sequence is reloaded, because `TimerEngine.stop` resets
        // its own remaining time on the way out of RUNNING. From FINISHED it does not — `stop` is a
        // no-op there — so this is the only fixture on which the reload is load-bearing. Found by
        // mutation: dropping the reload reddened nothing at all with only the mid-race case here.
        runner.start()
        advanceTo(300_000L)
        assertEquals(TimerState.FINISHED, runner.engine.currentState)
        assertEquals("GO!", runner.readout().text)

        runner.stop()
        assertEquals(TimerState.IDLE, runner.engine.currentState)
        assertEquals("5:00", runner.readout().text)
        assertEquals(BG_NORMAL_ARGB, runner.readout().backgroundArgb)
    }

    @Test
    fun `the engine is anchored to the injected clock and not to wall time`() {
        runner.start()
        // The clock never moves, so neither does the race — however long the test takes to run.
        Thread.sleep(30L)
        runner.tick()
        assertEquals("5:00", runner.readout().text)
        assertEquals(TimerState.RUNNING, runner.engine.currentState)
    }
}
