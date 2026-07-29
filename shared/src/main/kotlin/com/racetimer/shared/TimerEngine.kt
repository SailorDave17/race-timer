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
 *  5. Call [stop] / [reset] to cancel.
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

    private val listeners = mutableListOf<TimerListener>()

    // --- Public API -----------------------------------------------------------

    val currentState: TimerState get() = state
    val loadedSequence: RaceSequence? get() = sequence

    /** Remaining milliseconds to the gun; 0 once finished; negative after the gun. */
    val remainingMs: Long
        get() = if (state == TimerState.RUNNING) gunTimeMs - clock.elapsedMs()
        else pausedRemainingMs

    private var pausedRemainingMs: Long = 0L

    fun addListener(l: TimerListener) { listeners += l }
    fun removeListener(l: TimerListener) { listeners -= l }

    /** Load a sequence and reset the engine to IDLE. */
    fun load(seq: RaceSequence) {
        sequence = seq
        state = TimerState.IDLE
        pausedRemainingMs = seq.totalMs
        pendingCues = ArrayDeque(seq.cues.sortedByDescending { it.offsetMs })
    }

    /**
     * Start (or resume) the countdown.
     * If paused, resumes from where it left off.
     * If IDLE or FINISHED, starts fresh from the beginning.
     */
    fun start() {
        val seq = sequence ?: return
        when (state) {
            TimerState.RUNNING -> return
            TimerState.PAUSED -> {
                // Re-anchor gun time to now + remaining
                gunTimeMs = clock.elapsedMs() + pausedRemainingMs
                state = TimerState.RUNNING
            }
            TimerState.IDLE, TimerState.FINISHED -> {
                pendingCues = ArrayDeque(seq.cues.sortedByDescending { it.offsetMs })
                gunTimeMs = clock.elapsedMs() + seq.totalMs
                state = TimerState.RUNNING
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
        pendingCues = ArrayDeque(seq.cues.sortedByDescending { it.offsetMs })
    }

    /** Stop without resetting (sequence position lost). */
    fun stop() {
        if (state == TimerState.RUNNING || state == TimerState.PAUSED) {
            state = TimerState.IDLE
        }
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
        if (now - lastSyncTimeMs < guardMs) return
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
        val seq = sequence ?: return
        pendingCues = ArrayDeque(
            seq.cues
                .filter { it.offsetMs < remaining }
                .sortedByDescending { it.offsetMs }
        )

        listeners.forEach { it.onSync(snapped) }
    }

    private var lastSyncTimeMs: Long = Long.MIN_VALUE

    /**
     * Must be called periodically (e.g. every 100 ms from a coroutine or Handler).
     * Fires any pending cues whose time has arrived and notifies the UI listener.
     */
    fun tick() {
        if (state != TimerState.RUNNING) return

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
                    state = TimerState.FINISHED
                    listeners.forEach { it.onGun() }
                }
            } else {
                break
            }
        }

        if (state == TimerState.RUNNING) {
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

    /** Capture the running state for persistence. Returns null unless [TimerState.RUNNING]. */
    fun snapshot(): Snapshot? {
        val seq = sequence ?: return null
        if (state != TimerState.RUNNING) return null
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
     */
    fun restore(seq: RaceSequence, snap: Snapshot): RestoreOutcome {
        load(seq)
        val nowElapsed = clock.elapsedMs()
        val sameBoot = nowElapsed >= snap.capturedElapsedMs

        gunTimeMs = if (sameBoot) {
            snap.gunElapsedMs                              // exact: monotonic domain intact
        } else {
            nowElapsed + (snap.gunWallMs - wallClock.nowMs())  // degraded: wall-clock reconstruction
        }

        val remaining = gunTimeMs - nowElapsed
        return when {
            remaining <= 0L -> {
                state = TimerState.FINISHED
                RestoreOutcome.EXPIRED
            }
            else -> {
                pendingCues = ArrayDeque(
                    seq.cues
                        .filter { it.offsetMs <= remaining }
                        .sortedByDescending { it.offsetMs }
                )
                state = TimerState.RUNNING
                if (sameBoot) RestoreOutcome.EXACT else RestoreOutcome.DEGRADED
            }
        }
    }
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
