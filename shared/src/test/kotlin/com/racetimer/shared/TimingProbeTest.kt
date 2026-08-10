package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [TimingProbe], the measurement half of #126.
 *
 * Two of these are arithmetic and read as trivial. They are here because both numbers have a sign
 * that is easy to invert and no outward symptom when it is — a dispatch error reported with the
 * wrong sign reads as a cue firing *early*, which nobody would think to disbelieve — and because the
 * comparable rule that was left inline at its call site (#98's audio-track reset) was wrong there for
 * two weeks while every call reported success.
 *
 * The one that carries real weight is `the identity holds against a real engine run`: it proves the
 * substitution in [TimingProbe.dispatchErrorMs]'s KDoc against [TimerEngine] itself rather than
 * against a restatement of the same algebra, so the two cannot drift apart if the engine ever changes
 * how it anchors the gun.
 */
class TimingProbeTest {

    private fun engineAt(nowSource: () -> Long) =
        TimerEngine(MonotonicClock { nowSource() }, WallClock { 1_000_000L })

    // --- dispatchErrorMs --------------------------------------------------------

    @Test fun `a cue that fires exactly on its boundary has zero error`() {
        // The 3-long at 3:00 of a Scholastic race, dispatched with remaining exactly 3:00.
        assertEquals(0L, TimingProbe.dispatchErrorMs(cueOffsetMs = 180_000L, remainingMs = 180_000L))
    }

    @Test fun `a late cue reports a positive error`() {
        // Dispatch happened 47 ms after the boundary, so 47 ms less remains than the offset.
        assertEquals(47L, TimingProbe.dispatchErrorMs(cueOffsetMs = 180_000L, remainingMs = 179_953L))
    }

    @Test fun `an early cue reports a negative error`() {
        // Cannot happen through the scheduler, but the sign has to be unambiguous either way: a
        // clamped-at-zero reading would hide a re-anchoring bug rather than report it.
        assertEquals(-12L, TimingProbe.dispatchErrorMs(cueOffsetMs = 180_000L, remainingMs = 180_012L))
    }

    @Test fun `the gun's error is measured the same way as any other cue`() {
        // The gun sits at offset 0, where the arithmetic is just the negated remaining — the one
        // case where an implementation that special-cased the gun would still look right on the
        // others.
        assertEquals(31L, TimingProbe.dispatchErrorMs(cueOffsetMs = 0L, remainingMs = -31L))
    }

    @Test fun `a four-second deferral is reported at its full size`() {
        // The #126 scenario: a suspended CPU defers the scheduled wake-up. Nothing in the arithmetic
        // saturates, so a doze-sized miss is reported as a doze-sized miss.
        assertEquals(
            4_318L,
            TimingProbe.dispatchErrorMs(cueOffsetMs = 60_000L, remainingMs = 55_682L),
        )
    }

    @Test fun `the identity holds against a real engine run`() {
        // The claim under test is the substitution `error = offsetMs - remainingMs`, which drops
        // TimerEngine's private gun anchor out of the expression. Proving it against the engine
        // rather than against the same algebra written twice is the whole point: if the engine ever
        // changes how it anchors, this fails and the KDoc stops being a promise nothing checks.
        var fakeNow = 0L
        val engine = engineAt { fakeNow }

        val observed = mutableListOf<Pair<Long, Long>>() // offsetMs to remainingMs at dispatch
        engine.addListener(object : TimerListener {
            override fun onCue(cue: SequenceCue) { observed += cue.offsetMs to engine.remainingMs }
            override fun onGun() {}
            override fun onTick(remainingMs: Long) {}
            override fun onSync(snappedToMs: Long) {}
        })

        val sequence = BuiltInSequences.scholastic
        engine.load(sequence)
        engine.start()

        // Walk the race in coarse, deliberately uneven steps so cues are found late by varying
        // amounts, which is what a poll on a contended looper actually does.
        val gunAt = sequence.totalMs
        var step = 0
        while (fakeNow <= gunAt) {
            fakeNow += if (step++ % 3 == 0) 137L else 91L
            engine.tick()
        }

        assertTrue("no cues fired; the walk never reached them", observed.isNotEmpty())
        for ((offsetMs, remainingAtDispatch) in observed) {
            // What the engine's own firing rule says the error was: dispatch time minus boundary.
            // Reconstructed here by the long route through the gun anchor, rather than the short one
            // the probe uses.
            val dispatchedAtMs = gunAt - remainingAtDispatch
            val boundaryMs = gunAt - offsetMs
            assertEquals(
                "cue at offset $offsetMs",
                dispatchedAtMs - boundaryMs,
                TimingProbe.dispatchErrorMs(offsetMs, remainingAtDispatch),
            )
        }
    }

    @Test fun `every cue of a real run is reported late, never early`() {
        // A sign inversion in dispatchErrorMs passes every fixed-value test above if the fixtures are
        // read back with the same inversion. This one cannot be satisfied that way: a polled walk can
        // only ever find a cue at or after its boundary, so the sign is pinned by the behaviour of
        // the walk rather than by a number written down here.
        var fakeNow = 0L
        val engine = engineAt { fakeNow }

        val errors = mutableListOf<Long>()
        engine.addListener(object : TimerListener {
            override fun onCue(cue: SequenceCue) {
                errors += TimingProbe.dispatchErrorMs(cue.offsetMs, engine.remainingMs)
            }
            override fun onGun() {}
            override fun onTick(remainingMs: Long) {}
            override fun onSync(snappedToMs: Long) {}
        })

        val sequence = BuiltInSequences.scholastic
        engine.load(sequence)
        engine.start()

        while (fakeNow <= sequence.totalMs) {
            fakeNow += 143L
            engine.tick()
        }

        assertTrue("no cues fired; the walk never reached them", errors.isNotEmpty())
        assertTrue("a polled walk cannot find a cue early: " + errors, errors.all { it >= 0L })
        // Inclusive, and the boundary case is real rather than slack in the bound: the first cue of
        // this sequence sits at offset == totalMs, so it comes due at the instant the gun is anchored
        // and the first poll of the walk finds it a whole step later. Measured [143, 60, 90, ...] —
        // one cue at exactly the step, the rest below it. A strict `<` fails on that first cue, which
        // is the run this test would otherwise call a defect.
        assertTrue("poll step is 143 ms, so no cue can be later than that: " + errors, errors.all { it <= 143L })
    }

    // --- sleepDivergenceMs / deepSleepSinceMs -----------------------------------

    @Test fun `divergence is the gap between the two platform clocks`() {
        // elapsedRealtime counts through suspend, uptimeMillis does not, so the gap is suspend time
        // since boot.
        assertEquals(90_000L, TimingProbe.sleepDivergenceMs(elapsedRealtimeMs = 500_000L, uptimeMs = 410_000L))
    }

    @Test fun `a race the CPU stayed awake through reports no sleep`() {
        // Both clocks advanced by the same 180 s, so the gap did not move. This is the reading the
        // wake lock is supposed to produce for a whole countdown, and it is the control the hardware
        // runs are read against.
        val baseline = TimingProbe.sleepDivergenceMs(elapsedRealtimeMs = 500_000L, uptimeMs = 410_000L)
        assertEquals(
            0L,
            TimingProbe.deepSleepSinceMs(
                elapsedRealtimeMs = 680_000L,
                uptimeMs = 590_000L,
                baselineDivergenceMs = baseline,
            ),
        )
    }

    @Test fun `sleep is reported as the amount uptime fell behind`() {
        // 180 s of wall time, of which uptime only saw 176.5 s: the CPU was suspended for 3.5 s and
        // any cue due in that window was deferred by up to that much.
        val baseline = TimingProbe.sleepDivergenceMs(elapsedRealtimeMs = 500_000L, uptimeMs = 410_000L)
        assertEquals(
            3_500L,
            TimingProbe.deepSleepSinceMs(
                elapsedRealtimeMs = 680_000L,
                uptimeMs = 586_500L,
                baselineDivergenceMs = baseline,
            ),
        )
    }

    @Test fun `a negative reading is surfaced rather than clamped`() {
        // Cannot happen within one boot. It can happen across a reboot, where a baseline from the
        // previous boot is meaningless — and a clamp would report that as a clean zero, which is the
        // same answer as a perfectly held wake lock. The two must not be indistinguishable.
        assertTrue(
            TimingProbe.deepSleepSinceMs(
                elapsedRealtimeMs = 12_000L,
                uptimeMs = 12_000L,
                baselineDivergenceMs = 90_000L,
            ) < 0L,
        )
    }
}
