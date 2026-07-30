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
 * @param label       Human-readable description of the pattern (e.g. "3 long"). Documentation
 *                    only: nothing in the app renders it today.
 */
data class SignalPattern(
    val longBlasts: Int = 0,
    val shortBlasts: Int = 0,
    val sustainedMs: Long = 0L,
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

    // --- US Sailing 5-4-1-go (RRS 26) ---
    val usSailing: RaceSequence = RaceSequence(
        id = "us_sailing_5_4_1",
        name = "US Sailing 5-4-1-Go",
        cues = listOf(
            SequenceCue(
                offsetMs = 5 * 60_000L,
                signal = SignalPattern(longBlasts = 1, label = "Warning — class flag up"),
            ),
            SequenceCue(
                offsetMs = 4 * 60_000L,
                signal = SignalPattern(longBlasts = 1, label = "Preparatory — P/I/Z/U flag up"),
            ),
            SequenceCue(
                offsetMs = 1 * 60_000L,
                signal = SignalPattern(longBlasts = 1, label = "One-minute — prep flag down"),
            ),
            SequenceCue(
                offsetMs = 0L,
                signal = SignalPattern(longBlasts = 1, shortBlasts = 3, label = "Start — class flag down"),
                isGun = true,
            ),
        ),
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
            // 0:50 and 0:40 break the silence the sequence used to leave between 1:00 and 0:30.
            SequenceCue(
                offsetMs = 50_000L,
                signal = SignalPattern(shortBlasts = 1, label = "1 short"),
            ),
            SequenceCue(
                offsetMs = 40_000L,
                signal = SignalPattern(shortBlasts = 1, label = "1 short"),
            ),
            // 0:30 and 0:20 keep the descending 3-short / 2-short call the committee sounds.
            SequenceCue(
                offsetMs = 30_000L,
                signal = SignalPattern(shortBlasts = 3, label = "3 short"),
            ),
            SequenceCue(
                offsetMs = 20_000L,
                signal = SignalPattern(shortBlasts = 2, label = "2 short"),
            ),
        ) + (10 downTo 1).map { sec ->
            // The last ten seconds are a flat, even tick — every second identical — so the sailor
            // counts pulses off the wrist instead of decoding a pattern on the line.
            SequenceCue(
                offsetMs = sec * 1_000L,
                signal = SignalPattern(shortBlasts = 1, label = "1 short"),
            )
        } + SequenceCue(
            // One sustained blast, not a count: the gun is the only cue with nothing to count.
            offsetMs = 0L,
            signal = SignalPattern(sustainedMs = 3_000L, label = "Start — sustained"),
            isGun = true,
        ),
    )

    // --- Club racing 3-2-1-go ---
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

        // Final 5-second individual ticks
        for (sec in 1L..5L) {
            if (sec < totalSeconds) {
                cues += SequenceCue(
                    offsetMs = sec * 1_000L,
                    signal = SignalPattern(shortBlasts = 1, label = "$sec"),
                )
            }
        }

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
