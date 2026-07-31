package com.racetimer.wear

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable as wearComposable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.RestoreOutcome
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.TimerListener
import com.racetimer.shared.TimerState
import com.racetimer.shared.formatCountdown
import com.racetimer.wear.ui.RaceTimerTheme
import com.racetimer.wear.ui.SequencePickerScreen
import com.racetimer.wear.ui.TimerScreen

/**
 * Main entry point for the Wear OS Race Timer app.
 *
 * Responsibilities:
 * - Bind to [TimerService] so the countdown keeps running when the app is backgrounded.
 * - Keep the screen on while a sequence is running, and while a just-ended race-manager summary is
 *   on screen (FLAG_KEEP_SCREEN_ON).
 * - Drive the Compose UI by polling the engine state every [UI_REFRESH_MS].
 * - Handle Start / Sync / Stop actions by dispatching to the service.
 */
class MainActivity : ComponentActivity() {

    // --- Service binding ------------------------------------------------------

    private var timerService: TimerService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val lb = binder as? TimerService.LocalBinder ?: return
            timerService = lb.service
            serviceBound = true
            lb.service.engine.addListener(engineListener)
            refreshUiState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            timerService?.engine?.removeListener(engineListener)
            timerService = null
        }
    }

    // --- UI state (mutable state drives Compose recomposition) ----------------

    // Seeded with the loaded sequence's full duration: before Start there is no service to bind
    // to, and showing 0:00 made a fresh launch look like a race that had already finished.
    private var uiRemainingMs by mutableStateOf(BuiltInSequences.usSailing.totalMs)
    // Only meaningful in TimerState.COUNTING_UP; 0 the rest of the time since nothing reads it.
    private var uiElapsedMs by mutableStateOf(0L)
    private var uiTimerState by mutableStateOf(TimerState.IDLE)
    private var uiSequenceName by mutableStateOf(BuiltInSequences.usSailing.name)
    private var uiSyncLabel by mutableStateOf<String?>(null)
    private var uiShowResyncPrompt by mutableStateOf(false)

    /** Set once the sailor taps Sync after a degraded recovery, dismissing the re-sync prompt. */
    private var resyncAcknowledged = false

    private var uiMessage by mutableStateOf<String?>(null)

    private var selectedSequence: RaceSequence = BuiltInSequences.usSailing

    // --- UI refresh handler ---------------------------------------------------

    private val uiHandler = Handler(Looper.getMainLooper())

    /**
     * Fallback refresh for the states that emit nothing.
     *
     * While a sequence runs, the service's tick loop drives [TimerListener.onTick] on this very
     * looper — the service shares the activity's process — so the UI takes the countdown from that
     * callback rather than sampling the engine on a second timer of its own. Idle, finished and
     * not-yet-bound produce no callbacks, and a Stop tap (or the service's own return to idle after
     * the gun) has to be picked up somehow, so this keeps running underneath at a rate suited to
     * state changes rather than to a countdown.
     */
    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            refreshUiState()
            uiHandler.postDelayed(this, UI_FALLBACK_REFRESH_MS)
        }
    }

    // --- Keep-screen-on management --------------------------------------------

    private var screenOnActive = false

    private fun setScreenOn(on: Boolean) {
        if (on == screenOnActive) return
        screenOnActive = on
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // --- Activity lifecycle ---------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            RaceTimerTheme {
                val navController = rememberSwipeDismissableNavController()

                SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = NAV_TIMER,
                ) {
                    wearComposable(NAV_TIMER) {
                        TimerScreen(
                            remainingMs = uiRemainingMs,
                            elapsedMs = uiElapsedMs,
                            state = uiTimerState,
                            sequenceName = uiSequenceName,
                            syncLabel = uiSyncLabel,
                            showResyncPrompt = uiShowResyncPrompt,
                            message = uiMessage,
                            onStart = { handleStart() },
                            onStop = { handleStop() },
                            onSync = { handleSync() },
                            onEndRace = { handleEndRace() },
                            onPickSequence = { navController.navigate(NAV_PICKER) },
                        )
                    }
                    wearComposable(NAV_PICKER) {
                        SequencePickerScreen(
                            onSequenceSelected = { seq ->
                                selectedSequence = seq
                                uiSequenceName = seq.name
                                // Reflect the new sequence's duration straight away, so picking
                                // one shows what is about to be started rather than the old total.
                                uiRemainingMs = seq.totalMs
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to the service (start it if already running)
        val serviceIntent = Intent(this, TimerService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        uiHandler.post(uiRefreshRunnable)
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(uiRefreshRunnable)
        if (serviceBound) {
            timerService?.engine?.removeListener(engineListener)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // --- User actions ---------------------------------------------------------

    private fun handleStart() {
        resyncAcknowledged = false
        startForegroundService(TimerService.startIntent(this, selectedSequence.id))
    }

    private fun handleStop() {
        startService(TimerService.stopIntent(this))
    }

    private fun handleEndRace() {
        startService(TimerService.endRaceIntent(this))
    }

    private fun handleSync() {
        resyncAcknowledged = true
        startService(TimerService.syncIntent(this))
    }

    // --- Engine listener ------------------------------------------------------

    private val engineListener = object : TimerListener {
        override fun onCue(cue: SequenceCue) { /* haptics handled in service */ }

        // The gun is the one transition worth catching the instant it happens rather than on the
        // next fallback pass: it is what flips the display to "GO!".
        override fun onGun() = refreshUiState()

        // Drives the countdown while running, at the engine's own cadence and on its thread.
        override fun onTick(remainingMs: Long) = refreshUiState()

        override fun onSync(snappedToMs: Long) {
            val label = "Synced → ${formatCountdown(snappedToMs)}"
            uiSyncLabel = label
            uiHandler.postDelayed({ uiSyncLabel = null }, SYNC_LABEL_DURATION_MS)
        }

        override fun onClockAdjusted(remainingMs: Long) {
            showTransientMessage("Clock changed — countdown held steady")
        }
    }

    /** Post a Tier 1 banner (see `docs/message-surface.md`) that clears itself. */
    private fun showTransientMessage(text: String) {
        uiMessage = text
        uiHandler.postDelayed({ uiMessage = null }, MESSAGE_DURATION_MS)
    }

    // --- State refresh --------------------------------------------------------

    /**
     * Round [rawMs] up to the whole second the screen actually shows.
     *
     * Everything downstream of this value is second-granular: [formatCountdown] rounds up to the
     * next whole second, and the background-colour and flash thresholds all sit on whole seconds.
     * Storing raw milliseconds meant the Compose state changed on all 20 engine ticks a second and
     * recomposed the screen each time to draw a frame identical to the last one.
     *
     * Rounding the same way [formatCountdown] does keeps every consumer's output byte-identical —
     * a threshold on a whole second is crossed by the rounded value at exactly the same instant as
     * by the raw one — while letting the state change only when the display does.
     */
    private fun displayedRemainingMs(rawMs: Long): Long =
        if (rawMs <= 0L) 0L else ((rawMs + 999L) / 1_000L) * 1_000L

    /**
     * Round [rawMs] *down* to the whole second [formatElapsed] shows, for the same recomposition
     * reason as [displayedRemainingMs] — but floored rather than ceiled, because elapsed race time
     * is a stopwatch reading, not a countdown (see [formatElapsed]'s doc for why that direction
     * matters).
     */
    private fun displayedElapsedMs(rawMs: Long): Long =
        if (rawMs <= 0L) 0L else (rawMs / 1_000L) * 1_000L

    /**
     * Say out loud that a Start did something other than start.
     *
     * Tapping Start can resume a race that survived process death, or discard one whose gun had
     * already gone. Both leave the screen showing something the sailor did not ask for — a countdown
     * already part-way down, or a fresh one where a running race was expected — and with no Reset
     * button there is no longer an obvious "no, from the top" control to reach for. So name what
     * happened; the way out of a resumed race is Stop, which clears the snapshot.
     *
     * DEGRADED is deliberately absent: it needs a sustained instruction, and gets the Tier 3
     * re-sync prompt instead of a banner that vanishes in 3 s.
     */
    private fun announceRestoreOutcome() {
        when (timerService?.consumeRestoreNotice()) {
            RestoreOutcome.EXACT -> showTransientMessage("Resumed race in progress")
            RestoreOutcome.EXPIRED -> showTransientMessage("Old race ended — starting fresh")
            RestoreOutcome.DEGRADED, null -> Unit
        }
    }

    private fun refreshUiState() {
        // Binding uses BIND_AUTO_CREATE, so the service exists well before anything is started -
        // with an engine holding no sequence, whose remainingMs is 0. Showing that made a fresh
        // launch read as a finished race, so preview the pending sequence's duration instead.
        val engine = timerService?.engine
        if (engine == null || engine.loadedSequence == null) {
            uiRemainingMs = selectedSequence.totalMs
            uiElapsedMs = 0L
            uiTimerState = TimerState.IDLE
            uiShowResyncPrompt = false
            setScreenOn(false)
            return
        }
        uiTimerState = engine.currentState
        if (uiTimerState == TimerState.COUNTING_UP || uiTimerState == TimerState.RACE_ENDED) {
            // remainingMs stays live and negative past the gun (see its doc) — flip the sign
            // rather than adding a second engine getter for what is the same number the other way.
            // In RACE_ENDED that value is frozen (see TimerEngine.endRace), so this keeps reading
            // the same final elapsed time on every poll rather than needing its own held-value state.
            uiElapsedMs = displayedElapsedMs(-engine.remainingMs)
        } else {
            uiRemainingMs = displayedRemainingMs(engine.remainingMs)
        }
        announceRestoreOutcome()
        // Prompt a re-sync only while a degraded recovery is still running and unconfirmed.
        uiShowResyncPrompt = timerService?.lastRestoreOutcome == RestoreOutcome.DEGRADED &&
            engine.currentState == TimerState.RUNNING &&
            !resyncAcknowledged
        // Manage keep-screen-on. RACE_ENDED is included alongside RUNNING: the whole point of that
        // state is to hold the final race time up for the race committee to read, and letting the
        // screen sleep the instant they tap End Race would defeat it. Unlike RUNNING this has no
        // wake-lock backing it (see TimerService.onGun — released at the gun, on purpose), so it
        // only holds the screen while the activity itself is in the foreground; backgrounding still
        // lets it sleep, same as it always could between taps.
        setScreenOn(engine.currentState == TimerState.RUNNING || engine.currentState == TimerState.RACE_ENDED)
    }

    companion object {
        private const val NAV_TIMER = "timer"
        private const val NAV_PICKER = "picker"
        /**
         * Fallback poll rate; the running countdown comes from onTick, not from this.
         *
         * Kept at 100 ms rather than slowed further because a Stop or Reset tap silences the engine
         * and so has nothing but this to notice it — and past about 100 ms a button stops feeling
         * like it responded. An idle pass writes no state that changed, so it costs no recomposition.
         */
        private const val UI_FALLBACK_REFRESH_MS = 100L
        private const val SYNC_LABEL_DURATION_MS = 2_000L
        private const val MESSAGE_DURATION_MS = 3_000L
    }
}
