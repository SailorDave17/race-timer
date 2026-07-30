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
 * - Keep the screen on while a sequence is running (FLAG_KEEP_SCREEN_ON).
 * - Drive the Compose UI by polling the engine state every [UI_REFRESH_MS].
 * - Handle Start / Sync / Stop / Reset actions by dispatching to the service.
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
    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            refreshUiState()
            uiHandler.postDelayed(this, UI_REFRESH_MS)
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
                            state = uiTimerState,
                            sequenceName = uiSequenceName,
                            syncLabel = uiSyncLabel,
                            showResyncPrompt = uiShowResyncPrompt,
                            message = uiMessage,
                            onStart = { handleStart() },
                            onStop = { handleStop() },
                            onReset = { handleReset() },
                            onSync = { handleSync() },
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

    private fun handleReset() {
        startService(TimerService.resetIntent(this))
    }

    private fun handleSync() {
        resyncAcknowledged = true
        startService(TimerService.syncIntent(this))
    }

    // --- Engine listener ------------------------------------------------------

    private val engineListener = object : TimerListener {
        override fun onCue(cue: SequenceCue) { /* haptics handled in service */ }
        override fun onGun() { /* state refreshed via ticker */ }
        override fun onTick(remainingMs: Long) { /* refreshed below */ }
        override fun onSync(snappedToMs: Long) {
            val label = "Synced → ${formatCountdown(snappedToMs)}"
            uiSyncLabel = label
            uiHandler.postDelayed({ uiSyncLabel = null }, SYNC_LABEL_DURATION_MS)
        }

        override fun onClockAdjusted(remainingMs: Long) {
            uiMessage = "Clock changed — countdown held steady"
            uiHandler.postDelayed({ uiMessage = null }, MESSAGE_DURATION_MS)
        }
    }

    // --- State refresh --------------------------------------------------------

    private fun refreshUiState() {
        // Binding uses BIND_AUTO_CREATE, so the service exists well before anything is started -
        // with an engine holding no sequence, whose remainingMs is 0. Showing that made a fresh
        // launch read as a finished race, so preview the pending sequence's duration instead.
        val engine = timerService?.engine
        if (engine == null || engine.loadedSequence == null) {
            uiRemainingMs = selectedSequence.totalMs
            uiTimerState = TimerState.IDLE
            uiShowResyncPrompt = false
            setScreenOn(false)
            return
        }
        uiRemainingMs = engine.remainingMs
        uiTimerState = engine.currentState
        // Prompt a re-sync only while a degraded recovery is still running and unconfirmed.
        uiShowResyncPrompt = timerService?.lastRestoreOutcome == RestoreOutcome.DEGRADED &&
            engine.currentState == TimerState.RUNNING &&
            !resyncAcknowledged
        // Manage keep-screen-on
        setScreenOn(engine.currentState == TimerState.RUNNING)
    }

    companion object {
        private const val NAV_TIMER = "timer"
        private const val NAV_PICKER = "picker"
        private const val UI_REFRESH_MS = 50L
        private const val SYNC_LABEL_DURATION_MS = 2_000L
        private const val MESSAGE_DURATION_MS = 3_000L
    }
}
