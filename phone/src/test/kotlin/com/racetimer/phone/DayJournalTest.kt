package com.racetimer.phone

import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.MonotonicClock
import com.racetimer.shared.SignalPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/** A clock the test moves by hand. Its own name — a file-private class still collides in-package. */
private class JournalClock(var nowMs: Long = 0L) : MonotonicClock {
    override fun elapsedMs(): Long = nowMs
}

/** Keeps every line a journal wrote, so an assertion reads the record rather than a side effect. */
private class RecordingSink : JournalSink {
    val lines = mutableListOf<String>()
    override fun append(lines: List<String>) {
        this.lines += lines
    }
}

/** The scheduler shape [HandlerCueScheduler] has, fired by the test rather than by a looper. */
private class JournalScheduler : CueScheduler {
    var armedDelayMs: Long? = null
    private var armedAction: Runnable? = null

    override fun armIn(delayMs: Long, action: Runnable) {
        armedDelayMs = delayMs
        armedAction = action
    }

    override fun cancel() {
        armedDelayMs = null
        armedAction = null
    }

    val isArmed: Boolean get() = armedAction != null

    fun fire() {
        val action = armedAction ?: throw AssertionError("nothing armed")
        cancel()
        action.run()
    }
}

/** A silent sounder — the cue path is not this file's subject and must not be reached. */
private class QuietSounder : CueSounder {
    override fun prepare() {}
    override fun warmUp(patterns: List<SignalPattern>) {}
    override fun playCue(pattern: SignalPattern) {}
    override fun release() {}
}

/**
 * The start-day recorder (#216): its format, its arming gate, and what a race actually writes.
 *
 * Off the JVM entirely — no Robolectric, no device, no filesystem. `DayJournal`'s Android half (the
 * battery broadcast, the file sink, `Log.isLoggable`) is deliberately thin for exactly that reason:
 * everything with a decision in it is a pure function or takes its collaborators as parameters.
 *
 * What these tests are *for*: the journal gets one shot at a day that cannot be re-run. A format
 * bug, a dropped field or a cue record that never fires is not something to find afterwards.
 */
class DayJournalTest {

    private val sink = RecordingSink()

    private fun armedJournal(wallMs: Long = 1_755_000_000_000L, uptime: () -> Long = { 0L }) =
        DayJournal(
            armed = true,
            sink = sink,
            wallClockMs = { wallMs },
            uptimeMs = uptime,
            // Inline, so a test never has to wait on the background writer. Production's own
            // executor is a daemon thread; what is asserted here is what gets handed to it.
            executor = Executor(Runnable::run),
        )

    @Test
    fun `a record carries both clocks, the kind, and its fields in order`() {
        armedJournal(wallMs = 1_755_000_000_000L, uptime = { 4_242L }).apply {
            record("cue", "offsetMs" to 180_000L, "gun" to 0)
            flush()
        }
        assertEquals(
            listOf("2025-08-12T12:00:00Z 4242 cue offsetMs=180000 gun=0"),
            sink.lines,
        )
    }

    /**
     * A null field is absent, never the word "null".
     *
     * The version name of a package the manager could not read is genuinely unknown, and a record
     * saying `versionName=null` reads as a fact about the build rather than about the reading.
     */
    @Test
    fun `a null field is dropped rather than written`() {
        armedJournal().apply {
            record("session", "model" to "SM-S918U", "versionName" to null, "sdk" to 36)
            flush()
        }
        assertEquals(1, sink.lines.size)
        assertFalse("a null reached the record: ${sink.lines[0]}", sink.lines[0].contains("null"))
        assertTrue(sink.lines[0].endsWith("session model=SM-S918U sdk=36"))
    }

    /**
     * Values are made safe for a space-delimited record at the writer, not at the call sites.
     *
     * Cue labels contain spaces — "3 long" is a real one — so this is exercised by every race, not
     * by a hypothetical. Doing it here is what stops a new record kind quietly emitting a line the
     * parser splits in the wrong place.
     */
    @Test
    fun `values are collapsed so a space or an equals cannot break a record`() {
        assertEquals("3_long", sanitiseJournalValue("3 long"))
        assertEquals("1_long_3_short", sanitiseJournalValue("1 long 3 short"))
        assertEquals("a:b", sanitiseJournalValue("a=b"))
        assertEquals("_", sanitiseJournalValue(""))
        // Whitespace-only is not empty, and each character is replaced rather than the run being
        // collapsed. Asserted at its measured value rather than at the tidier one it was first
        // written with: the rule is per-character and total, and a collapsing rule would be a
        // second thing to get right for a case no real value produces.
        assertEquals("___", sanitiseJournalValue("   "))
    }

    /**
     * An unarmed journal is silent, and that is the state every shipped build is in.
     *
     * The instrument compiles into release on purpose (see `DayJournal`'s class doc), so "does
     * nothing unless armed" is a property of the product rather than of the test harness.
     */
    @Test
    fun `an unarmed journal does no work at all, not merely no writing`() {
        // The clocks are counted, and that is the point rather than a flourish. `record` and
        // `flush` each carry their own `armed` check, so an assertion on `sink.lines` alone passes
        // with EITHER guard present — measured: deleting the one in `record` reddened 0 of 141.
        // The two are not interchangeable. `flush`'s guard stops a write; `record`'s stops the
        // formatting, and that is the one the cue path depends on, because a shipped unarmed build
        // must not build a string per cue for a file nobody is writing. Reading a clock is the
        // observable proof that the formatting ran, so counting the reads asserts the mechanism
        // instead of the outcome the other guard also produces.
        var clockReads = 0
        val journal = DayJournal(
            armed = false,
            sink = sink,
            wallClockMs = { clockReads++; 0L },
            uptimeMs = { clockReads++; 0L },
            executor = Executor(Runnable::run),
        )
        repeat(50) { journal.record("cue", "offsetMs" to it) }
        journal.flush()
        assertEquals(emptyList<String>(), sink.lines)
        assertEquals("an unarmed journal formatted a record", 0, clockReads)
        assertFalse(journal.isArmed)
        assertFalse(DayJournal.OFF.isArmed)
    }

    /**
     * Records queue and reach the sink only on a flush — the property the cue path depends on.
     *
     * A synchronous write at the moment of a cue would be an instrument competing with the one
     * deadline this app has. If this assertion ever inverts, that guarantee has gone.
     */
    @Test
    fun `a record is buffered until something flushes it`() {
        val journal = armedJournal()
        journal.record("cue", "offsetMs" to 0)
        assertEquals("a record reached the sink without a flush", emptyList<String>(), sink.lines)
        journal.flush()
        assertEquals(1, sink.lines.size)
        // A second flush with nothing queued must not re-write what was already written.
        journal.flush()
        assertEquals(1, sink.lines.size)
    }

    // -----------------------------------------------------------------------
    // What a race actually writes — the wiring, not the formatter
    // -----------------------------------------------------------------------

    private fun raceJournal(clock: JournalClock) = DayJournal(
        armed = true,
        sink = sink,
        wallClockMs = { 1_755_000_000_000L },
        uptimeMs = { clock.nowMs },
        executor = Executor(Runnable::run),
    )

    private fun kinds() = sink.lines.map { it.split(" ")[2] }

    private fun fieldsOf(line: String): Map<String, String> =
        line.split(" ").drop(3).associate { it.split("=", limit = 2).let { p -> p[0] to p[1] } }

    @Test
    fun `a whole race writes its schedule, every cue, and its end`() {
        val clock = JournalClock()
        val scheduler = JournalScheduler()
        val journal = raceJournal(clock)
        val runner = PhoneRaceRunner(clock, QuietSounder(), scheduler, journal)

        runner.select(BuiltInSequences.scholasticRaceManager)
        runner.start()
        var guard = 1_000
        while (scheduler.isArmed) {
            assertTrue("dispatch loop did not converge", guard-- > 0)
            clock.nowMs += scheduler.armedDelayMs!!
            scheduler.fire()
        }
        clock.nowMs += 600_000L
        runner.endRace()

        val expected = BuiltInSequences.scholasticRaceManager.cues.map { it.offsetMs }.sortedDescending()

        // The schedule is written at start, so "zero missed cues" is judged against what THIS race
        // was going to fire rather than against whatever the sequence says later.
        val start = sink.lines.single { it.contains(" race_start ") }
        assertEquals(
            expected.joinToString(separator = ":"),
            fieldsOf(start)["schedule"],
        )

        // Every cue in the schedule produced a record, and nothing else did.
        val fired = sink.lines.filter { it.contains(" cue ") }.map { fieldsOf(it)["offsetMs"]!!.toLong() }
        assertEquals(expected, fired.sortedDescending())

        assertTrue("the gun is marked", sink.lines.any { it.contains(" cue ") && fieldsOf(it)["gun"] == "1" })
        assertTrue("the race end is recorded", kinds().contains("race_end"))
        assertEquals("600000", fieldsOf(sink.lines.last { it.contains(" race_end ") })["elapsedMs"])
    }

    /**
     * Lateness is measured, and a cue that fired on its boundary reads zero.
     *
     * Both halves matter. A `lateMs` that were always zero would be a field with no instrument
     * behind it, and this repo has shipped one of those before (#58: every cue up to ±212 ms out
     * while the number on screen was never wrong).
     */
    @Test
    fun `cue lateness reports the delay the dispatch actually took`() {
        val clock = JournalClock()
        val scheduler = JournalScheduler()
        val journal = raceJournal(clock)
        val runner = PhoneRaceRunner(clock, QuietSounder(), scheduler, journal)

        runner.select(BuiltInSequences.scholastic)
        runner.start()
        // The flush is explicit because records are buffered until one happens — which is the
        // property the test above asserts, and it means a cue is not on the record the instant it
        // fires. In a real day the flush arrives with the next battery sample or at End Race.
        journal.flush()
        // The first cue fires synchronously inside start(), on its own boundary.
        assertEquals("0", fieldsOf(sink.lines.first { it.contains(" cue ") })["lateMs"])

        // Now overshoot the next boundary by 250 ms before letting the dispatch run.
        clock.nowMs += scheduler.armedDelayMs!! + 250L
        scheduler.fire()
        journal.flush()
        val second = sink.lines.filter { it.contains(" cue ") }[1]
        assertEquals("250", fieldsOf(second)["lateMs"])
    }

    // -----------------------------------------------------------------------
    // The boundary that keeps a diagnostic from becoming app state
    // -----------------------------------------------------------------------

    /**
     * Nothing in the phone module reads the journal back.
     *
     * `ModuleBoundaryTest`'s persistence guard names `SharedPreferences` and `DataStore` and is
     * deliberately unchanged by #216, because the journal is not app state: it is written, pulled
     * off the device by a human, and never consulted by the running app. That claim is only worth
     * the paragraph if something holds it — a diagnostic that quietly acquires a reader has
     * acquired a failure mode, and it would acquire it in a file nobody re-reads.
     *
     * Lives here rather than in `ModuleBoundaryTest` because it is #216's rule and this is #216's
     * test; it uses the same source-scan shape and the same comment-blindness caveat, and it sits
     * outside every directory it reads for the reason that file's own docstring gives.
     */
    @Test
    fun `the journal is written and never read back`() {
        val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find the repo root from ${File("").absolutePath}")
        val sources = File(repoRoot, "phone/src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        // A scan of nothing passes silently — locate the sources before believing the result.
        assertTrue("no phone sources were scanned", sources.size >= 7)

        val readers = listOf("readText", "readLines", "readBytes", "bufferedReader", "useLines", "forEachLine")
        val offenders = sources.flatMap { file ->
            file.readLines().withIndex()
                .filterNot { (_, line) -> line.trimStart().let {
                    it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
                } }
                .filter { (_, line) -> readers.any { line.contains(it) } }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertEquals(
            "Something under :phone reads a file back. The start-day journal (#216) is write-only " +
                "by design — that is what makes it a diagnostic rather than a second persistent " +
                "store beside PhoneRacePersistence, and it is the reason ModuleBoundaryTest's " +
                "persistence guard did not have to be widened to admit it.",
            emptyList<String>(),
            offenders,
        )
    }
}
