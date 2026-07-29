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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable as wearComposable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.RestoreOutcome
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.TimerListener
import com.racetimer.shared.TimerState
import com.racetimer.wear.ui.RaceTimerTheme
import com.racetimer.wear.ui.SequencePickerScreen
import com.racetimer.wear.ui.TimerScreen
import com.racetimer.wear.ui.formatMmSs

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

    private var uiRemainingMs by mutableStateOf(0L)
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
                            onPause = { handlePause() },
                            onPickSequence = { navController.navigate(NAV_PICKER) },
                        )
                    }
                    wearComposable(NAV_PICKER) {
                        SequencePickerScreen(
                            onSequenceSelected = { seq ->
                                selectedSequence = seq
                                uiSequenceName = seq.name
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
        // Resuming keeps any prior sync acknowledgement; only a fresh start clears it.
        if (uiTimerState != TimerState.PAUSED) resyncAcknowledged = false
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

    private fun handlePause() {
        startService(TimerService.pauseIntent(this))
    }

    // --- Engine listener ------------------------------------------------------

    private val engineListener = object : TimerListener {
        override fun onCue(cue: SequenceCue) { /* haptics handled in service */ }
        override fun onGun() { /* state refreshed via ticker */ }
        override fun onTick(remainingMs: Long) { /* refreshed below */ }
        override fun onSync(snappedToMs: Long) {
            val label = "Synced → ${formatMmSs(snappedToMs)}"
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
        val engine = timerService?.engine ?: return
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
