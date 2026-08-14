package com.racetimer.phone

import com.racetimer.android.SystemMonotonicClock
import com.racetimer.phone.ui.PhoneReadout
import com.racetimer.phone.ui.displayedElapsedMs
import com.racetimer.phone.ui.displayedRemainingMs
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.MonotonicClock
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.TimerEngine
import com.racetimer.shared.TimerListener
import com.racetimer.shared.TimerState

/**
 * The race and its cue path, owned by whoever must outlive the screen (#202, #203).
 *
 * This class was `PhoneTimerViewModel` while the phone was foreground-only: a `ViewModel` outlives a
 * rotation, which was the longest lifetime the countdown needed. #203 makes the race survive the
 * screen going off, and the thing that outlives *that* is [PhoneTimerService] — so the logic moved
 * out of the ViewModel class and the service owns an instance instead. A plain class rather than
 * anything lifecycle-aware, because its two owners so far have different lifecycles and the tests
 * have none.
 *
 * The clock is injected. Production passes [SystemMonotonicClock] — `elapsedRealtime`, immune to
 * NTP corrections and to the officer setting the phone's clock — and tests pass a fake, which is
 * what lets the countdown be driven through a whole race in a unit test with no device.
 *
 * ### The cue path is scheduled, never polled (#202)
 *
 * Cues dispatch through [cueScheduler], armed on each cue's own boundary from
 * [TimerEngine.msUntilNextCue] — the watch's `scheduleNextCue` pattern, which replaced a poll that
 * put every cue up to ±212 ms out (#58). The polls that do exist — the service's notification tick
 * and the composition's display refresh — still call [tick] and so *can* fire a cue, deliberately:
 * the engine dequeues under its own guard, so a poll is a backstop that recovers a missed wake-up
 * and can never double-fire. What it must never be is the thing the cue path relies on, and the
 * unit test proves it is not by driving a whole race through the scheduler with no poll running.
 *
 * [cueSounder] and [cueScheduler] default to no-ops so a test or preview can construct this without
 * an audio stack; production construction is [PhoneTimerService.onCreate], which supplies the real
 * pair.
 */
class PhoneRaceRunner(
    clock: MonotonicClock = SystemMonotonicClock,
    private val cueSounder: CueSounder = CueSounder.SILENT,
    private val cueScheduler: CueScheduler = CueScheduler.NONE,
) {

    val engine = TimerEngine(clock)

    /** The sequences this runner offers — see [CONSOLE_SEQUENCES]. */
    val sequences: List<RaceSequence> = CONSOLE_SEQUENCES

    var selected: RaceSequence = sequences.first()
        private set

    /**
     * Sounds each cue as the engine fires it, whichever path noticed it was due.
     *
     * Registered for the life of the runner rather than per race, so a cue can never fire into
     * a gap between races where nobody was listening. Haptics are deliberately absent — that is
     * #208, and the watch's ordering lesson (vibration first, audio best-effort) arrives with it.
     */
    private val cueListener = object : TimerListener {
        override fun onCue(cue: SequenceCue) {
            cueSounder.playCue(cue.signal)
        }

        override fun onGun() {}

        override fun onTick(remainingMs: Long) {}

        override fun onSync(snappedToMs: Long) {}
    }

    /**
     * Dispatches the cue whose boundary just arrived, then arms the next one.
     *
     * [TimerEngine.tick] is what actually fires the cue (through [cueListener]); this runnable is
     * only the wake-up that makes it happen *on* the boundary rather than at a poll's convenience.
     */
    private val cueDispatch = Runnable {
        engine.tick()
        armCueDispatch()
    }

    init {
        engine.load(selected)
        engine.addListener(cueListener)
        // Both halves of the pre-race cost, paid at construction: the track is built and idling
        // (#114) and the selected sequence's cues are rendered (#98) long before a Start can happen.
        cueSounder.prepare()
        cueSounder.warmUp(selected.cues.map { it.signal })
    }

    /** Choose the sequence to run. Loading it is what puts its full duration on the idle screen. */
    fun select(sequence: RaceSequence) {
        selected = sequence
        engine.load(sequence)
        // Render the new pick now, seconds ahead of any Start — the head start #98 measured the
        // first cue losing without. Shapes are cached, so re-picking re-renders almost nothing.
        cueSounder.warmUp(sequence.cues.map { it.signal })
    }

    fun start() {
        // Re-prepare at arm, the watch's twice-called pattern: the construction-time track can have
        // been torn down by an audio-stack hiccup, and rebuilding it here still lands ahead of the
        // first cue. In the ordinary case this costs one comparison (#114).
        cueSounder.prepare()
        engine.start()
        // Fire what is already due, synchronously. The first cue of every sequence sits at
        // `offsetMs == totalMs`, due the instant the gun is anchored — the watch measured leaving
        // it to the next scheduled pass at ~170 ms late (#62).
        engine.tick()
        armCueDispatch()
    }

    /**
     * Abandon the run and return to a fresh copy of the same sequence.
     *
     * `stop()` alone leaves the engine IDLE with a zeroed readout; reloading is what puts the full
     * duration back on screen, so a stopped race and a not-yet-started one look the same — which is
     * what they are.
     */
    fun stop() {
        engine.stop()
        engine.load(selected)
        // With nothing running, msUntilNextCue is null and this only disarms the pending dispatch —
        // a cue must not fire out of a race the officer just ended.
        armCueDispatch()
    }

    /**
     * Snap the countdown to the nearest minute (#204's control; the mechanism lands with #203).
     *
     * The engine refuses on its own terms — outside RUNNING, in a lead-in, under the double-tap
     * guard — and the re-arm after a refused sync is a no-op, so this is unconditional the same way
     * the watch's ACTION_SYNC handler is: re-arming costs one cancel-and-post, missing an accepted
     * sync aims every remaining cue at the wrong boundary.
     *
     * The caller that holds the wake lock must re-size it after this — a sync can move the gun
     * *later*, and #126 measured the lock expiring mid-race when only Start ever sized it. That
     * re-acquire lives in [PhoneTimerService], beside the lock it re-sizes.
     */
    fun sync() {
        engine.sync()
        armCueDispatch()
    }

    /**
     * Advance the engine and report what the screen should now show.
     *
     * Polled — by the service's notification tick and by the composition while the timer screen is
     * visible — which is right for a *display*: it is second-granular and the anchor is monotonic,
     * so a late poll reads the correct time rather than a drifted one. The cue path does not rely
     * on any poll (see the class doc); the re-arm below is the backstop half of the watch's
     * pattern, keeping the scheduled dispatch aimed at the right boundary after anything this tick
     * may have fired.
     */
    fun tick(): PhoneReadout {
        engine.tick()
        armCueDispatch()
        return readout()
    }

    /** The current readout without advancing anything — what the screen draws before the first tick. */
    fun readout(): PhoneReadout {
        val state = engine.currentState
        val remaining = engine.remainingMs
        val elapsed = if (state == TimerState.COUNTING_UP || state == TimerState.RACE_ENDED) {
            displayedElapsedMs(-remaining)
        } else {
            0L
        }
        return PhoneReadout.of(state, displayedRemainingMs(remaining), elapsed)
    }

    /**
     * (Re)aim the scheduled dispatch at the next cue boundary, or disarm when nothing is pending.
     *
     * Runs after anything that moves the queue or the anchor — start, stop, sync, a dispatch that
     * fired, and every poll tick as the backstop — because all of those change which cue is next
     * and when. The `coerceAtLeast` covers a boundary that slipped past while this was being
     * computed: arming at zero fires it now, which is as close to on time as it can still be.
     */
    private fun armCueDispatch() {
        cueScheduler.cancel()
        val dueInMs = engine.msUntilNextCue() ?: return
        cueScheduler.armIn(dueInMs.coerceAtLeast(0L), cueDispatch)
    }

    /** Tear the cue path down. The owner is going away; nothing plays after this. */
    fun release() {
        cueScheduler.cancel()
        engine.removeListener(cueListener)
        cueSounder.release()
    }

    companion object {
        /**
         * The sailor sequences the console clock offers.
         *
         * Not [BuiltInSequences.all]: that list also carries the two race-manager variants, whose
         * defining behaviour is counting *up* after the gun (#206), and a mode listed before its
         * post-gun half exists would look shipped and end the race at the gun.
         *
         * A companion rather than instance-only state so the picker can render before the service
         * binding lands — the list is a fact about the app, not about a particular race.
         */
        val CONSOLE_SEQUENCES: List<RaceSequence> = listOf(
            BuiltInSequences.usSailing,
            BuiltInSequences.scholastic,
            BuiltInSequences.club,
        )
    }
}
