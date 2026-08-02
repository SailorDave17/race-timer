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
        val seq = BuiltInSequences.custom(totalMinutes = 6)
        assertEquals(360_000L, seq.totalMs)
    }

    @Test fun `custom sequence gun cue at 0`() {
        val seq = BuiltInSequences.custom(totalMinutes = 2)
        val gun = seq.cues.first { it.isGun }
        assertEquals(0L, gun.offsetMs)
    }

    @Test fun `custom sequence runs its whole cue list in order`() {
        val seq = BuiltInSequences.custom(totalMinutes = 2)
        engine.load(seq)
        engine.start()
        // One tick per cue boundary, walked from the top: this is the sequence as the sailor hears
        // it, not as the list declares it.
        for (cue in seq.cues) {
            fakeNow = seq.totalMs - cue.offsetMs
            engine.tick()
        }
        assertEquals(seq.cues, cues)
        assertTrue(gunFired)
    }

    @Test fun `a one-minute custom race still fires its minute cue and the whole tail`() {
        val seq = BuiltInSequences.custom(totalMinutes = 1)
        engine.load(seq)
        engine.start()
        // The minute cue is due the instant the countdown starts at 1:00, so it must not be skipped
        // as "already past" the way a cue above the total would be.
        engine.tick()
        assertEquals(listOf(60_000L), cues.map { it.offsetMs })

        fakeNow += 60_000L
        engine.tick()
        assertEquals(seq.cues, cues)
        assertTrue(gunFired)
    }

    // --- Restoring a custom race ----------------------------------------------

    @Test fun `a custom race restores as itself from its persisted id`() {
        // The failure this guards is silent: a custom id is not in BuiltInSequences.all, so a
        // lookup that falls back to a default resumed the race at US Sailing's duration and cues
        // with nothing on screen to say so. Restoring through the same id the snapshot carries is
        // the whole of the fix, so it is what the test drives.
        engine.load(BuiltInSequences.custom(totalMinutes = 8))
        engine.start()
        fakeNow += 90_000L
        fakeWall += 90_000L
        val snap = engine.snapshot()!!

        // A fresh engine on the same boot — the process died, the clocks did not.
        val revived = TimerEngine(fakeClock, fakeWallClock)
        val resolved = BuiltInSequences.resolve(snap.sequenceId)
        assertNotNull("custom id must resolve", resolved)
        val outcome = revived.restore(resolved!!, snap)

        assertEquals(RestoreOutcome.EXACT, outcome)
        assertEquals(BuiltInSequences.custom(totalMinutes = 8).id, resolved.id)
        assertEquals(8 * 60_000L, resolved.totalMs)
        assertEquals(390_000L, revived.remainingMs) // 8:00 less the 1:30 that elapsed
    }

    @Test fun `a restored custom race sounds only the cues it has not passed`() {
        engine.load(BuiltInSequences.custom(totalMinutes = 3))
        engine.start()
        fakeNow += 100_000L // 1:40 remaining: the 3:00 and 2:00 cues are spent
        fakeWall += 100_000L
        val snap = engine.snapshot()!!

        val revived = TimerEngine(fakeClock, fakeWallClock)
        revived.addListener(listener)
        revived.restore(BuiltInSequences.resolve(snap.sequenceId)!!, snap)

        fakeNow += 100_000L // run it out past the gun
        revived.tick()
        assertEquals(
            // 1:20 was left at the kill, so 3:00 and 2:00 are spent and must not sound again.
            BuiltInSequences.custom(totalMinutes = 3).cues
                .filter { it.offsetMs <= 80_000L }
                .map { it.offsetMs },
            cues.map { it.offsetMs },
        )
    }

    // --- Previewing a snapshot without restoring it ---------------------------

    @Test fun `remainingFromSnapshot agrees with what restore actually does`() {
        // The whole reason this helper exists: the pre-start screen shows the sailor a number and
        // then a tap has to deliver it. If these two ever disagree, Resume becomes a lie — which is
        // exactly the bug the offer was added to fix, in a new place.
        engine.load(BuiltInSequences.custom(totalMinutes = 8))
        engine.start()
        fakeNow += 137_000L
        fakeWall += 137_000L
        val snap = engine.snapshot()!!

        val previewed = remainingFromSnapshot(snap, fakeNow, fakeWall)
        val revived = TimerEngine(fakeClock, fakeWallClock)
        revived.restore(BuiltInSequences.resolve(snap.sequenceId)!!, snap)

        assertEquals(revived.remainingMs, previewed)
        assertEquals(343_000L, previewed) // 8:00 less 2:17
    }

    @Test fun `remainingFromSnapshot keeps counting down as the clock runs on`() {
        // The saved gun is fixed in the monotonic domain, so it keeps approaching whether or not the
        // app is open. A preview captured once and held would sit frozen while the race ran away.
        engine.load(BuiltInSequences.scholastic)
        engine.start()
        val snap = engine.snapshot()!!

        assertEquals(180_000L, remainingFromSnapshot(snap, fakeNow, fakeWall))
        assertEquals(120_000L, remainingFromSnapshot(snap, fakeNow + 60_000L, fakeWall + 60_000L))
        assertEquals(0L, remainingFromSnapshot(snap, fakeNow + 180_000L, fakeWall + 180_000L))
    }

    @Test fun `remainingFromSnapshot goes negative past the gun of a count-up race`() {
        // Not a spent race — for a race-manager sequence the gun is where the job starts, and the
        // negated value is the elapsed race time the pre-start preview shows instead of a countdown.
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        val snap = engine.snapshot()!!

        val remaining = remainingFromSnapshot(snap, fakeNow + 300_000L, fakeWall + 300_000L)
        assertEquals(-120_000L, remaining)          // 3:00 sequence, 5:00 later
        assertEquals(120_000L, -remaining)          // 2:00 of race elapsed
    }

    @Test fun `remainingFromSnapshot falls back to wall-clock across a reboot`() {
        engine.load(BuiltInSequences.club)
        engine.start()
        fakeNow += 30_000L
        fakeWall += 30_000L
        val snap = engine.snapshot()!!

        // A reboot: the monotonic clock resets to near zero while the wall clock keeps running.
        val afterRebootElapsed = 5_000L
        val afterRebootWall = fakeWall + 20_000L
        assertTrue("reboot must look like a backwards monotonic step", afterRebootElapsed < snap.capturedElapsedMs)

        // 3:00 sequence, 30 s spent before the kill and 20 s of real time lost to the reboot.
        assertEquals(130_000L, remainingFromSnapshot(snap, afterRebootElapsed, afterRebootWall))
    }

    // --- Whether a saved race may be offered at all ----------------------------

    @Test fun `resumeOfferRemainingMs withholds a saved race when another sequence is selected`() {
        // The bug this guards: re-dialling the Custom stepper changes the id, so a saved 8:00 race
        // stops matching what Start would run — but the pre-start screen went on previewing its
        // clock. Start was right all along (TimerService restores only on savedSeqId == sequenceId),
        // so the screen promised the previous duration and then delivered the newly chosen one.
        val saved = BuiltInSequences.custom(totalMinutes = 8)
        engine.load(saved)
        engine.start()
        fakeNow += 60_000L
        fakeWall += 60_000L
        val snap = engine.snapshot()!!

        val nowSelected = BuiltInSequences.custom(totalMinutes = 5)
        assertNull(resumeOfferRemainingMs(snap, saved, nowSelected.id, fakeNow, fakeWall))
    }

    @Test fun `resumeOfferRemainingMs offers the saved race when its own sequence is selected`() {
        // The other half: fixing the above must not cost the offer the case it exists for.
        val saved = BuiltInSequences.custom(totalMinutes = 8)
        engine.load(saved)
        engine.start()
        fakeNow += 60_000L
        fakeWall += 60_000L
        val snap = engine.snapshot()!!

        assertEquals(420_000L, resumeOfferRemainingMs(snap, saved, saved.id, fakeNow, fakeWall))
    }

    @Test fun `resumeOfferRemainingMs withholds without discarding, so re-selecting brings it back`() {
        // Withheld, not cleared: wandering to another sequence and back must not destroy a race the
        // service is still holding. One snapshot, two answers, decided only by what is selected.
        val saved = BuiltInSequences.custom(totalMinutes = 8)
        engine.load(saved)
        engine.start()
        fakeNow += 60_000L
        fakeWall += 60_000L
        val snap = engine.snapshot()!!

        assertNull(resumeOfferRemainingMs(snap, saved, BuiltInSequences.club.id, fakeNow, fakeWall))
        assertEquals(420_000L, resumeOfferRemainingMs(snap, saved, saved.id, fakeNow, fakeWall))
    }

    @Test fun `resumeOfferRemainingMs withholds a countdown whose gun has passed`() {
        val saved = BuiltInSequences.club // 3:00
        engine.load(saved)
        engine.start()
        val snap = engine.snapshot()!!

        assertNull(
            resumeOfferRemainingMs(snap, saved, saved.id, fakeNow + 180_001L, fakeWall + 180_001L),
        )
    }

    @Test fun `resumeOfferRemainingMs still offers a count-up race past its gun`() {
        // Past the gun is where a race-manager sequence lives, so the negative reading is the offer
        // rather than a refusal — the one case where a spent clock is still resumable.
        val saved = BuiltInSequences.scholasticRaceManager
        engine.load(saved)
        engine.start()
        val snap = engine.snapshot()!!

        assertEquals(
            -120_000L,
            resumeOfferRemainingMs(snap, saved, saved.id, fakeNow + 300_000L, fakeWall + 300_000L),
        )
    }

    // --- What Start is about to throw away (#89) -------------------------------

    @Test fun `discardedOnStartRemainingMs names the race a mismatched Start would destroy`() {
        // The loss #89 is about: ACTION_START takes the load-and-start branch on an id mismatch and
        // persistSnapshot() writes the new race over the old one. Nothing on screen said so.
        val saved = BuiltInSequences.custom(totalMinutes = 8)
        engine.load(saved)
        engine.start()
        fakeNow += 60_000L
        fakeWall += 60_000L
        val snap = engine.snapshot()!!

        val doomed = BuiltInSequences.custom(totalMinutes = 5)
        assertEquals(
            420_000L,
            discardedOnStartRemainingMs(snap, saved, doomed.id, fakeNow, fakeWall),
        )
    }

    @Test fun `discardedOnStartRemainingMs is silent when the saved race is the selected one`() {
        // Selecting the saved race's own sequence means Start resumes it, not destroys it.
        val saved = BuiltInSequences.custom(totalMinutes = 8)
        engine.load(saved)
        engine.start()
        fakeNow += 60_000L
        fakeWall += 60_000L
        val snap = engine.snapshot()!!

        assertNull(discardedOnStartRemainingMs(snap, saved, saved.id, fakeNow, fakeWall))
    }

    @Test fun `a spent countdown is neither offered nor worth warning about`() {
        // Both sides go quiet together. Warning about a race with nothing left to resume would train
        // the sailor to ignore the warning that matters.
        val saved = BuiltInSequences.club // 3:00
        engine.load(saved)
        engine.start()
        val snap = engine.snapshot()!!
        val spentElapsed = fakeNow + 180_001L
        val spentWall = fakeWall + 180_001L

        assertNull(resumeOfferRemainingMs(snap, saved, saved.id, spentElapsed, spentWall))
        assertNull(discardedOnStartRemainingMs(snap, saved, "custom_5m", spentElapsed, spentWall))
    }

    @Test fun `the offer and the discard warning are exact complements`() {
        // The structural guarantee behind #89's fix: one recoverability rule, two readings of it, so
        // they cannot drift the way the expiry rule did when it was written out twice inverted.
        // Whatever is selected, a recoverable race is either resumable or doomed - never both, never
        // neither.
        val saved = BuiltInSequences.custom(totalMinutes = 8)
        engine.load(saved)
        engine.start()
        fakeNow += 60_000L
        fakeWall += 60_000L
        val snap = engine.snapshot()!!

        val selections = listOf(
            saved.id,
            BuiltInSequences.custom(totalMinutes = 5).id,
            BuiltInSequences.club.id,
            BuiltInSequences.usSailing.id,
            BuiltInSequences.scholasticRaceManager.id,
        )
        for (selected in selections) {
            val offered = resumeOfferRemainingMs(snap, saved, selected, fakeNow, fakeWall)
            val doomed = discardedOnStartRemainingMs(snap, saved, selected, fakeNow, fakeWall)
            assertTrue(
                "exactly one of offer/discard must answer for selection=$selected, got $offered / $doomed",
                (offered == null) != (doomed == null),
            )
            assertEquals("both must report the same clock", 420_000L, offered ?: doomed)
        }
    }

    @Test fun `a count-up race past its gun is still worth warning about`() {
        // The count-up exception has to reach the warning too, not just the offer: past the gun is
        // where a race-manager race lives, so it is still destroyable and still worth protecting.
        val saved = BuiltInSequences.scholasticRaceManager
        engine.load(saved)
        engine.start()
        val snap = engine.snapshot()!!

        assertEquals(
            -120_000L,
            discardedOnStartRemainingMs(
                snap, saved, BuiltInSequences.club.id,
                fakeNow + 300_000L, fakeWall + 300_000L,
            ),
        )
    }

    // --- What "a race is on screen" actually means (#87) ------------------------

    @Test fun `a stopped engine is IDLE but still holds its sequence`() {
        // Why the pre-start screen asks the state rather than whether a sequence is loaded (#87).
        // stop() keeps the sequence on purpose — see its doc, and the pausedRemainingMs line that
        // exists so the idle screen reads a full duration rather than 0:00 — so
        // `loadedSequence != null` stayed true after Stop. The screen went on rendering the *stopped*
        // race's length while the sailor picked a different sequence, and Start then correctly ran
        // the new one, disagreeing with the number they had just read.
        engine.load(BuiltInSequences.club) // 3:00
        engine.start()
        fakeNow += 30_000L
        fakeWall += 30_000L
        engine.stop()

        assertEquals(TimerState.IDLE, engine.currentState)
        assertNotNull("stop() keeps the sequence on purpose", engine.loadedSequence)
        // The trap in one line: this is the *stopped* race's duration, and it is what the screen was
        // showing under whatever sequence name the sailor had since picked.
        assertEquals(180_000L, engine.remainingMs)
    }

    @Test fun `a race past its gun is not IDLE, so the gun stays on screen`() {
        // The other side of the same condition: FINISHED must keep rendering from the engine. Treating
        // "no race" as "not RUNNING" instead of "IDLE" would flip the screen to the next sequence's
        // duration at the exact moment the gun fires.
        engine.load(BuiltInSequences.club)
        engine.start()
        fakeNow += 180_000L
        fakeWall += 180_000L
        engine.tick()

        assertEquals(TimerState.FINISHED, engine.currentState)
        assertTrue("the gun must still own the screen", engine.currentState != TimerState.IDLE)
    }

    @Test fun `a count-up race and its frozen summary are both non-IDLE`() {
        // RACE_ENDED exists precisely to hold the final time up for the race committee, so it has to
        // count as a race on screen too — the one case where a *finished* race must outrank the
        // selection for as long as the sailor is still looking at it.
        engine.load(BuiltInSequences.scholasticRaceManager) // 3:00, counts up past the gun
        engine.start()
        fakeNow += 200_000L
        fakeWall += 200_000L
        engine.tick()
        assertEquals(TimerState.COUNTING_UP, engine.currentState)

        engine.endRace()
        assertEquals(TimerState.RACE_ENDED, engine.currentState)

        engine.stop()
        assertEquals("only Stop returns it to the pre-start screen", TimerState.IDLE, engine.currentState)
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

    // --- Count-up (race-manager) -----------------------------------------------

    @Test fun `gun cue transitions to COUNTING_UP for a count-up sequence`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        advanceTo(BuiltInSequences.scholasticRaceManager.totalMs + 1_000L)
        assertEquals(TimerState.COUNTING_UP, engine.currentState)
        assertTrue(gunFired)
    }

    @Test fun `a non-count-up sequence still lands on FINISHED, unaffected by COUNTING_UP existing`() {
        engine.load(BuiltInSequences.scholastic)
        engine.start()
        advanceTo(BuiltInSequences.scholastic.totalMs + 1_000L)
        assertEquals(TimerState.FINISHED, engine.currentState)
    }

    @Test fun `remainingMs keeps counting further negative through COUNTING_UP`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        advanceTo(BuiltInSequences.scholasticRaceManager.totalMs)
        assertEquals(TimerState.COUNTING_UP, engine.currentState)

        val atGun = engine.remainingMs
        fakeNow += 90_000L  // 90s into the race
        // -remainingMs is the elapsed time a caller displays; it must have grown by exactly the
        // wall time that passed, the same monotonic-anchor guarantee the countdown relies on.
        assertEquals(atGun - 90_000L, engine.remainingMs)
    }

    @Test fun `onTick keeps firing every tick during COUNTING_UP`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        advanceTo(BuiltInSequences.scholasticRaceManager.totalMs)
        val ticksAtGun = ticks.size

        fakeNow += 500L
        engine.tick()
        assertTrue("onTick must still fire post-gun for a count-up sequence", ticks.size > ticksAtGun)
    }

    @Test fun `stop ends a count-up race and returns to IDLE`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        advanceTo(BuiltInSequences.scholasticRaceManager.totalMs)
        assertEquals(TimerState.COUNTING_UP, engine.currentState)

        engine.stop()
        assertEquals(TimerState.IDLE, engine.currentState)
    }

    @Test fun `snapshot is available during COUNTING_UP`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        advanceTo(BuiltInSequences.scholasticRaceManager.totalMs)
        assertNotNull(engine.snapshot())
    }

    // --- End Race / RACE_ENDED ---------------------------------------------------

    @Test fun `endRace freezes elapsed time and moves to RACE_ENDED`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        advanceTo(BuiltInSequences.scholasticRaceManager.totalMs)
        fakeNow += 45_000L  // 45s into the race

        engine.endRace()
        assertEquals(TimerState.RACE_ENDED, engine.currentState)
        assertEquals(-45_000L, engine.remainingMs)
    }

    @Test fun `remainingMs stays frozen during RACE_ENDED even as time passes`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        advanceTo(BuiltInSequences.scholasticRaceManager.totalMs)
        fakeNow += 45_000L
        engine.endRace()

        val frozen = engine.remainingMs
        fakeNow += 60_000L  // a minute passes while the summary is on screen
        assertEquals("elapsed time must not keep advancing once frozen", frozen, engine.remainingMs)
    }

    @Test fun `endRace is a no-op outside COUNTING_UP`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        // Still RUNNING — the gun hasn't fired yet.
        engine.endRace()
        assertEquals(TimerState.RUNNING, engine.currentState)
    }

    @Test fun `endRace is a no-op from IDLE`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.endRace()
        assertEquals(TimerState.IDLE, engine.currentState)
    }

    @Test fun `stop dismisses a RACE_ENDED summary back to IDLE`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        advanceTo(BuiltInSequences.scholasticRaceManager.totalMs)
        engine.endRace()
        assertEquals(TimerState.RACE_ENDED, engine.currentState)

        engine.stop()
        assertEquals(TimerState.IDLE, engine.currentState)
    }

    @Test fun `tick is a no-op during RACE_ENDED`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        advanceTo(BuiltInSequences.scholasticRaceManager.totalMs)
        engine.endRace()
        val ticksAtEnd = ticks.size

        fakeNow += 5_000L
        engine.tick()
        assertEquals("RACE_ENDED has nothing left to tick for", ticksAtEnd, ticks.size)
    }

    @Test fun `snapshot returns null during RACE_ENDED - not worth persisting`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        advanceTo(BuiltInSequences.scholasticRaceManager.totalMs)
        engine.endRace()
        assertNull(engine.snapshot())
    }

    @Test fun `dismissing RACE_ENDED via stop shows the full duration again, not 0-00`() {
        // Regression: stop() left pausedRemainingMs on endRace()'s frozen (negative) elapsed value,
        // so the idle screen right after Done read "0:00" (remainingMs clamps negative to that)
        // instead of the sequence's full countdown, ready for the next race.
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        advanceTo(BuiltInSequences.scholasticRaceManager.totalMs)
        fakeNow += 45_000L
        engine.endRace()

        engine.stop()
        assertEquals(TimerState.IDLE, engine.currentState)
        assertEquals(BuiltInSequences.scholasticRaceManager.totalMs, engine.remainingMs)
    }

    @Test fun `restore after the gun resumes COUNTING_UP for a count-up sequence, not EXPIRED`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        val snap = engine.snapshot()!!  // pre-gun snapshot; the gun anchor never moves

        // Kill the process well past the gun - the race committee's race is still running.
        fakeNow += BuiltInSequences.scholasticRaceManager.totalMs + 120_000L
        fakeWall += BuiltInSequences.scholasticRaceManager.totalMs + 120_000L

        val engine2 = TimerEngine(fakeClock, fakeWallClock)
        val outcome = engine2.restore(BuiltInSequences.scholasticRaceManager, snap)

        assertEquals(RestoreOutcome.EXACT, outcome)
        assertEquals(TimerState.COUNTING_UP, engine2.currentState)
        // 120s have elapsed since the gun; remainingMs is the negative of that.
        assertEquals(-120_000L, engine2.remainingMs, 1L)
    }

    @Test fun `restored count-up race can still be stopped`() {
        engine.load(BuiltInSequences.scholasticRaceManager)
        engine.start()
        val snap = engine.snapshot()!!
        fakeNow += BuiltInSequences.scholasticRaceManager.totalMs + 60_000L
        fakeWall += BuiltInSequences.scholasticRaceManager.totalMs + 60_000L

        val engine2 = TimerEngine(fakeClock, fakeWallClock)
        engine2.restore(BuiltInSequences.scholasticRaceManager, snap)
        engine2.stop()
        assertEquals(TimerState.IDLE, engine2.currentState)
    }

    // --- Cue scheduling (msUntilNextCue) --------------------------------------
    //
    // These back TimerService.scheduleNextCue, which exists so a cue lands on its boundary instead
    // of being found by the next poll. A wrong answer here is a cue fired at the wrong time, which
    // is the one thing this app cannot get wrong — so the boundary cases get their own tests.

    @Test fun `msUntilNextCue is null before the countdown starts`() {
        engine.load(BuiltInSequences.usSailing)
        assertNull(engine.msUntilNextCue())
    }

    @Test fun `msUntilNextCue counts down to the first cue`() {
        val seq = BuiltInSequences.usSailing
        engine.load(seq)
        engine.start()

        // The first cue sits at the top of the sequence, so it is due immediately.
        assertEquals(0L, engine.msUntilNextCue())

        // Once it has fired, the next one is the sync run into 4:00 — five ticks ahead of it.
        engine.tick()
        val next = engine.msUntilNextCue()!!
        assertTrue("Expected a positive wait, was $next", next > 0L)
        assertEquals(seq.totalMs - (4 * 60_000L + 5_000L), next)
    }

    @Test fun `msUntilNextCue goes negative rather than clamping when a cue is overdue`() {
        engine.load(BuiltInSequences.usSailing)
        engine.start()
        engine.tick()                     // clear the cue due at t=0

        val dueIn = engine.msUntilNextCue()!!
        fakeNow += dueIn + 250L           // sleep straight past the next boundary

        // The caller clamps; the engine reports the truth, so a late wake-up is visible rather than
        // looking like a cue that is due right now.
        assertEquals(-250L, engine.msUntilNextCue())
    }

    @Test fun `waiting msUntilNextCue then ticking fires the cue, and not before`() {
        engine.load(BuiltInSequences.usSailing)
        engine.start()
        engine.tick()                     // clear the cue due at t=0
        cues.clear()

        val wait = engine.msUntilNextCue()!!

        fakeNow += wait - 1L
        engine.tick()
        assertTrue("Cue fired 1 ms early", cues.isEmpty())

        fakeNow += 1L
        engine.tick()
        assertEquals(1, cues.size)
    }

    @Test fun `msUntilNextCue re-anchors after a sync`() {
        engine.load(BuiltInSequences.scholastic)
        engine.start()
        engine.tick()                     // clear the cue due at t=0
        cues.clear()

        fakeNow += 40_000L                // 2:20 remaining; snaps down to 2:00
        engine.sync()

        // Snapping onto 2:00 puts the 2:00 cue exactly on the boundary, so it is due at once. The
        // pre-sync answer was 60 s out against the old anchor — a caller that did not re-read after
        // the sync would sit on that stale wait and sound the cue 40 s late.
        assertEquals(0L, engine.msUntilNextCue())
        engine.tick()
        assertEquals(1, cues.size)
    }

    @Test fun `msUntilNextCue is null once the gun has fired`() {
        val seq = BuiltInSequences.usSailing
        engine.load(seq)
        engine.start()
        advanceTo(seq.totalMs + 1_000L)

        assertTrue("Expected the gun to have fired", gunFired)
        assertNull(engine.msUntilNextCue())
    }

    @Test fun `msUntilNextCue is null while paused`() {
        engine.load(BuiltInSequences.usSailing)
        engine.start()
        engine.tick()
        engine.pause()

        // Nothing should be armed against a countdown that is not running — the anchor is stale and
        // scheduling on it would fire a cue during the pause.
        assertNull(engine.msUntilNextCue())
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
