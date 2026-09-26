package com.racetimer.shared

// ---------------------------------------------------------------------------
// Whether the watch can be trusted to run a race, and what to say when it cannot
//
// `docs/message-surface.md` defines three tiers and a catalogue of conditions, and #13 is the story
// that builds them. The *judgement* lives here rather than in `wear/` for the reason every other
// rule in this module moved: a decision made inside a Compose screen or an Activity callback can
// only be verified by holding a watch, and this repo has already shipped a message surface nobody
// could see (#102) and a contrast figure nobody could check (#123).
//
// What stays in `wear/`: reading the platform. Whether a vibrator exists, whether battery saver is
// on, whether `startForegroundService` threw. Those are observations. Which one the sailor is told
// about, in what words, and whether Start survives, are decisions — and they are here.
// ---------------------------------------------------------------------------

/**
 * What the app has observed about this watch, gathered in `wear/` and judged by [startNotice].
 *
 * Every field is an *observation*, never a prediction. That distinction is #96's finding and it
 * holds here too: the platform refuses things silently, so the only trustworthy inputs are ones
 * something already tried or already read back.
 *
 * Defaults are all "fine", so a caller that cannot determine a condition reports nothing rather
 * than warning about a state it never established.
 */
data class DeviceReadiness(
    /**
     * `startForegroundService` refused the last attempt.
     *
     * The one condition that cannot be read ahead of time — there is no API that answers "would a
     * foreground service be allowed right now?", so this is set by catching the refusal and is
     * therefore always a *latched* fact about an attempt that already failed.
     */
    val foregroundServiceRefused: Boolean = false,
    /** The audio stack refused to give the app a track, so cues will be haptic-only. */
    val audioUnavailable: Boolean = false,
    /** `POST_NOTIFICATIONS` is denied, so the ongoing-activity notification cannot be posted. */
    val notificationsBlocked: Boolean = false,
    /** This watch has no vibrator, so every cue is audio-only. */
    val vibratorAbsent: Boolean = false,
    /** Battery saver is on, which can cut the cue audio short. */
    val batterySaverActive: Boolean = false,
)

/**
 * The activity-side memory of a refused `startForegroundService` dispatch, and — the part that is
 * a decision rather than an observation — when that memory expires (#165).
 *
 * [DeviceReadiness.foregroundServiceRefused] is latched by construction: there is no API that
 * answers "would a foreground service be allowed right now?", so the flag can only record an
 * attempt that already failed. A latched fact needs an expiry rule, and the two sides of the
 * process boundary get theirs differently. The service-side twin
 * (`TimerService.foregroundStartRefused`) expires by lifecycle for free — its refusal path stops
 * the service, and the rebind on the next `onStart` constructs a fresh one. The activity-side flag
 * has no such lifecycle, and before #165 it expired only on a successful dispatch — but the
 * blocking panel it puts up removes the Start button, the one control that dispatches. The panel
 * could outlive its own fix, and the documented remedy (a Settings round trip, returning through
 * `onStart`) could never clear it.
 *
 * So the expiry rule, held here where the JVM suite asserts it: **a return to the foreground
 * forgets the refusal.** The return leg of the Settings trip is exactly that, and a panel that
 * survived the remedy working would be indistinguishable from one it had not touched. If the
 * condition still holds, the next dispatch re-latches and the panel comes back — one optimistic
 * Start button against a dead end is the whole trade.
 */
class ForegroundRefusalLatch {
    /** True while a refused dispatch is the most recent thing known. Feeds [DeviceReadiness]. */
    var refused: Boolean = false
        private set

    /** `startForegroundService` returned: whatever was wrong is demonstrably not wrong now. */
    fun dispatchSucceeded() { refused = false }

    /** `startForegroundService` threw: latch, and let [startNotice] put the Tier 2 panel up. */
    fun dispatchRefused() { refused = true }

    /**
     * The activity came back to the foreground — for a blocked screen, the return leg of the
     * Settings trip [StartRemedy.APP_SETTINGS] started. Forget the refusal so Start is there to
     * try again.
     */
    fun returnedToForeground() { refused = false }
}

/**
 * Which surface of `docs/message-surface.md` a notice belongs on.
 *
 * Only two of the three appear here. Tier 1 is for news that clears itself, and nothing in this
 * file is news — every condition below is a standing fact about the watch that stays true until
 * the sailor changes something.
 */
enum class NoticeTier(val surface: MessageSurface) {
    /** Tier 2 — takes the Start button's place. The race cannot be armed while this is on screen. */
    BLOCKING(MessageSurface.BLOCKING_PANEL),

    /** Tier 3 — a persistent line under the sequence name. Start stays exactly where it was. */
    WARNING(MessageSurface.STATUS_LINE),
}

/**
 * The action offered alongside a notice, or the absence of one.
 *
 * Labelled by the **remedy** rather than the problem, per `docs/message-surface.md` — "Settings",
 * never "OK". A notice with [NONE] has no button at all, which is most Tier 3 lines: there is
 * nothing for the app to do about a missing vibrator.
 */
enum class StartRemedy(val label: String?) {
    /** No action. The sailor is being told, not asked. */
    NONE(null),

    /** Open this app's system settings page, where the foreground-service restriction is lifted. */
    APP_SETTINGS("Settings"),

    /** Open the notification settings for this app, where the denied permission can be granted. */
    NOTIFICATION_SETTINGS("Settings"),

    /**
     * Arm the sequence anyway, accepting silent cues.
     *
     * The one escape hatch `docs/message-surface.md` allows: a countdown a sailor can see and feel
     * is still a usable countdown, so audio is the only blocking condition with a way past it.
     */
    START_SILENT("Start silent"),
}

/**
 * A single thing to tell the sailor about the state of the watch, or null when there is nothing.
 *
 * Singular deliberately — rule 6 of `docs/message-surface.md`. Two stacked notices on a 45 mm
 * screen is worse than losing one, so [startNotice] picks by precedence rather than returning a
 * list and leaving the screen to choose.
 */
data class StartNotice(
    val tier: NoticeTier,
    val text: String,
    val remedy: StartRemedy,
) {
    /** True when Start must not be on screen at all — see `docs/message-surface.md` Tier 2. */
    val blocksStart: Boolean get() = tier == NoticeTier.BLOCKING
}

// --- The copy ---------------------------------------------------------------
// Held as constants so the tests assert the strings a sailor actually reads. Every one obeys the
// two copy rules: say the consequence rather than the cause, and stay inside [NOTICE_MAX_CHARS].
//
// *This comment said "the ~60 characters two lines of `caption1` hold" until #231, and named the
// wrong type on the wrong surface — `caption1` is the Tier 2 panel's, and the 60 is worked out from
// the Tier 1 banner's 11 sp. Two lines is what it buys there and nowhere else.* Which surface each
// string lands on, and what it holds, is [NoticeTier.surface] and [MessageSurface].

/** Tier 2. The countdown would not survive the screen turning off, which is the whole product. */
const val NOTICE_FOREGROUND_SERVICE_REFUSED = "Can't run in background — open Settings"

/** Tier 2, soft. The gun is the cue that matters, so name it rather than saying "audio failed". */
const val NOTICE_AUDIO_UNAVAILABLE = "Sound is off — the gun will be silent"

/** Tier 3. The consequence of a missing ongoing notification is the race being killed under you. */
const val NOTICE_NOTIFICATIONS_BLOCKED = "Notifications off — timer may be killed"

/** Tier 3. There is no remedy, so the copy is an instruction the sailor can actually follow. */
const val NOTICE_VIBRATOR_ABSENT = "No haptics — watch the screen"

/** Tier 3. Hedged on purpose: saver mode *can* cut the tone, and this has not been measured. */
const val NOTICE_BATTERY_SAVER = "Battery saver — sound may be cut"

/**
 * Tier 3, and the one notice on this list that is **not** about the pre-start screen (#96).
 *
 * Names the cause rather than only the consequence, which looks like a departure from rule 5 of
 * `docs/message-surface.md` and is not: here the cause *is* the remedy. "Cues may be silent" leaves
 * a sailor with nothing to do; "Do Not Disturb" is the switch they can reach.
 *
 * The second clause exists because of #144, and it is the difference between a true message and a
 * useful one. Before #144 the platform dropped every multi-pulse cue vibration under Do Not Disturb
 * as well, so both channels were dead. #144 declared the vibrations' usage and *measured* the
 * result: **30 of 30 cues delivered at `zen_mode=2`, the 3000 ms gun included**. So the wrist is now
 * the channel that survives exactly this condition, and a warning that stopped at "cues silent"
 * would send a sailor to watch the screen through a start they could have felt.
 */
const val NOTICE_CUE_VOLUME_REFUSED = "Do Not Disturb — cues silent, wrist still buzzing"

/**
 * The longest a notice may be, in characters — a **shared ceiling over every surface**.
 *
 * `docs/message-surface.md` works this out from the geometry rather than from taste, and the
 * geometry it uses is the **Tier 1 banner's**: one line at 11 sp inside that surface's width cap is
 * roughly 34 characters, and two lines is all the gap above the Start button holds.
 *
 * That is a ceiling, not a fit, for the two surfaces it was not derived for — which is #231. A
 * string can sit well inside 60 and still take three lines on the Tier 3 status line, whose cap is
 * a third narrower, and [NOTICE_CUE_VOLUME_REFUSED] does exactly that. The per-surface budget is
 * [MessageSurface], and `StartPreconditionsTest` asserts every notice against **both**: this
 * ceiling, which is coarse and holds for all of them, and its own surface's line budget, which is
 * the one that can actually catch a copy edit spilling off a plate.
 *
 * Kept rather than replaced. It is a cheap house rule that has never been wrong, and a sentence
 * this short is worth more to whoever writes the next notice than an arithmetic model they would
 * have to run.
 */
const val NOTICE_MAX_CHARS = 60

/**
 * The one notice worth showing for [readiness], or null when the watch is ready.
 *
 * ### Precedence, and why it is this order
 *
 * Worst consequence first, and "worst" means *what the sailor loses*, not how broken the platform
 * is. A refused foreground service loses the whole race the moment the screen sleeps, so it
 * outranks everything. Silent cues lose one channel of two. The three Tier 3 lines are caveats on a
 * race that will otherwise run correctly.
 *
 * ### Why blocking is not the same as bad
 *
 * Only [DeviceReadiness.foregroundServiceRefused] blocks unconditionally. Audio blocks *softly* --
 * it offers [StartRemedy.START_SILENT], and once [silentStartAccepted] the same condition comes
 * back as a Tier 3 line so the sailor keeps being reminded for the rest of the session without
 * being stopped again.
 *
 * A denied notification permission deliberately does **not** block, which is where this function
 * departs from the catalogue in `docs/message-surface.md` as it was first written. #13's own first
 * acceptance criterion requires the sequence to start and run in that case, and it is right: on
 * Android 13+ a foreground service still starts with `POST_NOTIFICATIONS` denied — only its
 * notification is suppressed — so blocking would refuse a race that would have worked. The
 * catalogue row was changed to match rather than the criterion (owner decision, 2026-08-11).
 *
 * @param silentStartAccepted true once the sailor has tapped "Start silent" this session, which
 *        demotes the audio notice from Tier 2 to Tier 3 for the duration.
 */
fun startNotice(
    readiness: DeviceReadiness,
    silentStartAccepted: Boolean = false,
): StartNotice? = when {
    readiness.foregroundServiceRefused -> StartNotice(
        NoticeTier.BLOCKING, NOTICE_FOREGROUND_SERVICE_REFUSED, StartRemedy.APP_SETTINGS,
    )

    readiness.audioUnavailable && !silentStartAccepted -> StartNotice(
        NoticeTier.BLOCKING, NOTICE_AUDIO_UNAVAILABLE, StartRemedy.START_SILENT,
    )

    readiness.audioUnavailable -> StartNotice(
        NoticeTier.WARNING, NOTICE_AUDIO_UNAVAILABLE, StartRemedy.NONE,
    )

    readiness.notificationsBlocked -> StartNotice(
        NoticeTier.WARNING, NOTICE_NOTIFICATIONS_BLOCKED, StartRemedy.NOTIFICATION_SETTINGS,
    )

    readiness.vibratorAbsent -> StartNotice(
        NoticeTier.WARNING, NOTICE_VIBRATOR_ABSENT, StartRemedy.NONE,
    )

    readiness.batterySaverActive -> StartNotice(
        NoticeTier.WARNING, NOTICE_BATTERY_SAVER, StartRemedy.NONE,
    )

    else -> null
}

/**
 * The one notice worth showing while a race is **armed**, or null when there is nothing (#96).
 *
 * ### Why this is not a sixth [DeviceReadiness] field
 *
 * Every condition [startNotice] judges is readable, or already latched, *before* a tap on Start.
 * This one is not: the platform refuses a volume raise **silently** — *measured on an SM-R925U at
 * `zen_mode=2`*, `setStreamVolume` threw nothing, changed nothing and left the stream muted — so
 * there is no API that answers "will the cues be audible?" ahead of time. The only honest answer
 * comes from having tried and read the value back, which `TimerService.ensureCueStreamAudible` does
 * at the moment the race is armed.
 *
 * Folding it into [DeviceReadiness] would therefore have put a *stale* observation on the pre-start
 * screen: after a refused race the flag is still true, and the next race has not been attempted yet.
 * A verdict about the last race, displayed as though it were about the next one, is precisely the
 * prediction this story was rewritten to avoid. Kept separate, the two functions each say only what
 * their inputs support.
 *
 * ### Why the state is an argument
 *
 * So that "which screens can this line appear on" is a decision in `shared/`, where it is asserted,
 * rather than a branch in `MainActivity` that only a wrist can check. It is also what lets
 * `MessageContrastTest` **derive** the backgrounds this line can be drawn on — by driving this
 * function — instead of restating them.
 *
 * [TimerState.RUNNING] and no other state. Before the gun there are cues left to be silent; after
 * it there are none, so a warning that outlived the gun would be a line about nothing sitting on
 * the screen a race committee reads its finish times off.
 *
 * @param cueVolumeRefused the **measured** outcome of this race's volume raise — `TimerService`
 *        tried, read the volume back, and was refused. Never a prediction.
 */
fun armedNotice(state: TimerState, cueVolumeRefused: Boolean): StartNotice? =
    if (state == TimerState.RUNNING && cueVolumeRefused) {
        // No remedy button: the fix is a system control this app cannot open on the sailor's behalf,
        // and rule 3 of `docs/message-surface.md` keeps anything tappable off a running race anyway.
        StartNotice(NoticeTier.WARNING, NOTICE_CUE_VOLUME_REFUSED, StartRemedy.NONE)
    } else {
        null
    }

// --- A cue lost mid-race (#161) ---------------------------------------------

/**
 * Which way the audio path lost a cue the sailor was timing a start off (#161).
 *
 * Three paths in `ToneManager.writeCue` fail *after* the race is under way, and until #161 all
 * three produced a log line and nothing else. They collapse to two consequences, which is why this
 * enum has two cases rather than three:
 *
 * - [DROPPED] — the write returned `<= 0`, or threw [IllegalStateException] and took the track with
 *   it. Either way the cue does not sound at all.
 * - [TRUNCATED] — the tail write of a cue longer than the buffer threw, so the cue starts and stops
 *   early.
 *
 * ### Why the two are distinguished, when the sailor's remedy is the same
 *
 * Both leave the wrist as the surviving channel, so "watch/feel the wrist" is the action either
 * way, and one message would have satisfied rule 5 of `docs/message-surface.md`. They are separated
 * because the *failure modes differ in kind*, not in degree. A dropped cue is **absent** — the
 * sailor hears nothing and knows to trust the wrist. A truncated cue is **present and wrong**: a
 * three-second gun cut to a fraction of itself sounds like a short blast, which in every sequence
 * this app ships is a different mark. Silence is noticed; a plausible wrong signal is acted on.
 *
 * ### No state parameter, unlike [armedNotice]
 *
 * `armedNotice` takes a [TimerState] because it describes a standing condition that is true in
 * states where it is not worth saying. A lost cue is news, and it can only be news in a state where
 * a cue fired — which includes `COUNTING_UP`, since a race-manager sequence goes on signalling past
 * the gun. So there is no state in which this notice could arrive and be wrong to show, and a gate
 * asserting one would be a branch that can never be taken.
 */
enum class CueLoss {
    /** The cue did not sound at all. */
    DROPPED,

    /** The cue sounded, and stopped early. */
    TRUNCATED,
}

/**
 * Tier 1. Consequence first, then the channel that still works — the shape [NOTICE_CUE_VOLUME_REFUSED]
 * set in #96, and for the same reason: #144 measured 30 of 30 cue vibrations delivered under total
 * silence, so the wrist is genuinely the channel to fall back on rather than a hopeful clause.
 */
const val NOTICE_CUE_DROPPED = "Cue silent — wrist still buzzing"

/**
 * Tier 1. "Cut short" rather than "truncated": the sailor needs to know the blast they just heard
 * was not the whole blast, because its length is what tells the marks apart.
 */
const val NOTICE_CUE_TRUNCATED = "Cue cut short — wrist still buzzing"

/**
 * The banner copy for a cue the audio path lost, or null when nothing was lost.
 *
 * The whole judgement for #161, in `shared/` where the JVM suite can assert it, for the reason this
 * file's header gives: the alternative is a `when` inside an Activity callback that only a wrist can
 * check, and this repo has already shipped a message surface nobody could see (#102).
 *
 * Null-in / null-out deliberately, so the caller can hand it the read-and-clear result directly —
 * `showTransientMessage(cueLossNotice(service.consumeCueLoss()) ?: return)` — without a second
 * branch on the activity side deciding what "nothing was lost" looks like.
 */
fun cueLossNotice(loss: CueLoss?): String? = when (loss) {
    CueLoss.DROPPED -> NOTICE_CUE_DROPPED
    CueLoss.TRUNCATED -> NOTICE_CUE_TRUNCATED
    null -> null
}
