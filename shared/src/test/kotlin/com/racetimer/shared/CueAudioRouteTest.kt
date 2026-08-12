package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the cue-routing rule and the volume floor that backs it (#95).
 *
 * The expected values below are **written out**, never computed from the inputs. A tidier-looking
 * `if (silenced) MEDIA else ALARM` on the right-hand side would compare [cueStream] against a second
 * copy of itself and pass however either one was wrong — the mistake `prove-a-guard-test-can-fail`
 * records from #105, where an expected value derived from the code under test could not be reddened by
 * any mutation of it. Having to edit the table when a case is added *is* the assertion working.
 *
 * The `the setting off is a total veto` test that used to live here is **gone on purpose**. It asserted
 * that switching the sailor's toggle off restored the silence exactly, which is the behaviour the
 * 2026-08-06 directive rules out; leaving it would have pinned the defect in place. Its removal is
 * criterion 1 of the rescoped issue, not an untested gap.
 */
class CueAudioRouteTest {

    /**
     * Every combination of the two inputs, with the answer stated rather than derived.
     *
     * Volumes are `15` and `0` because those are the two the SM-R925U actually reports — the maximum
     * index on that device and the muted one — rather than arbitrary non-zero and zero values.
     */
    private data class Case(
        val ringerSilenced: Boolean,
        val alarmVolume: Int,
        val expected: CueStream,
        val why: String,
    )

    private val table = listOf(
        Case(true, 15, CueStream.MEDIA, "the measured SM-R925U case: vibrate mode, alarm reads 15"),
        Case(true, 0, CueStream.MEDIA, "silenced, and the alarm slider is down too"),
        Case(false, 0, CueStream.MEDIA, "normal ringer, but the alarm slider is at zero"),
        Case(false, 15, CueStream.ALARM, "the ordinary race: nothing to route around"),
    )

    @Test fun `the routing table is what it says it is`() {
        for (case in table) {
            assertEquals(
                "${case.why} (silenced=${case.ringerSilenced}, alarmVolume=${case.alarmVolume})",
                case.expected,
                cueStream(case.ringerSilenced, case.alarmVolume),
            )
        }
    }

    @Test fun `the table covers every combination of its inputs`() {
        // Without this, a row deleted in a refactor leaves a smaller table that still passes. Four
        // rows: two ringer states x two volume states.
        assertEquals(4, table.size)
        assertEquals(4, table.map { it.ringerSilenced to it.alarmVolume }.toSet().size)
    }

    // --- The properties the table is there to protect --------------------------

    @Test fun `nothing the sailor can set makes a silenced watch keep the alarm stream`() {
        // The directive, as an assertion: there is no input left that vetoes the reroute. This is the
        // replacement for the deleted veto test, and it points the opposite way — where that one
        // proved a switch could restore the silence, this proves nothing can.
        //
        // Note what it calls. Filtering the table on `it.expected` would read identically and assert
        // nothing about the code, checking the table against itself; the veto test was written that
        // way first and survived every mutation until it was fixed to call the rule.
        val keptOnAlarmWhileSilenced = table
            .filter { it.ringerSilenced }
            .map { cueStream(it.ringerSilenced, it.alarmVolume) }
            .filter { it != CueStream.MEDIA }
        assertEquals(emptyList<CueStream>(), keptOnAlarmWhileSilenced)
    }

    @Test fun `a silenced watch is the only thing that moves a cue off the alarm stream`() {
        // The reason this is not simply "always use media": with the device healthy, the cue stays
        // exactly where #61 verified it. If this ever fails, every race has quietly moved onto the
        // media slider.
        assertEquals(CueStream.ALARM, cueStream(ringerSilenced = false, alarmVolume = 15))
    }

    @Test fun `a volume read alone would not have caught the defect`() {
        // The measured trap, pinned as a test rather than left in a comment. On the SM-R925U
        // STREAM_ALARM is aliased to STREAM_NOTIFICATION, so getStreamVolume(STREAM_ALARM) returns a
        // healthy 15 while the cue is inaudible. A future "simplification" that drops the ringer input
        // and keys off the volume alone is a *valid* read returning a plausible number, and this is
        // the assertion that says no.
        assertEquals(CueStream.MEDIA, cueStream(ringerSilenced = true, alarmVolume = 15))
    }

    @Test fun `an alarm slider at zero reroutes even in normal ringer mode`() {
        // The case the ringer read cannot see, which is why alarmVolume is a second trigger and not
        // dead weight. Dropping it would leave this passing only by accident of the row above.
        assertEquals(CueStream.MEDIA, cueStream(ringerSilenced = false, alarmVolume = 0))
    }

    // --- The volume floor ------------------------------------------------------

    @Test fun `a stream at zero is raised to the floor`() {
        // The case routing alone never fixed: correctly moved to media, and the media slider is down.
        // 11 of 15 is the SM-R925U's own maximum index against CUE_VOLUME_FLOOR_PERCENT.
        assertEquals(11, raisedCueVolume(currentVolume = 0, maxVolume = 15))
    }

    @Test fun `a stream already above the floor is left alone`() {
        // Not "returns 15" — returns *nothing to do*. The distinction is the whole point: a race that
        // changes no device state has nothing to restore and cannot strand a raised volume.
        assertNull(raisedCueVolume(currentVolume = 15, maxVolume = 15))
        assertNull(raisedCueVolume(currentVolume = 12, maxVolume = 15))
    }

    @Test fun `a stream exactly at the floor is left alone`() {
        // The boundary, stated rather than left to the reader of the `<`. Raising to a value already
        // held would write the same number and still create a restore obligation.
        assertNull(raisedCueVolume(currentVolume = 11, maxVolume = 15))
    }

    @Test fun `one step below the floor is raised`() {
        // The complement of the case above. Without this pair, an off-by-one in the comparison passes.
        assertEquals(11, raisedCueVolume(currentVolume = 10, maxVolume = 15))
    }

    @Test fun `the floor never lowers a volume the sailor has raised`() {
        // A sailor at maximum has said something about how loud they want their race. Asserted over a
        // range rather than one value, because a `!=` mistake in the comparison only shows above the
        // floor.
        for (volume in 11..15) {
            assertNull("volume $volume is at or above the floor", raisedCueVolume(volume, maxVolume = 15))
        }
    }

    @Test fun `the floor rounds up, never down`() {
        // On a 15-step slider, 70% is 10.5. Rounding down would put the floor at 10 — below the
        // percentage this constant claims — and one index on a watch is a whole step of real loudness.
        assertEquals(11, raisedCueVolume(currentVolume = 0, maxVolume = 15))
        // 7 * 70% = 4.9 -> 5, not 4.
        assertEquals(5, raisedCueVolume(currentVolume = 0, maxVolume = 7))
    }

    @Test fun `a stream with no usable range is not touched`() {
        // A device reporting max 0 is telling us the stream has no range. Computing a floor out of
        // that produces a confident number from nothing, and setting it would be the one case where
        // this code makes a watch worse.
        assertNull(raisedCueVolume(currentVolume = 0, maxVolume = 0))
        assertNull(raisedCueVolume(currentVolume = 0, maxVolume = -1))
    }

    @Test fun `a one-step stream is raised to its only audible value`() {
        // max 1 rounds to a floor of 1: the coerce is what stops a tiny range collapsing to 0, which
        // would "raise" the volume to silence and report success.
        assertEquals(1, raisedCueVolume(currentVolume = 0, maxVolume = 1))
    }
}
