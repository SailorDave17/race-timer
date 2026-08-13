package com.racetimer.phone

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.racetimer.phone.ui.DisplayChoiceScreen
import com.racetimer.phone.ui.PhoneTheme
import com.racetimer.phone.ui.SequencePickerScreen
import com.racetimer.phone.ui.TimerScreen
import com.racetimer.shared.BG_NORMAL_ARGB
import com.racetimer.shared.TimerState
import kotlinx.coroutines.delay

/**
 * How often the composition asks the engine where it is.
 *
 * This is a *display* refresh, not a cue schedule. The readout is second-granular and the engine is
 * anchored to `elapsedRealtime`, so a poll that arrives late reads the correct remaining time rather
 * than a drifted one — the error a poll introduces is in *when the screen changes*, bounded by this
 * interval, and never in what it says. A cue cannot be driven this way and is not: scheduling the
 * audio against the anchor is #202, and it does not go through here.
 */
private const val UI_REFRESH_MS = 50L

/**
 * The phone app: a standalone start-sequence timer for the committee-boat console.
 *
 * Three screens: set the screen up for the day (#225), pick a sequence, then run it. The last two
 * mirror the watch, so an officer glancing between a wrist and a console is looking at the same
 * product twice rather than two apps that agree.
 *
 * The display choice stands *in front of* the picker rather than beside it, and cannot be bypassed —
 * which is what makes a tap-through land on a known combination rather than on whatever the platform
 * would otherwise have done.
 *
 * What it deliberately does not do yet, each with the story that brings it:
 *  - sound anything (#202) or survive the screen going off (#203)
 *  - sync to the flag (#204), restore after a kill (#205), count up after the gun (#206)
 *
 * One known gap, pre-dating this story and deliberately not fixed here: which *screen* is showing is
 * `remember`ed rather than saved, so rotating mid-race returns to the picker. The engine keeps
 * running on its own anchor (it is in a `ViewModel`) and the display choice is retained, so nothing
 * is lost but the position — but it is a real defect and belongs to a story of its own.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The window's own background, so the frame before Compose first draws is the app's colour
        // rather than the system's. Read from the shared constant, not restated — the same rule the
        // rest of this module follows for colour (#197 AC 3).
        window.setBackgroundDrawable(ColorDrawable(BG_NORMAL_ARGB.toInt()))
        setContent {
            PhoneTheme {
                // The window is handed in as a lambda rather than looked up from the composition.
                // It keeps `RaceTimerApp` free of an activity cast, and it keeps the one call to
                // #199's mechanism at the edge of the app where the window actually lives.
                RaceTimerApp(applyDisplay = { choice ->
                    window.applyDisplayProperties(choice.keepScreenOn, choice.fullBrightness)
                })
            }
        }
    }
}

/**
 * `internal` rather than private so the routing tests can render it with a recording
 * [applyDisplay]. That is what lets all four combinations be asserted as *what was handed to the
 * mechanism* — positive evidence in every case, including the all-off corner, which on a real
 * window is indistinguishable from nothing having run.
 */
@Composable
internal fun RaceTimerApp(
    applyDisplay: (DisplayChoice) -> Unit,
    viewModel: PhoneTimerViewModel = viewModel(),
    displayChoice: DisplayChoiceViewModel = viewModel(),
) {
    var onTimerScreen by remember { mutableStateOf(false) }
    var readout by remember { mutableStateOf(viewModel.readout()) }
    var state by remember { mutableStateOf(viewModel.engine.currentState) }

    // Keyed on the choice as well as on having been answered, so a later surface that lets the
    // officer change their mind mid-day applies without anyone remembering to add a call here.
    // Nothing is applied before the surface is answered: until then the phone behaves as an
    // unmodified one, which is also what makes the choice screen itself an honest preview.
    LaunchedEffect(displayChoice.answered, displayChoice.choice) {
        if (displayChoice.answered) {
            applyDisplay(displayChoice.choice)
        }
    }

    // The one loop that drives everything on screen. It runs only while the timer screen is in
    // composition, which is also the only time anything it produces is visible; the engine keeps its
    // own monotonic anchor regardless, so a loop that stops and restarts reads the true remaining
    // time on its next pass rather than resuming a count.
    LaunchedEffect(onTimerScreen) {
        while (onTimerScreen) {
            readout = viewModel.tick()
            state = viewModel.engine.currentState
            delay(UI_REFRESH_MS)
        }
    }

    val running = state == TimerState.RUNNING

    // Back returns to the picker, but never mid-race: the gesture is one an officer makes without
    // looking, and it must not be able to end a start sequence. While running it falls through to
    // the system, which backgrounds the app with the engine still anchored.
    BackHandler(enabled = onTimerScreen && !running) {
        viewModel.stop()
        readout = viewModel.readout()
        state = viewModel.engine.currentState
        onTimerScreen = false
    }

    if (!displayChoice.answered) {
        // AC 1: the picker is not reachable from here. There is no skip affordance and no back
        // route into this branch — `answered` is set once and never cleared, so the surface is
        // passed exactly once per process and the two screens below become reachable together.
        DisplayChoiceScreen(
            keepScreenOn = displayChoice.choice.keepScreenOn,
            fullBrightness = displayChoice.choice.fullBrightness,
            onKeepScreenOnChange = displayChoice::setKeepScreenOn,
            onFullBrightnessChange = displayChoice::setFullBrightness,
            onContinue = displayChoice::confirm,
        )
    } else if (onTimerScreen) {
        TimerScreen(
            readout = readout,
            sequenceName = viewModel.selected.name,
            running = running,
            onStart = {
                viewModel.start()
                readout = viewModel.readout()
                state = viewModel.engine.currentState
            },
            onStop = {
                viewModel.stop()
                readout = viewModel.readout()
                state = viewModel.engine.currentState
            },
        )
    } else {
        SequencePickerScreen(
            sequences = viewModel.sequences,
            onSelect = { sequence ->
                viewModel.select(sequence)
                readout = viewModel.readout()
                state = viewModel.engine.currentState
                onTimerScreen = true
            },
        )
    }
}
