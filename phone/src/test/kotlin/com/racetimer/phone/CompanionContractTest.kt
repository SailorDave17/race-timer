package com.racetimer.phone

import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.SignalPattern
import com.racetimer.shared.withLeadIn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The cue-to-event map and the event's wire shape, against pro-companion ADR 004's contract (#294).
 *
 * Robolectric only because `org.json` is the platform's, and a bare JVM test gets the stub that
 * throws. Nothing here touches a device surface.
 */
@RunWith(RobolectricTestRunner::class)
class CompanionContractTest {

    /** What the companion is told about each of [sequence]'s cues, in firing order, as text. */
    private fun reports(sequence: RaceSequence): List<String> =
        sequence.cues.sortedByDescending { it.offsetMs }.mapNotNull { cue ->
            when (val report = companionReportOf(sequence, cue)) {
                is CueReport.Signal -> "${report.name}@${report.secondsToStart}"
                CueReport.Start -> "start"
                null -> null
            }
        }

    @Test
    fun `US Sailing is told its five signals from 5 00 to 1 00 and the gun, and none of its ticks`() {
        assertEquals(
            listOf("warning@300", "preparatory@240", "3 minutes@180", "2 minutes@120", "one_minute@60", "start"),
            reports(BuiltInSequences.usSailing),
        )
    }

    @Test
    fun `a race-manager variant is told the same signals its cues sound`() {
        assertEquals(
            listOf("warning@300", "preparatory@240", "one_minute@60", "start"),
            reports(BuiltInSequences.usSailingRaceManager),
        )
        assertEquals(
            listOf("warning@180", "preparatory@120", "1 long 3 short@90", "one_minute@60", "start"),
            reports(BuiltInSequences.scholasticRaceManager),
        )
    }

    @Test
    fun `3-2-1 sequences name the same three signals 5-4-1 does`() {
        assertEquals(
            listOf("warning@180", "preparatory@120", "1 long 3 short@90", "one_minute@60", "start"),
            reports(BuiltInSequences.scholastic),
        )
        assertEquals(listOf("warning@180", "preparatory@120", "one_minute@60", "start"), reports(BuiltInSequences.club))
    }

    @Test
    fun `a Custom race is named by position, down to one minute`() {
        assertEquals(listOf("warning@60", "start"), reports(BuiltInSequences.custom(1)))
        assertEquals(listOf("warning@120", "one_minute@60", "start"), reports(BuiltInSequences.custom(2)))
        assertEquals(
            listOf("warning@300", "preparatory@240", "1 long — 3:00@180", "1 long — 2:00@120", "one_minute@60", "start"),
            reports(BuiltInSequences.custom(5)),
        )
    }

    @Test
    fun `a lead-in adds nothing the companion is told`() {
        val base = BuiltInSequences.scholasticRaceManager
        val armed = withLeadIn(base, 60)!!
        assertTrue("the armed sequence has run-up cues of its own", armed.cues.size > base.cues.size)
        assertEquals(reports(base), reports(armed))
    }

    /**
     * The lead-in clause on its own. Every real run-up is SYNC and PROMPT ticks, which the voice
     * filter already drops, so the test above cannot tell the clause is there (#294's review): this
     * is a horn cue, at 2:30 (no named signal's position in Club), marked as run-up.
     */
    @Test
    fun `a run-up cue is never told, even one that sounds like a signal`() {
        val runUp = SequenceCue(offsetMs = 150_000L, signal = SignalPattern(longBlasts = 1, label = "run-up horn"), isLeadIn = true)
        val sequence = BuiltInSequences.club.copy(cues = listOf(runUp) + BuiltInSequences.club.cues)
        assertEquals(null, companionReportOf(sequence, runUp))
        assertEquals("the same horn, not run-up, is told", "run-up horn", (companionReportOf(sequence, runUp.copy(isLeadIn = false)) as CueReport.Signal).name)
    }

    /**
     * The rule over every sequence the console offers, so a sequence added to shared is held to it
     * without a line here: one warning, at the sequence's own first signal; one gun, last; nothing
     * below a minute but the gun.
     */
    @Test
    fun `every console sequence is told one warning first, one gun last, and nothing below a minute but the gun`() {
        val all = PhoneRaceRunner.CONSOLE_SEQUENCES + (1..10).map { BuiltInSequences.custom(it) }
        for (sequence in all) {
            val told = reports(sequence)
            assertEquals(sequence.id, "warning@${sequence.sequenceMs / 1_000}", told.first())
            assertEquals(sequence.id, "start", told.last())
            assertEquals(sequence.id, 1, told.count { it == "start" })
            assertEquals(sequence.id, 1, told.count { it.startsWith("warning@") })
            val belowAMinute = told.filter { it != "start" && it.substringAfterLast('@').toInt() < 60 }
            assertEquals(sequence.id, emptyList<String>(), belowAMinute)
        }
    }

    private val contractFields = setOf("v", "id", "kind", "at_ms", "sequence", "sequence_name", "signal", "seconds_to_start")

    @Test
    fun `each kind carries exactly its contract fields, and no fleet, class or race id`() {
        val start = JSONObject(CompanionEvent("e1", CompanionKind.SEQUENCE_START, 1_000L, "e1", sequenceName = "Club 3-2-1-Go").toJson())
        val signal = JSONObject(CompanionEvent("e2", CompanionKind.SIGNAL, 2_000L, "e1", signal = "warning", secondsToStart = 180).toJson())
        val gun = JSONObject(CompanionEvent("e3", CompanionKind.START, 3_000L, "e1").toJson())

        assertEquals(setOf("v", "id", "kind", "at_ms", "sequence", "sequence_name"), start.keys().asSequence().toSet())
        assertEquals(setOf("v", "id", "kind", "at_ms", "sequence", "signal", "seconds_to_start"), signal.keys().asSequence().toSet())
        assertEquals(setOf("v", "id", "kind", "at_ms", "sequence"), gun.keys().asSequence().toSet())
        for (event in listOf(start, signal, gun)) {
            assertTrue(contractFields.containsAll(event.keys().asSequence().toSet()))
            assertEquals(1, event.getInt("v"))
        }
        assertEquals("sequence_start", start.getString("kind"))
        assertEquals("signal", signal.getString("kind"))
        assertEquals("start", gun.getString("kind"))
        assertEquals(2_000L, signal.getLong("at_ms"))
        assertEquals(180, signal.getInt("seconds_to_start"))
    }

    @Test
    fun `a sequence name is escaped by the platform, never by hand`() {
        val name = "Quote \" backslash \\ dash —"
        val event = JSONObject(CompanionEvent("e1", CompanionKind.SEQUENCE_START, 1L, "e1", sequenceName = name).toJson())
        assertEquals(name, event.getString("sequence_name"))
    }
}
