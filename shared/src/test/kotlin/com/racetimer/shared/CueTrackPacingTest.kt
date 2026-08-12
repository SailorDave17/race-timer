package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame arithmetic behind keeping one audio track playing across a race (#114).
 *
 * Every case here is one that fails *silently* on the device — a wrong answer is a cue a few
 * milliseconds out or a `delivered` figure that does not match the cue, never an exception. `wear/`
 * has no test source set, which is why the rules are in `shared` to begin with.
 */
class CueTrackPacingTest {

    private val rate = 48_000

    @Test
    fun `queue depth is what has been written and not yet played`() {
        // 480 frames written, 0 played: 10 ms still queued.
        assertEquals(480, CueTrackPacing.framesBetween(earlier = 0, later = 480))
        assertEquals(10L, CueTrackPacing.framesToMs(480, rate))
    }

    @Test
    fun `a fully drained track has nothing queued`() {
        assertEquals(0, CueTrackPacing.framesBetween(earlier = 96_000, later = 96_000))
        assertEquals(0L, CueTrackPacing.framesToMs(0, rate))
    }

    /**
     * The reason both counters are [Int] and neither is widened before subtracting.
     *
     * `AudioTrack.getPlaybackHeadPosition()` wraps at 2^32 — about 12.4 hours at 48 kHz, which a watch
     * left armed overnight reaches. Two's complement subtraction is still correct across the wrap; a
     * `toLong()` on either side before the subtraction is what would break it, turning a wrapped head
     * into a distance of four billion frames and every cue after it into a flush.
     */
    @Test
    fun `frame distance survives the 32-bit wrap`() {
        val head = Int.MAX_VALUE - 100
        // Added through a variable so the compiler does not fold it and warn about the overflow that
        // is the entire point of the case.
        val chunk = CueTrackPacing.msToFrames(10L, rate)
        val written = head + chunk // wraps to a negative Int
        assertTrue("the test's own premise: this addition must wrap", written < 0)
        assertEquals(480, CueTrackPacing.framesBetween(head, written))
        assertEquals(10L, CueTrackPacing.framesToMs(CueTrackPacing.framesBetween(head, written), rate))
    }

    @Test
    fun `a head that has passed what was written reads as nothing queued`() {
        // Underrun: the mixer consumed everything and the counter briefly runs ahead. Negative frames
        // are not negative milliseconds, and a negative queue must never move a cue earlier.
        assertEquals(-96, CueTrackPacing.framesBetween(earlier = 480, later = 384))
        assertEquals(0L, CueTrackPacing.framesToMs(-96, rate))
    }

    @Test
    fun `milliseconds convert to frames at the output rate`() {
        assertEquals(480, CueTrackPacing.msToFrames(10L, rate))
        assertEquals(441, CueTrackPacing.msToFrames(10L, 44_100))
        assertEquals(0, CueTrackPacing.msToFrames(0L, rate))
    }

    @Test
    fun `a sub-millisecond request still writes a frame`() {
        // A heartbeat that writes nothing is not a heartbeat: the track would be dropped from the
        // mixer's active list and the next cue would pay the startOutput this whole change removes.
        assertEquals(1, CueTrackPacing.msToFrames(1L, 500))
    }

    @Test
    fun `a rate the platform never reported cannot produce a division by zero`() {
        assertEquals(0L, CueTrackPacing.framesToMs(480, 0))
        assertEquals(0, CueTrackPacing.msToFrames(10L, 0))
    }

    @Test
    fun `keep-alive silence does not trip the flush valve`() {
        // The whole point of the budget: one heartbeat chunk must append, not flush. 10 ms chunk
        // against a 40 ms budget.
        assertFalse(CueTrackPacing.needsFlushBeforeCue(queuedMs = 10L, maxQueuedMs = 40L))
        assertFalse(CueTrackPacing.needsFlushBeforeCue(queuedMs = 40L, maxQueuedMs = 40L))
    }

    @Test
    fun `a previous cue still sounding does trip it`() {
        // The shortest cue in any sequence is a 120 ms sync tick, and the gun is 3000 ms. Appending
        // behind either would play two signals back to back — a signal nobody gave.
        assertTrue(CueTrackPacing.needsFlushBeforeCue(queuedMs = 120L, maxQueuedMs = 40L))
        assertTrue(CueTrackPacing.needsFlushBeforeCue(queuedMs = 3_000L, maxQueuedMs = 40L))
    }

    @Test
    fun `a cue is audible after the audio queued ahead of it drains`() {
        assertEquals(1_010L, CueTrackPacing.audibleAtMs(writtenAtMs = 1_000L, queuedMs = 10L))
        assertEquals(1_000L, CueTrackPacing.audibleAtMs(writtenAtMs = 1_000L, queuedMs = 0L))
    }

    /**
     * The delivered-frame reading, which is the one instrument that can tell a cue that sounded from a
     * cue that was merely submitted — and the one #98's truncation defect hid behind.
     */
    @Test
    fun `delivered frames are counted from where the cue starts, not from the head at write time`() {
        // A gun: 480 frames of keep-alive silence still queued, then 144000 frames of cue.
        val headAtWrite = 0
        val cueStartFrame = headAtWrite + 480
        val headAtHousekeeping = cueStartFrame + 144_000

        assertEquals(144_000, CueTrackPacing.framesBetween(cueStartFrame, headAtHousekeeping))
        assertEquals(3_000L, CueTrackPacing.framesToMs(144_000, rate))

        // Counting from the head instead would report the silence as part of the cue — 10 ms of a
        // truncation budget spent before the measurement even starts.
        assertEquals(144_480, CueTrackPacing.framesBetween(headAtWrite, headAtHousekeeping))
    }

    // --- The three constants, which are only correct relative to each other -----------------------
    //
    // Each of these was a number sitting in ToneManager, in a module with no test source set, where
    // being wrong produces no error at all: too large a budget plays two signals back to back, too
    // small a one flushes on every cue and undoes #114 with everything still green. Pinning the
    // *relationships* is the only thing that can fail.

    @Test
    fun `the heartbeat never trips the flush valve`() {
        assertFalse(
            "a heartbeat that flushes would make every cue pay startOutput again, silently",
            CueTrackPacing.needsFlushBeforeCue(
                queuedMs = CueTrackPacing.KEEP_MIXED_CHUNK_MS,
                maxQueuedMs = CueTrackPacing.MAX_QUEUED_MS,
            ),
        )
    }

    @Test
    fun `the shortest cue in any sequence always trips the flush valve`() {
        // A sync tick is the shortest thing this app plays. If a cue that short can append behind a
        // previous one, the sailor hears two signals where one was given.
        val shortestCueMs = CueTiming.SYNC_ON + CueTiming.SYNC_OFF
        assertTrue(
            "MAX_QUEUED_MS ($shortestCueMs ms cue) must be below the shortest cue",
            CueTrackPacing.needsFlushBeforeCue(
                queuedMs = shortestCueMs,
                maxQueuedMs = CueTrackPacing.MAX_QUEUED_MS,
            ),
        )
    }

    @Test
    fun `the heartbeat writes less often than the silence it writes lasts is exceeded`() {
        // Interval well above chunk, or the track is being fed continuously rather than kept alive —
        // which is a stream of queued silence for every cue to drain through.
        assertTrue(
            "the heartbeat must be a heartbeat, not a stream",
            CueTrackPacing.KEEP_MIXED_INTERVAL_MS > CueTrackPacing.KEEP_MIXED_CHUNK_MS * 4,
        )
    }
}
