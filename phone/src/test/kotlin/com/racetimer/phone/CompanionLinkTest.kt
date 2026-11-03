package com.racetimer.phone

import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.WallClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executor

/**
 * The link's worker, run by hand. Tasks run only when the test says, in the order they were posted,
 * on a clock the test moves, so a backoff or a linger is a number of milliseconds rather than a wait.
 *
 * [posts] counts what the CALLER posted. It is how "a cue the companion is not told about costs no
 * post" is asserted as a count rather than read off the code.
 */
internal class ManualWorker : LinkWorker {
    var now = 0L
    var posts = 0
    private val tasks = mutableListOf<Pair<Long, Runnable>>()

    override val executor: Executor = Executor { tasks += now to it }
    override fun post(task: Runnable) {
        posts++
        tasks += now to task
    }
    override fun postDelayed(task: Runnable, delayMs: Long) {
        tasks += (now + delayMs) to task
    }
    override fun cancel(task: Runnable) {
        tasks.removeAll { it.second === task }
    }
    override fun nowMs(): Long = now

    /** Run everything due by now, and whatever running it makes due, oldest first. */
    fun runDue() {
        while (true) {
            val next = tasks.filter { it.first <= now }.minByOrNull { it.first } ?: return
            tasks.remove(next)
            next.second.run()
        }
    }

    fun advance(ms: Long) {
        now += ms
        runDue()
    }

    val waiting: Int get() = tasks.size
}

/**
 * The companion, as the link sees it. Its connection callbacks arrive through the worker's executor,
 * as the real transport's do, never inside the call that asked for them. [answers] scripts what each
 * send gets back, and an empty script answers `accepted`.
 *
 * [sent] keeps every JSON sent, resends included, so a resend under the same id is something a test
 * can see rather than assume.
 */
internal class FakeTransport(private val worker: ManualWorker, var installed: Boolean = true) : CompanionTransport {
    val sent = mutableListOf<String>()
    val answers = ArrayDeque<SendResult>()
    var binds = 0
    var unbinds = 0
    var bound = false
    var bindSucceeds = true
    private var connected = false
    private var listener: CompanionTransport.Listener? = null

    override fun trusted(): Boolean = installed

    override fun bind(listener: CompanionTransport.Listener): Boolean {
        binds++
        if (!bindSucceeds) return false
        this.listener = listener
        bound = true
        worker.executor.execute {
            if (bound) {
                connected = true
                listener.onConnected()
            }
        }
        return true
    }

    override fun send(json: String): SendResult {
        if (!connected) return SendResult.NotConnected
        sent += json
        val answer = answers.removeFirstOrNull() ?: SendResult.Answered(CompanionContract.ACCEPTED)
        if (answer == SendResult.Dead) connected = false
        return answer
    }

    override fun unbind() {
        unbinds++
        bound = false
        connected = false
    }

    fun kinds(): List<String> = sent.map { JSONObject(it).getString("kind") }
    fun ids(): List<String> = sent.map { JSONObject(it).getString("id") }
}

/** A wall clock that ticks one millisecond per read, so every event's time is its own. */
internal class CountingWallClock(private var nowMs: Long = 1_700_000_000_000L) : WallClock {
    override fun nowMs(): Long = nowMs++
}

@RunWith(RobolectricTestRunner::class)
class CompanionLinkTest {

    @get:Rule
    val dir = TemporaryFolder()

    private lateinit var file: File
    private lateinit var worker: ManualWorker
    private lateinit var transport: FakeTransport
    private var nextId = 0

    @Before
    fun setUp() {
        file = File(dir.root, CompanionOutbox.FILE_NAME)
        worker = ManualWorker()
        transport = FakeTransport(worker)
    }

    /** The outbox the last link opened, so a test can count its writes. */
    private var opened: CompanionOutbox? = null

    private fun link(
        t: CompanionTransport = transport,
        w: ManualWorker = worker,
        clock: WallClock = CountingWallClock(),
    ) = CompanionLinkEmitter(
        transport = t,
        openOutbox = { CompanionOutbox(file).also { opened = it } },
        worker = w,
        wallClock = clock,
        newId = { "id-${nextId++}" },
    )

    /** Every cue of [sequence], fired in order into [link], the gun last. */
    private fun fireAll(link: CompanionLink, sequence: RaceSequence) {
        sequence.cues.sortedByDescending { it.offsetMs }.forEach { link.cueFired(sequence, it) }
    }

    private val club = BuiltInSequences.club

    @Test
    fun `a sequence is sent as its start, its signals and its gun, in order, each under one sequence id`() {
        val link = link()
        link.sequenceStarted(club)
        fireAll(link, club)
        worker.runDue()

        assertEquals(listOf("sequence_start", "signal", "signal", "signal", "start"), transport.kinds())
        val events = transport.sent.map(::JSONObject)
        val sequenceId = events.first().getString("sequence")
        assertEquals("a sequence_start names itself", events.first().getString("id"), sequenceId)
        assertTrue(events.all { it.getString("sequence") == sequenceId })
        assertEquals(events.size, transport.ids().toSet().size)
        assertEquals("Club 3-2-1-Go", events.first().getString("sequence_name"))
        assertEquals(listOf("warning", "preparatory", "one_minute"), events.filter { it.has("signal") }.map { it.getString("signal") })
        assertEquals(
            "each event's time is when it fired, in firing order",
            events.map { it.getLong("at_ms") }.sorted(),
            events.map { it.getLong("at_ms") },
        )
        assertTrue("everything taken leaves the outbox", CompanionOutbox(file).isEmpty)
    }

    @Test
    fun `the cue path only stamps and posts, and a cue the companion is not told about posts nothing`() {
        val link = link()
        link.sequenceStarted(club)
        fireAll(link, club)

        assertEquals("nothing reached the companion on the caller's thread", 0, transport.sent.size + transport.binds)
        // One post for the start, and one per cue the companion is told about: three signals and the
        // gun. Club also sounds its final five, which cost nothing.
        assertEquals(1 + 4, worker.posts)
        assertTrue(club.cues.size > 4)
    }

    @Test
    fun `with no companion installed nothing is bound, nothing is sent and nothing is written`() {
        transport.installed = false
        val link = link()
        link.sequenceStarted(club)
        fireAll(link, club)
        link.sequenceEnded()
        worker.advance(CompanionLinkEmitter.LINGER_MS * 2)

        assertEquals(0, transport.binds)
        assertEquals(0, transport.sent.size)
        assertFalse("no outbox file on a phone without the companion", file.exists())
        // A count, not only the file: a write that the forgetting deletes at once leaves no file to
        // find, and the absent phone must not write at all (#294's review, finding 4).
        assertEquals("not written even for a moment", 0, opened?.writes ?: 0)
    }

    @Test
    fun `each event's time is the caller's clock at the call, never the worker's at the send`() {
        val clock = object : WallClock {
            var nowMs = 0L
            override fun nowMs(): Long = nowMs
        }
        val link = link(clock = clock)
        clock.nowMs = 1_000L
        link.sequenceStarted(club)
        club.cues.sortedByDescending { it.offsetMs }.forEachIndexed { i, cue ->
            clock.nowMs = 10_000L + i * 1_000L
            link.cueFired(club, cue)
        }
        // What a clock read on the worker would see, long after every call.
        clock.nowMs = 999_999L
        worker.runDue()

        // Club fires its warning, preparatory and one-minute as cues 0, 1 and 2, then the final
        // five (3 to 7, not sent), then the gun as cue 8.
        assertEquals(
            listOf(1_000L, 10_000L, 11_000L, 12_000L, 18_000L),
            transport.sent.map { JSONObject(it).getLong("at_ms") },
        )
    }

    @Test
    fun `a refused bind is retried up the ladder through the linger, then left for the next sequence`() {
        transport.bindSucceeds = false
        val link = link()
        link.sequenceStarted(club)
        fireAll(link, club)
        worker.runDue()
        val bindsByTheGun = transport.binds

        var lastTryAt = 0L
        var binds = bindsByTheGun
        repeat(((CompanionLinkEmitter.LINGER_MS + 60_000L) / 1_000L).toInt()) {
            worker.advance(1_000L)
            if (transport.binds > binds) {
                binds = transport.binds
                lastTryAt = worker.now
            }
        }

        assertTrue("tried again after the gun, more than once", transport.binds > bindsByTheGun + 1)
        assertTrue("and not past the linger and one wait", lastTryAt <= CompanionLinkEmitter.LINGER_MS + CompanionLinkEmitter.BACKOFF_MS.last())
        assertEquals("then stopped", 0, worker.waiting)
        assertEquals(5, CompanionOutbox(file).pending.size)
    }

    @Test
    fun `a bind refused past the gun's first retry and accepted later is delivered within the linger`() {
        transport.bindSucceeds = false
        val link = link()
        link.sequenceStarted(club)
        fireAll(link, club)
        worker.runDue()
        // Still refused at the one retry the gun's finish() schedules by itself, so only the ladder
        // can reach the bind that is accepted later. Accepted at that first retry, the finish()
        // retry alone delivered it, and the test passed without the fix it exists for (#294's
        // mutation N3: predicted 2 red, measured 1).
        val bindsByTheGun = transport.binds
        var waited = 0
        while (transport.binds == bindsByTheGun && waited++ < 60) worker.advance(1_000L)
        assertTrue("the gun's own retry tried the bind", transport.binds > bindsByTheGun)
        transport.bindSucceeds = true

        repeat(120) { worker.advance(1_000L) }

        assertEquals(5, transport.sent.size)
        assertTrue(CompanionOutbox(file).isEmpty)
        assertFalse("let go once delivered", transport.bound)
    }

    @Test
    fun `a companion that stops being trusted loses the backlog it can no longer be sent`() {
        transport.answers.addAll(List(5) { SendResult.Answered(CompanionContract.NO_CORE) })
        val link = link()
        link.sequenceStarted(club)
        worker.runDue()
        assertFalse(CompanionOutbox(file).isEmpty)

        transport.installed = false
        link.sequenceStarted(club)
        worker.runDue()
        assertTrue(CompanionOutbox(file).isEmpty)
        assertFalse(transport.bound)
    }

    @Test
    fun `no_core keeps the event and resends it under the same id until it is accepted`() {
        transport.answers.addAll(listOf(SendResult.Answered(CompanionContract.NO_CORE), SendResult.Answered(CompanionContract.NO_CORE)))
        val link = link()
        link.sequenceStarted(club)
        worker.runDue()
        assertEquals(1, transport.sent.size)

        worker.advance(CompanionLinkEmitter.BACKOFF_MS[0])
        worker.advance(CompanionLinkEmitter.BACKOFF_MS[1])

        assertEquals(3, transport.sent.size)
        assertEquals("one event, sent three times", 1, transport.ids().toSet().size)
        assertEquals("the same bytes every time", 1, transport.sent.toSet().size)
        assertTrue(CompanionOutbox(file).isEmpty)
    }

    @Test
    fun `a status the contract does not know is kept and resent like no_core`() {
        transport.answers.addAll(listOf(SendResult.Answered("from_the_future"), SendResult.Answered(null)))
        val link = link()
        link.sequenceStarted(club)
        worker.runDue()
        worker.advance(CompanionLinkEmitter.BACKOFF_MS[0])
        worker.advance(CompanionLinkEmitter.BACKOFF_MS[1])

        assertEquals(3, transport.sent.size)
        assertEquals(1, transport.ids().toSet().size)
    }

    @Test
    fun `refused and malformed are let go, because a resend cannot change them`() {
        transport.answers.addAll(listOf(SendResult.Answered(CompanionContract.REFUSED), SendResult.Answered(CompanionContract.MALFORMED)))
        val link = link()
        link.sequenceStarted(club)
        link.cueFired(club, club.cues.first())
        worker.runDue()
        worker.advance(CompanionLinkEmitter.BACKOFF_MS.last() * 4)

        assertEquals(2, transport.sent.size)
        assertEquals(2, transport.ids().toSet().size)
        assertTrue(CompanionOutbox(file).isEmpty)
    }

    @Test
    fun `a dead binder is let go and bound again, and the event goes under its own id`() {
        transport.answers.add(SendResult.Dead)
        val link = link()
        link.sequenceStarted(club)
        worker.runDue()
        assertEquals(1, transport.unbinds)

        worker.advance(CompanionLinkEmitter.BACKOFF_MS[0])

        assertEquals(2, transport.binds)
        assertEquals(2, transport.sent.size)
        assertEquals(1, transport.ids().toSet().size)
        assertTrue(CompanionOutbox(file).isEmpty)
    }

    @Test
    fun `after the gun the link lets go once everything is taken`() {
        val link = link()
        link.sequenceStarted(club)
        fireAll(link, club)
        worker.runDue()

        assertFalse(transport.bound)
        assertEquals("nothing left to retry", 0, worker.waiting)
    }

    @Test
    fun `events the companion never takes are resent for the linger, then left for the next sequence`() {
        transport.answers.addAll(List(1_000) { SendResult.Answered(CompanionContract.NO_CORE) })
        val link = link()
        link.sequenceStarted(club)
        fireAll(link, club)
        worker.runDue()
        assertTrue("still bound after the gun, resending", transport.bound)

        // A second at a time, so each resend runs at its own moment rather than all at the end.
        var lastBoundAt = 0L
        repeat(((CompanionLinkEmitter.LINGER_MS + 60_000L) / 1_000L).toInt()) {
            worker.advance(1_000L)
            if (transport.bound) lastBoundAt = worker.now
        }

        assertTrue("resent during the linger, oldest first", transport.sent.size > 5)
        assertEquals(1, transport.ids().toSet().size)
        // The last resend inside the linger schedules one more wait, which is when the link sees
        // the linger is over: so it holds for the linger plus at most one backoff.
        val longestWait = CompanionLinkEmitter.BACKOFF_MS.last()
        assertTrue("held no longer than the linger and one wait", lastBoundAt <= CompanionLinkEmitter.LINGER_MS + longestWait)
        assertTrue("held to the linger's end", lastBoundAt >= CompanionLinkEmitter.LINGER_MS)
        assertFalse("let go after the linger", transport.bound)
        assertEquals(0, worker.waiting)
        assertEquals("all five wait in the file", 5, CompanionOutbox(file).pending.size)
    }

    @Test
    fun `a backlog outlives the process and goes first, under its own ids, at the next sequence`() {
        transport.answers.addAll(List(1_000) { SendResult.Answered(CompanionContract.NO_CORE) })
        val first = link()
        first.sequenceStarted(club)
        fireAll(first, club)
        worker.runDue()
        val waiting = CompanionOutbox(file).pending
        assertEquals(5, waiting.size)

        // A new process: a new link on the same file, and a companion that answers again.
        val worker2 = ManualWorker()
        val transport2 = FakeTransport(worker2)
        val second = link(transport2, worker2)
        second.sequenceStarted(club)
        worker2.runDue()

        assertEquals("the backlog first, byte for byte", waiting, transport2.sent.take(5))
        assertEquals("then the new sequence's start", "sequence_start", transport2.kinds()[5])
        assertTrue(CompanionOutbox(file).isEmpty)
    }

    @Test
    fun `a resumed race carries on under the sequence id it was saved with`() {
        val first = link()
        first.sequenceStarted(club)
        worker.runDue()
        val sequenceId = JSONObject(transport.sent.single()).getString("sequence")

        val worker2 = ManualWorker()
        val transport2 = FakeTransport(worker2)
        val second = link(transport2, worker2)
        second.sequenceResumed()
        second.cueFired(club, club.cues.first())
        worker2.runDue()

        assertEquals(listOf("signal"), transport2.kinds())
        assertEquals(sequenceId, JSONObject(transport2.sent.single()).getString("sequence"))
    }

    @Test
    fun `a binding that never comes keeps every event for the next bind`() {
        transport.bindSucceeds = false
        val link = link()
        link.sequenceStarted(club)
        fireAll(link, club)
        worker.runDue()

        assertEquals(0, transport.sent.size)
        assertEquals(5, CompanionOutbox(file).pending.size)
    }
}
