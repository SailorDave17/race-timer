package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue
import kotlin.math.abs

/**
 * Start on either device (#220), driven end to end in virtual time: two devices, each with its own
 * [PairLink], [PairRaceBook], [PairStarts] and a real [TimerEngine] on its own clock, joined by a
 * simulated Data Layer.
 *
 * Every verdict is judged where it matters — **the physical instant each device's gun fires** — and
 * not by comparing numbers the code under test computed. The clocks are booted at unrelated instants
 * and the watch runs 10 ppm slow (#218's pair), so a device that anchored the gun on the wrong clock,
 * or translated it the wrong way round, fires hours away from the other rather than a little off.
 */
class PairRaceTest {

    // --- the world --------------------------------------------------------------------------------

    /** Virtual physical time, in whole milliseconds, and everything scheduled against it. */
    private class World {
        var now = 0L
            private set
        private var seq = 0L
        private val queue = PriorityQueue<Triple<Long, Long, () -> Unit>>(
            compareBy<Triple<Long, Long, () -> Unit>>({ it.first }, { it.second }),
        )

        fun at(delayMs: Long, task: () -> Unit): () -> Unit {
            var cancelled = false
            queue.add(Triple(now + delayMs, seq++, { if (!cancelled) task() }))
            return { cancelled = true }
        }

        fun advance(byMs: Long) {
            val until = now + byMs
            while (queue.isNotEmpty() && queue.peek().first <= until) {
                val (at, _, task) = queue.poll()
                now = at
                task()
            }
            now = until
        }
    }

    /** A device's `elapsedRealtime`: [bootReading] at physical zero, running [fastPpm] fast, floored. */
    private class Device(val id: String, private val world: World, private val bootReading: Long, val fastPpm: Long) : MonotonicClock {
        override fun elapsedMs(): Long = exactAt(world.now).toLong()
        fun exactAt(physical: Long): Double = bootReading + physical + physical * fastPpm / 1_000_000.0
    }

    private val world = World()

    /** Messages in flight, per direction, and whether the link carries anything at all. */
    private var phoneToWatchMs = 60L
    private var watchToPhoneMs = 90L
    private var severed = false
    private var nearby = true
    /** False when the other device does not run the app: a query then finds nobody. */
    private var present = true
    private var dropNext: (from: String, text: String) -> Boolean = { _, _ -> false }
    private val sent = mutableListOf<Pair<String, String>>()

    /**
     * One device: its link, its book of races, its start coordinator, and the engine a race runs on
     * — driven the way `PhoneRaceRunner` drives one, with every cue armed on its own boundary.
     */
    private inner class Node(
        val device: Device,
        wallSkewMs: Long,
        console: Boolean,
        firstRoundId: Long,
        raceIds: Iterator<Long>,
    ) : PairRaceHost {
        // The wall clock reaches only the engine's persistence. Nothing the pair orders reads it —
        // which the crossing test at the owner's measured skew holds to.
        val wall = WallClock { WALL_EPOCH + world.now + wallSkewMs }
        val engine = TimerEngine(device, wall)
        val joins = mutableListOf<PairJoin>()
        val contests = mutableListOf<PairContest?>()
        val cuesAt = mutableListOf<Pair<Long, Long>>() // offset, physical time
        var gunAt: Long? = null
        private var cancelCue: (() -> Unit)? = null
        lateinit var peer: Node
        val book = PairRaceBook(newRaceId = { raceIds.next() })
        val link: PairLink = PairLink(
            clock = device,
            transport = object : PairTransport {
                override fun send(peerId: String, payload: ByteArray, onFailed: () -> Unit) {
                    val text = String(payload, Charsets.UTF_8)
                    sent += device.id to text
                    if (severed || peerId != peer.device.id || dropNext(device.id, text)) return
                    val delay = if (device === phone) phoneToWatchMs else watchToPhoneMs
                    val target = peer
                    world.at(delay) { if (!severed) target.link.onMessage(device.id, payload, target.device.elapsedMs()) }
                }

                override fun queryPeers() {
                    world.at(0L) { answerQuery() }
                }
            },
            scheduler = PairScheduler { delayMs, task -> world.at(delayMs, task) },
            onPeerStart = { starts.onPeerStart(it) },
            firstRoundId = firstRoundId,
        )
        val starts = PairStarts(book, link, this, device, console, onContest = { contests += it })

        private fun answerQuery() {
            link.onPeers(if (present) listOf(PeerNode(peer.device.id, nearby)) else emptyList())
        }

        init {
            engine.addListener(object : TimerListener {
                override fun onCue(cue: SequenceCue) {
                    cuesAt += cue.offsetMs to world.now
                }

                override fun onGun() {
                    gunAt = world.now
                }

                override fun onTick(remainingMs: Long) {}
                override fun onSync(snappedToMs: Long) {}
            })
        }

        /** The officer taps Start: the race runs first, and only then is the peer told. */
        fun tapStart(sequence: RaceSequence) {
            engine.load(sequence)
            engine.start()
            engine.tick()
            armCues()
            starts.startedHere(sequence.id, gun())
        }

        fun stop() {
            engine.stop()
            cancelCue?.invoke()
            starts.ended()
        }

        fun gun(): Long = engine.snapshot()?.gunElapsedMs ?: throw AssertionError("${device.id} is running nothing")

        override fun raceRunning(): Boolean =
            engine.currentState == TimerState.RUNNING || engine.currentState == TimerState.COUNTING_UP

        override fun join(join: PairJoin): Boolean {
            joins += join
            if (engine.join(join.sequence, join.race.gun.gunMs, join.lateCueGraceMs) == JoinOutcome.EXPIRED) return false
            engine.tick()
            armCues()
            return true
        }

        private fun armCues() {
            cancelCue?.invoke()
            val dueMs = engine.msUntilNextCue() ?: return
            cancelCue = world.at(maxOf(dueMs, 1L)) {
                engine.tick()
                armCues()
            }
        }
    }

    // A phone up for ten hours and a watch up for three, the watch 10 ppm slow: #218's pair.
    private val phone = Device("phone-node", world, bootReading = 36_000_000L, fastPpm = 0L)
    private val watch = Device("watch-node", world, bootReading = 10_800_000L, fastPpm = -10L)

    private var watchWallSkewMs = 0L

    private val phoneNode by lazy { Node(phone, 0L, console = true, firstRoundId = 1_000L, raceIds = generateSequence(100L) { it + 1 }.iterator()) }
    private val watchNode by lazy {
        Node(watch, watchWallSkewMs, console = false, firstRoundId = 2_000L, raceIds = generateSequence(200L) { it + 1 }.iterator())
    }

    private fun pair() {
        phoneNode.peer = watchNode
        watchNode.peer = phoneNode
        phoneNode.link.onPeers(listOf(PeerNode(watch.id, nearby)))
        watchNode.link.onPeers(listOf(PeerNode(phone.id, nearby)))
    }

    /** Both apps on screen long enough to hold a minute of rounds, as a pre-start screen does. */
    private fun linkUp() {
        pair()
        phoneNode.link.setActive(true)
        watchNode.link.setActive(true)
        world.advance(65_000L)
    }

    private fun physical(device: Device, reading: Long): Double =
        (reading - device.exactAt(0L)) / (1.0 + device.fastPpm / 1_000_000.0)

    /** Both devices' guns, as physical instants, and how far apart they are. */
    private fun gunGapMs(): Double = abs(physical(phone, phoneNode.gun()) - physical(watch, watchNode.gun()))

    private fun startsSentBy(device: Device) = sent.count { it.first == device.id && it.second.startsWith("rtpair1 start") }

    // --- AC 1 and 2: one start, the same gun, each device's own engine ------------------------------

    @Test
    fun `a start on the phone runs the watch's own engine to the same gun, and losing the link after changes nothing`() {
        linkUp()
        phoneNode.tapStart(BuiltInSequences.usSailing)
        world.advance(1_000L)

        val join = watchNode.joins.single()
        assertEquals(BuiltInSequences.usSailing, join.sequence)
        assertTrue("a linked pair joins within the budget", join.race.gun.inBudget)
        val bound = join.race.gun.errorBoundMs!!
        assertTrue("guns ${gunGapMs()} ms apart, bound $bound", gunGapMs() <= bound)

        // Nothing crosses the link from here to the gun, and both still fire it. No remote ticking:
        // the watch's count is its own engine on its own clock.
        severed = true
        world.advance(BuiltInSequences.usSailing.totalMs + 5_000L)
        val phoneGun = phoneNode.gunAt ?: throw AssertionError("the phone never fired its gun")
        val watchGun = watchNode.gunAt ?: throw AssertionError("the watch never fired its gun")
        // A millisecond of tick granularity on each side, over the bound the join reported.
        assertTrue("guns fired ${abs(phoneGun - watchGun)} ms apart, bound $bound", abs(phoneGun - watchGun) <= bound + 2L)
    }

    @Test
    fun `a start on the watch runs the phone the same way`() {
        linkUp()
        watchNode.tapStart(BuiltInSequences.scholasticRaceManager)
        world.advance(1_000L)

        val join = phoneNode.joins.single()
        assertEquals(BuiltInSequences.scholasticRaceManager, join.sequence)
        assertTrue(gunGapMs() <= join.race.gun.errorBoundMs!!)
        assertNull("a start into an idle phone is no conflict", phoneNode.starts.contest)
    }

    @Test
    fun `the joining device sounds the warning the start's own transit made late`() {
        linkUp()
        phoneNode.tapStart(BuiltInSequences.usSailing)
        world.advance(1_000L)

        val (offset, at) = watchNode.cuesAt.first()
        assertEquals("the warning signal, 5:00, is not dropped", 5 * 60_000L, offset)
        assertTrue("and it sounds as the start lands", at - phoneNode.cuesAt.first().second in 0L..JOIN_LATE_CUE_GRACE_MS)
    }

    private fun pings() = sent.count { it.second.startsWith("rtpair1 ping") }

    @Test
    fun `from a start to its gun both devices keep measuring with no screen on, and stop at the gun`() {
        linkUp()
        phoneNode.link.setActive(false)
        watchNode.link.setActive(false)
        world.advance(60_000L)
        val idle = pings()
        world.advance(60_000L)
        assertEquals("off screen with no race, nothing asks", idle, pings())

        phoneNode.tapStart(BuiltInSequences.club)
        world.advance(BuiltInSequences.club.totalMs)

        // Each device holds from its start to its gun: a burst of five about every 30 s for three
        // minutes, six bursts each. Asserted as a floor, since the last burst can fall either side.
        assertTrue("rounds kept coming through the countdown: ${pings() - idle}", pings() - idle >= 50)
        world.advance(2_000L) // a burst under way at the gun is let finish
        val settled = pings()
        world.advance(10 * 60_000L)
        assertEquals("and nothing asks after the gun", settled, pings())
    }

    // --- AC 3: no counterpart ----------------------------------------------------------------------

    @Test
    fun `with no counterpart the race starts exactly as it would on a phone that never heard of a watch`() {
        // The transport is present and finds nobody: the phone-standalone case.
        present = false
        phoneNode.peer = watchNode
        phoneNode.link.onPeers(emptyList())
        phoneNode.link.setActive(true)
        world.advance(5_000L)
        val tapAt = phone.elapsedMs()

        phoneNode.tapStart(BuiltInSequences.usSailing)

        assertEquals(TimerState.RUNNING, phoneNode.engine.currentState)
        assertEquals("anchored on this clock, now plus the sequence", tapAt + BuiltInSequences.usSailing.totalMs, phoneNode.gun())
        assertEquals("the first cue sounded synchronously, as it always did", listOf(5 * 60_000L), phoneNode.cuesAt.map { it.first })
        world.advance(BuiltInSequences.usSailing.totalMs)
        assertNotNull(phoneNode.gunAt)
        assertEquals("and nothing went anywhere", 0, startsSentBy(phone))
    }

    @Test
    fun `with no Data Layer at all the race is the same race`() {
        phoneNode.peer = watchNode
        phoneNode.link.onUnavailable()
        val tapAt = phone.elapsedMs()

        phoneNode.tapStart(BuiltInSequences.club)

        assertEquals(tapAt + BuiltInSequences.club.totalMs, phoneNode.gun())
        assertEquals(0, startsSentBy(phone))
    }

    // --- AC 4: near-simultaneous starts -------------------------------------------------------------

    /** The watch taps [gapMs] after the phone, inside the transit window, so the two starts cross. */
    private fun crossingStarts(gapMs: Long, phoneFirst: Boolean = true) {
        linkUp()
        if (phoneFirst) {
            phoneNode.tapStart(BuiltInSequences.usSailing)
            world.advance(gapMs)
            watchNode.tapStart(BuiltInSequences.usSailing)
        } else {
            watchNode.tapStart(BuiltInSequences.usSailing)
            world.advance(gapMs)
            phoneNode.tapStart(BuiltInSequences.usSailing)
        }
        world.advance(2_000L)
    }

    private fun assertConverged(onKey: RaceKey) {
        assertEquals("the phone runs the winner", onKey, phoneNode.book.current?.key)
        assertEquals("the watch runs the winner", onKey, watchNode.book.current?.key)
        val bound = maxOf(phoneNode.book.current!!.gun.errorBoundMs ?: 0L, watchNode.book.current!!.gun.errorBoundMs ?: 0L)
        assertTrue("guns ${gunGapMs()} ms apart, bound $bound", gunGapMs() <= bound)
    }

    @Test
    fun `two starts that cross converge on the later tap, here the watch's`() {
        crossingStarts(gapMs = 40L)

        val watchRace = watchNode.book.current!!
        assertTrue("the watch's own race won", watchRace.startedHere)
        assertConverged(watchRace.key)
        assertEquals("the watch kept its race and was not moved", 0, watchNode.joins.size)
    }

    @Test
    fun `two starts that cross converge on the later tap, here the phone's`() {
        crossingStarts(gapMs = 40L, phoneFirst = false)

        val phoneRace = phoneNode.book.current!!
        assertTrue(phoneRace.startedHere)
        assertConverged(phoneRace.key)
        assertEquals(0, phoneNode.joins.size)
    }

    @Test
    fun `a device switching to the winner does not sound the warning twice`() {
        crossingStarts(gapMs = 40L)

        assertEquals(
            "the phone sounded its own 5:00 and not the watch's 40 ms later",
            1,
            phoneNode.cuesAt.count { it.first == 5 * 60_000L },
        )
        assertEquals("a switch has no grace", 0L, phoneNode.joins.single().lateCueGraceMs)
    }

    @Test
    fun `two taps at the same instant still converge on one race`() {
        crossingStarts(gapMs = 0L)

        assertConverged(phoneNode.book.current!!.key)
    }

    @Test
    fun `two starts that cross are ordered as they were tapped, whatever the wall clocks say`() {
        // The owner's pair, measured on hardware for #220: the watch's wall clock 3.2–3.4 s behind
        // the phone's, both on automatic time. Ordered by wall clock, the watch's later tap lost every
        // crossing. Ordered on the console's clock, it wins.
        watchWallSkewMs = -3_300L
        crossingStarts(gapMs = 40L)

        val winner = watchNode.book.current!!
        assertTrue("the later tap, the watch's, won", winner.startedHere)
        assertConverged(winner.key)
    }

    @Test
    fun `the earlier tap loses a crossing even with its wall clock ahead`() {
        watchWallSkewMs = 3_300L
        crossingStarts(gapMs = 40L, phoneFirst = false)

        val winner = phoneNode.book.current!!
        assertTrue("the later tap, the phone's, won", winner.startedHere)
        assertConverged(winner.key)
    }

    @Test
    fun `two starts with the same stamp are ordered by race id, the same way on both devices`() {
        val onPhone = PairRaceBook(newRaceId = { 100L })
        val onWatch = PairRaceBook(newRaceId = { 200L })
        val phoneRace = onPhone.startedHere(BuiltInSequences.club.id, 1_000L, consoleNowMs = 5_000L)
        val watchRace = onWatch.startedHere(BuiltInSequences.club.id, 2_000L, consoleNowMs = 5_000L)

        val phoneVerdict = onPhone.decide(PeerStart(watchRace.key, watchRace.sequenceId, JoinGun(1_000L, 20L)), running = true)
        val watchVerdict = onWatch.decide(PeerStart(phoneRace.key, phoneRace.sequenceId, JoinGun(2_000L, 20L)), running = true)

        assertEquals(watchRace.key, (phoneVerdict as StartVerdict.Join).race.key)
        assertEquals(watchRace.key, (watchVerdict as StartVerdict.Keep).mine.key)
    }

    @Test
    fun `a restart on a watch the link cannot measure still outranks the race it saw`() {
        // No round ever completes — every ping, reply and shared round is lost — so the watch cannot
        // place its tap on the phone's clock, and only the rule that raises a stamp past every start
        // seen can order its restart after the phone's race.
        dropNext = { _, text -> !text.startsWith("rtpair1 start") }
        pair()
        phoneNode.tapStart(BuiltInSequences.usSailing)
        world.advance(20_000L)
        assertNull("the positive control: the watch joined with no rounds", watchNode.joins.single().race.gun.errorBoundMs)
        watchNode.stop()
        world.advance(3_000L)

        watchNode.tapStart(BuiltInSequences.club)
        world.advance(1_000L)

        assertEquals(BuiltInSequences.club, phoneNode.engine.loadedSequence)
        assertEquals(watchNode.book.current!!.key, phoneNode.book.current!!.key)
    }

    @Test
    fun `a start lost in transit still converges, because the winner sends its race again`() {
        // The watch's start never arrives. The phone's does, loses on the watch, and the watch sends
        // its own again — which is the only way the phone ever learns of it.
        dropNext = { from, text -> from == watch.id && text.startsWith("rtpair1 start") && startsSentBy(watch) == 1 }
        crossingStarts(gapMs = 40L)

        assertConverged(watchNode.book.current!!.key)
        assertEquals("the watch sent its race twice", 2, startsSentBy(watch))
    }

    // --- AC 4: the choice on the phone --------------------------------------------------------------

    @Test
    fun `the phone offers the choice when the watch's start beats its own, and the watch offers nothing`() {
        crossingStarts(gapMs = 40L)

        val contest = phoneNode.starts.contest ?: throw AssertionError("the phone should offer the choice")
        assertFalse(contest.followed.startedHere)
        assertTrue(contest.other.startedHere)
        assertTrue(watchNode.contests.all { it == null })
    }

    @Test
    fun `the phone offers the choice when its own start beats the watch's`() {
        crossingStarts(gapMs = 40L, phoneFirst = false)

        val contest = phoneNode.starts.contest ?: throw AssertionError("the phone should offer the choice")
        assertTrue(contest.followed.startedHere)
        assertFalse(contest.other.startedHere)
    }

    @Test
    fun `choosing the other start moves both devices to its gun`() {
        crossingStarts(gapMs = 40L)
        val contest = phoneNode.starts.contest!!
        val phoneOriginalGun = contest.other.gun.gunMs

        phoneNode.starts.choose(contest.other)
        world.advance(1_000L)

        assertNull(phoneNode.starts.contest)
        assertEquals("the phone is on the chosen start's gun, exactly as it held it", phoneOriginalGun, phoneNode.gun())
        assertConverged(contest.other.key.copy(stamp = phoneNode.book.current!!.key.stamp))
    }

    @Test
    fun `each half of the choice names the start tapped on its own device, whichever is followed`() {
        val phoneRace = race(startedHere = true, gunMs = 10_000L)
        val watchRace = race(startedHere = false, gunMs = 10_600L)

        for (contest in listOf(PairContest(followed = watchRace, other = phoneRace), PairContest(followed = phoneRace, other = watchRace))) {
            assertEquals(phoneRace, contest.startedOn(here = true))
            assertEquals(watchRace, contest.startedOn(here = false))
        }
    }

    @Test
    fun `choosing the start already followed changes nothing and sends nothing`() {
        crossingStarts(gapMs = 40L)
        val contest = phoneNode.starts.contest!!
        val gun = phoneNode.gun()
        val sentBefore = startsSentBy(phone)

        phoneNode.starts.choose(contest.followed)
        world.advance(1_000L)

        assertNull(phoneNode.starts.contest)
        assertEquals(gun, phoneNode.gun())
        assertEquals(sentBefore, startsSentBy(phone))
    }

    // --- a restart on either device -----------------------------------------------------------------

    @Test
    fun `a restart on the watch takes over the phone's race, and the phone says so`() {
        linkUp()
        phoneNode.tapStart(BuiltInSequences.usSailing)
        world.advance(20_000L)
        watchNode.stop()
        world.advance(3_000L)

        watchNode.tapStart(BuiltInSequences.club)
        world.advance(1_000L)

        assertEquals(BuiltInSequences.club, phoneNode.engine.loadedSequence)
        assertConverged(watchNode.book.current!!.key)
        assertNotNull("the console's race was replaced by the wrist's: the phone says so", phoneNode.starts.contest)
    }

    @Test
    fun `a restart on the watch of its own race moves the phone without asking`() {
        // The phone was following the watch all along: both starts are the watch's, so there are
        // not two devices' starts to choose between.
        linkUp()
        watchNode.tapStart(BuiltInSequences.usSailing)
        world.advance(20_000L)
        watchNode.stop()
        world.advance(3_000L)
        watchNode.tapStart(BuiltInSequences.club)
        world.advance(1_000L)

        assertEquals("the positive control: the phone did move", BuiltInSequences.club, phoneNode.engine.loadedSequence)
        assertConverged(watchNode.book.current!!.key)
        assertNull(phoneNode.starts.contest)
    }

    @Test
    fun `a restart on the phone takes over the watch's race without a word on either`() {
        linkUp()
        phoneNode.tapStart(BuiltInSequences.usSailing)
        world.advance(20_000L)
        phoneNode.stop()
        phoneNode.tapStart(BuiltInSequences.club)
        world.advance(1_000L)

        assertEquals(BuiltInSequences.club, watchNode.engine.loadedSequence)
        assertConverged(phoneNode.book.current!!.key)
        assertNull("the phone started it; there is nothing to choose", phoneNode.starts.contest)
    }

    @Test
    fun `a race restored after a process death yields to any start the peer sends`() {
        linkUp()
        val seq = BuiltInSequences.club
        watchNode.engine.restore(seq, TimerEngine.Snapshot(seq.id, watch.elapsedMs() + 90_000L, 0L, watch.elapsedMs()))
        watchNode.starts.restored()

        phoneNode.tapStart(BuiltInSequences.usSailing)
        world.advance(1_000L)

        assertEquals(BuiltInSequences.usSailing, watchNode.engine.loadedSequence)
        assertEquals("a switch, so no grace", 0L, watchNode.joins.single().lateCueGraceMs)
    }

    // --- AC 5: a gun the link cannot vouch for ------------------------------------------------------

    @Test
    fun `a start the link cannot place within the budget is joined anyway, and flagged`() {
        // A slow link: 300 ms each way bounds every round to about 300 ms, three times the budget.
        phoneToWatchMs = 300L
        watchToPhoneMs = 300L
        linkUp()

        phoneNode.tapStart(BuiltInSequences.usSailing)
        world.advance(2_000L)

        val gun = watchNode.joins.single().race.gun
        assertFalse(gun.inBudget)
        assertEquals("the watch counts rather than refusing", TimerState.RUNNING, watchNode.engine.currentState)
        assertEquals("Phone start ±${gun.errorBoundMs} ms — tap Sync to confirm", pairJoinNotice(gun, "Phone"))
        // And the bound it reports is honest even so.
        assertTrue(gunGapMs() <= gun.errorBoundMs!!)
    }

    @Test
    fun `a start with no rounds behind it is joined unmeasured, and flagged`() {
        pair() // found, but nobody has been on screen: no rounds on either side
        phoneNode.tapStart(BuiltInSequences.usSailing)
        world.advance(1_000L)

        val gun = watchNode.joins.single().race.gun
        assertNull(gun.errorBoundMs)
        assertEquals("Phone start unmeasured — tap Sync to confirm", pairJoinNotice(gun, "Phone"))
        assertEquals(TimerState.RUNNING, watchNode.engine.currentState)
    }

    // --- starts that are not joined -----------------------------------------------------------------

    @Test
    fun `a start whose gun has passed is not joined`() {
        pair()
        watchNode.starts.onPeerStart(PeerStart(RaceKey(1L, 1L), BuiltInSequences.club.id, JoinGun(watch.elapsedMs() - 1L, 20L)))

        assertTrue(watchNode.joins.isEmpty())
        assertEquals(TimerState.IDLE, watchNode.engine.currentState)
    }

    @Test
    fun `a race-manager start whose gun has passed is joined into its count-up`() {
        pair()
        watchNode.starts.onPeerStart(
            PeerStart(RaceKey(1L, 1L), BuiltInSequences.usSailingRaceManager.id, JoinGun(watch.elapsedMs() - 30_000L, 20L)),
        )

        assertEquals(TimerState.COUNTING_UP, watchNode.engine.currentState)
    }

    @Test
    fun `a start for a sequence this app does not have is not joined`() {
        pair()
        watchNode.starts.onPeerStart(PeerStart(RaceKey(1L, 1L), "a_sequence_from_the_future", JoinGun(watch.elapsedMs() + 60_000L, 20L)))

        assertTrue(watchNode.joins.isEmpty())
    }

    @Test
    fun `a copy of a start already taken changes nothing`() {
        pair()
        val start = PeerStart(RaceKey(5L, 9L), BuiltInSequences.club.id, JoinGun(watch.elapsedMs() + 60_000L, 20L))
        watchNode.starts.onPeerStart(start)
        world.advance(5_000L)
        watchNode.starts.onPeerStart(start)

        assertEquals(1, watchNode.joins.size)
    }

    // --- the words ----------------------------------------------------------------------------------

    @Test
    fun `a join inside the budget says nothing, and one at the budget exactly is inside it`() {
        assertNull(pairJoinNotice(JoinGun(0L, 0L), "Watch"))
        assertNull(pairJoinNotice(JoinGun(0L, PAIR_SKEW_BUDGET_MS), "Watch"))
        assertEquals("Watch start ±101 ms — tap Sync to confirm", pairJoinNotice(JoinGun(0L, PAIR_SKEW_BUDGET_MS + 1L), "Watch"))
    }

    @Test
    fun `the join line fits the watch's Tier 3 plate whatever the bound`() {
        // docs/message-surface.md rule 7: held against the surface it renders on, not argued. The
        // bound is unbounded above — a round after a reconnect stall can run to tens of seconds — so
        // the widest bounds a real link produces are checked, not only the typical one.
        val lines = listOf(PAIR_SKEW_BUDGET_MS + 1L, 9_999L, 52_000L, 999_999L).map { pairJoinNotice(JoinGun(0L, it), "Phone")!! } +
            pairJoinNotice(JoinGun(0L, null), "Phone")!!
        for (line in lines) {
            assertTrue("\"$line\" is over the $NOTICE_MAX_CHARS-character ceiling", line.length <= NOTICE_MAX_CHARS)
            assertTrue(
                "\"$line\" takes ${MessageSurface.STATUS_LINE.linesFor(line)} lines on the plate, which holds ${MessageSurface.STATUS_LINE.maxLines}",
                MessageSurface.STATUS_LINE.holds(line),
            )
        }
    }

    private fun race(startedHere: Boolean, gunMs: Long, sequence: RaceSequence = BuiltInSequences.usSailing) =
        PairRace(RaceKey(gunMs, if (startedHere) 1L else 2L), sequence.id, JoinGun(gunMs, 0L), startedHere)

    @Test
    fun `the choice line says who started, how far apart, and which start both follow`() {
        assertEquals(
            "Watch started too, 0.6 s later — both following the watch",
            pairContestLine(PairContest(followed = race(false, 10_600L), other = race(true, 10_000L)), "Watch", "phone"),
        )
        assertEquals(
            "Watch started too, 1.2 s earlier — both following the phone",
            pairContestLine(PairContest(followed = race(true, 10_000L), other = race(false, 8_800L)), "Watch", "phone"),
        )
        assertEquals(
            "Watch started too, at the same moment — both following the watch",
            pairContestLine(PairContest(followed = race(false, 10_040L), other = race(true, 10_000L)), "Watch", "phone"),
        )
        assertEquals(
            "Watch started too, Club 3-2-1-Go — both following the watch",
            pairContestLine(
                PairContest(followed = race(false, 10_000L, BuiltInSequences.club), other = race(true, 10_000L)),
                "Watch",
                "phone",
            ),
        )
    }

    private companion object {
        /** 2026-09-28 in wall-clock milliseconds, near enough: only differences are ever read. */
        const val WALL_EPOCH = 1_790_000_000_000L
    }
}
