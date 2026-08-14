package com.racetimer.phone

import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.MonotonicClock
import com.racetimer.shared.SignalPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A monotonic clock the test moves by hand. Its own name rather than `FakeClock` because a
 * file-private top-level class still collides across files in one Kotlin package.
 */
private class SteppedClock(var nowMs: Long = 0L) : MonotonicClock {
    override fun elapsedMs(): Long = nowMs
}

/**
 * Records what the ViewModel asked of the audio path, in order, so ordering claims — prepared
 * before the first cue, warmed up on selection — are asserted as positive evidence rather than
 * inferred from silence.
 */
private class RecordingSounder : CueSounder {
    val events = mutableListOf<String>()
    val played = mutableListOf<String>()

    override fun prepare() {
        events += "prepare"
    }

    override fun warmUp(patterns: List<SignalPattern>) {
        events += "warmUp:${patterns.size}"
    }

    override fun playCue(pattern: SignalPattern) {
        events += "play:${pattern.label}"
        played += pattern.label
    }

    override fun release() {
        events += "release"
    }
}

/**
 * Holds the single armed dispatch the way [HandlerCueScheduler] does — arming replaces, cancel
 * disarms — but fires only when the test says so, which is what lets a race be driven boundary by
 * boundary with no looper.
 */
private class RecordingScheduler : CueScheduler {
    var armedDelayMs: Long? = null
    var armedAction: Runnable? = null

    override fun armIn(delayMs: Long, action: Runnable) {
        armedDelayMs = delayMs
        armedAction = action
    }

    override fun cancel() {
        armedDelayMs = null
        armedAction = null
    }

    /** Run the armed dispatch the way the looper would — consuming it. */
    fun fire() {
        val action = armedAction ?: throw AssertionError("nothing armed")
        cancel()
        action.run()
    }
}

/**
 * The cue path is scheduled against the engine's anchor, never polled (#202 AC 1).
 *
 * The deliberate absence in every test here: `viewModel.tick()` — the display poll — is never
 * called unless the test is *about* the poll. A cue path that only works when the display loop
 * happens to run is the defect, and these tests cannot pass on one.
 */
class CueDispatchIsScheduledTest {

    private val clock = SteppedClock()
    private val sounder = RecordingSounder()
    private val scheduler = RecordingScheduler()
    private val viewModel = PhoneTimerViewModel(clock, sounder, scheduler)

    /** Advance the clock to the armed boundary and let the dispatch fire, returning what played. */
    private fun fireNextBoundary() {
        val delay = scheduler.armedDelayMs ?: throw AssertionError("nothing armed")
        clock.nowMs += delay
        scheduler.fire()
    }

    @Test
    fun `every cue of a race lands through the scheduler with the display poll never running`() {
        viewModel.select(BuiltInSequences.scholastic)
        viewModel.start()

        // The cue due at the anchor instant fired synchronously inside start() itself — the #62
        // lesson: leaving it to any later pass measurably delays it.
        val expected = BuiltInSequences.scholastic.cues.map { it.signal.label }
        assertEquals(expected.take(1), sounder.played)

        // Drive the whole race off the scheduler alone. No tick(), no poll — if the playback path
        // has any reliance on the display loop, this loop starves and the assertion below fails.
        // Capped well above any sequence's cue count so a scheduling regression that re-arms
        // without advancing (measured under mutation: a halved delay integer-divides to zero and
        // the boundary never arrives) fails here with a message instead of hanging the suite.
        var boundariesLeft = 1_000
        while (scheduler.armedAction != null) {
            assertTrue("dispatch loop did not converge", boundariesLeft-- > 0)
            fireNextBoundary()
        }

        assertEquals(expected, sounder.played)
        // The race is over: nothing may be left armed to fire into the silence after the gun.
        assertNull(scheduler.armedDelayMs)
    }

    @Test
    fun `each dispatch is armed at the next cue's own boundary, not at a poll interval`() {
        viewModel.select(BuiltInSequences.scholastic)
        viewModel.start()

        // scholastic opens 3:00 (fires at start), then 2:00 — the first armed dispatch must sit
        // exactly on that boundary, 60 s out, not on any rounding of it.
        assertEquals(60_000L, scheduler.armedDelayMs)

        fireNextBoundary()
        // 2:00 fired; next is 1:30, 30 s later.
        assertEquals(30_000L, scheduler.armedDelayMs)
    }

    @Test
    fun `the track is prepared and the cues rendered before the first cue can sound`() {
        // Construction already prepared the track and warmed the default sequence (#114, #98) —
        // before any race exists, which is the only ordering that keeps startOutput off a deadline.
        val firstPlay = sounder.events.indexOfFirst { it.startsWith("play:") }
        assertTrue(sounder.events.indexOf("prepare") >= 0)
        assertTrue(firstPlay == -1)

        viewModel.start()
        // start() re-prepares (the twice-called pattern) and only then fires the due cue.
        val events = sounder.events
        val lastPrepare = events.lastIndexOf("prepare")
        val play = events.indexOfFirst { it.startsWith("play:") }
        assertTrue("prepare must precede the first cue: $events", lastPrepare in 0 until play)
    }

    @Test
    fun `selecting a sequence warms its cues ahead of the race`() {
        sounder.events.clear()
        viewModel.select(BuiltInSequences.club)
        assertEquals(
            listOf("warmUp:${BuiltInSequences.club.cues.size}"),
            sounder.events,
        )
    }

    @Test
    fun `the display poll is a backstop that cannot double-fire a cue`() {
        viewModel.select(BuiltInSequences.scholastic)
        viewModel.start()

        // Let a boundary slip past unserviced — the scheduler wake-up never runs — and have the
        // display poll find it, which is the backstop's whole job.
        clock.nowMs += 60_000L
        viewModel.tick()
        assertEquals(2, sounder.played.size)

        // The poll re-armed the dispatch for the *next* cue. Firing it must not replay anything.
        assertNotNull(scheduler.armedAction)
        fireNextBoundary()
        assertEquals(3, sounder.played.size)
        assertEquals(sounder.played, sounder.played.distinct())
    }

    @Test
    fun `stop disarms the pending dispatch and no cue fires out of an ended race`() {
        viewModel.select(BuiltInSequences.scholastic)
        viewModel.start()
        assertNotNull(scheduler.armedAction)

        viewModel.stop()
        assertNull(scheduler.armedAction)

        // Time passing after the stop changes nothing — there is nothing armed to fire, and the
        // played list stays exactly the one cue the start dispatched.
        clock.nowMs += 10 * 60_000L
        assertEquals(1, sounder.played.size)
    }

    @Test
    fun `clearing the ViewModel releases the audio path`() {
        // onCleared is protected; ViewModelStore teardown is what calls it in production. Reaching
        // through the store keeps the test on the public surface.
        val store = androidx.lifecycle.ViewModelStore()
        val provider = androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return PhoneTimerViewModel(clock, sounder, scheduler) as T
                }
            },
        )
        provider[PhoneTimerViewModel::class.java]
        store.clear()
        assertTrue(sounder.events.contains("release"))
    }
}
