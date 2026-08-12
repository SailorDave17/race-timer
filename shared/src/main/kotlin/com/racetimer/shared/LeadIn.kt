package com.racetimer.shared

// ---------------------------------------------------------------------------
// Lead-in: the run-up that lets a race manager start an external signal box on the mark
//
// On the committee boat the watch is not the only thing making noise. The sailors hear the signal
// box; the race manager hears their wrist. Started by hand, whatever gap the race manager's thumb
// introduces is a gap the fleet races to for the whole sequence, and no mid-run Sync can close it —
// snapping to a whole minute corrects a *drifted* watch against a sequence already under way, which
// is a different problem from aligning two devices at the instant they both begin.
//
// So the watch waits. But it waits in TWO stages, because the box does not begin its sequence when
// you press it:
//
//   4:10  tap Start
//     |   STAGE 1 — prep: LEAD_IN_PREP_SECONDS to get a hand to the box.
//     |   Its last five seconds tick in CueVoice.SYNC.
//   4:00  PRESS THE BOX — CueVoice.PROMPT, the one cue that must be acted on.
//     |   STAGE 2 — the box's own alert window. The box sounds its 5 short horns here;
//     |   the watch is silent, because the box is doing the talking.
//   3:00  the sequence's own first signal — 3 long — and confirmation the two agree.
//     |   ...the sequence exactly as it always was...
//   0:00  gun
//
// The iStart Pro's ALERT is "optional 5 short horns before the first warning signal", and it sits at
// the TOP of the box's programme — so pressing START sounds it immediately and the box's real first
// signal comes one alert-window later. From the iStart Owner's Manual v2.0:
//
//   Dinghy (RRS App. S, 3 min)   alert 3:15, first signal 3:00  ->  15 s window
//   Rule 26 (5 min)              alert 6:00, first signal 5:00  ->  60 s window
//   alert switched off                                          ->   0 s window
//
// That Dinghy schedule is cue-for-cue BuiltInSequences.scholasticRaceManager, which is what makes
// these numbers this app's rather than a vendor's trivia.
//
// Everything the UI would otherwise have to branch on lives here as data (#104 AC 12): the presets,
// the custom bounds, the prep constant, the two-stage split, and which sequences offer the control
// at all. `TimerScreen` asks; it does not decide.
// ---------------------------------------------------------------------------

/**
 * Stage 1: how long the race manager gets to reach the box, in whole seconds.
 *
 * A constant, not a choice. It is the time to move a hand, which does not vary from club to club the
 * way a box's configuration does — and every value a picker offers is one more thing to get wrong on
 * the water. If ten seconds turns out not to be enough, change it here; do not make it a question.
 */
const val LEAD_IN_PREP_SECONDS = 10

/**
 * The box alert windows offered without dialling, shortest first: none, iStart Dinghy, iStart Rule 26.
 *
 * These name the **box's** alert setting rather than a total lead, because that is the fact the race
 * manager actually holds — it is printed on the mode chart on the back of their unit — whereas a
 * total is something they would have to compute from it. All three are offered whatever sequence is
 * loaded: which alert pairs with which style is the race manager's business, the box may be set to
 * something the watch cannot infer, and guessing is how two devices end up disagreeing.
 */
val BOX_ALERT_PRESET_SECONDS: List<Int> = listOf(0, 15, 60)

/** A box that sounds no alert. The race manager presses on the sequence's own first signal. */
const val BOX_ALERT_NONE = 0

/**
 * The shortest *dialled* alert, in whole seconds. Zero is reached through [BOX_ALERT_NONE] instead.
 *
 * Five, because the stage-1 run-in is five ticks: below that the prompt and the sequence's own first
 * signal crowd each other, and an alert window shorter than the cadence announcing it is not a window
 * the race manager can use.
 */
const val BOX_ALERT_MIN_SECONDS = 5

/**
 * The longest alert, in whole seconds — 2:00.
 *
 * Wide enough to clear the 60 s Rule 26 preset with room for a longer box warning, and deliberately
 * not wider: a one-second stepper reaching several minutes is a long scroll, and an accidentally
 * *large* alert is harder to spot on a round screen than an accidentally small one. Widen it if real
 * hardware turns out to need it; do not widen it speculatively.
 */
const val BOX_ALERT_MAX_SECONDS = 120

/** Whole seconds, because box alerts are stated in whole seconds. Excludes zero — see [BOX_ALERT_NONE]. */
val BOX_ALERT_DIALLED_RANGE: IntRange = BOX_ALERT_MIN_SECONDS..BOX_ALERT_MAX_SECONDS

/** Every alert value that may be armed, dialled or not. */
fun isValidBoxAlert(seconds: Int): Boolean =
    seconds == BOX_ALERT_NONE || seconds in BOX_ALERT_DIALLED_RANGE

/** Where the alert picker opens before there has ever been a choice to reopen on. */
const val DEFAULT_BOX_ALERT_SECONDS = BOX_ALERT_NONE

/**
 * The whole lead a [boxAlertSeconds] setting produces: prep, then the box's alert window.
 *
 * The number the race manager is about to watch count down, as against the one they were asked for.
 * Both belong on screen at the moment of committing — showing only the alert makes the countdown a
 * surprise, and showing only the total asks them to work backwards to check it against their box.
 */
fun leadInSecondsFor(boxAlertSeconds: Int): Int = LEAD_IN_PREP_SECONDS + boxAlertSeconds

// ---------------------------------------------------------------------------
// Which sequences offer it
// ---------------------------------------------------------------------------

/**
 * May [sequence] be armed with a lead-in?
 *
 * **Race-manager modes only.** Syncing to an external signal box is committee work: a sailor has no
 * box to sync to, and a sailor who triggers this by accident starts their race late — the one error
 * this app must never introduce.
 *
 * Read off [RaceSequence.countUpAfterFinish] rather than a flag of its own, because that *is* the
 * property being asked about. It says the gun is not the end of this job, which is the definition of
 * committee work and is true of exactly the race-manager sequences. A second flag set on precisely
 * the same sequences would be a second copy of one fact, and this file's neighbours in `shared/`
 * exist because of what two copies of one rule have cost here before (see `RestorePlan.kt`). If a
 * sequence ever needs one without the other, this one line is where that divergence gets stated —
 * not fifteen call sites.
 *
 * A sequence that already carries a lead answers true as well; it is still committee work. What
 * stops a lead stacking on a lead is [withLeadIn], which arms the base sequence rather than
 * whatever it was handed.
 */
fun offersLeadIn(sequence: RaceSequence): Boolean = sequence.countUpAfterFinish

/**
 * Is [remainingMs] still inside [sequence]'s lead-in — either stage — rather than in the sequence?
 *
 * The boundary is strict: at exactly [RaceSequence.sequenceMs] the sequence's own first signal is
 * firing, and that signal is the end of the lead-in, not part of it.
 *
 * Two callers, and they want it for the same reason. `TimerEngine.sync` refuses to snap here —
 * there is nothing to snap *to* yet, since the sequence proper has not begun — and snapping 4:07 to
 * 4:00 on a 3:00 sequence silently deletes seven seconds of the very lead the run-up exists to
 * protect. The screen then drops the Sync button for the duration, because a control that takes the
 * tap and does nothing reads as broken.
 */
fun isInLeadIn(sequence: RaceSequence, remainingMs: Long): Boolean =
    sequence.leadInMs > 0L && remainingMs > sequence.sequenceMs

// ---------------------------------------------------------------------------
// Arming a sequence
// ---------------------------------------------------------------------------

/**
 * [sequence] with a lead-in sized for a box whose alert window is [boxAlertSeconds], or null if it
 * cannot have one.
 *
 * Null rather than a clamp or a fallback, matching [BuiltInSequences.resolve]: the two ways to get
 * here are the picker, which only ever offers legal values, and a persisted id, where substituting
 * *something* would be the "wrong duration, wrong cues, no error" failure #51 already paid for.
 *
 * The gun lands at `prep + alert + the sequence's own duration` from the tap, and it lands there
 * because [RaceSequence.totalMs] adds the stages rather than reading the largest cue offset — see
 * [RaceSequence.sequenceMs] for why that distinction is the whole of this.
 *
 * The sequence's own cues are passed through untouched, by identity: the 3-long still fires at 3:00,
 * the tail is bit-for-bit what it was, and the gun is the same object. Only the run-up is new.
 *
 * Arms the **base** sequence, so handing this a sequence that already carries a lead re-arms it at
 * the new value instead of stacking a second run-up on the first.
 */
fun withLeadIn(sequence: RaceSequence, boxAlertSeconds: Int): RaceSequence? {
    if (!isValidBoxAlert(boxAlertSeconds)) return null
    val base = leadInBaseOf(sequence) ?: return null
    if (!offersLeadIn(base)) return null
    val alertMs = boxAlertSeconds * 1_000L
    return RaceSequence(
        id = leadInId(base.id, boxAlertSeconds),
        name = base.name,
        cues = leadInRun(base.sequenceMs + alertMs, silentPrompt = alertMs == 0L) + base.cues,
        countUpAfterFinish = base.countUpAfterFinish,
        leadInMs = LEAD_IN_PREP_SECONDS * 1_000L + alertMs,
    )
}

/** [sequence] without its run-up, or [sequence] itself when it has none. Null if it cannot be rebuilt. */
fun leadInBaseOf(sequence: RaceSequence): RaceSequence? =
    if (sequence.leadInMs == 0L) sequence else BuiltInSequences.resolve(leadInBaseId(sequence.id))

/**
 * Stage 1's cues: five ticks counting into the press moment at [pressOffsetMs], then the prompt.
 *
 * **Five ticks at the end of stage 1, whatever the alert.** The run-in matters most closest to the
 * act, and a fixed five-tick tail is the same shape at every setting, so the race manager learns one
 * cadence rather than one per preset. Ticking a whole 60-second alert window would be unusable, and
 * it would put 60 pulses where the box's own alert needs to stand out. Same reasoning that keeps
 * `finalMinuteTail` single at 0:10 to 0:06 before `finalFiveRun` doubles.
 *
 * [BuiltInSequences.syncRunInto] rather than a second five-tick generator, so this cannot drift from
 * the runs US Sailing already uses into 4:00 and 1:00 — [CueVoice.SYNC] is precisely the voice that
 * exists so a tick counting *into* a signal can never be heard as a signal, and its lighter, shorter
 * haptic falls out of that voice via [CueTiming] rather than being set here.
 *
 * [silentPrompt] drops the prompt itself, and is set for exactly one case: an alert window of zero,
 * where the press moment and the sequence's own first signal are the same instant. Two cues on one
 * offset is not a thing a sequence may contain, and it is not a thing the race manager needs — the
 * 3-long is already unmissable and already means "the box should be going now". Which is what the
 * first draft of this feature assumed universally, and it was right only here.
 */
private fun leadInRun(pressOffsetMs: Long, silentPrompt: Boolean): List<SequenceCue> {
    val ticks = BuiltInSequences.syncRunInto(pressOffsetMs, "box start")
    val prompt = if (silentPrompt) emptyList() else listOf(
        SequenceCue(
            offsetMs = pressOffsetMs,
            signal = SignalPattern(
                shortBlasts = PROMPT_PULSES,
                voice = CueVoice.PROMPT,
                label = "Press the signal box",
            ),
        ),
    )
    return (ticks + prompt).map { it.copy(isLeadIn = true) }
}

/**
 * Pulses in the press prompt.
 *
 * Five, at [CueTiming.PROMPT_ON] — 400 ms of stutter, read as one event rather than as a count. The
 * number is deliberately *not* meaningful: a prompt that could be counted invites being read as a
 * blast pattern, and `3 short` is a real cue at 0:30 of this very sequence.
 */
private const val PROMPT_PULSES = 5

// ---------------------------------------------------------------------------
// The alert window inside the id
// ---------------------------------------------------------------------------
//
// The sequence id is the entire persisted state: `TimerEngine.Snapshot` stores only `sequenceId` and
// `BuiltInSequences.resolve` rebuilds from it, which is why a Custom race's duration lives inside
// `custom_8m` rather than beside it. The alert window is the same problem — anything not derivable
// from the id does not survive a process kill — so it goes in the id too, and AC 13 (restore in
// either stage, at any alert value) is satisfied by construction rather than by a fifth persisted key.
//
// The prep stage is deliberately NOT in the id, because it is not a choice. The cost of that is
// real and worth naming: an id written by one build and restored by another inherits the *restoring*
// build's prep. Ten seconds either way on a race already under way is a smaller error than carrying a
// constant around in every id to guard against a change nobody is planning.

private const val ALERT_ID_MARKER = "_alert"
private const val ALERT_ID_SUFFIX = "s"

/** The id [baseId] armed for a [boxAlertSeconds] box carries, e.g. `scholastic_race_manager_alert60s`. */
fun leadInId(baseId: String, boxAlertSeconds: Int): String =
    "$baseId$ALERT_ID_MARKER$boxAlertSeconds$ALERT_ID_SUFFIX"

/**
 * The box alert window encoded in [id], or null when [id] carries none or carries a nonsensical one.
 *
 * Out-of-range values return null rather than clamping, exactly as [BuiltInSequences.customMinutes]
 * does: a malformed id must fail to resolve, not resolve to something plausible.
 */
fun boxAlertSeconds(id: String): Int? {
    val marker = id.lastIndexOf(ALERT_ID_MARKER)
    if (marker < 0 || !id.endsWith(ALERT_ID_SUFFIX)) return null
    val seconds = id
        .substring(marker + ALERT_ID_MARKER.length, id.length - ALERT_ID_SUFFIX.length)
        .toIntOrNull() ?: return null
    return if (isValidBoxAlert(seconds)) seconds else null
}

/** [id] with any alert suffix stripped, or [id] unchanged when it has none. */
fun leadInBaseId(id: String): String =
    if (boxAlertSeconds(id) == null) id else id.substring(0, id.lastIndexOf(ALERT_ID_MARKER))

/**
 * The armed sequence [id] names, or null when it names none. Backs [BuiltInSequences.resolve].
 *
 * A doubly-armed id (`..._alert5s_alert9s`) is refused rather than quietly arming once: it would
 * otherwise resolve to a sequence whose own id is *not* the one asked for, and the resume offer
 * compares those ids to decide whether a saved race may be offered back at all.
 */
internal fun leadInFromId(id: String): RaceSequence? {
    val seconds = boxAlertSeconds(id) ?: return null
    val baseId = leadInBaseId(id)
    if (boxAlertSeconds(baseId) != null) return null
    val base = BuiltInSequences.resolve(baseId) ?: return null
    return withLeadIn(base, seconds)
}
