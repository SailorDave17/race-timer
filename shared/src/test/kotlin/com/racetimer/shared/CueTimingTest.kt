package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [CueTiming], which decides the shape a cue takes on both channels.
 *
 * The point of these is that HapticManager and ToneManager read the *same* numbers: a change here
 * moves the buzz and the tone together or it is a bug, and until this file existed nothing said so
 * outside a KDoc paragraph. Nothing in here touches Android — CueTiming is arithmetic over
 * [SignalPattern], which is why it belongs in this module.
 */
class CueTimingTest {

    private fun blast(long: Int = 0, short: Int = 0, sustained: Long = 0L) =
        SignalPattern(longBlasts = long, shortBlasts = short, sustainedMs = sustained)

    private fun sync(short: Int) = SignalPattern(shortBlasts = short, voice = CueVoice.SYNC)

    // --- onMs / offMs -----------------------------------------------------------

    @Test fun `a blast cue takes its on-off from the long and short constants`() {
        val pattern = blast(long = 1, short = 1)
        assertEquals(CueTiming.LONG_ON, CueTiming.onMs(pattern, long = true))
        assertEquals(CueTiming.LONG_OFF, CueTiming.offMs(pattern, long = true))
        assertEquals(CueTiming.SHORT_ON, CueTiming.onMs(pattern, long = false))
        assertEquals(CueTiming.SHORT_OFF, CueTiming.offMs(pattern, long = false))
    }

    @Test fun `a sync cue keeps tick timing whether the blast is long or short`() {
        // The voice wins over the long/short axis: a sync cue has one shape, and asking for its
        // "long" blast must not hand back a blast's 500 ms. Both managers call through here with
        // long = true for the long-blast loop, so a voice-blind answer would stretch the buzz.
        val pattern = sync(short = 2)
        for (long in listOf(true, false)) {
            assertEquals("long = $long", CueTiming.SYNC_ON, CueTiming.onMs(pattern, long))
            assertEquals("long = $long", CueTiming.SYNC_OFF, CueTiming.offMs(pattern, long))
        }
    }

    @Test fun `a sync tick is well under a short blast on both channels`() {
        // What separates a tick from a signal by feel on a muted watch. Amplitude carries the rest.
        assertTrue("tick must be shorter than a short blast", CueTiming.SYNC_ON < CueTiming.SHORT_ON)
        assertTrue("tick amplitude must be gentler", CueTiming.SYNC_AMPLITUDE < 200)
    }

    // --- durationMs -------------------------------------------------------------

    @Test fun `blast counts add up with their trailing silence`() {
        assertEquals(
            3 * (CueTiming.LONG_ON + CueTiming.LONG_OFF),
            CueTiming.durationMs(blast(long = 3)),
        )
        assertEquals(
            2 * (CueTiming.SHORT_ON + CueTiming.SHORT_OFF),
            CueTiming.durationMs(blast(short = 2)),
        )
        assertEquals(
            (CueTiming.LONG_ON + CueTiming.LONG_OFF) + 2 * (CueTiming.SHORT_ON + CueTiming.SHORT_OFF),
            CueTiming.durationMs(blast(long = 1, short = 2)),
        )
    }

    @Test fun `a sustained cue reports its own length and ignores everything else`() {
        // sustainedMs replaces the counts rather than adding to them, and outranks isGun — the
        // ordering HapticManager relies on to keep club's gun on the legacy triple-buzz while
        // scholastic's states its own 3 s.
        assertEquals(3_000L, CueTiming.durationMs(blast(sustained = 3_000L)))
        assertEquals(3_000L, CueTiming.durationMs(blast(sustained = 3_000L), isGun = true))
        assertEquals(3_000L, CueTiming.durationMs(blast(long = 3, sustained = 3_000L)))
    }

    @Test fun `a gun stating no sustained length falls through to the triple-buzz`() {
        assertEquals(
            CueTiming.GUN_REPEAT * (CueTiming.LONG_ON + CueTiming.LONG_OFF),
            CueTiming.durationMs(blast(), isGun = true),
        )
    }

    @Test fun `a sync cue is not reported at blast length`() {
        // The overstatement this guards against is not theoretical: both callers size something off
        // this number — the gap check in RaceSequenceTest and ToneManager's audio teardown delay —
        // and a sync cue read as a blast is four times its real length on the tightest gap in any
        // sequence (usSailing's 1-second ticks).
        assertEquals(2 * (CueTiming.SYNC_ON + CueTiming.SYNC_OFF), CueTiming.durationMs(sync(short = 2)))
        assertTrue(
            "a sync cue must not be reported as long as the same counts in blasts",
            CueTiming.durationMs(sync(short = 2)) < CueTiming.durationMs(blast(short = 2)),
        )
    }

    // --- the SHORT_OFF ceiling --------------------------------------------------

    @Test fun `SHORT_OFF stays clear of the width that collapses the final five`() {
        // The doubled ticks of the final five seconds are `2 short` cues one second apart, and they
        // mean "final five" only while the gap inside a pair reads as clearly shorter than the gap
        // between pairs. Those two work out at SHORT_OFF - 10 and 690 - SHORT_OFF, so they meet at
        // 350, where the last five seconds become ten evenly-spaced beeps carrying nothing.
        //
        // #58 widened this constant to 250 chasing cues that sounded run-together; the real cause
        // was the audio output closing between blasts. The 2x floor below is a chosen guard rail,
        // not a measured perceptual threshold — it trips around 237 ms, well before the collapse,
        // so the next person to reach for this constant gets a failing test rather than a silently
        // duller countdown.
        val insidePairMs = CueTiming.SHORT_OFF - 10
        val betweenPairsMs = 690 - CueTiming.SHORT_OFF
        assertTrue(
            "SHORT_OFF = ${CueTiming.SHORT_OFF} leaves a ${insidePairMs} ms gap inside a pair " +
                "against ${betweenPairsMs} ms between pairs — the doubling stops reading",
            betweenPairsMs >= 2 * insidePairMs,
        )
    }
}
