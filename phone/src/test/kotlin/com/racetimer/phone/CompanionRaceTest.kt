package com.racetimer.phone

import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.SequenceCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Records what the runner told the companion link, on a log it can share with [RecordingSounder] and
 * [RecordingBuzzer], so "the link hears of a cue after it is buzzed and sounded" is one ordering read
 * off one list (#294).
 *
 * A cue is logged through [companionReportOf], the emitter's own filter, so the log shows what would
 * be sent, not every cue the runner passed on.
 */
internal class RecordingLink(val events: MutableList<String> = mutableListOf()) : CompanionLink {
    override fun sequenceStarted(sequence: RaceSequence) {
        events += "link:started:${sequence.id}"
    }

    override fun sequenceResumed() {
        events += "link:resumed"
    }

    override fun cueFired(sequence: RaceSequence, cue: SequenceCue) {
        when (val report = companionReportOf(sequence, cue)) {
            is CueReport.Signal -> events += "link:${report.name}"
            CueReport.Start -> events += "link:start"
            null -> Unit
        }
    }

    override fun sequenceEnded() {
        events += "link:ended"
    }

    val link: List<String> get() = events.filter { it.startsWith("link:") }
}

/**
 * The runner's half of the companion link (#294): which race events reach the link, in what order,
 * and where they sit on the cue path.
 */
class CompanionRaceTest {

    private val clock = SteppedClock()

    private fun runner(link: CompanionLink, events: MutableList<String> = mutableListOf()) = PhoneRaceRunner(
        clock = clock,
        cueSounder = RecordingSounder(events),
        cueBuzzer = RecordingBuzzer(events),
        companionLink = link,
    )

    /** Run the selected sequence from Start to its gun, ticking on every cue's own boundary. */
    private fun runToTheGun(runner: PhoneRaceRunner, sequence: RaceSequence) {
        runner.start()
        sequence.cues.map { sequence.totalMs - it.offsetMs }.distinct().sorted().forEach { at ->
            clock.nowMs = at
            runner.tick()
        }
    }

    @Test
    fun `a race from Start to the gun tells the link its start, its signals and its gun, in firing order`() {
        val link = RecordingLink()
        val runner = runner(link)
        runner.select(BuiltInSequences.usSailing)
        runToTheGun(runner, BuiltInSequences.usSailing)

        assertEquals(
            listOf(
                "link:started:us_sailing_5_4_1",
                "link:warning", "link:preparatory", "link:3 minutes", "link:2 minutes", "link:one_minute",
                "link:start",
            ),
            link.link,
        )
    }

    @Test
    fun `the link hears of a cue only after it has been buzzed and sounded`() {
        val events = mutableListOf<String>()
        val runner = runner(RecordingLink(events), events)
        runner.select(BuiltInSequences.club)
        runToTheGun(runner, BuiltInSequences.club)

        for ((label, told) in listOf("Warning" to "link:warning", "Start" to "link:start")) {
            val buzz = events.indexOf("buzz:$label")
            val play = events.indexOf("play:$label")
            val link = events.indexOf(told)
            assertTrue("$label: buzz $buzz, play $play, link $link in $events", buzz in 0 until play && play < link)
        }
        assertTrue(
            "the sequence's start reaches the link before its first signal",
            events.indexOf("link:started:club_3_2_1") < events.indexOf("link:warning"),
        )
    }

    @Test
    fun `every way a race ends tells the link it is over`() {
        val stopped = RecordingLink()
        runner(stopped).apply { select(BuiltInSequences.club); start(); stop() }
        assertEquals("link:ended", stopped.link.last())

        val abandoned = RecordingLink()
        runner(abandoned).apply { select(BuiltInSequences.club); start(); endRaceAndSelect(BuiltInSequences.scholastic) }
        assertEquals("link:ended", abandoned.link.last())

        val ended = RecordingLink()
        val counting = runner(ended)
        counting.select(BuiltInSequences.scholasticRaceManager)
        runToTheGun(counting, BuiltInSequences.scholasticRaceManager)
        counting.endRace()
        assertEquals(listOf("link:start", "link:ended"), ended.link.takeLast(2))

        val released = RecordingLink()
        runner(released).apply { select(BuiltInSequences.club); start(); release() }
        assertEquals("link:ended", released.link.last())
    }

    @Test
    fun `a race joined from the watch is a sequence this phone starts`() {
        val link = RecordingLink()
        val runner = runner(link)
        val club = BuiltInSequences.club
        runner.join(club, gunMs = club.totalMs, lateCueGraceMs = 0L)

        assertEquals(listOf("link:started:club_3_2_1", "link:warning"), link.link)
    }

    @Test
    fun `a restored race resumes its sequence rather than starting another`() {
        val first = runner(RecordingLink())
        first.select(BuiltInSequences.club)
        first.start()
        val snapshot = first.engine.snapshot()!!

        val link = RecordingLink()
        val restored = runner(link)
        clock.nowMs = 30_000L
        restored.restore(BuiltInSequences.club, snapshot)

        assertEquals(listOf("link:resumed"), link.link)
    }
}
