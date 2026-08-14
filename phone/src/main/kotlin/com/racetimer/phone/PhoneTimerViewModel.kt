package com.racetimer.phone

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
 * Holds the race for as long as the app is up.
 *
 * A `ViewModel` rather than state in the activity, because a phone propped on a committee-boat
 * console gets picked up and turned: an activity-owned engine would be destroyed and rebuilt on
 * that rotation, restarting the countdown at the one moment nobody can afford it. The audio path
 * lives here for the same reason — a rotation mid-race must not release a sounding track or
 * re-pay `startOutput`. Survival across *process death* is a different problem and a different
 * story (#205).
 *
 * The clock is injected. Production passes [SystemMonotonicClock] — `elapsedRealtime`, immune to
 * NTP corrections and to the officer setting the phone's clock — and tests pass a fake, which is
 * what lets the countdown be driven through a whole race in a unit test with no device.
 *
 * ### The cue path is scheduled, never polled (#202)
 *
 * Cues dispatch through [cueScheduler], armed on each cue's own boundary from
 * [TimerEngine.msUntilNextCue] — the watch's `scheduleNextCue` pattern, which replaced a poll that
 * put every cue up to ±212 ms out (#58). The display's 50 ms poll ([tick]) still calls
 * [TimerEngine.tick] and so *can* fire a cue, deliberately: the engine dequeues under its own
 * guard, so the poll is a backstop that recovers a missed wake-up and can never double-fire. What
 * it must never be is the thing the cue path relies on, and the unit test proves it is not by
 * driving a whole race through the scheduler with the poll never running.
 *
 * [cueSounder] and [cueScheduler] default to no-ops so a test or preview can construct this without
 * an audio stack; production construction goes through [factory], which supplies the real pair.
 */
class PhoneTimerViewModel(
    clock: MonotonicClock = SystemMonotonicClock,
    private val cueSounder: CueSounder = CueSounder.SILENT,
    private val cueScheduler: CueScheduler = CueScheduler.NONE,
) : ViewModel() {

    val engine = TimerEngine(clock)

    /**
     * The sailor sequences the console clock offers.
     *
     * Not [BuiltInSequences.all]: that list also carries the two race-manager variants, whose
     * defining behaviour is counting *up* after the gun (#206), and a mode listed before its
     * post-gun half exists would look shipped and end the race at the gun.
     */
    val sequences: List<RaceSequence> = listOf(
        BuiltInSequences.usSailing,
        BuiltInSequences.scholastic,
        BuiltInSequences.club,
    )

    var selected: RaceSequence = sequences.first()
        private set

    /**
     * Sounds each cue as the engine fires it, whichever path noticed it was due.
     *
     * Registered for the life of the ViewModel rather than per race, so a cue can never fire into
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
     * only the wake-up that makes it happen *on* the boundary rather than at the display poll's
     * convenience.
     */
    private val cueDispatch = Runnable {
        engine.tick()
        armCueDispatch()
    }

    init {
        engine.load(selected)
        engine.addListener(cueListener)
        // Both halves of the pre-race cost, paid at launch: the track is built and idling (#114)
        // and the selected sequence's cues are rendered (#98) long before a Start can happen.
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
        // Re-prepare at arm, the watch's twice-called pattern: the launch-time track can have been
        // torn down by an audio-stack hiccup, and rebuilding it here still lands ahead of the
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
     * Advance the engine and report what the screen should now show.
     *
     * Polled from the composition (see `MainActivity`), which is right for a *display* — it is
     * second-granular and the anchor is monotonic, so a late poll reads the correct time rather
     * than a drifted one. The cue path does not rely on this loop (see the class doc); the re-arm
     * below is the backstop half of the watch's pattern, keeping the scheduled dispatch aimed at
     * the right boundary after anything this tick may have fired.
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
     * Runs after anything that moves the queue or the anchor — start, stop, a dispatch that fired,
     * and every display tick as the backstop — because all of those change which cue is next and
     * when. The `coerceAtLeast` covers a boundary that slipped past while this was being computed:
     * arming at zero fires it now, which is as close to on time as it can still be.
     */
    private fun armCueDispatch() {
        cueScheduler.cancel()
        val dueInMs = engine.msUntilNextCue() ?: return
        cueScheduler.armIn(dueInMs.coerceAtLeast(0L), cueDispatch)
    }

    override fun onCleared() {
        cueScheduler.cancel()
        engine.removeListener(cueListener)
        cueSounder.release()
    }

    companion object {
        /**
         * Production construction: the real audio path and the real scheduler.
         *
         * A factory rather than constructor defaults because [PhoneCueSounder] needs a `Context`
         * the ViewModel deliberately does not hold. `MainActivity` is the one caller; a
         * `viewModel()` obtained anywhere without this factory gets the silent defaults, which is
         * why the activity passes its instance down explicitly rather than letting the composition
         * resolve its own.
         */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return PhoneTimerViewModel(
                        cueSounder = PhoneCueSounder(appContext),
                        cueScheduler = HandlerCueScheduler(),
                    ) as T
                }
            }
        }
    }
}
