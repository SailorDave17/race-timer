package com.racetimer.shared

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TimerEngine] using a fake monotonic clock so we can control time.
 */
class TimerEngineTest {

    private var fakeNow = 0L
    private val fakeClock = MonotonicClock { fakeNow }

    private var fakeWall = 1_000_000L
    private val fakeWallClock = WallClock { fakeWall }

    private lateinit var engine: TimerEngine
    private val cues = mutableListOf<SequenceCue>()
    private var gunFired = false
    private val ticks = mutableListOf<Long>()
    private var syncedTo: Long? = null

    private val listener = object : TimerListener {
        override fun onCue(cue: SequenceCue) { cues += cue }
        override fun onGun() { gunFired = true }
        override fun onTick(remainingMs: Long) { ticks += remainingMs }
        override fun onSync(snappedToMs: Long) { syncedTo = snappedToMs }
    }

    @Before fun setUp() {
        fakeNow = 0L
        fakeWall = 1_000_000L
        cues.clear()
        gunFired = false
        ticks.clear()
        syncedTo = null

        engine = TimerEngine(fakeClock, fakeWallClock)
        engine.addListener(listener)
    }

    // --- Basic lifecycle tests ------------------------------------------------

    @Test fun `initial state is IDLE`() {
        assertEquals(TimerState.IDLE, engine.currentState)
    }

    @Test fun `load sets state to IDLE`() {
        engine.load(BuiltInSequences.club)
        assertEquals(TimerState.IDLE, engine.currentState)
    }

    @Test fun `start transitions to RUNNING`() {
        engine.load(BuiltInSequences.club)
        engine.start()
        assertEquals(TimerState.RUNNING, engine.currentState)
    }

    @Test fun `remainingMs decreases over time`() {
        engine.load(BuiltInSequences.club)
        engine.start()
        val r0 = engine.remainingMs
        fakeNow += 1_000L
        val r1 = engine.remainingMs
        assertEquals(r0 - 1_000L, r1)
    }

    @Test fun `reset returns to IDLE with full duration`() {
        engine.load(BuiltInSequences.club)
        engine.start()
        fakeNow += 10_000L
        engine.reset()
        assertEquals(TimerState.IDLE, engine.currentState)
        assertEquals(BuiltInSequences.club.totalMs, engine.remainingMs)
    }

    @Test fun `pause captures remaining and stops decreasing`() {
        engine.load(BuiltInSequences.club)
        engine.start()
        fakeNow += 5_000L
        engine.pause()
        val r = engine.remainingMs
        fakeNow += 10_000L
        assertEquals(r, engine.remainingMs)
        assertEquals(TimerState.PAUSED, engine.currentState)
    }

    @Test fun `resume after pause continues from correct position`() {
        engine.load(BuiltInSequences.club)
        engine.start()
        fakeNow += 5_000L
        engine.pause()
        val pausedAt = engine.remainingMs
        fakeNow += 100_000L  // wall time passes while paused
        engine.start()      // resume
        // remaining should be pausedAt minus any tick advance
        fakeNow += 1_000L
        assertEquals(pausedAt - 1_000L, engine.remainingMs)
    }

    // --- Cue firing -----------------------------------------------------------

    @Test fun `club sequence fires correct number of cues`() {
        engine.load(BuiltInSequences.club)
        engine.start()

        // Advance past every cue
        advanceTo(BuiltInSequences.club.totalMs + 1_000L)

        // 3 named cues + 5 final-five ticks + 1 gun = 9 cues total
        assertEquals(9, cues.size)
        assertTrue(gunFired)
    }

    @Test fun `first cue fires at correct time`() {
        // The club sequence's first cue sits at offsetMs == totalMs (the 3:00 warning of a 3:00
        // sequence), so it fires when now >= gunTimeMs - 180_000 == startTime — i.e. on the very
        // first tick after start, not later.
        engine.load(BuiltInSequences.club)
        engine.start()

        engine.tick()
        assertEquals(1, cues.size)
        assertEquals(3 * 60_000L, cues[0].offsetMs)

        // The 2:00 cue must wait its turn rather than following immediately.
        fakeNow += 1L
        engine.tick()
        assertEquals(1, cues.size)
    }

    @Test fun `gun cue transitions to FINISHED`() {
        engine.load(BuiltInSequences.club)
        engine.start()
        advanceTo(BuiltInSequences.club.totalMs + 1_000L)
        assertEquals(TimerState.FINISHED, engine.currentState)
        assertTrue(gunFired)
    }

    @Test fun `us sailing sequence has 30 cues`() {
        engine.load(BuiltInSequences.usSailing)
        engine.start()
        advanceTo(BuiltInSequences.usSailing.totalMs + 1_000L)
        assertEquals(30, cues.size)
        // The sync ticks are the ones packed tightest — ten of them at 1-second spacing — so this
        // is also the check that the engine delivers every one of them rather than coalescing.
        assertEquals(10, cues.count { it.signal.voice == CueVoice.SYNC })
    }

    @Test fun `scholastic sequence has 19 cues`() {
        engine.load(BuiltInSequences.scholastic)
        engine.start()
        advanceTo(BuiltInSequences.scholastic.totalMs + 1_000L)
        assertEquals(19, cues.size)
    }

    // --- Sync -----------------------------------------------------------------

    @Test fun `sync snaps to nearest minute - round up`() {
        engine.load(BuiltInSequences.usSailing)
        engine.start()
        // Advance 8s (4:52 remaining from 5:00 start) → should snap to 5:00 (nearest)
        // US Sailing totalMs = 300_000. After 8s elapsed: remaining = 292_000 (4:52)
        // 4:52 → nearest minute = 5:00 (upper, since 8 > 30)
        fakeNow = 8_000L
        engine.sync()
        val snapped = syncedTo!!
        assertEquals(5 * 60_000L, snapped)
    }

    @Test fun `sync round-down floors when within the correction bound`() {
        engine.load(BuiltInSequences.usSailing)
        engine.start()
        // remaining = 200_000 (3:20) -> floor to 3:00 is a 20s move, within the +/-30s bound.
        fakeNow = 100_000L
        engine.sync(roundDown = true)
        assertEquals(3 * 60_000L, syncedTo!!)
    }

    @Test fun `sync round-down clamps to nearest when flooring would delete a minute`() {
        engine.load(BuiltInSequences.usSailing)
        engine.start()
        // remaining = 235_000 (3:55). Naive floor -> 3:00 is a 55s deletion (OCS footgun).
        // The +/-30s clamp must instead fall back to nearest -> 4:00.
        fakeNow = 65_000L
        engine.sync(roundDown = true)
        assertEquals(4 * 60_000L, syncedTo!!)
    }

    @Test fun `sync rounding up does not re-fire an already-fired cue`() {
        engine.load(BuiltInSequences.club)  // cues at 3:00, 2:00, 1:00, 0:05..0:01, gun
        engine.start()
        // Fire the 3:00 and 2:00 cues by ticking at their boundaries.
        fakeNow = 60_000L
        engine.tick()
        assertEquals(1, cues.count { it.offsetMs == 120_000L })  // 2:00 fired once

        // Sync with 2:00 exactly remaining: the just-fired 2:00 cue must NOT be re-queued.
        engine.sync()
        fakeNow = 60_100L
        engine.tick()
        assertEquals(1, cues.count { it.offsetMs == 120_000L })  // still once, no double-fire
    }

    @Test fun `first sync of a race is never swallowed by the double-tap guard`() {
        // Regression: the guard used to seed lastSyncTimeMs with Long.MIN_VALUE, so `now - last`
        // overflowed negative and every first sync of a race silently did nothing.
        engine.load(BuiltInSequences.usSailing)
        engine.start()
        fakeNow = 8_000L
        engine.sync()
        assertNotNull("first sync must be honoured", syncedTo)
        assertEquals(5 * 60_000L, syncedTo)
    }

    @Test fun `sync guard prevents double-snap within 1 second`() {
        engine.load(BuiltInSequences.usSailing)
        engine.start()
        fakeNow = 8_000L
        engine.sync()
        val first = syncedTo
        syncedTo = null
        fakeNow += 500L  // only 500 ms later — within guard
        engine.sync()
        assertNull(syncedTo)  // second sync was ignored
        assertEquals(first, syncedTo ?: first)
    }

    @Test fun `sync after guard window is allowed`() {
        engine.load(BuiltInSequences.usSailing)
        engine.start()
        fakeNow = 8_000L
        engine.sync()
        fakeNow += 1_100L // past the 1-second guard
        engine.sync()
        assertNotNull(syncedTo)
    }

    // --- Custom sequence ------------------------------------------------------

    @Test fun `custom 6-minute sequence has correct totalMs`() {
        val seq = BuiltInSequences.custom(totalSeconds = 360L)
        assertEquals(360_000L, seq.totalMs)
    }

    @Test fun `custom sequence gun cue at 0`() {
        val seq = BuiltInSequences.custom(totalSeconds = 120L)
        val gun = seq.cues.first { it.isGun }
        assertEquals(0L, gun.offsetMs)
    }

    // --- State restoration ----------------------------------------------------

    @Test fun `restore resumes correctly on same boot`() {
        engine.load(BuiltInSequences.club)
        engine.start()
        fakeNow += 30_000L
        fakeWall += 30_000L
        val snap = engine.snapshot()!!

        // Simulate a killed-and-relaunched process: new engine, same clocks (same boot).
        val engine2 = TimerEngine(fakeClock, fakeWallClock)
        val outcome = engine2.restore(BuiltInSequences.club, snap)

        assertEquals(RestoreOutcome.EXACT, outcome)
        assertEquals(TimerState.RUNNING, engine2.currentState)
        // Remaining should be exactly (180_000 - 30_000) = 150_000, no drift.
        assertEquals(150_000L, engine2.remainingMs, 1L)
    }

    @Test fun `NTP step during process death does not move the gun`() {
        engine.load(BuiltInSequences.club)
        engine.start()
        val snap = engine.snapshot()!!

        // 5s of real death, plus a +3s NTP correction to the wall clock — monotonic clock is unaffected.
        fakeNow += 5_000L
        fakeWall += 5_000L + 3_000L

        val engine2 = TimerEngine(fakeClock, fakeWallClock)
        val outcome = engine2.restore(BuiltInSequences.club, snap)

        // Same boot (elapsedRealtime intact) -> monotonic trusted, gun unmoved by the NTP step.
        assertEquals(RestoreOutcome.EXACT, outcome)
        assertEquals(180_000L - 5_000L, engine2.remainingMs, 1L)
    }

    @Test fun `reboot forces degraded restore via wall clock`() {
        engine.load(BuiltInSequences.club)
        engine.start()
        fakeNow += 30_000L
        fakeWall += 30_000L
        val snap = engine.snapshot()!!

        // Reboot: elapsedRealtime resets to zero (goes backwards), wall clock keeps advancing (10s down).
        fakeNow = 0L
        fakeWall += 10_000L

        val engine2 = TimerEngine(fakeClock, fakeWallClock)
        val outcome = engine2.restore(BuiltInSequences.club, snap)

        assertEquals(RestoreOutcome.DEGRADED, outcome)
        assertEquals(TimerState.RUNNING, engine2.currentState)
        // 150_000 remained at snapshot, minus 10s of reboot downtime measured on the wall clock.
        assertEquals(140_000L, engine2.remainingMs, 1L)
    }

    @Test fun `restore after gun results in FINISHED`() {
        engine.load(BuiltInSequences.club)
        engine.start()
        val snap = engine.snapshot()!!

        fakeNow += 190_000L  // well past the gun
        fakeWall += 190_000L
        val engine2 = TimerEngine(fakeClock, fakeWallClock)
        val outcome = engine2.restore(BuiltInSequences.club, snap)
        assertEquals(RestoreOutcome.EXPIRED, outcome)
        assertEquals(TimerState.FINISHED, engine2.currentState)
        assertEquals(0L, engine2.remainingMs)
    }

    // --- Remaining time once the gun has fired --------------------------------

    @Test fun `remainingMs is zero after the gun fires`() {
        engine.load(BuiltInSequences.club)
        engine.start()
        advanceTo(BuiltInSequences.club.totalMs + 1_000L)

        // Outside RUNNING the getter reports the paused position, which load() had seeded with the
        // sequence total — so a FINISHED engine used to claim a whole countdown was still to come.
        assertEquals(TimerState.FINISHED, engine.currentState)
        assertEquals(0L, engine.remainingMs)
    }

    // --- Helper ---------------------------------------------------------------

    /** Advance fake clock in 100 ms steps, calling tick() each step. */
    private fun advanceTo(totalElapsedMs: Long) {
        val step = 100L
        while (fakeNow < totalElapsedMs) {
            fakeNow += step
            engine.tick()
        }
    }

    private fun assertEquals(expected: Long, actual: Long, tolerance: Long) {
        assertTrue(
            "Expected ~$expected but was $actual (tolerance ±$tolerance)",
            Math.abs(actual - expected) <= tolerance
        )
    }
}
