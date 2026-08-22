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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.racetimer.phone.ui.ConfirmEndRaceDialog
import com.racetimer.phone.ui.CustomDurationScreen
import com.racetimer.phone.ui.DEFAULT_CUSTOM_MINUTES
import com.racetimer.phone.ui.DisplayChoiceScreen
import com.racetimer.phone.ui.PhoneReadout
import com.racetimer.phone.ui.PhoneTheme
import com.racetimer.phone.ui.SequencePickerScreen
import com.racetimer.phone.ui.TimerScreen
import android.os.SystemClock
import com.racetimer.shared.BG_NORMAL_ARGB
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.RestoreOutcome
import com.racetimer.shared.TimerState
import com.racetimer.shared.formatCountdown
import com.racetimer.shared.resumeOfferRemainingMs
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
 * What it deliberately does not do yet, with the story that brings it: the two-stage signal-box
 * lead-in (#207) and cue haptics (#208). (Restore after a kill was listed here until #209 noticed
 * the line had outlived #205, which shipped it — `offerSavedRace` below is that work. Count-up
 * after the gun was listed until #206, which is the same lesson landing a second time: a line
 * naming a *future* story number is correct when written and false the moment that story merges,
 * and no grep aimed at the change will find it, because the change is not what it names.)
 *
 * **Position across a recreation was the gap, and #281 closed it — by not restoring a position at
 * all.** Which screen is showing is still `remember`ed rather than saved; what changed is that the
 * app no longer needs to remember it, because the *engine* already knows. A bind that lands on a
 * RUNNING or COUNTING_UP race opens the timer screen from that state (see `RaceTimerApp`), so a
 * rotation, a mid-race Back, or a system destroy-and-recreate all come back to the running race
 * without a saved-state mechanism to get out of step with it. Deriving the screen from the race is
 * the point: a persisted "was on the timer screen" flag could outlive the race it described.
 *
 * #281 measured what that gap actually cost, which was worse than losing your place: the recreated
 * UI got **no route back** to the live race (`offerSavedRace` correctly declines to offer onto a
 * non-IDLE engine), and the natural recovery — tapping the same sequence in the picker — ran
 * `select` → `TimerEngine.load`, which sets IDLE unconditionally and killed the race outright.
 * Three individually-correct pieces composing into a dead race. The selection half is guarded in
 * `PhoneRaceRunner.select` and the destructive path now has to be confirmed.
 *
 * The idle-state **selection** half was closed earlier: since #209 the pick is written to prefs on
 * every selection and re-applied from the launch plan on every bind, so what comes back is what the
 * officer chose rather than the default. The engine keeps running through all of it regardless; it
 * is in the service.
 */
class MainActivity : ComponentActivity() {

    /** The service's race, or null before the binding lands. Compose state so the UI follows it. */
    private val runnerState = mutableStateOf<PhoneRaceRunner?>(null)

    /** The saved race's remaining time as display text, when a launch found one to offer (#205). */
    private val resumeOfferState = mutableStateOf<String?>(null)

    /**
     * Where the Custom stepper should reopen: the length a restored or remembered Custom race was
     * running, or null to leave the default alone (#209).
     *
     * Derived by the shared plan from the sequence's own id rather than stored as a second value, so
     * there is nothing here that can disagree with the race.
     */
    private val customMinutesState = mutableStateOf<Int?>(null)

    /**
     * The officer's display answers, resolved from the **process-scoped** store (#281, #225).
     *
     * `by lazy` rather than a call inside the composition: `ViewModelProvider.get` is idempotent and
     * would be harmless per recomposition, but the lifetime being talked about here is the point of
     * the property, and resolving it once where it can be read is what makes that legible.
     *
     * [RaceTimerPhoneApplication] owns the store, so this instance is shared by every activity this
     * process creates and is destroyed only with the process. That is what #225 always said the
     * retention was; until #281 it was an activity-scoped `viewModel()` and a destroy-and-recreate
     * silently reset it.
     */
    private val processDisplayChoice: DisplayChoiceViewModel by lazy {
        ViewModelProvider(applicationContext as RaceTimerPhoneApplication)[
            DisplayChoiceViewModel::class.java,
        ]
    }

    private var boundService: PhoneTimerService? = null

    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val lb = binder as? PhoneTimerService.LocalBinder ?: return
            boundService = lb.service
            offerSavedRace(lb.service)
            runnerState.value = lb.service.runner
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            runnerState.value = null
        }
    }

    /**
     * Put the saved race in front of the officer, when the shared plan found one (#205).
     *
     * Only onto an idle engine: a bind that lands mid-race — a rotation, a return from the
     * background — must not re-offer the race that is already running. The offer's number is what
     * resuming will actually show, from the same shared arithmetic the watch's offer uses.
     */
    private fun offerSavedRace(service: PhoneTimerService) {
        if (service.runner.engine.currentState != TimerState.IDLE) return
        val plan = service.launchPlan()
        // Applied whether or not a race survived, and before the offer is considered: a pick
        // outranks nothing, but it is what an ordinary launch — no race saved, three Club starts run
        // yesterday — has to open on (#88, #209).
        plan.sequence?.let { service.runner.select(it) }
        plan.customMinutes?.let { customMinutesState.value = it }
        val resumable = plan.resumable ?: return
        val (snapshot, sequence) = resumable
        service.runner.select(sequence)
        val remainingMs = resumeOfferRemainingMs(
            snapshot, sequence, sequence.id,
            SystemClock.elapsedRealtime(), System.currentTimeMillis(),
        ) ?: return
        resumeOfferState.value = formatCountdown(remainingMs)
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
                    onSyncRace = { PhoneTimerService.sync(this) },
                    onEndRace = { PhoneTimerService.endRace(this) },
                    resumeOffer = resumeOfferState.value,
                    onStartOverRace = { PhoneTimerService.start(this, freshStart = true) },
                    collectRestoreNotice = { boundService?.consumeRestoreNotice() },
                    // Every selection, including a Custom one just dialled: the id is the whole
                    // record, so remembering it is what makes the next cold launch open where the
                    // officer left off (#209).
                    onSequencePicked = { boundService?.savePickedSequence(it.id) },
                    initialCustomMinutes = customMinutesState.value,
                    // Resolved from the *Application's* store, not this activity's (#281). #225
                    // ratified the officer's display answers as lasting for the life of the
                    // process; the parameter's `viewModel()` default gives the life of the
                    // **activity**, which is a different thing the moment the system destroys and
                    // recreates one — and #281 measured "Screen for today" being re-asked over a
                    // race still running in a live process. This one line is the whole fix for
                    // that half, and it stores nothing: the state still dies with the process,
                    // because that is when this store dies.
                    displayChoice = processDisplayChoice,
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
        boundService = null
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
    onSyncRace: (() -> Unit)? = null,
    onEndRace: (() -> Unit)? = null,
    resumeOffer: String? = null,
    onStartOverRace: (() -> Unit)? = null,
    collectRestoreNotice: (() -> RestoreOutcome?)? = null,
    onSequencePicked: ((RaceSequence) -> Unit)? = null,
    initialCustomMinutes: Int? = null,
    displayChoice: DisplayChoiceViewModel = viewModel(),
) {
    var onTimerScreen by remember { mutableStateOf(false) }
    var onCustomScreen by remember { mutableStateOf(false) }
    var readout by remember(runner) {
        mutableStateOf(runner?.readout() ?: PhoneReadout.of(TimerState.IDLE, 0L, 0L))
    }
    var state by remember(runner) {
        mutableStateOf(runner?.engine?.currentState ?: TimerState.IDLE)
    }

    val startRace = onStartRace ?: { runner?.start() }
    val stopRace = onStopRace ?: { runner?.stop() }
    val syncRace = onSyncRace ?: { runner?.sync() }
    val endRaceNow = onEndRace ?: { runner?.endRace() }
    val startOverRace = onStartOverRace ?: { runner?.start() }

    // Consumed once either offer control is tapped (or Back declines it); the snapshot on disk
    // outlives a decline, so the next launch offers again — discarding is Start over's job alone.
    var offerConsumed by remember { mutableStateOf(false) }
    var restoreNotice by remember { mutableStateOf<String?>(null) }

    // The sequence a selection chose while a race was still running, held until the officer says
    // whether to end that race (#281 AC 4). Null whenever there is nothing to confirm — and it is
    // the *pending* selection rather than a bare flag, because the question names the sequence and
    // answering yes has to apply the very thing that was refused.
    var pendingSequence by remember { mutableStateOf<RaceSequence?>(null) }

    fun refresh() {
        runner ?: return
        readout = runner.readout()
        state = runner.engine.currentState
    }

    // A bind that lands on a race already running puts the officer back on it (#281 AC 1, AC 5).
    //
    // The engine outlives the activity — it is in the service — so after a rotation, a mid-race
    // Back, or a system destroy-and-recreate, the composition is new while the race is not. Before
    // #281 that composition opened on the picker, which is a screen for choosing a race that is
    // already running, and the obvious tap there destroyed it.
    //
    // **Derived from the engine, not restored from saved state.** There is deliberately no
    // persisted "was on the timer screen" flag: such a flag can outlive the race it describes,
    // and would then send the officer to a timer screen with nothing behind it. The engine's own
    // state cannot disagree with the engine.
    //
    // Keyed on `runner` because that is when the answer can first be known: it is null until the
    // service binding lands, and re-keying on it also re-runs this after `onStop`/`onStart` drops
    // and restores the binding. `raceInProgress` is the runner's own rule (RUNNING or COUNTING_UP)
    // rather than a second copy of it here.
    LaunchedEffect(runner) {
        if (runner != null && runner.raceInProgress) {
            readout = runner.readout()
            state = runner.engine.currentState
            onTimerScreen = true
        }
    }

    // A saved race walks the officer straight to it: the offer lands on the timer screen, not
    // behind a picker tap they have no reason to make (#205). The activity already selected the
    // saved sequence in the runner before handing the offer over.
    //
    // Cannot collide with the reattach above: `offerSavedRace` returns early on a non-IDLE engine,
    // so a live race produces no offer at all, and an offer implies the engine was idle.
    LaunchedEffect(resumeOffer, runner) {
        if (resumeOffer != null && !offerConsumed && runner != null) {
            readout = runner.readout()
            state = runner.engine.currentState
            onTimerScreen = true
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
            collectRestoreNotice?.invoke()?.let { outcome ->
                restoreNotice = when (outcome) {
                    RestoreOutcome.EXACT -> null
                    RestoreOutcome.DEGRADED ->
                        "Restored after a reboot — re-sync at the next flag"
                    RestoreOutcome.EXPIRED ->
                        "The saved race had already finished — started fresh"
                }
            }
            if (state != TimerState.RUNNING && state != TimerState.FINISHED) restoreNotice = null
            delay(UI_REFRESH_MS)
        }
    }

    // A race the officer is actively running — the countdown, and (#206) the count-up past the gun,
    // which is just as much a race in progress even though the clock changed direction. RACE_ENDED
    // is deliberately not here: a frozen summary is finished, and Back dismissing it is right.
    val raceActive = state == TimerState.RUNNING || state == TimerState.COUNTING_UP

    // #279. Where the race is reaches the *app* here and stops here: what crosses into the display
    // mechanism is still two booleans, computed by `displayChoiceInEffect` and asserted by
    // `ModuleBoundaryTest` to be all that path can see.
    val countingUp = state == TimerState.COUNTING_UP

    // The one moment worth interrupting an officer for (#279). The whole rule, and the argument for
    // each of its five clauses, is `countUpBrightnessPromptShows` — a pure function since #281,
    // which is where it is proven.
    //
    // It moved out of here because #281 took away the only arrangement that could exercise its
    // `onTimerScreen` clause through the UI. That clause says **silence is only an answer if the
    // question was askable**; #279 justified it by an activity recreation mid-count-up landing on
    // the picker, and the effect above is precisely what stops that happening. Left inline, the
    // clause would have kept a real job (the frames before the service binding lands) and no way to
    // fail — so it was given a subject a test can reach instead of being deleted on the strength of
    // a green suite.
    val brightnessPrompt = countUpBrightnessPromptShows(
        answered = displayChoice.answered,
        onTimerScreen = onTimerScreen,
        countingUp = countingUp,
        fullBrightnessChosen = displayChoice.choice.fullBrightness,
        countUpKeepsBrightness = displayChoice.countUpKeepsBrightness,
    )

    // Silence dims (#279). Keyed on the prompt, so any change that takes the question off screen —
    // an answer, End Race, leaving the timer screen — cancels it, and a timer that outlived the
    // question would dim a panel nobody was asked about.
    //
    // Backgrounding cancels it too, and **not** because the composition goes: a `setContent`
    // composition survives `onStop`. It is `onStop` clearing `runnerState` that re-keys `state` to
    // IDLE and drops `countingUp`. That is a real dependency on another lifecycle callback rather
    // than a property of Compose, so a change that keeps the binding across `onStop` — plausible
    // for #216's all-day run — would leave this running unattended. Stated because the earlier
    // wording here credited the composition and would have made that change look safe.
    LaunchedEffect(brightnessPrompt) {
        if (brightnessPrompt) {
            delay(COUNT_UP_PROMPT_DWELL_MS)
            displayChoice.answerCountUpBrightness(keepBright = false)
        }
    }

    val displayInEffect = displayChoiceInEffect(
        chosen = displayChoice.choice,
        countingUp = countingUp,
        countUpKeepsBrightness = displayChoice.countUpKeepsBrightness,
    )

    // Keyed on what is *in effect* rather than on what was chosen, so the count-up rule above
    // applies through the same one call site — the seam #225 left open for exactly this ("a later
    // surface that lets the officer change their mind mid-day applies without anyone remembering to
    // add a call here"). Nothing is applied before the launch surface is answered: until then the
    // phone behaves as an unmodified one, which is also what makes the choice screen an honest
    // preview. A pass where the effective value has not changed re-applies nothing, which is what
    // keeps "the display was decided once" true of a race nobody was asked about.
    LaunchedEffect(displayChoice.answered, displayInEffect) {
        if (displayChoice.answered) {
            applyDisplay(displayInEffect)
        }
    }

    /**
     * Take [sequence] and open the timer screen — the one path both entries take, once the running
     * race (if any) has been dealt with.
     *
     * Remembering happens here rather than at each call site so a later third way of choosing a race
     * cannot forget to (#88's shape: the watch's `applySelection` centralises it for exactly this
     * reason).
     */
    fun openWith(sequence: RaceSequence) {
        onSequencePicked?.invoke(sequence)
        refresh()
        onTimerScreen = true
    }

    /**
     * Select [sequence] unless that would discard a race in progress (#281 AC 4).
     *
     * Guarded on the binding having landed: selecting is runner state, and navigating to a timer
     * screen with nothing behind it would show a dead readout.
     *
     * `select` returning **false** means the engine is RUNNING or COUNTING_UP and nothing moved —
     * so the selection is parked and the officer is asked, rather than losing a race to a tap. That
     * is the state #281 measured, where this function ran straight through `TimerEngine.load` and
     * killed a live race with no warning and no undo.
     */
    fun selectAndOpen(sequence: RaceSequence) {
        runner ?: return
        if (!runner.select(sequence)) {
            pendingSequence = sequence
            return
        }
        openWith(sequence)
    }

    // Backing out of the stepper is a plain cancel: nothing was selected on the way in, so there is
    // no race state to unwind and the picker is exactly where the officer was.
    BackHandler(enabled = onCustomScreen) { onCustomScreen = false }

    // Back returns to the picker, but never mid-race: the gesture is one an officer makes without
    // looking, and it must not be able to end a start sequence. While a race is on it falls through
    // to the system, which backgrounds the app with the race still in the service.
    BackHandler(enabled = onTimerScreen && !raceActive) {
        // Backing out of the offer declines it for this sitting without discarding the snapshot;
        // stopping an idle engine is a no-op beyond returning the screen to the top.
        offerConsumed = true
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
            state = state,
            onStart = {
                startRace()
                refresh()
            },
            onStop = {
                stopRace()
                refresh()
            },
            onSync = {
                // The snap is the feedback: on a console-sized readout the display jumping to the
                // whole minute is visible in a way a wrist needed a beep for. The engine's own
                // double-tap guard makes a nervous second tap harmless.
                syncRace()
                refresh()
            },
            onEndRace = {
                endRaceNow()
                refresh()
            },
            notice = restoreNotice,
            brightnessPrompt = brightnessPrompt,
            onKeepBright = { displayChoice.answerCountUpBrightness(keepBright = true) },
            onDimCountUp = { displayChoice.answerCountUpBrightness(keepBright = false) },
            resumeOffer = if (offerConsumed) null else resumeOffer,
            onResume = {
                offerConsumed = true
                startRace()
                refresh()
            },
            onStartOver = {
                offerConsumed = true
                startOverRace()
                refresh()
            },
        )
    } else if (onCustomScreen) {
        CustomDurationScreen(
            // The stepper reopens on the length last run rather than the default, so re-running last
            // week's start is one tap. Null leaves the default alone — see LaunchPlan.customMinutes,
            // which derives this from the sequence id rather than storing it a second time.
            initialMinutes = initialCustomMinutes ?: DEFAULT_CUSTOM_MINUTES,
            onConfirm = { chosenMinutes ->
                // `custom` is what turns minutes into a sequence, and its id carries them back out
                // (`custom_8m`). Everything downstream — the pick in prefs, the race snapshot,
                // `BuiltInSequences.resolve` on the next launch — reads that one string.
                selectAndOpen(BuiltInSequences.custom(chosenMinutes))
                onCustomScreen = false
            },
        )
    } else {
        SequencePickerScreen(
            sequences = runner?.sequences ?: PhoneRaceRunner.CONSOLE_SEQUENCES,
            onSelect = ::selectAndOpen,
            // Withheld until the binding lands, for the picker entries' own reason: a stepper that
            // cannot select anything is the dead menu entry #209 exists to avoid.
            onCustomSelected = if (runner != null) ({ onCustomScreen = true }) else null,
        )
    }

    // #281 AC 4. Outside the screen chain on purpose: it is a question *about* a selection rather
    // than a screen the officer navigated to, and the branch that raised it must stay composed
    // underneath — cancelling has to put them back exactly where they were.
    //
    // Reachable only from the picker or the Custom stepper, because those are the only two branches
    // that call `selectAndOpen`; the timer screen has no control that selects a sequence. So this
    // cannot cover a running readout, which is the concern behind the watch's own
    // "blocking is pre-start only" rule (`docs/message-surface.md`, a Wear document).
    //
    // After AC 1 this should never render in the shipped app: a live race opens the timer screen and
    // Back is disabled while it runs, so the picker is not reachable with a race in progress. It is
    // kept because the alternative leaves the invariant in the navigation alone — and navigation
    // held in a `remember` is what produced #281.
    pendingSequence?.let { sequence ->
        ConfirmEndRaceDialog(
            sequenceName = sequence.name,
            onConfirm = {
                pendingSequence = null
                // The one sanctioned way past the refusal, named so the destructive path is
                // greppable. `runner` cannot be null here: `selectAndOpen` returns early on a null
                // runner and is the only thing that sets `pendingSequence`.
                runner?.endRaceAndSelect(sequence)
                openWith(sequence)
            },
            onDismiss = {
                // The race is untouched — `select` already refused, so there is nothing to undo.
                // The selection is simply dropped and the officer stays where they were.
                pendingSequence = null
            },
        )
    }
}
