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
 * @param clock  Injectable monotonic clock (defaults to [SystemMonotonicClock]).
 */
/**
 * @param clock  Injectable monotonic clock. In production, pass [com.racetimer.wear.SystemMonotonicClock]
 *               (or any [MonotonicClock] backed by [android.os.SystemClock.elapsedRealtime]).
 *               In unit tests, inject a fake clock via a lambda: `MonotonicClock { fakeNow }`.
 */
class TimerEngine(private val clock: MonotonicClock) {

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

    /** Most recently fired cue index (for state restoration). */
    private var lastFiredCueOffset: Long = Long.MAX_VALUE

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
        lastFiredCueOffset = Long.MAX_VALUE
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
        lastFiredCueOffset = Long.MAX_VALUE
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
     * @param roundDown  If true, always round down (never gain time).  Default false (nearest).
     * @param guardMs    Minimum interval between consecutive syncs.
     */
    fun sync(roundDown: Boolean = false, guardMs: Long = 1_000L) {
        if (state != TimerState.RUNNING) return

        val now = clock.elapsedMs()
        if (now - lastSyncTimeMs < guardMs) return
        lastSyncTimeMs = now

        val remaining = gunTimeMs - now
        val snapped = snapToMinute(remaining, roundDown)

        // Re-anchor the gun time
        gunTimeMs = now + snapped

        // Re-queue all cues that should still fire
        val seq = sequence ?: return
        pendingCues = ArrayDeque(
            seq.cues
                .filter { it.offsetMs <= snapped }
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
     * Returns the absolute wall-clock target time (ms since epoch) for the gun so that
     * state can be persisted and restored across process restarts.
     *
     * Because we use monotonic time internally, this is a best-effort approximation via
     * the difference between wall clock and monotonic offset.
     */
    fun gunWallClockMs(): Long {
        val monotonicOffsetMs = gunTimeMs - clock.elapsedMs()
        return System.currentTimeMillis() + monotonicOffsetMs
    }

    /**
     * Restore a running sequence from a previously persisted [gunWallClockMs] value.
     * Call this after [load] if the process was killed while running.
     */
    fun restoreFromWallClock(seq: RaceSequence, gunWallClockMs: Long) {
        load(seq)
        val monotonicNow = clock.elapsedMs()
        val wallNow = System.currentTimeMillis()
        gunTimeMs = monotonicNow + (gunWallClockMs - wallNow)
        val remaining = gunTimeMs - monotonicNow
        if (remaining <= 0) {
            state = TimerState.FINISHED
        } else {
            pendingCues = ArrayDeque(
                seq.cues
                    .filter { it.offsetMs <= remaining }
                    .sortedByDescending { it.offsetMs }
            )
            state = TimerState.RUNNING
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
