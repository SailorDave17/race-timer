/**
 * Data models for race start sequences.
 *
 * A [RaceSequence] is a named list of [SequenceCue] entries. Each cue fires at a given
 * number of milliseconds before the start (positive = before the gun, 0 = the gun itself).
 * The cue carries a [SignalPattern] that drives both audio and haptics.
 */
package com.racetimer.shared

// ---------------------------------------------------------------------------
// Signal / haptic patterns
// ---------------------------------------------------------------------------

/**
 * What a cue is *for*, which decides how it sounds and feels rather than how many blasts it has.
 *
 * [SignalPattern] counts blasts and states durations; it has no way to say "this one is a different
 * kind of thing". The voice is that axis. Both channels read it — see [CueTiming] —
 * so a voice that is easy to pick out by ear is equally easy to pick out by feel, which is the whole
 * point on a watch that may be muted or have no speaker at all.
 */
enum class CueVoice {
    /** A race signal: what the committee is sounding. The default for every cue. */
    BLAST,

    /**
     * A wrist-only tick counting down *into* a signal, so a sailor whose watch has drifted can snap
     * it onto the committee's sequence. Deliberately unlike [BLAST]: it is not a signal, and a
     * sailor must never mistake one for the other.
     */
    SYNC,

    /**
     * An instruction to the *wearer* to act, right now — not something the fleet is being told.
     *
     * One user today: the end of a lead-in's prep stage, where the race manager must press the
     * signal box or the whole race runs misaligned (see `LeadIn.kt`). That instant is the most
     * important in the lead-in and it had nothing on it: [SYNC] ticks count *into* a mark and
     * deliberately are not one, so five ticks left the mark itself silent.
     *
     * Distinct from both on both channels, and distinguishable by **texture** rather than by count.
     * A count can be misheard as a blast pattern — `3 short` is a real cue at 0:30 of this very
     * sequence — so this is a fast stutter at full strength that reads as one event, not as a number
     * of blasts. See [CueTiming.PROMPT_ON] and [CueWaveform].
     */
    PROMPT,
}

/**
 * Describes the blast structure of a single cue: how many long and short blasts it is made of, or —
 * where the cue is one held signal rather than a count — how long that signal runs.
 *
 * Drives both channels of a cue on the watch. The vibration and the tone read the same pattern and
 * land on the same blast boundaries, so a cue sounds the shape it feels.
 *
 * @param longBlasts  Number of long horn blasts.
 * @param shortBlasts Number of short horn blasts.
 * @param sustainedMs One continuous blast of this many milliseconds. Zero on every cue built from
 *                    discrete blasts; set only where the cue is a single held signal, such as the
 *                    Scholastic gun. When non-zero it *replaces* the blast counts rather than
 *                    following them — a sustained cue is one unbroken buzz and tone, which the
 *                    counts cannot express at any value.
 * @param voice       Which kind of cue this is. Defaults to [CueVoice.BLAST], which is what keeps
 *                    every sequence written before the voice existed sounding exactly as it did.
 * @param label       Human-readable description of the pattern (e.g. "3 long"). Documentation
 *                    only: nothing in the app renders it today.
 */
data class SignalPattern(
    val longBlasts: Int = 0,
    val shortBlasts: Int = 0,
    val sustainedMs: Long = 0L,
    val voice: CueVoice = CueVoice.BLAST,
    val label: String = "",
)

// ---------------------------------------------------------------------------
// A single timed cue inside a sequence
// ---------------------------------------------------------------------------

/**
 * One timed cue in a race sequence.
 *
 * @param offsetMs      Milliseconds before the start gun (positive).  The gun itself is 0.
 * @param signal        The blast pattern to play/buzz at this cue.
 * @param isGun         True only for the 0:00 "Start" cue.
 * @param isLeadIn      True for a cue belonging to the run-up *ahead* of the sequence's own first
 *                      signal, rather than to the sequence itself — see `LeadIn.kt`. These are the
 *                      only cues that may sit above [RaceSequence.sequenceMs], and marking them is
 *                      what lets that value stay the sequence's own duration instead of growing by
 *                      whatever the run-up happens to be. Nothing else reads it: a lead-in tick is
 *                      told apart *by the sailor* through [CueVoice.SYNC], the way every other sync
 *                      tick is.
 */
data class SequenceCue(
    val offsetMs: Long,
    val signal: SignalPattern,
    val isGun: Boolean = false,
    val isLeadIn: Boolean = false,
)

// ---------------------------------------------------------------------------
// Named race sequence
// ---------------------------------------------------------------------------

/**
 * A complete start sequence consisting of an ordered list of [SequenceCue]s.
 *
 * @param id       Stable identifier used to persist the user's selection.
 * @param name     Display name shown in the UI.
 * @param cues     All cues sorted in *descending* order of [SequenceCue.offsetMs]
 *                 (earliest/largest offset first, gun last).
 * @param countUpAfterFinish Whether the engine should keep running as an elapsed-time stopwatch
 *                 once the gun fires, instead of settling into [TimerState.FINISHED] and resetting.
 *                 For a race committee (as opposed to a sailor), the gun is not the end of the job —
 *                 it is the moment race duration starts mattering. See [TimerState.COUNTING_UP].
 * @param leadInMs Silent run-up ahead of the sequence's own first signal, or 0 for a sequence that
 *                 begins at its first signal the way every built-in does. Set only by
 *                 [withLeadIn] — see `LeadIn.kt` for what it is for.
 */
data class RaceSequence(
    val id: String,
    val name: String,
    val cues: List<SequenceCue>,
    val countUpAfterFinish: Boolean = false,
    val leadInMs: Long = 0L,
) {
    /**
     * The sequence's **own** countdown: where its first signal sits, unaffected by any lead-in.
     *
     * Reads only the cues that belong to the sequence, so a run-up bolted on top cannot inflate it —
     * that inflation is exactly the accident to avoid, since a lead-in's ticks fire in the last five
     * seconds of the lead and so sit at `sequenceMs + 5 s` whatever the lead actually is. Deriving
     * the countdown from the largest offset would therefore report a 70-second lead as a 5-second
     * one, silently, and [TimerEngine.start] anchors the gun on exactly that number.
     */
    val sequenceMs: Long = cues.filterNot { it.isLeadIn }.maxOfOrNull { it.offsetMs } ?: 0L

    /**
     * What the clock starts at and what [TimerEngine.start] anchors the gun on: the lead-in, then
     * the sequence.
     *
     * Computed once at construction rather than on each read: the idle screen reads this every UI
     * refresh, and a getter re-scanned all ~30 cues each time to return a value that cannot change.
     */
    val totalMs: Long = sequenceMs + leadInMs
}

// ---------------------------------------------------------------------------
// Built-in sequences
// ---------------------------------------------------------------------------

object BuiltInSequences {

    // -----------------------------------------------------------------------
    // Shared building blocks
    //
    // Declared before the sequences that use them: these are property initialisers on an object, so
    // they run in declaration order, and a sequence referring upward to one not yet initialised
    // would read null.
    // -----------------------------------------------------------------------

    /**
     * The last five seconds, 0:05 to 0:01, doubled — the one cadence every sequence shares.
     *
     * These are the only seconds a sailor lives through with their head up and their hands full, so
     * they get a signature the wrist can carry on its own: two pulses instead of one, every second
     * the same. The double is not a pattern to decode — all five are identical — it marks a *phase*,
     * and the single ticks immediately before it are what make the change legible.
     *
     * Every sequence composes this, `club` and [custom] included, so a sailor never has to remember
     * which sequence is loaded to know what the last five seconds mean.
     */
    private val finalFiveRun: List<SequenceCue> = (5 downTo 1).map { sec ->
        SequenceCue(
            offsetMs = sec * 1_000L,
            signal = SignalPattern(shortBlasts = 2, label = "2 short — final five"),
        )
    }

    /**
     * The last minute, from 0:50 to 0:01 — the cadence a sailor counts off the wrist on the line.
     *
     * Shared by every sequence that ends in a real start, so the final minute cannot come to mean
     * two different things depending on which sequence is loaded. 0:30 and 0:20 keep the descending
     * 3-short / 2-short call the race committee actually sounds.
     *
     * The last ten seconds are two phases, not one: 0:10 to 0:06 tick flat and single, then
     * [finalFiveRun] doubles for 0:05 to 0:01. Within each phase every second is identical, so the
     * sailor still counts pulses rather than decoding a pattern — the single change of shape between
     * them is the whole message, and it is why 0:10 to 0:06 must stay single.
     */
    private val finalMinuteTail: List<SequenceCue> = listOf(
        SequenceCue(
            offsetMs = 50_000L,
            signal = SignalPattern(shortBlasts = 1, label = "1 short"),
        ),
        SequenceCue(
            offsetMs = 40_000L,
            signal = SignalPattern(shortBlasts = 1, label = "1 short"),
        ),
        SequenceCue(
            offsetMs = 30_000L,
            signal = SignalPattern(shortBlasts = 3, label = "3 short"),
        ),
        SequenceCue(
            offsetMs = 20_000L,
            signal = SignalPattern(shortBlasts = 2, label = "2 short"),
        ),
    ) + (10 downTo 6).map { sec ->
        SequenceCue(
            offsetMs = sec * 1_000L,
            signal = SignalPattern(shortBlasts = 1, label = "1 short"),
        )
    } + finalFiveRun

    /** One sustained blast, not a count: the gun is the only cue with nothing to count. */
    private val sustainedGun = SequenceCue(
        offsetMs = 0L,
        signal = SignalPattern(sustainedMs = 3_000L, label = "Start — sustained"),
        isGun = true,
    )

    /**
     * The Scholastic/ICSA sequence's head, 3:00 through 1:00 — the ICSA blast pattern the race
     * committee actually sounds, and identical for every variant built on it.
     *
     * Extracted so [scholastic] and [scholasticRaceManager] cannot drift apart on the part they are
     * supposed to share; only their tails differ (the race-manager variant uses its own cadence
     * below the minute, [raceManagerTail], rather than [finalMinuteTail]).
     */
    private val scholasticHead: List<SequenceCue> = listOf(
        SequenceCue(
            offsetMs = 3 * 60_000L,
            signal = SignalPattern(longBlasts = 3, label = "3 long"),
        ),
        SequenceCue(
            offsetMs = 2 * 60_000L,
            signal = SignalPattern(longBlasts = 2, label = "2 long"),
        ),
        SequenceCue(
            offsetMs = 90_000L, // 1:30
            signal = SignalPattern(longBlasts = 1, shortBlasts = 3, label = "1 long 3 short"),
        ),
        SequenceCue(
            offsetMs = 1 * 60_000L,
            signal = SignalPattern(longBlasts = 1, label = "1 long"),
        ),
    )

    /**
     * The race-manager tail below the minute: 0:30/0:20/0:10 descending, then single ticks at
     * 0:05 through 0:01.
     *
     * Deliberately its own cadence, not [finalMinuteTail] — no 0:50/0:40 warning ticks, nothing
     * between 0:09 and 0:06, and the final five are single ticks rather than [finalFiveRun]'s
     * doubled pair. A race manager is tracking the committee's own countdown by ear, not
     * cross-checking their own watch against it the way a sailor's final five is for, so the
     * doubled-pulse "phase change" signal that cadence exists for doesn't apply here.
     */
    private val raceManagerTail: List<SequenceCue> = listOf(
        SequenceCue(
            offsetMs = 30_000L,
            signal = SignalPattern(shortBlasts = 3, label = "3 short"),
        ),
        SequenceCue(
            offsetMs = 20_000L,
            signal = SignalPattern(shortBlasts = 2, label = "2 short"),
        ),
        SequenceCue(
            offsetMs = 10_000L,
            signal = SignalPattern(shortBlasts = 1, label = "1 short"),
        ),
    ) + (5 downTo 1).map { sec ->
        SequenceCue(
            offsetMs = sec * 1_000L,
            signal = SignalPattern(shortBlasts = 1, label = "1 short"),
        )
    }

    /**
     * Five one-per-second ticks running *into* the signal at [signalOffsetMs] — 5, 4, 3, 2, 1.
     *
     * These are not signals and must not be heard as ones, so they carry [CueVoice.SYNC]. Their job
     * is to give a sailor whose watch has drifted a beat of warning *before* the mark, which is the
     * only moment the drift can still be corrected — the mark itself is too late.
     *
     * `internal` rather than private because a lead-in's run-up is this same five-tick cadence,
     * built from this same function (see `LeadIn.kt`). One definition, so the run into a lead-in's
     * end and the run into a US Sailing signal cannot come to mean two different things.
     */
    internal fun syncRunInto(signalOffsetMs: Long, signalName: String): List<SequenceCue> =
        (5 downTo 1).map { sec ->
            SequenceCue(
                offsetMs = signalOffsetMs + sec * 1_000L,
                signal = SignalPattern(
                    shortBlasts = 1,
                    voice = CueVoice.SYNC,
                    label = "Sync — $signalName in $sec",
                ),
            )
        }

    // --- US Sailing 5-4-1-go (RRS 26) ---
    //
    // Voiced for the wrist rather than for the horn: RRS 26 signals are long horn blasts, but a
    // sailor cannot count a 500 ms buzz against another 500 ms buzz without looking. Every cue here
    // is short blasts, and the two that matter procedurally — prep up at 4:00, prep down at 1:00 —
    // are doubled so they stand out from the plain minute ticks around them.
    val usSailing: RaceSequence = RaceSequence(
        id = "us_sailing_5_4_1",
        name = "US Sailing 5-4-1-Go",
        cues = listOf(
            SequenceCue(
                offsetMs = 5 * 60_000L,
                signal = SignalPattern(shortBlasts = 1, label = "Warning — class flag up"),
            ),
        ) + syncRunInto(4 * 60_000L, "prep") + listOf(
            SequenceCue(
                offsetMs = 4 * 60_000L,
                signal = SignalPattern(shortBlasts = 2, label = "Preparatory — P/I/Z/U flag up"),
            ),
            SequenceCue(
                offsetMs = 3 * 60_000L,
                signal = SignalPattern(shortBlasts = 1, label = "3 minutes"),
            ),
            SequenceCue(
                offsetMs = 2 * 60_000L,
                signal = SignalPattern(shortBlasts = 1, label = "2 minutes"),
            ),
        ) + syncRunInto(1 * 60_000L, "one-minute") + listOf(
            SequenceCue(
                offsetMs = 1 * 60_000L,
                signal = SignalPattern(shortBlasts = 2, label = "One-minute — prep flag down"),
            ),
        ) + finalMinuteTail + sustainedGun,
    )

    // --- Scholastic / ICSA 3-minute sequence ---
    val scholastic: RaceSequence = RaceSequence(
        id = "scholastic",
        name = "Scholastic (ICSA)",
        cues = scholasticHead + finalMinuteTail + sustainedGun,
    )

    // --- Scholastic / ICSA, race-manager variant ---
    //
    // Shares [scholasticHead] with [scholastic] — the Race Committee sails the same 3:00-to-1:00
    // opening either way — but takes its own cadence below the minute ([raceManagerTail], not
    // [finalMinuteTail]) and sets [countUpAfterFinish], so the engine keeps running as a race-time
    // stopwatch after the gun instead of resetting to idle. See [TimerEngine]'s COUNTING_UP state
    // and TimerService.onGun.
    val scholasticRaceManager: RaceSequence = RaceSequence(
        id = "scholastic_race_manager",
        name = "Scholastic - Race Manager",
        cues = scholasticHead + raceManagerTail + sustainedGun,
        countUpAfterFinish = true,
    )

    // --- Club racing 3-2-1-go ---
    //
    // Carries [finalFiveRun] and nothing else below the minute: club has never had a final-minute
    // cadence, and giving it one is a re-voicing of its own. The last five seconds are the exception
    // because they must mean the same thing in every sequence.
    val club: RaceSequence = RaceSequence(
        id = "club_3_2_1",
        name = "Club 3-2-1-Go",
        cues = listOf(
            SequenceCue(
                offsetMs = 3 * 60_000L,
                signal = SignalPattern(longBlasts = 1, label = "Warning"),
            ),
            SequenceCue(
                offsetMs = 2 * 60_000L,
                signal = SignalPattern(longBlasts = 1, label = "Preparatory"),
            ),
            SequenceCue(
                offsetMs = 1 * 60_000L,
                signal = SignalPattern(longBlasts = 1, label = "One-minute"),
            ),
        ) + finalFiveRun + listOf(
            SequenceCue(
                offsetMs = 0L,
                signal = SignalPattern(longBlasts = 1, shortBlasts = 3, label = "Start"),
                isGun = true,
            ),
        ),
    )

    /** All built-in sequences in display order. */
    val all: List<RaceSequence> = listOf(usSailing, scholastic, scholasticRaceManager, club)

    // -----------------------------------------------------------------------
    // Custom sequence
    // -----------------------------------------------------------------------

    /** The shortest custom race: one whole minute, which is [finalMinuteTail] and nothing above it. */
    const val CUSTOM_MIN_MINUTES = 1

    private const val CUSTOM_ID_PREFIX = "custom_"
    private const val CUSTOM_ID_SUFFIX = "m"

    /**
     * The id a [custom] sequence of [totalMinutes] carries.
     *
     * The duration is *inside* the id on purpose: it is the only thing persistence stores about the
     * chosen sequence (see [TimerEngine.Snapshot.sequenceId]), so encoding the minutes there is what
     * lets [resolve] rebuild the exact same race after process death. Anything not derivable from
     * this string cannot survive a kill.
     */
    fun customId(totalMinutes: Int): String = "$CUSTOM_ID_PREFIX$totalMinutes$CUSTOM_ID_SUFFIX"

    /** The whole-minute duration encoded in [id], or null if [id] is not a well-formed custom id. */
    fun customMinutes(id: String): Int? {
        if (!id.startsWith(CUSTOM_ID_PREFIX) || !id.endsWith(CUSTOM_ID_SUFFIX)) return null
        val minutes = id
            .substring(CUSTOM_ID_PREFIX.length, id.length - CUSTOM_ID_SUFFIX.length)
            .toIntOrNull() ?: return null
        return if (minutes >= CUSTOM_MIN_MINUTES) minutes else null
    }

    /**
     * The sequence a persisted [id] names, or null when nothing answers to it.
     *
     * Nullable deliberately. The obvious alternative — falling back to a default — is what made a
     * saved Custom race come back as a US Sailing one: `custom_8m` is not in [all], so a lookup that
     * substitutes on miss reports success while running the wrong duration and the wrong cues. A null
     * forces the caller to decide, and to say so on screen (see `docs/message-surface.md`).
     *
     * The lead-in branch is the same property one level out: a race armed with a lead time persists
     * as `scholastic_race_manager_lead70s` and nothing else, so this is what makes a process death
     * during the run-up come back on the right clock rather than 70 seconds short.
     */
    fun resolve(id: String): RaceSequence? =
        all.firstOrNull { it.id == id }
            ?: leadInFromId(id)
            ?: customMinutes(id)?.let { custom(it) }

    /**
     * A race of [totalMinutes] whole minutes, counted down in the Scholastic/ICSA voice.
     *
     * One long blast on every whole minute from the top down to and including 1:00, then
     * [finalMinuteTail] and the [sustainedGun] below it — so the part of the countdown a sailor
     * actually races to is bit-for-bit the Scholastic tail, and only the length above the minute is
     * the sailor's own. Nothing here is a new voice: the minute cue is the same `1 long` Scholastic
     * sounds at 1:00, which is why an unfamiliar duration still sounds like a sequence the sailor
     * already knows.
     *
     * A 1-minute race is therefore one long blast at 1:00, the tail, and the gun — the minimum, and
     * the reason [CUSTOM_MIN_MINUTES] is 1 rather than 0: below a minute there is no minute cue left
     * to sound and the tail itself would be truncated.
     *
     * [totalMinutes] is clamped rather than rejected. The picker already enforces the minimum, so a
     * smaller value can only arrive from a malformed persisted id — and on a watch about to time a
     * race, a one-minute countdown is a better answer than an exception. (That path is guarded too:
     * [customMinutes] returns null rather than clamping, so [resolve] rejects the id outright.)
     */
    fun custom(totalMinutes: Int): RaceSequence {
        val minutes = totalMinutes.coerceAtLeast(CUSTOM_MIN_MINUTES)
        val minuteCues = (minutes downTo 1).map { minute ->
            SequenceCue(
                offsetMs = minute * 60_000L,
                signal = SignalPattern(longBlasts = 1, label = "1 long — $minute:00"),
            )
        }
        return RaceSequence(
            id = customId(minutes),
            name = "Custom $minutes:00",
            cues = minuteCues + finalMinuteTail + sustainedGun,
        )
    }
}
