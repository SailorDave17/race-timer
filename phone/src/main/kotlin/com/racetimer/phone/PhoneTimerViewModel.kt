package com.racetimer.phone

import androidx.lifecycle.ViewModel
import com.racetimer.android.SystemMonotonicClock
import com.racetimer.phone.ui.PhoneReadout
import com.racetimer.phone.ui.displayedElapsedMs
import com.racetimer.phone.ui.displayedRemainingMs
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.MonotonicClock
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.TimerEngine
import com.racetimer.shared.TimerState

/**
 * Holds the race for as long as the app is up.
 *
 * A `ViewModel` rather than state in the activity, because a phone propped on a committee-boat
 * console gets picked up and turned: an activity-owned engine would be destroyed and rebuilt on
 * that rotation, restarting the countdown at the one moment nobody can afford it. Survival across
 * *process death* is a different problem and a different story (#205).
 *
 * The clock is injected. Production passes [SystemMonotonicClock] — `elapsedRealtime`, immune to
 * NTP corrections and to the officer setting the phone's clock — and tests pass a fake, which is
 * what lets the countdown be driven through a whole race in a unit test with no device.
 *
 * Deliberately silent: [TimerEngine] fires its cues to listeners and this registers none. Sounding
 * them is #202, and doing it here would put a cue path in the module before the story that measures
 * what a cue on a phone should even be declared as.
 */
class PhoneTimerViewModel(
    clock: MonotonicClock = SystemMonotonicClock,
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

    init {
        engine.load(selected)
    }

    /** Choose the sequence to run. Loading it is what puts its full duration on the idle screen. */
    fun select(sequence: RaceSequence) {
        selected = sequence
        engine.load(sequence)
    }

    fun start() = engine.start()

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
    }

    /**
     * Advance the engine and report what the screen should now show.
     *
     * Polled from the composition (see `MainActivity`), which is right for a *display* — it is
     * second-granular and the anchor is monotonic, so a late poll reads the correct time rather
     * than a drifted one. It would be wrong for a *cue*, which has to be scheduled against the
     * anchor; that path arrives with #202 and does not go through here.
     */
    fun tick(): PhoneReadout {
        engine.tick()
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
}
