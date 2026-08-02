package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [formatCountdown] and, most importantly, for the invariant that ties it to the engine:
 * the display must read the cue's own time at the instant the cue fires.
 */
class CountdownFormatTest {

    // --- Rounding rule --------------------------------------------------------

    @Test fun `whole seconds format exactly`() {
        assertEquals("5:00", formatCountdown(300_000L))
        assertEquals("1:00", formatCountdown(60_000L))
        assertEquals("0:30", formatCountdown(30_000L))
        assertEquals("0:01", formatCountdown(1_000L))
    }

    @Test fun `partial seconds round up, so the display never claims time the sailor still has`() {
        assertEquals("1:00", formatCountdown(59_999L))
        assertEquals("1:00", formatCountdown(59_001L))
        assertEquals("0:59", formatCountdown(59_000L))
        assertEquals("0:59", formatCountdown(58_001L))
        assertEquals("0:01", formatCountdown(1L))
    }

    @Test fun `zero and post-gun times format as zero`() {
        assertEquals("0:00", formatCountdown(0L))
        assertEquals("0:00", formatCountdown(-1L))
        assertEquals("0:00", formatCountdown(-5_000L))
    }

    @Test fun `minutes roll over correctly`() {
        assertEquals("2:00", formatCountdown(120_000L))
        assertEquals("1:59", formatCountdown(119_000L))
        assertEquals("10:00", formatCountdown(600_000L))
    }

    // --- Regression: issue #36, feedback triggered a second late ---------------

    /**
     * Drives the engine with a realistic 50 ms tick loop whose ticks are deliberately *not* aligned
     * to the cue boundary (a real Handler loop never is) and asserts the countdown text shown when
     * each cue fires. Flooring the display, the one-minute horn landed on "0:59".
     */
    @Test fun `each cue fires while the display still reads the cue's own time`() {
        var now = 0L
        val engine = TimerEngine(MonotonicClock { now })
        val firedAt = mutableMapOf<Long, String>()

        engine.addListener(object : TimerListener {
            override fun onCue(cue: SequenceCue) { firedAt[cue.offsetMs] = formatCountdown(engine.remainingMs) }
            override fun onGun() {}
            override fun onTick(remainingMs: Long) {}
            override fun onSync(snappedToMs: Long) {}
        })

        engine.load(BuiltInSequences.usSailing)
        engine.start()

        // Phase the loop off the boundary so cue times land mid-tick, as they do on the watch.
        now = 20L
        while (engine.currentState == TimerState.RUNNING) {
            engine.tick()
            now += 50L
        }

        assertEquals("4:00", firedAt[4 * 60_000L])
        assertEquals("1:00", firedAt[1 * 60_000L])
        assertEquals("0:00", firedAt[0L])
    }

    /**
     * The same invariant at second granularity, over the scholastic sequence's dense final cues
     * (0:30, 0:20, 0:10 and the 5..1 ticks) where an off-by-one-second display is most obvious.
     */
    @Test fun `short cues also fire on their own displayed second`() {
        var now = 0L
        val engine = TimerEngine(MonotonicClock { now })
        val firedAt = mutableMapOf<Long, String>()

        engine.addListener(object : TimerListener {
            override fun onCue(cue: SequenceCue) { firedAt[cue.offsetMs] = formatCountdown(engine.remainingMs) }
            override fun onGun() {}
            override fun onTick(remainingMs: Long) {}
            override fun onSync(snappedToMs: Long) {}
        })

        engine.load(BuiltInSequences.scholastic)
        engine.start()

        now = 37L
        while (engine.currentState == TimerState.RUNNING) {
            engine.tick()
            now += 50L
        }

        assertEquals("1:30", firedAt[90_000L])
        assertEquals("0:30", firedAt[30_000L])
        assertEquals("0:10", firedAt[10_000L])
        assertEquals("0:05", firedAt[5_000L])
        assertEquals("0:01", firedAt[1_000L])
    }

    // --- formatElapsed ----------------------------------------------------------

    @Test fun `elapsed whole seconds format exactly`() {
        assertEquals("0:00", formatElapsed(0L))
        assertEquals("0:01", formatElapsed(1_000L))
        assertEquals("0:30", formatElapsed(30_000L))
        assertEquals("1:00", formatElapsed(60_000L))
    }

    @Test fun `partial seconds round down, the opposite of formatCountdown`() {
        // A stopwatch must not claim a second that has not fully elapsed yet.
        assertEquals("0:59", formatElapsed(59_999L))
        assertEquals("0:00", formatElapsed(999L))
        assertEquals("1:00", formatElapsed(60_001L))
    }

    @Test fun `non-positive elapsed formats as zero`() {
        assertEquals("0:00", formatElapsed(-1L))
        assertEquals("0:00", formatElapsed(-5_000L))
    }

    @Test fun `minutes roll over to the next hour digit only once an hour has passed`() {
        assertEquals("59:59", formatElapsed(59 * 60_000L + 59_000L))
        assertEquals("1:00:00", formatElapsed(60 * 60_000L))
        assertEquals("1:00:01", formatElapsed(60 * 60_000L + 1_000L))
    }

    @Test fun `hours accumulate past the first`() {
        assertEquals("2:05:09", formatElapsed(2 * 3_600_000L + 5 * 60_000L + 9_000L))
    }
}
