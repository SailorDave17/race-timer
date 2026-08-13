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
 * Two screens — pick a sequence, then run it — mirroring the watch, so an officer glancing between
 * a wrist and a console is looking at the same product twice rather than two apps that agree.
 *
 * What it deliberately does not do yet, each with the story that brings it:
 *  - sound anything (#202) or survive the screen going off (#203)
 *  - hold the screen on or drive the panel bright (#199 the mechanism, #225 who chooses)
 *  - sync to the flag (#204), restore after a kill (#205), count up after the gun (#206)
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
                RaceTimerApp()
            }
        }
    }
}

@Composable
private fun RaceTimerApp(viewModel: PhoneTimerViewModel = viewModel()) {
    var onTimerScreen by remember { mutableStateOf(false) }
    var readout by remember { mutableStateOf(viewModel.readout()) }
    var state by remember { mutableStateOf(viewModel.engine.currentState) }

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

    if (onTimerScreen) {
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
