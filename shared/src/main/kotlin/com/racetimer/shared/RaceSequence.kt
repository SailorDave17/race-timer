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
 * Describes how many long and short blasts make up a single cue.
 * Used to drive both audio synthesis and vibration patterns on the watch.
 *
 * @param longBlasts  Number of sustained (≈2 s) horn blasts.
 * @param shortBlasts Number of short (≈1 s) horn blasts.
 * @param label       Human-readable description shown in the UI (e.g. "3 long").
 */
data class SignalPattern(
    val longBlasts: Int = 0,
    val shortBlasts: Int = 0,
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
            SequenceCue(
                offsetMs = 5_000L,
                signal = SignalPattern(shortBlasts = 1, label = "1 short"),
            ),
            SequenceCue(
                offsetMs = 4_000L,
                signal = SignalPattern(shortBlasts = 1, label = "1 short"),
            ),
            SequenceCue(
                offsetMs = 3_000L,
                signal = SignalPattern(shortBlasts = 1, label = "1 short"),
            ),
            SequenceCue(
                offsetMs = 2_000L,
                signal = SignalPattern(shortBlasts = 1, label = "1 short"),
            ),
            SequenceCue(
                offsetMs = 1_000L,
                signal = SignalPattern(shortBlasts = 1, label = "1 short"),
            ),
            SequenceCue(
                offsetMs = 0L,
                signal = SignalPattern(longBlasts = 1, label = "Start"),
                isGun = true,
            ),
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
