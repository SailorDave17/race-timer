/**
 * Core timer engine for the race start countdown.
 *
 * All timing is anchored to a [MonotonicClock] (injected). In production on Android this is
 * backed by [android.os.SystemClock.elapsedRealtime], but the interface is declared here in the
 * pure-JVM shared module so the engine can be unit-tested without any Android dependency.
 *
 * Usage:
 *  1. Call [load] with the desired [RaceSequence].
 *  2. Call [start] to begin the countdown.
 *  3. Poll [remainingMs] (or observe via a coroutine loop / Handler) to update the UI.
 *  4. Call [sync] to snap to the nearest whole minute at any time while running.
 *  5. Call [stop] / [reset] to cancel. For a [RaceSequence.countUpAfterFinish] sequence, call
 *     [endRace] once the gun has fired to freeze the elapsed time for review, then [stop] to
 *     dismiss it.
 *
 * The engine is deliberately pure (no Android UI dependencies) so it can be unit-tested
 * without a device.  The [clock] parameter lets tests inject a fake clock.
 */
package com.racetimer.shared

// ---------------------------------------------------------------------------
// Clock abstraction (injectable for tests)
// ---------------------------------------------------------------------------

/** Returns monotonic elapsed time in milliseconds. */
fun interface MonotonicClock {
    fun elapsedMs(): Long
}

/**
 * Returns wall-clock time (ms since epoch).
 *
 * Declared as an injectable interface (rather than calling [System.currentTimeMillis] directly)
 * so tests can step or jump the wall clock independently of the monotonic clock — which is exactly
 * what a reboot or an NTP correction does in the wild.
 */
fun interface WallClock {
    fun nowMs(): Long
}

/**
 * Outcome of a [TimerEngine.restore] attempt, so the caller (and UI) can react to a degraded recovery.
 */
enum class RestoreOutcome {
    /** Same boot, clock stable — the monotonic gun time was trusted verbatim. Zero drift. */
    EXACT,
    /** Reboot or wall-clock step detected — gun reconstructed from wall-clock. Re-sync advised. */
    DEGRADED,
    /** The gun already fired while the process was dead. */
    EXPIRED,
}

// ---------------------------------------------------------------------------
// Timer state
// ---------------------------------------------------------------------------

enum class TimerState {
    /** No sequence loaded / timer reset. */
    IDLE,
    /** Countdown active. */
    RUNNING,
    /** Paused (sequence retained, can resume). */
    PAUSED,
    /** Sequence complete — gun has fired. */
    FINISHED,
    /**
     * Gun has fired for a [RaceSequence.countUpAfterFinish] sequence: the engine keeps running,
     * anchored to the same gun time, as an elapsed-time stopwatch instead of settling into
     * [FINISHED]. Ends via [TimerEngine.endRace] — there is no automatic teardown, because unlike
     * a sailor's countdown a race committee's job is not done when the gun fires.
     */
    COUNTING_UP,
    /**
     * A [COUNTING_UP] race has been manually ended (see [TimerEngine.endRace]): the final elapsed
     * time is frozen so the race committee can read it, rather than snapping straight back to
     * [IDLE] the way ending any other run does. [TimerEngine.stop] moves on to [IDLE] once they're
     * done looking.
     */
    RACE_ENDED,
}

// ---------------------------------------------------------------------------
// Listener / callback interface
// ---------------------------------------------------------------------------

/** Implement to receive cue events from [TimerEngine]. */
interface TimerListener {
    /** Called on each [SequenceCue] when it fires (in monotonic order, gun last). */
    fun onCue(cue: SequenceCue)

    /** Called when the gun fires (offsetMs == 0). */
    fun onGun()

    /** Called once per tick so the UI can refresh the display. */
    fun onTick(remainingMs: Long)

    /**
     * Called when a sync snap is performed.
     * @param snappedToMs The new remaining ms (a whole minute boundary).
     */
    fun onSync(snappedToMs: Long)

    /**
     * Called when a wall-clock adjustment (e.g. an NTP correction or manual time change) is
     * detected while the countdown is running. The monotonic countdown itself is unaffected;
     * [remainingMs] is the still-correct remaining time. Implementers may surface a brief
     * on-watch notice and/or re-persist derived wall-clock state. Default: no-op.
     */
    fun onClockAdjusted(remainingMs: Long) {}
}

// ---------------------------------------------------------------------------
// Timer engine
// ---------------------------------------------------------------------------

/**
 * @param clock      Injectable monotonic clock. In production, pass [com.racetimer.wear.SystemMonotonicClock]
 *                   (or any [MonotonicClock] backed by [android.os.SystemClock.elapsedRealtime]).
 *                   In unit tests, inject a fake clock via a lambda: `MonotonicClock { fakeNow }`.
 * @param wallClock  Injectable wall clock, used only for persistence/restore across process death.
 *                   Defaults to [System.currentTimeMillis]; tests inject a fake to simulate reboots
 *                   and NTP steps.
 */
class TimerEngine(
    private val clock: MonotonicClock,
    private val wallClock: WallClock = WallClock { System.currentTimeMillis() },
) {

    // --- Internal state -------------------------------------------------------

    private var sequence: RaceSequence? = null
    private var state: TimerState = TimerState.IDLE

    /**
     * Monotonic timestamp (ms) at which the gun fires.
     * Updated on start and whenever [sync] is called.
     */
    private var gunTimeMs: Long = 0L

    /** Cues that have not yet fired in the current run. */
    private var pendingCues: ArrayDeque<SequenceCue> = ArrayDeque()

    /**
     * Baseline (wall-clock minus monotonic) offset captured when the countdown started or was
     * restored. Used by [pollClockAdjustment] to detect wall-clock jumps mid-run.
     */
    private var wallMonoOffsetMs: Long = 0L

    private val listeners = mutableListOf<TimerListener>()

    // --- Public API -----------------------------------------------------------

    val currentState: TimerState get() = state
    val loadedSequence: RaceSequence? get() = sequence

    /**
     * Remaining milliseconds to the gun; 0 once finished; negative after the gun.
     *
     * Stays live (computed from [gunTimeMs], not a stored snapshot) throughout [TimerState.COUNTING_UP]
     * too, so it keeps counting further negative after the gun rather than freezing at 0 — the
     * elapsed race time a caller wants is simply `-remainingMs` in that state. [TimerState.RACE_ENDED]
     * is the one exception: [endRace] freezes this value at the moment it's called, so a caller reads
     * the same final elapsed time no matter how much later they ask.
     */
    val remainingMs: Long
        get() = if (state == TimerState.RUNNING || state == TimerState.COUNTING_UP)
            gunTimeMs - clock.elapsedMs()
        else pausedRemainingMs

    private var pausedRemainingMs: Long = 0L

    fun addListener(l: TimerListener) { listeners += l }
    fun removeListener(l: TimerListener) { listeners -= l }

    /**
     * Rebuild [pendingCues] from [seq] in firing order (largest offset first, gun last).
     *
     * [keep] selects the cues that have not yet fired. It defaults to all of them, which is right
     * whenever the countdown is starting from the top; the two mid-run requeues ([sync] and
     * [restore]) pass their own predicate, and they deliberately differ at the boundary — see each
     * call site for why.
     */
    private fun queueCues(seq: RaceSequence, keep: (SequenceCue) -> Boolean = { true }) {
        pendingCues = ArrayDeque(seq.cues.filter(keep).sortedByDescending { it.offsetMs })
    }

    /** Load a sequence and reset the engine to IDLE. */
    fun load(seq: RaceSequence) {
        sequence = seq
        state = TimerState.IDLE
        pausedRemainingMs = seq.totalMs
        queueCues(seq)
    }

    /**
     * Start (or resume) the countdown.
     * If paused, resumes from where it left off.
     * If IDLE or FINISHED, starts fresh from the beginning.
     */
    fun start() {
        val seq = sequence ?: return
        when (state) {
            // COUNTING_UP alongside RUNNING: both are an active run already under way, and Start
            // has nothing to do to either — a race committee mid-race doesn't get a second gun.
            // RACE_ENDED too: there is no Start control while a summary is on screen (only Done),
            // so this only matters defensively — but starting fresh out from under an unread
            // summary would be a worse surprise than simply doing nothing.
            TimerState.RUNNING, TimerState.COUNTING_UP, TimerState.RACE_ENDED -> return
            TimerState.PAUSED -> {
                // Re-anchor gun time to now + remaining
                gunTimeMs = clock.elapsedMs() + pausedRemainingMs
                state = TimerState.RUNNING
                captureClockBaseline()
            }
            TimerState.IDLE, TimerState.FINISHED -> {
                queueCues(seq)
                gunTimeMs = clock.elapsedMs() + seq.totalMs
                state = TimerState.RUNNING
                captureClockBaseline()
            }
        }
    }

    /** Pause the countdown (retains position). */
    fun pause() {
        if (state != TimerState.RUNNING) return
        pausedRemainingMs = remainingMs
        state = TimerState.PAUSED
    }

    /** Stop and reset to IDLE (full sequence from scratch). */
    fun reset() {
        val seq = sequence ?: return
        state = TimerState.IDLE
        pausedRemainingMs = seq.totalMs
        queueCues(seq)
    }

    /**
     * Stop without resetting (sequence position lost).
     *
     * Also how a [TimerState.RACE_ENDED] summary is dismissed: once the race committee is done
     * reading the final time, this is the same "give up the current run state, return to IDLE"
     * action it always is for [TimerState.RUNNING] or [TimerState.PAUSED].
     */
    fun stop() {
        if (state == TimerState.RUNNING || state == TimerState.PAUSED ||
            state == TimerState.COUNTING_UP || state == TimerState.RACE_ENDED
        ) {
            state = TimerState.IDLE
            // remainingMs reads pausedRemainingMs outside RUNNING/COUNTING_UP (see its getter), and
            // stopping from RACE_ENDED (or PAUSED) leaves it holding a frozen mid-run value —
            // endRace()'s elapsed reading, or pause()'s countdown position. Without this, the idle
            // screen right after dismissing a race summary read "0:00" instead of the sequence's
            // full duration: Stop should leave the engine ready for a fresh countdown, not stuck
            // showing the last run's number.
            sequence?.let { pausedRemainingMs = it.totalMs }
        }
    }

    /**
     * End a [TimerState.COUNTING_UP] race: freezes the current elapsed time and moves to
     * [TimerState.RACE_ENDED] so the final race duration stays on screen for the race committee to
     * read, rather than [stop] snapping straight back to [TimerState.IDLE]. Call [stop] afterward
     * (e.g. a "Done" tap) once they're finished reviewing it.
     *
     * No-op outside [TimerState.COUNTING_UP] — there is nothing to freeze if a race isn't running.
     */
    fun endRace() {
        if (state != TimerState.COUNTING_UP) return
        // remainingMs reads pausedRemainingMs outside RUNNING/COUNTING_UP (see its getter) — capture
        // the live value now so the frozen reading is the elapsed time at the moment of the tap, not
        // whatever pausedRemainingMs last held (stale, from load()).
        pausedRemainingMs = gunTimeMs - clock.elapsedMs()
        state = TimerState.RACE_ENDED
    }

    /**
     * Snap the running countdown to the nearest whole minute.
     *
     * For example, with 4:05 remaining this jumps to 4:00; with 3:52 it jumps to 4:00.
     * The gun-time anchor is updated so that all future cues fire at the correct absolute
     * monotonic times — the display stays correct even after a snap.
     *
     * A double-tap guard prevents a second snap within [guardMs] milliseconds.
     *
     * @param roundDown       If true, prefer rounding down — but never by more than [maxCorrectionMs];
     *                        beyond that it falls back to nearest so a stray tap can't delete a minute.
     *                        Default false (nearest).
     * @param guardMs         Minimum interval between consecutive syncs.
     * @param maxCorrectionMs Maximum amount a single sync may move the clock. A sync is a correction,
     *                        not a jump; nearest is always within half a minute of the current time.
     */
    fun sync(roundDown: Boolean = false, guardMs: Long = 1_000L, maxCorrectionMs: Long = 30_000L) {
        if (state != TimerState.RUNNING) return

        val now = clock.elapsedMs()
        // null == never synced this session, so the first sync is always allowed. (Do not fold this
        // into a sentinel Long: `now - Long.MIN_VALUE` overflows negative and swallowed every
        // first sync of a race.)
        lastSyncTimeMs?.let { if (now - it < guardMs) return }
        lastSyncTimeMs = now

        val remaining = gunTimeMs - now
        var snapped = snapToMinute(remaining, roundDown)

        // A sync is a *correction*, not a jump: never move the clock more than maxCorrectionMs.
        // This guards round-down from flooring a near-full minute away (e.g. 3:55 -> 3:00, a 55s
        // deletion that would silently make the sailor OCS). If the chosen rounding overshoots the
        // bound, fall back to nearest, which is always within half a minute.
        if (kotlin.math.abs(snapped - remaining) > maxCorrectionMs) {
            snapped = snapToMinute(remaining, roundDown = false)
        }

        // Re-anchor the gun time
        gunTimeMs = now + snapped

        // Re-queue only cues that have NOT already fired. A cue at offset O fires when the countdown
        // reaches O, so anything with O >= the pre-snap remaining is already spent; re-adding it (the
        // old `<= snapped` bug) double-fired a horn whenever a sync rounded up onto a fired boundary.
        // Strictly `<` for that reason, unlike restore's `<=`: here the boundary cue has fired.
        val seq = sequence ?: return
        queueCues(seq) { it.offsetMs < remaining }

        listeners.forEach { it.onSync(snapped) }
    }

    /** Monotonic time of the last accepted [sync], or null if none yet. Backs the double-tap guard. */
    private var lastSyncTimeMs: Long? = null

    /** Capture the current wall-clock/monotonic offset as the drift baseline. */
    private fun captureClockBaseline() {
        wallMonoOffsetMs = wallClock.nowMs() - clock.elapsedMs()
    }

    /**
     * Detect a wall-clock jump (e.g. NTP correction) since the countdown started.
     *
     * The countdown is anchored to the monotonic clock, so a wall-clock jump does **not** change
     * the remaining time. But any *derived* wall-clock state (used to survive process death) does
     * drift, so callers should re-persist it when this returns true. Also fires
     * [TimerListener.onClockAdjusted] so the UI can reassure the sailor.
     *
     * Call periodically (e.g. from the tick loop). Re-baselines on detection so each jump is
     * reported once.
     *
     * @param thresholdMs Minimum absolute drift to treat as a jump (default 2 s).
     * @return true if a jump was detected and the baseline was reset.
     */
    fun pollClockAdjustment(thresholdMs: Long = 2_000L): Boolean {
        if (state != TimerState.RUNNING) return false
        val currentOffset = wallClock.nowMs() - clock.elapsedMs()
        val drift = currentOffset - wallMonoOffsetMs
        if (drift >= thresholdMs || drift <= -thresholdMs) {
            wallMonoOffsetMs = currentOffset
            val remaining = gunTimeMs - clock.elapsedMs()
            listeners.forEach { it.onClockAdjusted(remaining) }
            return true
        }
        return false
    }

    /**
     * Milliseconds until the next pending cue is due, or null when there is nothing left to fire.
     *
     * Exists so a caller can *schedule* [tick] against a cue boundary rather than discovering the
     * boundary by polling. Polling costs a cue up to one poll interval of lateness, at random, on
     * every cue: measured on an SM-R925U (#58), cue-to-cue spacing that should have been 1000 ms
     * came out anywhere from 923 to 1096 ms, and roughly half of that spread is the 50 ms poll
     * granularity alone. Cue timing is the whole product here — a sailor starts on it — so the
     * boundary is worth hitting exactly rather than within a poll.
     *
     * The value may be zero or negative when a cue is already due; a caller scheduling on it should
     * clamp rather than treat that as an error. Callers must re-read it after anything that changes
     * the queue or the anchor — [start], [sync], [restore], and each [tick] that fires something.
     *
     * Deliberately does *not* make the engine self-scheduling: it stays pure and clock-injected so
     * it can be tested without a device, and the caller keeps ownership of its own looper.
     */
    fun msUntilNextCue(): Long? {
        if (state != TimerState.RUNNING) return null
        val next = pendingCues.firstOrNull() ?: return null
        return (gunTimeMs - next.offsetMs) - clock.elapsedMs()
    }

    /**
     * Fires any pending cues whose time has arrived and notifies the UI listener.
     *
     * Drive this from [msUntilNextCue] so each cue lands on its boundary. A periodic call is still
     * worth keeping alongside that as a backstop — it is what refreshes the display, and it recovers
     * the cue if a scheduled wake-up is ever missed — but it must not be the only thing firing cues.
     */
    fun tick() {
        // COUNTING_UP included: there are no pendingCues left to fire by the time the engine
        // reaches it (the gun was the last one), but onTick below still needs driving so the
        // elapsed-time display keeps refreshing.
        if (state != TimerState.RUNNING && state != TimerState.COUNTING_UP) return

        val now = clock.elapsedMs()
        val remaining = gunTimeMs - now

        // Fire any cues whose target time has passed
        while (pendingCues.isNotEmpty()) {
            val nextCue = pendingCues.first()
            val cueFiresAt = gunTimeMs - nextCue.offsetMs
            if (now >= cueFiresAt) {
                pendingCues.removeFirst()
                listeners.forEach { it.onCue(nextCue) }
                if (nextCue.isGun) {
                    if (sequence?.countUpAfterFinish == true) {
                        // The race is not over — it is starting. remainingMs stays on the live
                        // gunTimeMs formula (see its getter), so nothing needs freezing here the
                        // way FINISHED's pausedRemainingMs does below.
                        state = TimerState.COUNTING_UP
                    } else {
                        state = TimerState.FINISHED
                        // remainingMs reads pausedRemainingMs outside RUNNING/COUNTING_UP, and
                        // load() left it at the sequence total — so without this the engine reports
                        // a full countdown still to go the instant the gun fires.
                        pausedRemainingMs = 0L
                    }
                    listeners.forEach { it.onGun() }
                }
            } else {
                break
            }
        }

        if (state == TimerState.RUNNING || state == TimerState.COUNTING_UP) {
            listeners.forEach { it.onTick(remaining) }
        }
    }

    // --- Persistence helpers --------------------------------------------------

    /**
     * A point-in-time capture of a running sequence, sufficient to restore it across process death.
     *
     * It carries the gun time in *both* clock domains plus the monotonic reading at capture:
     *  - [gunElapsedMs] is exact within the capturing boot (monotonic time keeps running while the
     *    app is gone) but becomes meaningless after a reboot (elapsedRealtime resets to zero).
     *  - [gunWallMs] survives a reboot but is vulnerable to wall-clock (NTP) steps.
     *  - [capturedElapsedMs] is the monotonic clock reading at capture. Because elapsedRealtime only
     *    ever increases within a boot and resets on reboot, a later reading that is *not smaller*
     *    proves we are in the same boot — so the monotonic gun time is still valid, immune to any
     *    NTP correction. A smaller reading means a reboot happened and we must fall back to wall-clock.
     */
    data class Snapshot(
        val sequenceId: String,
        val gunElapsedMs: Long,
        val gunWallMs: Long,
        val capturedElapsedMs: Long,
    )

    /**
     * Capture the running state for persistence. Returns null unless [TimerState.RUNNING] or
     * [TimerState.COUNTING_UP] — the gun anchor doesn't move once the countdown starts, so the same
     * snapshot taken pre-gun is exactly what [restore] needs to resume a count-up race too; nothing
     * has to be re-captured when the gun fires. [TimerState.RACE_ENDED] is deliberately not
     * snapshotted: it is a brief, dismissible review screen, not state worth surviving process
     * death for — a restore during that window just comes back to IDLE.
     */
    fun snapshot(): Snapshot? {
        val seq = sequence ?: return null
        if (state != TimerState.RUNNING && state != TimerState.COUNTING_UP) return null
        val nowElapsed = clock.elapsedMs()
        val remaining = gunTimeMs - nowElapsed
        return Snapshot(
            sequenceId = seq.id,
            gunElapsedMs = gunTimeMs,
            gunWallMs = wallClock.nowMs() + remaining,
            capturedElapsedMs = nowElapsed,
        )
    }

    /**
     * Restore a running sequence from a previously captured [Snapshot].
     *
     * Reboot detection relies on the one signal a wall-clock step can't forge: elapsedRealtime is
     * monotonic within a boot and resets to zero on reboot. If the current monotonic reading has not
     * gone backwards relative to the snapshot, we are in the same boot and the monotonic gun time is
     * trusted verbatim — drift-free and immune to NTP steps ([RestoreOutcome.EXACT]). If it has gone
     * backwards, a reboot occurred; the gun is reconstructed from wall-clock as a best effort and the
     * caller is told the recovery is [RestoreOutcome.DEGRADED] so it can prompt a re-sync.
     *
     * For a [RaceSequence.countUpAfterFinish] sequence, a gun that already fired while the process
     * was dead is not [RestoreOutcome.EXPIRED] the way it is for every other sequence — a race
     * committee's race is still running whether or not the watch was. Restore resumes straight into
     * [TimerState.COUNTING_UP] on the same gun anchor instead of discarding it.
     */
    fun restore(seq: RaceSequence, snap: Snapshot): RestoreOutcome {
        load(seq)
        val nowElapsed = clock.elapsedMs()
        val sameBoot = nowElapsed >= snap.capturedElapsedMs

        gunTimeMs = gunTimeFromSnapshot(snap, nowElapsed, wallClock.nowMs())

        val remaining = gunTimeMs - nowElapsed
        return when {
            remaining <= 0L && seq.countUpAfterFinish -> {
                // Nothing left to fire — the gun already sounded, dead process or not.
                queueCues(seq) { false }
                state = TimerState.COUNTING_UP
                captureClockBaseline()
                if (sameBoot) RestoreOutcome.EXACT else RestoreOutcome.DEGRADED
            }
            remaining <= 0L -> {
                state = TimerState.FINISHED
                pausedRemainingMs = 0L   // see tick(): the gun has fired, nothing is left to run
                RestoreOutcome.EXPIRED
            }
            else -> {
                // `<=`, unlike sync's strict `<`: nothing fired while the process was dead, so a cue
                // sitting exactly on `remaining` is still to come and tick() should sound it at once.
                queueCues(seq) { it.offsetMs <= remaining }
                state = TimerState.RUNNING
                captureClockBaseline()
                if (sameBoot) RestoreOutcome.EXACT else RestoreOutcome.DEGRADED
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Snapshot helpers (pure, testable)
//
// Extracted from TimerEngine.restore rather than copied out of it, and called by it, so a caller
// that wants to know what a snapshot holds *without* committing to it cannot drift from what
// restoring actually does. The drift would be silent and one-directional: a preview that disagrees
// with the restore shows a sailor one number and then starts them on another.
// ---------------------------------------------------------------------------

/**
 * The monotonic gun time [snap] restores to, given current readings of both clocks.
 *
 * Reboot detection is [TimerEngine.restore]'s: `elapsedRealtime` only ever increases within a boot
 * and resets to zero across one, so a current reading that has not gone backwards proves the
 * monotonic gun time is still valid — and it is then preferred, because it is immune to the NTP
 * steps the wall-clock reconstruction is exposed to.
 */
fun gunTimeFromSnapshot(snap: TimerEngine.Snapshot, nowElapsedMs: Long, nowWallMs: Long): Long =
    if (nowElapsedMs >= snap.capturedElapsedMs) {
        snap.gunElapsedMs                              // exact: monotonic domain intact
    } else {
        nowElapsedMs + (snap.gunWallMs - nowWallMs)    // degraded: wall-clock reconstruction
    }

/**
 * What [TimerEngine.restore] would put on the clock, computed without restoring anything.
 *
 * Lets the pre-start screen offer "Resume" against the number resuming will actually give, rather
 * than against the sequence's full duration — which is what it used to show, so a saved race read
 * 8:00 on screen and then resumed at 6:00 the instant it was tapped.
 *
 * Negative once the saved gun has passed. That is not automatically a spent race: for a
 * [RaceSequence.countUpAfterFinish] sequence it is the elapsed race time, negated, and restoring
 * resumes the count-up (see [TimerEngine.restore]). Callers must decide with the sequence in hand.
 */
fun remainingFromSnapshot(snap: TimerEngine.Snapshot, nowElapsedMs: Long, nowWallMs: Long): Long =
    gunTimeFromSnapshot(snap, nowElapsedMs, nowWallMs) - nowElapsedMs

/**
 * The number a "Resume" offer may stand on, or null when the saved race must not be offered at all.
 *
 * The offer on screen and what Start actually does have to agree, and there are two ways they drift:
 *
 * 1. **A different sequence is selected.** `TimerService.onStartCommand` restores only when the
 *    persisted id equals the id Start sends, so a saved `custom_8m` has no claim on a screen showing
 *    `custom_5m` — a tap there runs 5:00 from the top. Custom reaches this without ever leaving the
 *    sequence, because re-dialling the stepper changes the id, which is how the preview came to sit
 *    on the previous duration while Start correctly ran the new one.
 * 2. **The saved gun has already passed.** A spent countdown has no race to come back to. A
 *    [RaceSequence.countUpAfterFinish] race is the exception — past the gun is where it lives, and
 *    restoring resumes the count-up — so its negative reading is returned rather than refused.
 *
 * Returned rather than acted on, so a caller holding the snapshot keeps it: withholding the offer is
 * not the same as discarding the race, which only Stop, a fresh start, or the post-gun teardown do.
 * Selecting the saved race's own sequence again therefore brings the offer back.
 */
fun resumeOfferRemainingMs(
    snap: TimerEngine.Snapshot,
    sequence: RaceSequence,
    selectedSequenceId: String,
    nowElapsedMs: Long,
    nowWallMs: Long,
): Long? {
    if (snap.sequenceId != selectedSequenceId) return null
    val remaining = remainingFromSnapshot(snap, nowElapsedMs, nowWallMs)
    return if (remaining <= 0L && !sequence.countUpAfterFinish) null else remaining
}

// ---------------------------------------------------------------------------
// Sync helper (pure, testable)
// ---------------------------------------------------------------------------

/**
 * Snap [remainingMs] to the nearest whole minute (60 000 ms).
 *
 * @param roundDown  If true, always rounds toward zero (sailor never gains time).
 */
fun snapToMinute(remainingMs: Long, roundDown: Boolean = false): Long {
    if (remainingMs <= 0L) return 0L
    val minute = 60_000L
    val lower = (remainingMs / minute) * minute
    val upper = lower + minute
    return if (roundDown) {
        lower
    } else {
        if (remainingMs - lower <= upper - remainingMs) lower else upper
    }
}
