package com.racetimer.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.IBinder
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
import com.racetimer.phone.ui.PhoneReadout
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
 * interval, and never in what it says. A cue cannot be driven this way and is not: the audio is
 * scheduled against the anchor in [PhoneRaceRunner]'s cue path (#202), and it does not go through
 * here.
 */
private const val UI_REFRESH_MS = 50L

/**
 * The phone app: a standalone start-sequence timer for the committee-boat console.
 *
 * Three screens: set the screen up for the day (#225), pick a sequence, then run it. The last two
 * mirror the watch, so an officer glancing between a wrist and a console is looking at the same
 * product twice rather than two apps that agree.
 *
 * The race itself lives in [PhoneTimerService] (#203): this activity binds to read it, and Start
 * and Stop travel as service intents, so backgrounding the app or the screen sleeping takes the UI
 * away and nothing else. The activity holds **no** copy of the service's foreground-refusal state,
 * deliberately — the watch's activity-side twin of that flag is a one-way latch whose own remedy
 * cannot clear it (#165), and the phone declines to inherit the pattern.
 *
 * What it deliberately does not do yet, each with the story that brings it:
 *  - sync to the flag (#204), restore after a kill (#205), count up after the gun (#206)
 *
 * One known gap, pre-dating this story and deliberately not fixed here: which *screen* is showing
 * is `remember`ed rather than saved, so rotating mid-race returns to the picker — and now that the
 * race lives in a service the idle-state selection resets with the rebind too. The engine keeps
 * running through it all (it is in the service); the gap is position and pick, and it belongs to a
 * story of its own.
 */
class MainActivity : ComponentActivity() {

    /** The service's race, or null before the binding lands. Compose state so the UI follows it. */
    private val runnerState = mutableStateOf<PhoneRaceRunner?>(null)

    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val lb = binder as? PhoneTimerService.LocalBinder ?: return
            runnerState.value = lb.service.runner
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runnerState.value = null
        }
    }

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
                RaceTimerApp(
                    applyDisplay = { choice ->
                        window.applyDisplayProperties(choice.keepScreenOn, choice.fullBrightness)
                    },
                    runner = runnerState.value,
                    onStartRace = { PhoneTimerService.start(this) },
                    onStopRace = { PhoneTimerService.stop(this) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // BIND_AUTO_CREATE: between races the service lives exactly as long as something is bound
        // to it. A race in progress is different — ACTION_START made the service *started* and
        // foreground, so unbinding below takes the UI away and nothing else.
        serviceBound = bindService(
            Intent(this, PhoneTimerService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        runnerState.value = null
        super.onStop()
    }
}

/**
 * `internal` rather than private so the routing tests can render it with a recording
 * [applyDisplay]. That is what lets all four combinations be asserted as *what was handed to the
 * mechanism* — positive evidence in every case, including the all-off corner, which on a real
 * window is indistinguishable from nothing having run.
 *
 * [runner] defaults to a locally remembered silent one so those tests can also drive a race
 * through the UI with no service; production passes the bound service's runner (null until the
 * binding lands, which the UI guards). [onStartRace]/[onStopRace] default to driving [runner]
 * directly — production passes the service intents instead, which is what makes Start survive the
 * screen going off.
 */
@Composable
internal fun RaceTimerApp(
    applyDisplay: (DisplayChoice) -> Unit,
    runner: PhoneRaceRunner? = remember { PhoneRaceRunner() },
    onStartRace: (() -> Unit)? = null,
    onStopRace: (() -> Unit)? = null,
    displayChoice: DisplayChoiceViewModel = viewModel(),
) {
    var onTimerScreen by remember { mutableStateOf(false) }
    var readout by remember(runner) {
        mutableStateOf(runner?.readout() ?: PhoneReadout.of(TimerState.IDLE, 0L, 0L))
    }
    var state by remember(runner) {
        mutableStateOf(runner?.engine?.currentState ?: TimerState.IDLE)
    }

    val startRace = onStartRace ?: { runner?.start() }
    val stopRace = onStopRace ?: { runner?.stop() }

    fun refresh() {
        runner ?: return
        readout = runner.readout()
        state = runner.engine.currentState
    }

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
    // composition, which is also the only time anything it produces is visible; the engine keeps
    // its own monotonic anchor in the service regardless, so a loop that stops and restarts reads
    // the true remaining time on its next pass rather than resuming a count.
    LaunchedEffect(onTimerScreen, runner) {
        while (onTimerScreen && runner != null) {
            readout = runner.tick()
            state = runner.engine.currentState
            delay(UI_REFRESH_MS)
        }
    }

    val running = state == TimerState.RUNNING

    // Back returns to the picker, but never mid-race: the gesture is one an officer makes without
    // looking, and it must not be able to end a start sequence. While running it falls through to
    // the system, which backgrounds the app with the race still in the service.
    BackHandler(enabled = onTimerScreen && !running) {
        stopRace()
        refresh()
        onTimerScreen = false
    }

    if (!displayChoice.answered) {
        // AC 1 (#225): the picker is not reachable from here. There is no skip affordance and no
        // back route into this branch — `answered` is set once and never cleared, so the surface is
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
            sequenceName = runner?.selected?.name ?: "",
            running = running,
            onStart = {
                startRace()
                refresh()
            },
            onStop = {
                stopRace()
                refresh()
            },
        )
    } else {
        SequencePickerScreen(
            sequences = runner?.sequences ?: PhoneRaceRunner.CONSOLE_SEQUENCES,
            onSelect = { sequence ->
                // Guarded on the binding having landed: selecting is runner state, and navigating
                // to a timer screen with nothing behind it would show a dead readout.
                runner?.let {
                    it.select(sequence)
                    refresh()
                    onTimerScreen = true
                }
            },
        )
    }
}
