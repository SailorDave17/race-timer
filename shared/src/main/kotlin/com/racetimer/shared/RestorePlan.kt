package com.racetimer.shared

// ---------------------------------------------------------------------------
// Restore decisions (pure, testable)
//
// The three questions the watch asks about a race that outlived its process:
//
//   1. Do the persisted values amount to a race at all?          [snapshotFrom]
//   2. What should the pre-start screen open on?                 [launchPlan]
//   3. Does a tap on Start resume that race, or run from the top? [startPlan]
//
// All three used to be answered inside `wear/`, behind an Android `Context`, where the JVM suite
// could not reach them (#64). Two of them were answered in more than one place.
//
// That duplication, not the missing coverage, is what this file is really for. Every defect in the
// restore path so far has been two copies of one rule disagreeing: #89 (the recoverability rule,
// written out a second time inverted) and the stale resume preview before it (an offer that checked
// two of the three guards a start restores under, in a comment that said "the same two guards" out
// loud). Moving a rule here makes a second copy impossible rather than merely wrong.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 1. Is there a saved race?
// ---------------------------------------------------------------------------

/**
 * The stored `gunElapsedMs` reading that means "nothing stored".
 *
 * Unguarded, unlike the two below — see [snapshotFrom].
 */
const val NO_GUN_ELAPSED_MS = -1L

/** The stored `gunWallMs` reading that means "no gun anchor". Any value at or below zero is absent. */
const val NO_GUN_WALL_MS = -1L

/**
 * The stored `capturedElapsedMs` reading that means "nothing stored".
 *
 * `Long.MIN_VALUE` rather than -1 because this one is compared for equality, not for sign: the
 * capture reading is a monotonic clock sample and the sentinel has to be a value no sample could
 * ever be.
 */
const val NO_CAPTURED_ELAPSED_MS = Long.MIN_VALUE

/**
 * The race the four persisted values describe, or null when they do not describe one.
 *
 * The single answer to "is there a race to come back to". It was previously answered twice inside
 * `wear/`: once in `TimerService.savedSnapshot`, which the pre-start screen reads to decide whether
 * to offer Resume, and once inline in `onStartCommand`, which decides whether a tap actually
 * restores. Those two disagreeing is silent and one-directional — the screen offers a Resume that
 * the tap then refuses, and the sailor gets a fresh race where they asked for the one they had.
 *
 * [gunElapsedMs] is passed through unguarded on purpose. The four keys are written and cleared as a
 * set, so it is present whenever the two guarded ones are; and it is the *monotonic* half of the
 * anchor, which a restore is already prepared to distrust — [gunTimeFromSnapshot] falls back to the
 * wall-clock reconstruction when the monotonic domain has been broken by a reboot. Guarding it here
 * would refuse a restore that the engine can still do, just degraded.
 */
fun snapshotFrom(
    sequenceId: String?,
    gunElapsedMs: Long,
    gunWallMs: Long,
    capturedElapsedMs: Long,
): TimerEngine.Snapshot? {
    if (sequenceId == null) return null
    if (gunWallMs <= 0L || capturedElapsedMs == NO_CAPTURED_ELAPSED_MS) return null
    return TimerEngine.Snapshot(
        sequenceId = sequenceId,
        gunElapsedMs = gunElapsedMs,
        gunWallMs = gunWallMs,
        capturedElapsedMs = capturedElapsedMs,
    )
}

// ---------------------------------------------------------------------------
// 2. What does the app open on?
// ---------------------------------------------------------------------------

/**
 * Something the sailor has to be told on launch, because what they left behind could not be read.
 *
 * Named rather than worded: `shared/` has no resources and no business holding UI copy. The caller
 * renders these, at whatever tier `docs/message-surface.md` says.
 */
enum class LaunchNotice {
    /**
     * A race was saved, but its sequence id resolves to nothing — so the race cannot be rebuilt and
     * is gone. Announced rather than absorbed (#51's rule): the alternative is a sailor quietly
     * racing a sequence they did not pick.
     */
    SAVED_RACE_UNREADABLE,

    /** The remembered pick resolves to nothing, so the default is being shown instead. */
    PICKED_SEQUENCE_UNREADABLE,
}

/**
 * What the pre-start screen should show on launch, decided from persistence alone.
 *
 * @property sequence      The sequence to select, or null to keep the default. Null means "nothing
 *                         was remembered", never "something was remembered and rejected" — a
 *                         rejection comes with a [notice].
 * @property customMinutes Where the duration stepper should reopen, or null to leave it alone.
 *                         Derived from [sequence]'s own id, and carried here rather than left to the
 *                         caller so that the one place deciding *what* is selected also decides
 *                         where re-dialling it starts from. A Custom race the sailor runs repeatedly
 *                         is then one tap from where they left it.
 * @property resumable     The saved race to offer back, paired with the sequence it runs — which is
 *                         always [sequence], because a race is only ever offered on its own
 *                         sequence. Null when there is nothing to offer: either no race was saved,
 *                         or the one that was is spent. Withholding the offer is not discarding the
 *                         race (see [resumeOfferRemainingMs]).
 * @property notice        What went wrong, or null when nothing did.
 */
data class LaunchPlan(
    val sequence: RaceSequence?,
    val customMinutes: Int?,
    val resumable: Pair<TimerEngine.Snapshot, RaceSequence>?,
    val notice: LaunchNotice?,
)

/**
 * Decide what to open on, from a saved race and a remembered pick.
 *
 * A saved race outranks a remembered pick, because it *is* a pick — made more recently, and with a
 * race attached. This is the #57 symptom A fix in its entirety: before it, a killed Scholastic race
 * came back as a fresh 5-minute US Sailing one, because the activity relaunched holding the default
 * and never asked persistence what had been running.
 *
 * The fall-through is what makes the two records independent (#88). A saved race whose id resolves to
 * nothing does not drag the remembered pick down with it: the pick is separate state, written on
 * every selection and cleared by nothing, so falling back to it beats dropping to the default on top
 * of the bad news.
 *
 * @param snapshot         the persisted race, from [snapshotFrom], or null if none survived.
 * @param pickedSequenceId the sequence id last chosen, or null on a first-ever launch.
 */
fun launchPlan(
    snapshot: TimerEngine.Snapshot?,
    pickedSequenceId: String?,
    nowElapsedMs: Long,
    nowWallMs: Long,
): LaunchPlan {
    if (snapshot == null) return pickedPlan(pickedSequenceId)

    val saved = BuiltInSequences.resolve(snapshot.sequenceId)
        // The race is unreadable; the pick may not be. If it is unreadable too, its notice is the one
        // that survives — which is what the two overlapping banners already amounted to, since the
        // second overwrote the first on a screen with one message line.
        ?: return pickedPlan(pickedSequenceId).let {
            it.copy(notice = it.notice ?: LaunchNotice.SAVED_RACE_UNREADABLE)
        }

    // `saved.id` is passed as the selection because this plan selects exactly that sequence one line
    // below: the id half of the offer is satisfied by construction here, and only starts biting once
    // the sailor picks something else — which is [resumeOfferRemainingMs]'s job on every later tick.
    val remaining = resumeOfferRemainingMs(snapshot, saved, saved.id, nowElapsedMs, nowWallMs)
    return LaunchPlan(
        sequence = saved,
        customMinutes = BuiltInSequences.customMinutes(saved.id),
        resumable = if (remaining != null) snapshot to saved else null,
        notice = null,
    )
}

/** The launch plan when no race survived: open on the remembered pick, or on nothing. */
private fun pickedPlan(pickedSequenceId: String?): LaunchPlan {
    // A first-ever launch has no choice to honour, and the default is the right thing to show.
    val id = pickedSequenceId ?: return LaunchPlan(null, null, null, null)
    val picked = BuiltInSequences.resolve(id)
        ?: return LaunchPlan(null, null, null, LaunchNotice.PICKED_SEQUENCE_UNREADABLE)
    return LaunchPlan(
        sequence = picked,
        customMinutes = BuiltInSequences.customMinutes(picked.id),
        resumable = null,
        notice = null,
    )
}

// ---------------------------------------------------------------------------
// 3. Does Start resume, or run from the top?
// ---------------------------------------------------------------------------

/** What `ACTION_START` should do about the race still in persistence. */
sealed interface StartPlan {

    /** Resume [snapshot]: the sailor asked for the race they already had. */
    data class Resume(val snapshot: TimerEngine.Snapshot) : StartPlan

    /**
     * Run the selected sequence from the beginning.
     *
     * Reached four ways, and they are not equivalent to the sailor: Start over was tapped, nothing
     * was saved, something was saved for a *different* sequence (which this start is about to write
     * over — see [discardedOnStartRemainingMs]), or the engine is already running a race.
     */
    data object FromTheTop : StartPlan
}

/**
 * Decide whether a start resumes the saved race.
 *
 * [freshStart] is an **input to the decision**, not a precondition of it. It used to be neither: Start
 * over cleared the persisted keys and relied on that clear happening before this guard read them, so
 * the whole of "the race the sailor just declined must not come back" rested on the order of two
 * statements a hundred lines apart. Ordering is exactly what a unit test cannot hold still. The
 * clear still happens — the declined race must not be offered on the next launch either — but nothing
 * here depends on it having happened yet.
 *
 * @param saved               the persisted race, from [snapshotFrom], or null if none survived.
 * @param requestedSequenceId the id the Start intent carries — the sequence the sailor is looking at.
 * @param engineState         the engine's state as the intent arrives.
 */
fun startPlan(
    freshStart: Boolean,
    saved: TimerEngine.Snapshot?,
    requestedSequenceId: String,
    engineState: TimerState,
): StartPlan {
    if (freshStart) return StartPlan.FromTheTop
    if (saved == null) return StartPlan.FromTheTop
    // A saved race has no claim on a screen showing something else. The sailor is looking at the
    // sequence they are about to run, and running it is what a tap must do; the warning that this
    // discards the saved race belongs on screen before the tap (#89), not in a surprise restore.
    if (saved.sequenceId != requestedSequenceId) return StartPlan.FromTheTop
    // Only an idle engine restores. A second ACTION_START arriving at a running one — a double tap,
    // or a restart straight after the gun — must not re-enter the restore path and re-anchor a race
    // already under way. What it does instead is fall through to load() + start(), which takes that
    // race back to the top: #64's parting note, left as found because the pre-start buttons go on
    // the first tap, so the shipped UI cannot reach it.
    if (engineState != TimerState.IDLE) return StartPlan.FromTheTop
    return StartPlan.Resume(saved)
}
