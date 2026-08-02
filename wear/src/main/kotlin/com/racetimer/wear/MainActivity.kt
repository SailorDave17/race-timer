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
import com.racetimer.shared.TimerEngine
import com.racetimer.shared.TimerListener
import com.racetimer.shared.TimerState
import com.racetimer.shared.discardedOnStartRemainingMs
import com.racetimer.shared.forcesMaxBrightness
import com.racetimer.shared.formatCountdown
import com.racetimer.shared.keepsScreenOn
import com.racetimer.shared.resumeOfferRemainingMs
import com.racetimer.wear.ui.CustomDurationScreen
import com.racetimer.wear.ui.DEFAULT_CUSTOM_MINUTES
import com.racetimer.wear.ui.RaceTimerTheme
import com.racetimer.wear.ui.SequencePickerScreen
import com.racetimer.wear.ui.TimerScreen

/**
 * Main entry point for the Wear OS Race Timer app.
 *
 * Responsibilities:
 * - Bind to [TimerService] so the countdown keeps running when the app is backgrounded.
 * - Keep the screen on while a sequence is running, and while a just-ended race-manager summary is
 *   on screen (FLAG_KEEP_SCREEN_ON), and drive the panel to full brightness for the states that need
 *   to be readable in direct sunlight. Both rules live in `shared/` — see [applyDisplayPolicy].
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

    /**
     * True while the pre-start screen is offering Resume / Start over instead of a plain Start.
     * See [pendingResume].
     */
    private var uiResumeOffered by mutableStateOf(false)

    /**
     * True when [uiResumeOffered] is showing a race that is already past its gun — a race-manager
     * count-up, the one sequence whose race outlives its own start. The readout then shows elapsed
     * time rather than a countdown that would render as a negative number.
     */
    private var uiPreviewElapsed by mutableStateOf(false)

    /**
     * Names the saved race a tap on Start is about to destroy, or null when it would destroy nothing.
     *
     * The complement of [uiResumeOffered]: the saved race is recoverable but belongs to a *different*
     * sequence, so Start runs the selection and `persistSnapshot()` writes over the old race. That is
     * one tap and unrecoverable, and until #89 nothing said so — the sailor saw an ordinary Start
     * (#87's sibling fix having correctly removed the wrong-but-alarming Resume offer that used to sit
     * there by accident).
     *
     * A standing caveat rather than news, so Tier 3 per `docs/message-surface.md` — it must survive
     * until the sailor acts, because the thing it warns about is the very next tap.
     */
    private var uiDiscardWarning by mutableStateOf<String?>(null)

    private var selectedSequence: RaceSequence = BuiltInSequences.usSailing

    /**
     * The race left behind by an earlier process, and the sequence it was running.
     *
     * Held rather than reduced to a number on launch because the saved race's gun is fixed in the
     * monotonic domain — it keeps approaching whether or not the app is open — so the pre-start
     * preview has to be recomputed on every refresh to stay true. A number captured once would sit
     * frozen on screen while the race it describes ran away from it.
     *
     * Cleared as soon as the sailor answers the offer, either way: the snapshot is then either
     * resumed (and owned by the running engine) or discarded, and must not be offered twice.
     */
    private var pendingResume: Pair<TimerEngine.Snapshot, RaceSequence>? = null

    /**
     * Set the moment the sailor answers the resume offer, and cleared once the engine has the race.
     *
     * The buttons have to go on the tap, but the *number* must not: [startForegroundService] is
     * asynchronous, and for the tick or two before the service loads the sequence the refresh below
     * still finds no engine. Clearing [pendingResume] on the tap made it fall back to the sequence's
     * full duration in that window, so Resume flashed 8:00 before settling on the 4:11 it actually
     * resumed — the same lie the offer exists to prevent, just briefer.
     *
     * It also closes a double-tap: while the buttons are up and the engine is still IDLE, a second
     * ACTION_START would miss the restore guard's `IDLE` check on arrival and run `load()` + `start()`
     * instead, restarting the race from the top.
     */
    private var resumeAnswered = false

    /**
     * Where [CustomDurationScreen] reopens: the last duration chosen this session, or the one a
     * restored Custom race was running. Held here rather than in the composable so swiping out of
     * the stepper and back does not lose the number the sailor had already dialled in.
     */
    private var customMinutes: Int = DEFAULT_CUSTOM_MINUTES

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

    // --- Display management ----------------------------------------------------

    private var screenOnActive = false
    private var maxBrightnessActive = false

    /**
     * Apply both display rules for [state] together, from the one place that knows the state.
     *
     * They are separate rules — [keepsScreenOn] and [forcesMaxBrightness] disagree on
     * [TimerState.FINISHED], deliberately — but they must never be applied at different moments or
     * from different branches, which is why they are read here rather than at two call sites.
     */
    private fun applyDisplayPolicy(state: TimerState) {
        setScreenOn(keepsScreenOn(state))
        setMaxBrightness(forcesMaxBrightness(state))
    }

    private fun setScreenOn(on: Boolean) {
        if (on == screenOnActive) return
        screenOnActive = on
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Drive the panel to maximum brightness, or hand it back to the system (#65).
     *
     * A **window** override (`WindowManager.LayoutParams.screenBrightness`), not the system setting.
     * That choice is the whole of AC 2 and AC 3: the alternative — writing
     * `Settings.System.SCREEN_BRIGHTNESS` — needs `WRITE_SETTINGS`, changes the watch globally, and
     * leaves it pinned bright if this process dies mid-race, so "restore the previous brightness"
     * would become a promise the app cannot keep. A window override has nothing to restore. It applies
     * only while this window is the visible one and evaporates with the activity, so the system's own
     * brightness — whatever the sailor or the ambient sensor had it at — is untouched throughout and is
     * simply back in charge the moment [BRIGHTNESS_OVERRIDE_NONE] is set here.
     *
     * Mirrors [setScreenOn] down to the idempotence guard: both are window state, both are scoped to
     * the foreground, and neither needs undoing in `onStop`.
     */
    private fun setMaxBrightness(on: Boolean) {
        if (on == maxBrightnessActive) return
        maxBrightnessActive = on
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    // --- Activity lifecycle ---------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restorePendingSelection()

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
                            resumeOffered = uiResumeOffered,
                            previewElapsed = uiPreviewElapsed,
                            discardWarning = uiDiscardWarning,
                            onStart = { handleStart() },
                            onStartOver = { handleStartOver() },
                            onStop = { handleStop() },
                            onSync = { handleSync() },
                            onEndRace = { handleEndRace() },
                            onPickSequence = { navController.navigate(NAV_PICKER) },
                        )
                    }
                    wearComposable(NAV_PICKER) {
                        SequencePickerScreen(
                            onSequenceSelected = { seq ->
                                applySelection(seq)
                                navController.popBackStack()
                            },
                            onCustomSelected = { navController.navigate(NAV_CUSTOM) },
                        )
                    }
                    wearComposable(NAV_CUSTOM) {
                        CustomDurationScreen(
                            initialMinutes = customMinutes,
                            onConfirm = { chosenMinutes ->
                                customMinutes = chosenMinutes
                                applySelection(BuiltInSequences.custom(chosenMinutes))
                                // Back to the timer face, not to the picker: the sailor has finished
                                // choosing, and the picker they passed through has nothing left to
                                // offer. popBackStack() alone would strand them on it.
                                navController.popBackStack(NAV_TIMER, inclusive = false)
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Safety valve: [resumeAnswered] is normally cleared when the engine takes the race, so a
        // service that never arrived would otherwise leave the pre-start screen with no controls at
        // all. Leaving and returning is the one recovery a sailor would think to try, so make it work.
        resumeAnswered = false
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

    // --- Sequence selection ---------------------------------------------------

    /**
     * Arm [seq] as the sequence the next Start will run, and show it straight away.
     *
     * The duration is reflected immediately rather than at Start, so picking a sequence shows what
     * is about to be run instead of the previous one's total.
     */
    private fun applySelection(seq: RaceSequence) {
        selectedSequence = seq
        uiSequenceName = seq.name
        uiRemainingMs = seq.totalMs
        // Outlives the process, and outlives the race (#88). Written here rather than at the picker
        // callbacks so every path that changes the selection remembers it — including the two restore
        // paths below, which re-save what they just read and so cost nothing.
        TimerService.savePickedSequenceId(this, seq.id)
    }

    /**
     * Re-select the sequence of a race still persisted from an earlier process, and offer it back.
     *
     * A snapshot only outlives the process while a race is unfinished — Stop and the post-gun
     * teardown both clear it — so finding one means there is a race to come back to, and the
     * sequence the sailor should be looking at is that one, not the default. It also has to be the
     * id [handleResume] sends: the service will only restore when the two match (see
     * `TimerService.savedSnapshot`).
     *
     * An id nothing answers to is announced rather than absorbed. Tier 1 per `docs/message-surface.md`
     * — the same tier its neighbours in `announceRestoreOutcome` use, since this is news about a race
     * that is already gone rather than a condition the sailor can act on. The stale snapshot needs no
     * clearing: the next Start writes its own over the top.
     */
    private fun restorePendingSelection() {
        val snapshot = TimerService.savedSnapshot(this) ?: return restorePickedSelection()
        val saved = BuiltInSequences.resolve(snapshot.sequenceId)
        if (saved == null) {
            showTransientMessage("Saved race unreadable — starting fresh")
            // The race is gone, but the remembered pick is separate state and may be perfectly
            // readable — falling back to it beats dropping to US Sailing on top of the bad news.
            return restorePickedSelection()
        }
        applySelection(saved)
        // Reopen the stepper on the restored race's own length rather than the default, so a Custom
        // race the sailor wants to re-run is one tap from where they left it.
        BuiltInSequences.customMinutes(saved.id)?.let { customMinutes = it }

        // A saved race past its gun is spent and there is nothing to resume — except for a
        // count-up sequence, where the gun is where the race committee's job *starts*. Offering
        // Resume on a spent countdown would promise a race that no longer exists; the service's
        // EXPIRED path and its "Old race ended" banner still cover a stale snapshot if one is
        // reached by tapping Start.
        // The same rule the refresh re-applies on every tick. `saved.id` is passed as the selection
        // because [applySelection] above has just made it exactly that, so the id half is satisfied
        // by construction here and only starts biting once the sailor picks something else.
        val remaining = resumeOfferRemainingMs(
            snapshot,
            saved,
            saved.id,
            SystemMonotonicClock.elapsedMs(),
            System.currentTimeMillis(),
        )
        if (remaining != null) {
            pendingResume = snapshot to saved
        }
    }

    /**
     * Open on the sequence the sailor last chose, rather than the US Sailing default (#88).
     *
     * Reached only when no race survived — a saved race outranks a remembered pick, because it *is* a
     * pick, made more recently and with a race attached.
     *
     * Deliberately does nothing when there is nothing stored: a first-ever launch has no choice to
     * honour and US Sailing is the right thing to show. An id nothing answers to gets the same
     * treatment as an unreadable snapshot — announced, not absorbed, so the sailor learns the app is
     * not showing what they picked instead of quietly racing the wrong sequence (#51's rule).
     */
    private fun restorePickedSelection() {
        val id = TimerService.pickedSequenceId(this) ?: return
        val picked = BuiltInSequences.resolve(id)
        if (picked == null) {
            showTransientMessage("Saved sequence unreadable — using default")
            return
        }
        applySelection(picked)
        // Same reason as the snapshot path: a Custom race the sailor runs repeatedly should reopen the
        // stepper on its own length, not on the default.
        BuiltInSequences.customMinutes(picked.id)?.let { customMinutes = it }
    }

    /**
     * Refresh the Resume offer and the countdown standing behind it.
     *
     * Recomputed rather than stored: see [pendingResume]. Returns the remaining time to show, or
     * null when there is no offer, in which case the caller falls back to the selected sequence's
     * full duration the way it always did.
     *
     * The rule itself lives in [resumeOfferRemainingMs], so it can be tested without an Activity and
     * so this and [restorePendingSelection] cannot drift — they had the expiry half of it spelled out
     * twice, inverted. [pendingResume] is deliberately left set when the answer is null: an offer
     * withheld because a different sequence is now selected has to come back if the sailor selects
     * the saved race's own sequence again, and an expired one recomputes to null on every tick.
     */
    private fun pendingResumeRemainingMs(): Long? {
        val (snapshot, sequence) = pendingResume ?: return null
        return resumeOfferRemainingMs(
            snapshot,
            sequence,
            selectedSequence.id,
            SystemMonotonicClock.elapsedMs(),
            System.currentTimeMillis(),
        )
    }

    /**
     * The Tier 3 line warning that Start will destroy the saved race, or null when it will not.
     *
     * Recomputed every refresh for the same reason as [pendingResumeRemainingMs]: the saved race's gun
     * keeps approaching whether or not it is being looked at, so a race that is worth protecting now
     * may be spent a minute from now and the warning has to go with it.
     *
     * Names the *sequence* rather than the remaining time. The clock on screen belongs to the race the
     * sailor is about to start, and putting a second, different time next to it invites reading the
     * wrong one — the exact confusion #87 is about. The name is enough to identify what is being lost.
     */
    private fun discardWarning(): String? {
        val (snapshot, sequence) = pendingResume ?: return null
        discardedOnStartRemainingMs(
            snapshot,
            sequence,
            selectedSequence.id,
            SystemMonotonicClock.elapsedMs(),
            System.currentTimeMillis(),
        ) ?: return null
        return "Start discards saved ${sequence.name}"
    }

    /** Drop the saved race's claim on the pre-start screen, whichever way the sailor answered it. */
    private fun clearResumeOffer() {
        pendingResume = null
        resumeAnswered = false
        uiResumeOffered = false
        uiPreviewElapsed = false
        // The warning is the other half of that claim (see [uiDiscardWarning]) and goes with it — by
        // the time an engine holds a race, whatever was going to be discarded already has been.
        uiDiscardWarning = null
    }

    // --- User actions ---------------------------------------------------------

    /**
     * Start the selected sequence, resuming a saved race if the service still has one that matches.
     *
     * The only control on a normal pre-start screen, and the Resume half of the two the screen shows
     * after a process kill. Both are the same intent: the service decides whether there is anything
     * to resume, and it is the only thing that can decide it correctly.
     */
    private fun handleStart() {
        resyncAcknowledged = false
        // Answered, not discarded: the preview keeps the saved race's clock on screen until the
        // engine has it, so the number never jumps. See [resumeAnswered].
        resumeAnswered = true
        startForegroundService(TimerService.startIntent(this, selectedSequence.id))
    }

    /**
     * Run the selected sequence from the top, discarding the saved race rather than resuming it.
     *
     * Offered only alongside Resume, so the sailor is never choosing this without having been shown
     * what they are giving up.
     */
    private fun handleStartOver() {
        resyncAcknowledged = false
        // Discarded outright, unlike Resume: the sequence's full duration *is* the right preview now,
        // and it is what the fresh race will start from, so there is no jump to avoid.
        clearResumeOffer()
        startForegroundService(TimerService.startIntent(this, selectedSequence.id, freshStart = true))
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
        //
        // The test is the engine's *state*, not whether a sequence happens to be loaded (#87).
        // `stop()` deliberately keeps the sequence and parks remainingMs at its full duration, so
        // `loadedSequence != null` stayed true after Stop and this branch was skipped — leaving the
        // clock on the stopped race's length while the sailor picked a different sequence, which then
        // started correctly and disagreed with the number they had just read. IDLE is exactly "no race
        // on screen"; every other state has one worth rendering, including FINISHED, which is the gun
        // still reading GO! and must not flip to the next sequence's duration.
        val engine = timerService?.engine
        if (engine == null || engine.currentState == TimerState.IDLE) {
            // A race left over from a killed process gets the screen until the sailor answers it:
            // showing the sequence's full duration here is what made Resume a surprise, promising
            // 8:00 and delivering the 6:00 that was actually left.
            val resumeRemaining = pendingResumeRemainingMs()
            uiResumeOffered = resumeRemaining != null && !resumeAnswered
            uiPreviewElapsed = resumeRemaining != null && resumeRemaining <= 0L
            uiRemainingMs = displayedRemainingMs(resumeRemaining ?: selectedSequence.totalMs)
            uiElapsedMs = if (uiPreviewElapsed) displayedElapsedMs(-resumeRemaining!!) else 0L
            uiTimerState = TimerState.IDLE
            uiShowResyncPrompt = false
            // Suppressed once the sailor has answered: they have committed, the race is already being
            // discarded, and a warning about it is no longer something they can act on.
            uiDiscardWarning = if (resumeAnswered) null else discardWarning()
            // Every way a sequence ends — Stop, the post-gun teardown, Done — lands here, so this is
            // also the single point at which the brightness override is handed back (#65 AC 3).
            applyDisplayPolicy(TimerState.IDLE)
            return
        }
        // A race the engine is actually running outranks a saved one: it has already been answered.
        clearResumeOffer()
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
        // Keep-screen-on and the max-brightness override, both keyed off the engine state. The rules
        // and the reasoning behind each state now live in `shared/ScreenPolicy.kt`, where the JVM
        // suite can assert them — including the one state the two rules deliberately disagree on.
        applyDisplayPolicy(engine.currentState)
    }

    companion object {
        private const val NAV_TIMER = "timer"
        private const val NAV_PICKER = "picker"
        private const val NAV_CUSTOM = "custom"
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
