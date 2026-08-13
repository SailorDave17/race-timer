package com.racetimer.wear

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable as wearComposable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.racetimer.android.HapticManager
import com.racetimer.android.SystemMonotonicClock
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.DEFAULT_BOX_ALERT_SECONDS
import com.racetimer.shared.DeviceReadiness
import com.racetimer.shared.LaunchNotice
import com.racetimer.shared.NoticeTier
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.RestoreOutcome
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.StartNotice
import com.racetimer.shared.StartRemedy
import com.racetimer.shared.TimerEngine
import com.racetimer.shared.TimerListener
import com.racetimer.shared.TimerState
import com.racetimer.shared.armedNotice
import com.racetimer.shared.cueLossNotice
import com.racetimer.shared.discardedOnStartRemainingMs
import com.racetimer.shared.forcesMaxBrightness
import com.racetimer.shared.formatCountdown
import com.racetimer.shared.isInLeadIn
import com.racetimer.shared.keepsScreenOn
import com.racetimer.shared.launchPlan
import com.racetimer.shared.leadInBaseId
import com.racetimer.shared.leadInBaseOf
import com.racetimer.shared.offersLeadIn
import com.racetimer.shared.resumeOfferRemainingMs
import com.racetimer.shared.startNotice
import com.racetimer.shared.withLeadIn
import com.racetimer.wear.ui.CustomDurationScreen
import com.racetimer.wear.ui.DEFAULT_CUSTOM_MINUTES
import com.racetimer.wear.ui.LeadInDurationScreen
import com.racetimer.wear.ui.LeadInPickerScreen
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
            // The earliest moment the selection and the service both exist, and so the earliest the
            // cues of the race about to be run can start rendering (#98). [restorePendingSelection]
            // has already run in onCreate — it is what puts the launch's sequence in
            // [selectedSequence] — but binding is asynchronous and there was no service to tell.
            lb.service.warmUpCues(selectedSequence)
            // Unthrottled, and it has to be: binding is the moment the two service-side conditions
            // become readable at all, and until it happens [readDeviceReadiness] reports them as
            // "nothing to say". The throttle inside refreshUiState would otherwise hold the first
            // real answer back by up to a second (#13).
            refreshStartNotice()
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

    /**
     * The one thing worth telling the sailor about the state of this watch, or null (#13, #96).
     *
     * The *judgement* is never here. Two rules in `shared/` produce it and they do not overlap:
     * [startNotice] answers the pre-start screen from five conditions readable before a tap, and
     * [armedNotice] answers a running race from the one condition that can only be measured by
     * arming it. `refreshUiState` picks whichever branch it is in, so exactly one is ever live.
     *
     * Which tier it lands on decides whether it appears as a Tier 3 line under the sequence name or
     * as a Tier 2 panel standing where the Start button would be — and `TimerScreen` makes that
     * second case structural rather than conditional by rendering the panel *in place of* the
     * button, inside the branch that draws it. Blocking therefore cannot reach a running race by
     * construction, which is rule 3 of `docs/message-surface.md` enforced by geometry rather than by
     * a flag — and [armedNotice], the only rule that can speak during a race, returns a
     * [NoticeTier.WARNING] or nothing at all.
     */
    private var uiStartNotice by mutableStateOf<StartNotice?>(null)

    /**
     * Set once the sailor has tapped "Start silent" this session, demoting the audio block (#13).
     *
     * Per-process rather than persisted, and that is the conservative direction: a watch whose
     * audio stack recovers between launches should be offered its cues back rather than silently
     * inheriting last week's decision to go without them.
     */
    private var silentStartAccepted = false

    /**
     * True once this process has asked for `POST_NOTIFICATIONS`, so it asks at most once (#13 AC1).
     *
     * The system stops showing the dialog after a permanent denial and the launcher then returns
     * "denied" immediately, so re-asking is harmless — but asking once and then *saying something*
     * is what the criterion requires, and the Tier 3 line with its Settings remedy is the half that
     * survives a dialog the platform will never show again.
     */
    private var notificationPermissionRequested = false

    /**
     * True when the selected sequence may be armed with a lead-in, so the pre-start screen offers
     * the control (#104). The rule is [offersLeadIn], in `shared/` — race-manager modes only.
     */
    private var uiLeadInOffered by mutableStateOf(false)

    /**
     * True while the running race is still in its lead-in, which is what drops the Sync button for
     * the duration. See [isInLeadIn] for why Sync must not act there.
     */
    private var uiInLeadIn by mutableStateOf(false)

    private var selectedSequence: RaceSequence = BuiltInSequences.usSailing

    /**
     * Where [LeadInPickerScreen] and [LeadInDurationScreen] open: the lead last armed.
     *
     * Mirrors [customMinutes], and persisted for the same reason a Custom duration effectively is —
     * a club runs one signal box, so re-dialling its warning every week is the cost this remembers
     * away. Read once on launch; [TimerService] owns the storage.
     */
    private var lastBoxAlertSeconds: Int = DEFAULT_BOX_ALERT_SECONDS

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
                            onMessageExpired = { uiMessage = null },
                            resumeOffered = uiResumeOffered,
                            previewElapsed = uiPreviewElapsed,
                            discardWarning = uiDiscardWarning,
                            leadInOffered = uiLeadInOffered,
                            inLeadIn = uiInLeadIn,
                            startNotice = uiStartNotice,
                            onRemedy = { handleRemedy(it) },
                            onStart = { handleStart() },
                            onStartOver = { handleStartOver() },
                            onLeadIn = { navController.navigate(NAV_LEAD_IN) },
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
                    wearComposable(NAV_LEAD_IN) {
                        LeadInPickerScreen(
                            lastUsedSeconds = lastBoxAlertSeconds,
                            // Selecting an alert *starts the race* — the tapped chip was the confirm,
                            // and its label carried the value being committed to. Back to the timer
                            // face first so the countdown the race is running is what appears when
                            // the service reports in, rather than a picker for a race already under
                            // way.
                            onAlertSelected = { seconds ->
                                navController.popBackStack(NAV_TIMER, inclusive = false)
                                handleStartWithLeadIn(seconds)
                            },
                            onCustomSelected = { navController.navigate(NAV_LEAD_CUSTOM) },
                        )
                    }
                    wearComposable(NAV_LEAD_CUSTOM) {
                        LeadInDurationScreen(
                            initialSeconds = lastBoxAlertSeconds,
                            onConfirm = { seconds ->
                                navController.popBackStack(NAV_TIMER, inclusive = false)
                                handleStartWithLeadIn(seconds)
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
        // Ahead of the refresh below, so a grant arriving here is reflected on the first frame
        // rather than a second later. Both are here rather than in onCreate because this is the
        // callback a *return from Settings* comes through — which is exactly the trip the Tier 2
        // and Tier 3 remedies send the sailor on, and a notice that survived the fix would be worse
        // than one that never appeared (#13).
        requestNotificationPermissionOnce()
        refreshStartNotice()
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
        uiLeadInOffered = offersLeadIn(seq)
        // Outlives the process, and outlives the race (#88). Written here rather than at the picker
        // callbacks so every path that changes the selection remembers it — including the two restore
        // paths below, which re-save what they just read and so cost nothing.
        //
        // Stripped of any lead time (#104). A lead-in is a per-race choice, never a sticky one: the
        // only way `seq` carries one here is the restore path below re-selecting a saved lead-in race
        // so the Resume offer can match its id, and remembering *that* as the pick would arm every
        // later cold launch with a lead nobody re-chose — the invisible state the two-tap picker
        // exists to rule out. The lead the race manager last used is remembered separately, as a
        // value the picker opens on rather than as a race waiting to be started.
        TimerService.savePickedSequenceId(this, leadInBaseId(seq.id))
        // Start rendering this sequence's cues now rather than when Start is tapped (#98). Null on
        // the launch path, where this runs from onCreate before onStart has bound — onServiceConnected
        // makes the same call as soon as there is something to call it on.
        timerService?.warmUpCues(seq)
    }

    /**
     * Give up the lead-in a race was armed with, once that race is the engine's problem.
     *
     * A lead-in may sit in [selectedSequence] for exactly two windows: between arming a race and the
     * service picking the intent up, and — after a process kill — while the saved race is being
     * offered back, since [resumeOfferRemainingMs] can only make that offer when the selection's id
     * matches the race's. Both end when the engine takes the race, which is where this is called.
     *
     * Dropping it *then* rather than when the race ends is what keeps the number on screen honest
     * throughout: while the race runs the countdown comes from the engine, and by the time the
     * pre-start screen is reading [selectedSequence] again it is the plain sequence at its own
     * duration, with a plain Start.
     */
    private fun dropLeadInFromSelection() {
        if (selectedSequence.leadInMs == 0L) return
        // Null only if the base id resolves to nothing, which cannot happen for a sequence that was
        // built by arming one — but the selection is better left as it is than cleared on a surprise.
        val base = leadInBaseOf(selectedSequence) ?: return
        selectedSequence = base
        uiSequenceName = base.name
        uiLeadInOffered = offersLeadIn(base)
    }

    /**
     * Open on whatever the last process left behind: a race still running, or failing that the
     * sequence the sailor last picked.
     *
     * The decision itself is [launchPlan], in `shared/`, where the JVM suite can hold it — including
     * the #57 regression this method exists to prevent, where a killed Scholastic race came back as a
     * fresh 5-minute US Sailing one because the activity relaunched holding the default and never
     * asked persistence what had been running. What is left here is the part that genuinely needs an
     * Activity: reading the two records, and applying the answer to the screen.
     *
     * An id nothing answers to is announced rather than absorbed. Tier 1 per `docs/message-surface.md`
     * — the same tier its neighbours in `announceRestoreOutcome` use, since this is news about a race
     * that is already gone rather than a condition the sailor can act on. The stale snapshot needs no
     * clearing: the next Start writes its own over the top.
     */
    private fun restorePendingSelection() {
        // Read before the plan is applied and independently of it: the lead-time picker opens on
        // this whether or not a race survived, and it is a preference rather than part of any race.
        lastBoxAlertSeconds = TimerService.lastBoxAlertSeconds(this)
        val plan = launchPlan(
            TimerService.savedSnapshot(this),
            TimerService.pickedSequenceId(this),
            SystemMonotonicClock.elapsedMs(),
            System.currentTimeMillis(),
        )
        plan.sequence?.let { applySelection(it) }
        // Reopen the stepper on the restored race's own length rather than the default, so a Custom
        // race the sailor wants to re-run is one tap from where they left it. Null leaves whatever
        // was already dialled in alone.
        plan.customMinutes?.let { customMinutes = it }
        // Already paired with the sequence it runs, so there is nothing to look up and nothing to get
        // wrong: a race is only ever offered on its own sequence.
        pendingResume = plan.resumable
        plan.notice?.let { showTransientMessage(messageFor(it)) }
    }

    /**
     * The words for a [LaunchNotice]. They stay here because `shared/` has no resources and no
     * business holding UI copy — it decides *that* the sailor must be told, not how it reads.
     */
    private fun messageFor(notice: LaunchNotice): String = when (notice) {
        LaunchNotice.SAVED_RACE_UNREADABLE -> "Saved race unreadable — starting fresh"
        LaunchNotice.PICKED_SEQUENCE_UNREADABLE -> "Saved sequence unreadable — using default"
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

    // --- Device readiness (#13) -----------------------------------------------

    /**
     * True once `startForegroundService` itself threw, as opposed to the service being refused
     * after it started.
     *
     * There are two distinct ways this fails and both have to be caught, because they happen on
     * different sides of a process boundary. The platform can refuse to *deliver* the intent — a
     * background-start restriction, which throws here in the activity — or it can deliver it and
     * then refuse the `startForeground` call inside the service, which is
     * [TimerService.foregroundStartRefused]. Only catching the second would leave the first
     * throwing out of a tap handler and crashing the app, which is the loudest possible way to fail
     * silently: a crash tells the sailor nothing about why their race would not start.
     */
    private var foregroundStartRefusedHere = false

    /** Monotonic reading of the last [readDeviceReadiness], for [refreshStartNoticeThrottled]. */
    private var lastReadinessReadMs = Long.MIN_VALUE

    /**
     * The permission prompt for the ongoing-activity notification (#13 AC1).
     *
     * Registered unconditionally at construction because `registerForActivityResult` must be —
     * doing it inside a `Build.VERSION` branch throws `LifecycleOwners must call register before
     * they are STARTED`. The *launch* is what is guarded by API level, in
     * [requestNotificationPermissionOnce].
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Whichever way it was answered, the readiness picture just changed. Recomputing here
            // rather than trusting the boolean handed back keeps one reader of the real permission
            // state — the callback's argument and `checkSelfPermission` have disagreed before on
            // Android when a grant arrives while the activity is stopped.
            refreshStartNotice()
        }

    /**
     * Ask for `POST_NOTIFICATIONS` at most once per process, and only where it exists.
     *
     * The permission is Android 13+; this app's minSdk is 30, so on an older watch there is nothing
     * to request and nothing to warn about — the notification posts regardless.
     */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionRequested) return
        if (notificationsGranted()) return
        notificationPermissionRequested = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Read what this watch will actually do, as five observations for `shared/` to judge (#13).
     *
     * Read fresh on every refresh rather than cached, for the reason [TimerService.currentCueStream]
     * gives about its own inputs: every one of these is something the sailor can change between
     * opening the app and tapping Start. Putting the watch into battery saver on the walk down to
     * the boat is the ordinary case, not an edge one.
     *
     * The two service-side conditions read null-safely because the activity out-lives the binding on
     * both ends. An unbound service contributes *nothing* rather than a cheerful `false`: claiming
     * the audio stack is healthy on the strength of a service that has not started yet is precisely
     * the "predicting instead of observing" mistake #96 recorded.
     */
    private fun readDeviceReadiness(): DeviceReadiness {
        val service = timerService
        return DeviceReadiness(
            foregroundServiceRefused =
                foregroundStartRefusedHere || service?.foregroundStartRefused == true,
            audioUnavailable = service?.audioUnavailable == true,
            notificationsBlocked = !notificationsGranted(),
            vibratorAbsent = !HapticManager.hasVibrator(this),
            batterySaverActive =
                getSystemService(PowerManager::class.java)?.isPowerSaveMode == true,
        )
    }

    /** Recompute the notice on screen from the current state of the watch. */
    private fun refreshStartNotice() {
        lastReadinessReadMs = SystemMonotonicClock.elapsedMs()
        uiStartNotice = startNotice(readDeviceReadiness(), silentStartAccepted)
    }

    /**
     * Recompute the notice, but no more often than [READINESS_REFRESH_MS].
     *
     * [readDeviceReadiness] costs three binder calls — the permission check, the power-save read and
     * the vibrator lookup — and the pre-start refresh it hangs off runs at [UI_FALLBACK_REFRESH_MS],
     * ten times a second. Recomputing on every pass would spend 30 binder calls a second forever on
     * a screen whose answer changes at most when the sailor walks into Settings and back, which is a
     * cost #16's battery baseline would have to explain later.
     *
     * A second is the right granularity for the one case the event hooks miss — battery saver
     * switching itself on at a low-battery threshold while the sailor is looking at the screen.
     * Everything else ([onResume], the service binding, a start attempt, the permission result)
     * recomputes immediately through [refreshStartNotice].
     */
    private fun refreshStartNoticeThrottled() {
        val now = SystemMonotonicClock.elapsedMs()
        if (now - lastReadinessReadMs < READINESS_REFRESH_MS) return
        refreshStartNotice()
    }

    /**
     * Act on the button a Tier 2 panel or a Tier 3 line is offering.
     *
     * Each settings route is wrapped: an OEM watch can ship without the activity these intents name,
     * and an `ActivityNotFoundException` thrown out of a tap on the one control a blocked screen has
     * would crash the app at the exact moment the sailor was trying to fix it.
     */
    private fun handleRemedy(remedy: StartRemedy) {
        when (remedy) {
            StartRemedy.NONE -> Unit
            StartRemedy.START_SILENT -> {
                silentStartAccepted = true
                refreshStartNotice()
                handleStart()
            }
            StartRemedy.APP_SETTINGS -> openSettings(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                },
            )
            StartRemedy.NOTIFICATION_SETTINGS -> openSettings(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                },
            )
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            // ActivityNotFoundException, and on a locked-down watch a SecurityException. Neither is
            // worth crashing over: the notice stays on screen saying what is wrong, which is still
            // more than the sailor had before #13.
            Log.w(TAG, "No settings screen for ${intent.action}", e)
        }
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
        armRace(TimerService.startIntent(this, selectedSequence.id))
    }

    /**
     * Hand a start intent to the service, or put the Tier 2 panel up instead of crashing (#13).
     *
     * Every one of the three start paths goes through here, which is the point: before #13 each
     * called [startForegroundService] directly, so a background-start refusal threw out of whichever
     * tap handler happened to be the one the sailor used. Routing them through one method means the
     * failure is handled once and cannot be half-adopted — a second start path added later inherits
     * it rather than quietly reintroducing the crash.
     *
     * On success the latch is cleared. That matters more than it looks: the refusal condition is
     * usually something the sailor has just gone and fixed in Settings, and a panel that stayed up
     * after the remedy worked would be indistinguishable from one that had not.
     */
    private fun armRace(intent: Intent) {
        try {
            startForegroundService(intent)
            foregroundStartRefusedHere = false
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) and the SecurityException an
            // Android 14+ watch raises for a disallowed FGS type. Same response either way, so they
            // are caught by the supertype they share — the idiom ToneManager already uses.
            Log.e(TAG, "Foreground service refused at dispatch; blocking the start", e)
            foregroundStartRefusedHere = true
            // The tap is spent and no race is coming, so give the pre-start screen its controls
            // back rather than leaving the resume offer answered and the screen with neither.
            resumeAnswered = false
        }
        refreshStartNotice()
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
        armRace(TimerService.startIntent(this, selectedSequence.id, freshStart = true))
    }

    /**
     * Arm the selected sequence with a [leadSeconds] run-up and start it (#104).
     *
     * Always a fresh start. The lead-time picker is a deliberate two-tap arming action for a race
     * that does not exist yet, so it must not fall into the restore path and come back with somebody
     * else's clock — Resume is the control for that, and it is on the same screen.
     *
     * The armed sequence goes into [selectedSequence] as well as into the intent, so the ~100 ms
     * between the tap and the service reporting in previews the race being started (4:10 on a 70 s
     * lead) rather than the plain sequence's 3:00. [dropLeadInFromSelection] takes it back out the
     * moment the engine has the race.
     */
    private fun handleStartWithLeadIn(boxAlertSeconds: Int) {
        val armed = withLeadIn(selectedSequence, boxAlertSeconds) ?: return
        lastBoxAlertSeconds = boxAlertSeconds
        TimerService.saveLastBoxAlertSeconds(this, boxAlertSeconds)
        resyncAcknowledged = false
        clearResumeOffer()
        selectedSequence = armed
        uiRemainingMs = armed.totalMs
        armRace(TimerService.startIntent(this, armed.id, freshStart = true))
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

    /**
     * Post a Tier 1 banner (see `docs/message-surface.md`).
     *
     * Setting the text is all this does. **The three seconds are counted by the screen, not here**
     * (#102): this used to post its own `uiHandler.postDelayed` clear, which started the clock at the
     * moment of the call — fine for the two consumers that fire off an engine callback with the app
     * already up, and useless for the two that fire from `onCreate`. A cold launch on the watch takes
     * about four and a half seconds to first paint, measured, so a notice posted in `onCreate` had
     * already expired by the time there was anything to show it on. It was set, it reached
     * composition, and it was cleared 0.7 s after the first frame — which is why #102 read as "never
     * renders": nothing was broken except *when the timer started*.
     *
     * [TimerScreen] now owns the dwell and calls back when it is up, so the three seconds are three
     * seconds a sailor could actually have read it for, whatever the app spent getting there.
     */
    private fun showTransientMessage(text: String) {
        uiMessage = text
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

    /**
     * Say out loud that a cue the sailor was timing off did not sound, or did not sound in full (#161).
     *
     * The last leg of the route from the tone thread. `ToneManager` records the loss under the lock
     * it already holds, this collects it lock-free on the next refresh, and `cueLossNotice` in
     * `shared/` decides the words — so the only thing here is *when* to ask.
     *
     * Read-and-clear, so a loss is announced exactly once however many refreshes see it. Called from
     * the race-under-way branch of [refreshUiState] alongside [announceRestoreOutcome], which is the
     * only branch that can be reached while cues are firing.
     *
     * Tier 1 rather than Tier 3, unlike #96's Do Not Disturb line: that describes a condition
     * standing over the whole countdown, and this is a single event that has already happened. A
     * persistent line would still be on screen at the gun, saying nothing about the gun.
     */
    private fun announceCueLoss() {
        cueLossNotice(timerService?.consumeCueLoss())?.let { showTransientMessage(it) }
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
            // No race is running, so nothing is in a lead-in — and the control to arm one belongs to
            // the clean pre-start screen only, never beside the Resume/Start over pair (see
            // TimerScreen for why three controls do not fit this column).
            uiInLeadIn = false
            uiLeadInOffered = offersLeadIn(selectedSequence) && !uiResumeOffered
            // Suppressed once the sailor has answered: they have committed, the race is already being
            // discarded, and a warning about it is no longer something they can act on.
            uiDiscardWarning = if (resumeAnswered) null else discardWarning()
            // The pre-start screen is where every one of these five conditions is both true and
            // actionable, so this is the only branch that reads them (#13).
            refreshStartNoticeThrottled()
            // Every way a sequence ends — Stop, the post-gun teardown, Done — lands here, so this is
            // also the single point at which the brightness override is handed back (#65 AC 3).
            applyDisplayPolicy(TimerState.IDLE)
            return
        }
        // A race is under way, so nothing here is actionable and rule 3 of docs/message-surface.md
        // forbids taking the screen. The panel is already unreachable by construction — it renders
        // inside the idle button branch — and dropping the pre-start judgement as well keeps a stale
        // Tier 3 line from riding along under the sequence name for the length of a race (#13).
        //
        // What replaces it is the one warning that belongs *only* here (#96). The five conditions
        // above are all knowable before Start; whether the cues could be made audible is not, because
        // the platform refuses a volume raise without saying so. `TimerService` finds out by trying
        // at the moment the race is armed, and this is the first screen that can carry the answer.
        // `armedNotice` returns null in every state but RUNNING and whenever the raise was not
        // refused, so the ordinary race is byte-for-byte the screen it was.
        uiStartNotice = armedNotice(
            state = engine.currentState,
            cueVolumeRefused = timerService?.cueVolumeRefused == true,
        )
        // A race the engine is actually running outranks a saved one: it has already been answered.
        clearResumeOffer()
        // And the lead-in that armed it was a per-race choice, spent the moment the engine took the
        // race (#104) — see [dropLeadInFromSelection].
        dropLeadInFromSelection()
        uiTimerState = engine.currentState
        // Sync has nothing to snap to until the sequence proper is under way. Read from the engine's
        // own sequence and its live clock rather than from the selection, which no longer carries the
        // lead by the time this runs.
        uiInLeadIn = engine.currentState == TimerState.RUNNING &&
            engine.loadedSequence?.let { isInLeadIn(it, engine.remainingMs) } == true
        // Nothing to arm while a race is running; the control returns with the pre-start screen.
        uiLeadInOffered = false
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
        announceCueLoss()
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
        private const val TAG = "MainActivity"
        private const val NAV_TIMER = "timer"
        private const val NAV_PICKER = "picker"
        private const val NAV_CUSTOM = "custom"
        private const val NAV_LEAD_IN = "lead_in"
        private const val NAV_LEAD_CUSTOM = "lead_in_custom"
        /**
         * Fallback poll rate; the running countdown comes from onTick, not from this.
         *
         * Kept at 100 ms rather than slowed further because a Stop or Reset tap silences the engine
         * and so has nothing but this to notice it — and past about 100 ms a button stops feeling
         * like it responded. An idle pass writes no state that changed, so it costs no recomposition.
         */
        private const val UI_FALLBACK_REFRESH_MS = 100L
        private const val SYNC_LABEL_DURATION_MS = 2_000L

        /** How often the pre-start screen re-reads the watch's readiness. See the throttle. */
        private const val READINESS_REFRESH_MS = 1_000L
    }
}
