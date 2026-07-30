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
 * kind of thing". The voice is that axis. Both channels read it — see `CueTiming` on the wear side —
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
 */
data class SequenceCue(
    val offsetMs: Long,
    val signal: SignalPattern,
    val isGun: Boolean = false,
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
 * @param totalMs  Total countdown duration = the offset of the first cue.
 */
data class RaceSequence(
    val id: String,
    val name: String,
    val cues: List<SequenceCue>,
) {
    val totalMs: Long get() = cues.maxOfOrNull { it.offsetMs } ?: 0L
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
     * Five one-per-second ticks running *into* the signal at [signalOffsetMs] — 5, 4, 3, 2, 1.
     *
     * These are not signals and must not be heard as ones, so they carry [CueVoice.SYNC]. Their job
     * is to give a sailor whose watch has drifted a beat of warning *before* the mark, which is the
     * only moment the drift can still be corrected — the mark itself is too late.
     */
    private fun syncRunInto(signalOffsetMs: Long, signalName: String): List<SequenceCue> =
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
        cues = listOf(
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
        ) + finalMinuteTail + sustainedGun,
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
    val all: List<RaceSequence> = listOf(usSailing, scholastic, club)

    /** Build a [Custom] sequence from arbitrary duration and intermediate cues. */
    fun custom(
        totalSeconds: Long,
        intermediateCueOffsetsSec: List<Long> = listOf(60, 30, 10),
        name: String = "Custom ${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}",
    ): RaceSequence {
        val cues = mutableListOf<SequenceCue>()

        // Start cue (the gun)
        cues += SequenceCue(
            offsetMs = 0L,
            signal = SignalPattern(longBlasts = 1, shortBlasts = 3, label = "Start"),
            isGun = true,
        )

        // Intermediate cues
        intermediateCueOffsetsSec
            .filter { it in 1 until totalSeconds }
            .forEach { sec ->
                val min = sec / 60
                val s = sec % 60
                cues += SequenceCue(
                    offsetMs = sec * 1_000L,
                    signal = SignalPattern(shortBlasts = 1, label = "${min}:${s.toString().padStart(2, '0')}"),
                )
            }

        // Final 5-second individual ticks, doubled like every other sequence's. Added after the
        // intermediate cues so the `distinctBy` below still resolves an offset collision the same way
        // it always has: a caller-supplied intermediate at 0:05 keeps its own pattern.
        cues += finalFiveRun.filter { it.offsetMs < totalSeconds * 1_000L }

        // Warning cue at the top
        cues += SequenceCue(
            offsetMs = totalSeconds * 1_000L,
            signal = SignalPattern(longBlasts = 1, label = "Start in ${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"),
        )

        return RaceSequence(
            id = "custom_${totalSeconds}s",
            name = name,
            cues = cues.distinctBy { it.offsetMs }.sortedByDescending { it.offsetMs },
        )
    }
}
