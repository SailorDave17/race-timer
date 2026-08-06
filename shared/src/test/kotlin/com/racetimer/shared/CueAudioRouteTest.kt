package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the cue-routing rule (#95).
 *
 * The expected values below are **written out**, never computed from the inputs. A tidier-looking
 * `if (enabled && silenced) MEDIA else ALARM` on the right-hand side would compare [cueStream] against
 * a second copy of itself and pass however either one was wrong — the mistake `prove-a-guard-test-can-fail`
 * records from #105, where an expected value derived from the code under test could not be reddened by
 * any mutation of it. Having to edit the table when a case is added *is* the assertion working.
 */
class CueAudioRouteTest {

    /**
     * Every combination of the three inputs, with the answer stated rather than derived.
     *
     * Volumes are `15` and `0` because those are the two the SM-R925U actually reports — the maximum
     * index on that device and the muted one — rather than arbitrary non-zero and zero values.
     */
    private data class Case(
        val overrideEnabled: Boolean,
        val ringerSilenced: Boolean,
        val alarmVolume: Int,
        val expected: CueStream,
        val why: String,
    )

    private val table = listOf(
        // Setting off: today's behaviour, whatever the device is doing. This half of the table is
        // AC 3 — "with the setting off, behaviour is exactly what it is today" — and it is the half
        // that would silently stop being true if the override were ever moved ahead of the veto.
        Case(false, true, 0, CueStream.ALARM, "off, and the watch is silenced"),
        Case(false, true, 15, CueStream.ALARM, "off, silenced, alarm slider up"),
        Case(false, false, 0, CueStream.ALARM, "off, normal ringer, alarm slider at zero"),
        Case(false, false, 15, CueStream.ALARM, "off, and nothing is wrong anyway"),

        // Setting on. Only the last row leaves the cue where it is.
        Case(true, true, 15, CueStream.MEDIA, "the measured SM-R925U case: vibrate mode, alarm reads 15"),
        Case(true, true, 0, CueStream.MEDIA, "silenced, and the alarm slider is down too"),
        Case(true, false, 0, CueStream.MEDIA, "normal ringer, but the alarm slider is at zero"),
        Case(true, false, 15, CueStream.ALARM, "the ordinary race: nothing to route around"),
    )

    @Test fun `the routing table is what it says it is`() {
        for (case in table) {
            assertEquals(
                "${case.why} (enabled=${case.overrideEnabled}, silenced=${case.ringerSilenced}, " +
                    "alarmVolume=${case.alarmVolume})",
                case.expected,
                cueStream(case.overrideEnabled, case.ringerSilenced, case.alarmVolume),
            )
        }
    }

    @Test fun `the table covers every combination of its inputs`() {
        // Without this, a row deleted in a refactor leaves a smaller table that still passes. Eight
        // rows: two settings x two ringer states x two volume states.
        assertEquals(8, table.size)
        assertEquals(8, table.map { Triple(it.overrideEnabled, it.ringerSilenced, it.alarmVolume) }.toSet().size)
    }

    // --- The two properties the table is there to protect ---------------------

    @Test fun `the setting off is a total veto`() {
        // The sailor turning this off must get today's app back, not a quieter version of the new
        // one. Asserted as a property over the whole table rather than row by row, because the thing
        // being guarded is that *no* device condition can outvote the setting.
        //
        // Note what this calls. The obvious phrasing — filtering the table on `it.expected` — reads
        // identically and asserts nothing about the code: it checks the table against itself, so it
        // survives any mutation of `cueStream` whatever. It was written that way first and caught by
        // demoting the veto below the ringer check, which left this passing while the rule was broken.
        val reroutedWhileOff = table
            .filter { !it.overrideEnabled }
            .map { cueStream(it.overrideEnabled, it.ringerSilenced, it.alarmVolume) }
            .filter { it != CueStream.ALARM }
        assertEquals(emptyList<CueStream>(), reroutedWhileOff)
    }

    @Test fun `a silenced watch is the only thing that moves a cue off the alarm stream`() {
        // The complement of the veto, and the reason this is not simply "always use media": with the
        // setting on and the device healthy, the cue stays exactly where #61 verified it. If this ever
        // fails, every race has quietly moved onto the media slider.
        assertEquals(CueStream.ALARM, cueStream(overrideEnabled = true, ringerSilenced = false, alarmVolume = 15))
    }

    @Test fun `a volume read alone would not have caught the defect`() {
        // The measured trap, pinned as a test rather than left in a comment. On the SM-R925U
        // STREAM_ALARM is aliased to STREAM_NOTIFICATION, so getStreamVolume(STREAM_ALARM) returns a
        // healthy 15 while the cue is inaudible. A future "simplification" that drops the ringer input
        // and keys off the volume alone is a *valid* read returning a plausible number, and this is
        // the assertion that says no.
        assertEquals(
            CueStream.MEDIA,
            cueStream(overrideEnabled = true, ringerSilenced = true, alarmVolume = 15),
        )
    }

    @Test fun `an alarm slider at zero reroutes even in normal ringer mode`() {
        // The case the ringer read cannot see, which is why alarmVolume is a second trigger and not
        // dead weight. Dropping it would leave this passing only by accident of the row above.
        assertEquals(
            CueStream.MEDIA,
            cueStream(overrideEnabled = true, ringerSilenced = false, alarmVolume = 0),
        )
    }
}
