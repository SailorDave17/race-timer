package com.racetimer.shared

/**
 * The frame arithmetic `ToneManager` uses to keep one audio track playing across a whole race.
 *
 * ### Why a race keeps its track playing (#114)
 *
 * The track used to be paused and flushed after every cue, so every cue's `AudioTrack.play()` re-paid
 * `startOutput` — a round trip into the audio server. Measured on an SM-R925U, that costs **3-45 ms
 * mid-race and 138-297 ms on the first cue of a race**, because the first cue is the one that collides
 * with the service's own `startForeground`. There is nowhere to hide that cost: a cue fires and is due
 * `LEAD_IN_MS` later, which is 40 ms.
 *
 * So the track is started once, before the race, and never paused. A cue is then a buffer write onto a
 * track that is already playing, and nothing on the cue's own deadline talks to the audio server.
 *
 * ### What that costs, and why these functions exist
 *
 * A track that is playing with nothing to play underruns, and AudioFlinger drops an underrunning track
 * out of its active list after a second or so — which calls `stopOutput` and hands the cost straight
 * back. Keeping it active means writing a little silence at an interval shorter than that, which means
 * a cue can arrive with silence still queued ahead of it and start that much late.
 *
 * Both halves of that are arithmetic on frame counters, and both are the kind of arithmetic that fails
 * *quietly*: an off-by-a-buffer here shows up as a cue that is slightly late or a `delivered` figure
 * that is slightly wrong, never as an exception. They live here, in `shared`, for the same reason
 * [CueTiming.resetAtMs] does — `wear/` has no test source set, and the last timing rule left inline
 * there was wrong for two weeks while every call reported success (#98).
 */
object CueTrackPacing {

    /**
     * How often a silence heartbeat is written to keep the track in AudioFlinger's active list.
     *
     * Has to beat the mixer's own patience with an underrunning track, which is a fixed number of
     * mixer periods — on the order of a second, and not a number this side of the process can read.
     * 300 ms leaves room for a period several times longer than the SM-R925U's without the track ever
     * being dropped, and it is the cheap direction to be wrong in: too often costs a short memcpy, too
     * rarely costs the cue a `startOutput` and puts #114 straight back.
     *
     * The one thing here that no test can check, precisely because the number it must beat lives in
     * the audio server. The hardware run is the instrument — a `writeMs` that is not 0-1 ms is this
     * interval being too long.
     */
    const val KEEP_MIXED_INTERVAL_MS = 300L

    /**
     * How much silence each heartbeat writes.
     *
     * Directly the worst case a cue can be delayed by, since a cue written while this is still
     * draining queues behind it — so it is sized as small as will reliably survive a mixer pass rather
     * than as large as is convenient. At 10 ms the expected cost is around 5 ms, against an in-race
     * band of 4-45 ms and the 138-297 ms the change removes.
     *
     * Reported as `queuedMs` on every cue rather than corrected for. Writing the cue *early* by a
     * predicted amount is the obvious alternative and is worse: a cue that sounds before its deadline
     * is a signal given early, which is not recoverable the way a few milliseconds of lateness is.
     */
    const val KEEP_MIXED_CHUNK_MS = 10L

    /**
     * Queued audio above which a cue empties the track instead of appending to it.
     *
     * The number that makes the fast path safe, and the reason these three are in `shared` rather than
     * beside their only caller: it is meaningless on its own and only correct *relative* to the other
     * two and to [CueTiming]. Widening it past the shortest cue would let one cue append behind
     * another and play two signals back to back; narrowing it below the heartbeat would make every cue
     * flush and undo #114 silently, with nothing failing. `CueTrackPacingTest` pins both bounds.
     */
    const val MAX_QUEUED_MS = 40L

    /**
     * Frames between two positions on the same track, correct across the 32-bit wrap.
     *
     * `AudioTrack.getPlaybackHeadPosition()` returns a signed [Int] that **wraps** — the platform says
     * so outright, and at 48 kHz it wraps about every 12.4 hours. A watch left armed overnight reaches
     * it. Ordinary subtraction on two wrapped counters is still the right answer in two's complement,
     * so this is deliberately `later - earlier` and not something defensive with [Long]s: promoting to
     * `Long` first is what would break it, by turning a wrapped-negative head into a huge positive
     * number.
     *
     * Correct while the true distance is under 2^31 frames, which is 12 hours of audio.
     */
    fun framesBetween(earlier: Int, later: Int): Int = later - earlier

    /** Milliseconds of audio in [frames] at [sampleRateHz]. Rounds down; negative input clamps to 0. */
    fun framesToMs(frames: Int, sampleRateHz: Int): Long {
        if (frames <= 0 || sampleRateHz <= 0) return 0L
        return frames.toLong() * 1000L / sampleRateHz
    }

    /** Frames of audio in [ms] at [sampleRateHz], at least one whenever [ms] is positive. */
    fun msToFrames(ms: Long, sampleRateHz: Int): Int {
        if (ms <= 0L || sampleRateHz <= 0) return 0
        return maxOf(1, (ms * sampleRateHz / 1000L).toInt())
    }

    /**
     * Whether a cue must empty the track before it is written, rather than appending to it.
     *
     * Appending is the whole point — it is what keeps `startOutput` paid — but it is only correct while
     * what is already queued is *small*, because a cue written behind queued audio does not sound until
     * that audio has drained. Two things can be queued: the keep-alive silence, which is bounded by its
     * own chunk size and is the normal case, and a **previous cue that is still sounding**, which
     * happens when one cue is cancelled by the next and is hundreds or thousands of milliseconds.
     *
     * The second case has to flush, and flushing means pausing, which means paying `startOutput` again
     * on this cue. That is the old behaviour, correctly applied to the one case that needs it — a late
     * cue is recoverable, two cues played back to back is not what the sailor was signalled.
     *
     * [maxQueuedMs] is therefore a budget, not a tolerance: it must sit above the keep-alive chunk and
     * far below any real cue.
     */
    fun needsFlushBeforeCue(queuedMs: Long, maxQueuedMs: Long): Boolean = queuedMs > maxQueuedMs

    /**
     * When a cue written now will actually be heard, given [queuedMs] of audio ahead of it.
     *
     * The honest reading of a cue's lateness once the track is shared with keep-alive silence: the
     * write returning is not the sound starting. Reported rather than corrected for — the alternative
     * is writing the cue early by a predicted amount, and a cue that sounds *early* is a worse failure
     * than one that sounds a few milliseconds late.
     */
    fun audibleAtMs(writtenAtMs: Long, queuedMs: Long): Long = writtenAtMs + queuedMs
}
