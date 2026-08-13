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
 * Which surface of `docs/message-surface.md` a notice belongs on.
 *
 * Only two of the three appear here. Tier 1 is for news that clears itself, and nothing in this
 * file is news — every condition below is a standing fact about the watch that stays true until
 * the sailor changes something.
 */
enum class NoticeTier {
    /** Tier 2 — takes the Start button's place. The race cannot be armed while this is on screen. */
    BLOCKING,

    /** Tier 3 — a persistent line under the sequence name. Start stays exactly where it was. */
    WARNING,
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
// two copy rules: say the consequence rather than the cause, and stay inside the ~60 characters
// two lines of `caption1` hold on a round screen.

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
 * The longest a notice may be, in characters.
 *
 * `docs/message-surface.md` works this out from the geometry rather than from taste: one line at
 * 11 sp inside the width cap is roughly 34 characters, and two lines is all the gap above the Start
 * button holds. Asserted rather than described, because a copy change is exactly the kind of edit
 * nobody re-measures.
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
