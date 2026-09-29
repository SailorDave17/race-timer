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

    /**
     * Extra transit for one message, on top of its direction's delay (#221). Slows a single message
     * while the rounds stay fast, which a per-direction delay cannot do: slowing a direction biases
     * every round's midpoint by half the asymmetry, and that bias hides what a slow message costs.
     */
    private var extraTransit: (from: String, text: String) -> Long = { _, _ -> 0L }
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
        opensOn: SetupChoice,
    ) : PairRaceHost, PairSetupHost {
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

        /** What this device's pre-start screen shows (#221): the app's selection and picker alert. */
        var screen: SetupChoice = opensOn
        val applied = mutableListOf<SetupChoice>()
        val events = mutableListOf<PairEvent>()
        val moves = mutableListOf<JoinGun>()
        val syncsHeard = mutableListOf<Long>()

        /** Every reconnect correction the pair asked of this device (#222), taken or not. */
        val corrections = mutableListOf<JoinGun>()

        /** Every gap beyond D6's bound this device was told to flag for Sync (#222), and took. */
        val flaggedApart = mutableListOf<Long>()
        val link: PairLink = PairLink(
            clock = device,
            onStatus = { starts.onLinkStatus(it) },
            transport = object : PairTransport {
                override fun send(peerId: String, payload: ByteArray, onFailed: () -> Unit) {
                    val text = String(payload, Charsets.UTF_8)
                    sent += device.id to text
                    if (severed || peerId != peer.device.id || dropNext(device.id, text)) return
                    val delay = (if (device === phone) phoneToWatchMs else watchToPhoneMs) + extraTransit(device.id, text)
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
            onPeerSync = { starts.onPeerSync(it) },
            onPeerEnd = { starts.onPeerEnd(it) },
            onPeerSetup = { setup.onPeerSetup(it) },
            onPeerNearby = {
                setup.onPeerNearby()
                starts.onPeerNearby()
            },
            onPeerCheck = { starts.onPeerCheck(it) },
        )
        val setup = PairSetup(book, link, this, device, console, initial = opensOn)
        val starts = PairStarts(book, link, this, device, console, onContest = { contests += it }, setup = setup, onEvent = { events += it })

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
                override fun onSync(snappedToMs: Long) {
                    syncsHeard += snappedToMs
                }
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

        /** The officer taps Sync: the snap is taken here first, and only an accepted one is told. */
        fun tapSync() {
            if (!engine.sync()) return
            armCues()
            starts.syncedHere(gun())
        }

        /** The officer taps End Race: frozen here first, then told at the time it froze. */
        fun tapEndRace() {
            if (engine.currentState != TimerState.COUNTING_UP) return
            engine.endRace()
            cancelCue?.invoke()
            starts.endedHere(-engine.remainingMs)
        }

        /** The officer chooses [sequence] on the pre-start screen, with the picker's alert as it stands. */
        fun pick(sequence: RaceSequence, boxAlertSeconds: Int = screen.boxAlertSeconds) {
            screen = SetupChoice(sequence.id, boxAlertSeconds)
            setup.pickedHere(screen)
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

        override fun moveGun(gun: JoinGun): Boolean {
            moves += gun
            if (!engine.moveGun(gun.gunMs)) return false
            armCues()
            return true
        }

        override fun endRaceAt(elapsedMs: Long): Long? = engine.endRaceAt(elapsedMs)?.also { cancelCue?.invoke() }

        override fun correctGun(gun: JoinGun): Boolean {
            corrections += gun
            if (!engine.correctGun(gun.gunMs)) return false
            armCues()
            return true
        }

        override fun gunsApart(apartMs: Long): Boolean {
            if (engine.currentState != TimerState.RUNNING) return false
            flaggedApart += apartMs
            return true
        }

        /**
         * This device's gun, [byMs] away from where the pair put it — however a real gun comes to
         * that (#222). The fixture for D6's bound: nothing in the pair produces a gap of a chosen
         * size on demand, and the check that measures it still crosses the simulated link and is
         * placed through its rounds. Quiet, as a correction is, so it sounds nothing.
         */
        fun skewGun(byMs: Long) {
            val race = book.current ?: throw AssertionError("${device.id} has no race to skew")
            val skewed = race.gun.gunMs + byMs
            assertTrue("the positive control: a countdown to skew", engine.correctGun(skewed))
            book.moved(race.copy(gun = JoinGun(skewed, race.gun.errorBoundMs)))
            armCues()
        }

        override fun atPreStart(): Boolean = engine.currentState == TimerState.IDLE

        override fun apply(choice: SetupChoice) {
            applied += choice
            screen = choice
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

    /**
     * The watch's drift. #218's −10 ppm everywhere but D6's bound (#222), where a gap of exactly
     * 100 ms has to measure as exactly 100: there it is zero, with the link symmetric, so the rounds
     * place the other gun with no error at all and the only gap measured is the one put there.
     */
    private var watchPpm = -10L
    private val watch by lazy { Device("watch-node", world, bootReading = 10_800_000L, fastPpm = watchPpm) }

    private var watchWallSkewMs = 0L

    // What each app opens on from its own memory: different, so a test can tell whose setup won.
    private var phoneOpensOn = SetupChoice(BuiltInSequences.usSailing.id, 0)
    private var watchOpensOn = SetupChoice(BuiltInSequences.club.id, 0)

    private val phoneNode by lazy {
        Node(phone, 0L, console = true, firstRoundId = 1_000L, raceIds = generateSequence(100L) { it + 1 }.iterator(), opensOn = phoneOpensOn)
    }
    private val watchNode by lazy {
        Node(
            watch,
            watchWallSkewMs,
            console = false,
            firstRoundId = 2_000L,
            raceIds = generateSequence(200L) { it + 1 }.iterator(),
            opensOn = watchOpensOn,
        )
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

    // --- #221 AC 1 and 4: a Sync on either device moves both guns --------------------------------

    /** A race started on [on], linked, [intoMs] into its countdown. */
    private fun raceUnderWay(intoMs: Long, sequence: RaceSequence = BuiltInSequences.usSailing, on: () -> Node = { phoneNode }) {
        linkUp()
        on().tapStart(sequence)
        world.advance(intoMs)
    }

    /** [node]'s gun as a physical instant. */
    private fun physicalGun(node: Node): Double = physical(node.device, node.gun())

    private fun syncsSentBy(device: Device) = sent.count { it.first == device.id && it.second.startsWith("rtpair1 sync") }

    @Test
    fun `a Sync on the phone moves the watch to the same physical gun, and both fire it together`() {
        // The owner's pair: nothing a Sync crosses the link with may read a wall clock.
        watchWallSkewMs = -3_300L
        raceUnderWay(intoMs = 8_000L) // 4:52 on the phone: the snap rounds up to 5:00, 8 s later
        val before = physicalGun(phoneNode)

        phoneNode.tapSync()
        world.advance(1_000L)

        assertEquals("the phone's own gun moved 8 s later", 8_000.0, physicalGun(phoneNode) - before, 1.0)
        val moved = watchNode.moves.single()
        val bound = moved.errorBoundMs ?: throw AssertionError("a linked pair translates the moved gun")
        assertTrue("guns ${gunGapMs()} ms apart, bound $bound", gunGapMs() <= bound)
        world.advance(BuiltInSequences.usSailing.totalMs)
        val phoneGun = phoneNode.gunAt ?: throw AssertionError("the phone never fired its gun")
        val watchGun = watchNode.gunAt ?: throw AssertionError("the watch never fired its gun")
        assertTrue("guns fired ${abs(phoneGun - watchGun)} ms apart, bound $bound", abs(phoneGun - watchGun) <= bound + 2L)
    }

    @Test
    fun `a Sync on the watch moves the phone the same way, here down to the minute`() {
        raceUnderWay(intoMs = 20_000L) // 4:40: the watch's snap floors to 4:00
        val tappedAt = world.now

        watchNode.tapSync()
        world.advance(1_000L)

        val bound = phoneNode.moves.single().errorBoundMs!!
        assertTrue("guns ${gunGapMs()} ms apart, bound $bound", gunGapMs() <= bound)
        // The watch's gun is exact on its own clock: 4:00 after its tap, 10 ppm slow.
        assertEquals("both are on the watch's 4:00", 4 * 60_000.0, physicalGun(phoneNode) - tappedAt, bound + 5.0)
    }

    @Test
    fun `a Sync each device would round differently still lands on one gun, because the gun crosses and not the tap`() {
        // The phone taps at 4:50.000 exactly and rounds up to 5:00. The watch, reading the same race
        // a message's trip later at 4:49.94, would floor it to 4:00 if it snapped for itself.
        raceUnderWay(intoMs = 10_000L)

        phoneNode.tapSync()
        world.advance(1_000L)

        val bound = watchNode.moves.single().errorBoundMs!!
        assertTrue("guns ${gunGapMs()} ms apart — a minute apart would be two snaps", gunGapMs() <= bound)
    }

    @Test
    fun `two Syncs that cross settle on the later tap on both devices`() {
        raceUnderWay(intoMs = 8_000L)

        phoneNode.tapSync()
        world.advance(40L) // inside the phone's message's 60 ms trip: the two cross
        watchNode.tapSync()
        world.advance(2_000L)

        assertTrue("the watch kept its own, later Sync", watchNode.moves.isEmpty())
        assertEquals("the phone took the watch's", 1, phoneNode.moves.size)
        assertEquals(watchNode.book.current!!.gunKey, phoneNode.book.current!!.gunKey)
        assertEquals("and the watch sent its own again to answer the phone's older one", 2, syncsSentBy(watch))
        val bound = phoneNode.moves.single().errorBoundMs!!
        assertTrue("guns ${gunGapMs()} ms apart, bound $bound", gunGapMs() <= bound)
    }

    @Test
    fun `a Sync lost in transit still converges, because the later one is sent again`() {
        // The watch's own, later Sync never arrives. The phone's older one does, loses on the watch,
        // and the watch sends its own again — the only way the phone ever learns of it.
        dropNext = { from, text -> from == watch.id && text.startsWith("rtpair1 sync") && syncsSentBy(watch) == 1 }
        raceUnderWay(intoMs = 8_000L)

        phoneNode.tapSync()
        world.advance(40L)
        watchNode.tapSync()
        world.advance(2_000L)

        assertEquals("the positive control: the watch's first send was the one lost", 2, syncsSentBy(watch))
        assertEquals(watchNode.book.current!!.gunKey, phoneNode.book.current!!.gunKey)
        assertTrue(gunGapMs() <= phoneNode.moves.single().errorBoundMs!!)
    }

    @Test
    fun `the device that moves reports the countdown the peer's Sync left, as the officer is told it`() {
        raceUnderWay(intoMs = 8_000L)

        phoneNode.tapSync()
        world.advance(1_000L)

        val moved = watchNode.events.filterIsInstance<PairEvent.GunMoved>().single()
        assertEquals("Phone synced → 5:00", pairGunMovedLine(moved.setRemainingMs, "Phone"))
    }

    @Test
    fun `the minute the peer set reads the same however long its Sync took to cross`() {
        // Found on the owner's pair (#221's hardware run): the rounds were crossing in 50–100 ms while
        // one Sync took about 0.6 s, and the phone read "Watch synced → 0:59" for a Sync to 1:00,
        // because the line was worked out from the countdown left when the move landed. The same
        // here: fast rounds, one slow Sync. (Slowing the whole direction instead biases the offset
        // by half the asymmetry, which cancels most of the trip — that fixture passed the old code.)
        extraTransit = { _, text -> if (text.startsWith("rtpair1 sync")) 600L else 0L }
        raceUnderWay(intoMs = 8_000L)

        phoneNode.tapSync()
        world.advance(2_000L)

        val moved = watchNode.moves.single()
        assertTrue("the positive control: the moved gun is still placed well", moved.inBudget)
        val event = watchNode.events.filterIsInstance<PairEvent.GunMoved>().single()
        assertEquals("Phone synced → 5:00", pairGunMovedLine(event.setRemainingMs, "Phone"))
    }

    @Test
    fun `the peer's Sync reads as the minute it set, whichever side of it the move landed`() {
        for (remaining in listOf(5 * 60_000L - 60L, 5 * 60_000L, 5 * 60_000L + 40L, 5 * 60_000L - 499L)) {
            assertEquals("at $remaining ms", "Watch synced → 5:00", pairGunMovedLine(remaining, "Watch"))
        }
    }

    @Test
    fun `a Sync ends the choice between two starts on the phone`() {
        crossingStarts(gapMs = 40L)
        assertNotNull("the positive control: the choice is up", phoneNode.starts.contest)
        world.advance(6_000L)

        watchNode.tapSync()
        world.advance(1_000L)

        assertNull(phoneNode.starts.contest)
    }

    @Test
    fun `a race with no peer syncs exactly as it always did, and sends nothing`() {
        present = false
        phoneNode.peer = watchNode
        phoneNode.link.onPeers(emptyList())
        phoneNode.tapStart(BuiltInSequences.usSailing)
        world.advance(8_000L)
        val tappedAt = phone.elapsedMs()

        phoneNode.tapSync()

        assertEquals(tappedAt + 5 * 60_000L, phoneNode.gun())
        assertEquals(0, syncsSentBy(phone))
    }

    // --- #221 AC 2: End Race on either device ends both --------------------------------------------

    private val raceManager = BuiltInSequences.scholasticRaceManager

    @Test
    fun `End Race on the phone ends the watch's race at the phone's elapsed time`() {
        raceUnderWay(intoMs = raceManager.totalMs + 45_000L, sequence = raceManager)
        assertEquals("the positive control: the watch is counting up", TimerState.COUNTING_UP, watchNode.engine.currentState)

        phoneNode.tapEndRace()
        world.advance(1_000L)

        assertEquals(TimerState.RACE_ENDED, watchNode.engine.currentState)
        assertEquals("both screens freeze the one race time", phoneNode.engine.remainingMs, watchNode.engine.remainingMs)
    }

    @Test
    fun `End Race on the watch ends the phone's race the same way`() {
        raceUnderWay(intoMs = raceManager.totalMs + 45_000L, sequence = raceManager, on = { watchNode })

        watchNode.tapEndRace()
        world.advance(1_000L)

        assertEquals(TimerState.RACE_ENDED, phoneNode.engine.currentState)
        assertEquals(watchNode.engine.remainingMs, phoneNode.engine.remainingMs)
    }

    @Test
    fun `two End Race taps that cross settle on the earlier on both devices`() {
        raceUnderWay(intoMs = raceManager.totalMs + 45_000L, sequence = raceManager)

        phoneNode.tapEndRace()
        val phoneEnd = -phoneNode.engine.remainingMs
        world.advance(40L)
        watchNode.tapEndRace()
        val watchEnd = -watchNode.engine.remainingMs
        world.advance(2_000L)

        val earlier = minOf(phoneEnd, watchEnd)
        assertEquals(-earlier, phoneNode.engine.remainingMs)
        assertEquals(-earlier, watchNode.engine.remainingMs)
    }

    @Test
    fun `an End Race lost in transit still converges, because the earlier end is sent back`() {
        // The phone's earlier end never arrives. The watch's later one does, and the phone, which
        // keeps its own earlier end, sends it back — the only way the watch ever learns of it.
        dropNext = { from, text -> from == phone.id && text.startsWith("rtpair1 end") && endsSentBy(phone) == 1 }
        raceUnderWay(intoMs = raceManager.totalMs + 45_000L, sequence = raceManager)

        phoneNode.tapEndRace()
        val phoneEnd = -phoneNode.engine.remainingMs
        world.advance(500L)
        watchNode.tapEndRace()
        world.advance(2_000L)

        assertEquals("the positive control: the phone's first send was the one lost", 2, endsSentBy(phone))
        assertEquals(-phoneEnd, phoneNode.engine.remainingMs)
        assertEquals("the watch took the earlier end", -phoneEnd, watchNode.engine.remainingMs)
    }

    private fun endsSentBy(device: Device) = sent.count { it.first == device.id && it.second.startsWith("rtpair1 end") }

    // --- #221 AC 3: a control for a race not running here ------------------------------------------

    private fun dropLink() {
        nearby = false
        phoneNode.link.onPeers(listOf(PeerNode(watch.id, false)))
        watchNode.link.onPeers(listOf(PeerNode(phone.id, false)))
    }

    private fun restoreLink() {
        nearby = true
        phoneNode.link.onPeers(listOf(PeerNode(watch.id, true)))
        watchNode.link.onPeers(listOf(PeerNode(phone.id, true)))
    }

    @Test
    fun `a Sync that arrives after a reconnect for a race the watch no longer runs is dropped with a notice`() {
        raceUnderWay(intoMs = 20_000L)
        dropLink()
        // Out of range, the watch's officer restarts on another sequence: a race the phone never hears of.
        watchNode.stop()
        watchNode.tapStart(BuiltInSequences.club)
        val clubGun = watchNode.gun()
        world.advance(5_000L)
        // Tapped as the two meet. Since #222 the watch's check takes the phone over to the watch's
        // later restart within a message's trip of meeting, so a Sync of the phone's old race is only
        // still sent in that window — and it lands on a watch that left the race it names.
        restoreLink()
        phoneNode.tapSync() // a Sync of the phone's race, which the watch left
        world.advance(1_000L)

        assertEquals("the positive control: the Sync was sent", 1, syncsSentBy(phone))
        assertEquals(listOf(PairEvent.StaleControl(PairControl.SYNC)), watchNode.events)
        assertTrue("nothing was moved", watchNode.moves.isEmpty())
        assertEquals("the watch's own race is untouched", BuiltInSequences.club, watchNode.engine.loadedSequence)
        assertEquals(clubGun, watchNode.gun())
    }

    @Test
    fun `an End Race for a race the phone has stopped is dropped with a notice`() {
        raceUnderWay(intoMs = raceManager.totalMs + 30_000L, sequence = raceManager, on = { watchNode })
        phoneNode.stop() // Stop is local until #328

        watchNode.tapEndRace()
        world.advance(1_000L)

        assertEquals(TimerState.IDLE, phoneNode.engine.currentState)
        assertEquals(listOf(PairEvent.StaleControl(PairControl.END_RACE)), phoneNode.events)
    }

    @Test
    fun `a control naming a race this device never ran is dropped with a notice, and nothing moves`() {
        linkUp()
        watchNode.tapStart(BuiltInSequences.club)
        val gun = watchNode.gun()

        watchNode.starts.onPeerSync(PeerSync(RaceKey(Long.MAX_VALUE, 1L), raceId = 999L, JoinGun(watch.elapsedMs() + 60_000L, 20L), 60_000L))
        watchNode.starts.onPeerEnd(PeerEnd(raceId = 999L, elapsedMs = 5_000L))

        assertEquals(
            listOf(PairEvent.StaleControl(PairControl.SYNC), PairEvent.StaleControl(PairControl.END_RACE)),
            watchNode.events,
        )
        assertTrue(watchNode.moves.isEmpty())
        assertEquals(gun, watchNode.gun())
    }

    @Test
    fun `a copy of a Sync already taken changes nothing and says nothing`() {
        raceUnderWay(intoMs = 8_000L)
        phoneNode.tapSync()
        world.advance(1_000L)
        val sync = PeerSync(phoneNode.book.current!!.gunKey, phoneNode.book.current!!.key.raceId, JoinGun(watch.elapsedMs() + 1L, 20L), 1L)

        watchNode.starts.onPeerSync(sync)

        assertEquals(1, watchNode.moves.size)
        assertEquals(1, watchNode.events.size)
    }

    // --- #221 AC 5: the pre-start setup -----------------------------------------------------------

    @Test
    fun `two setups nobody has touched settle on the phone's the moment the devices meet`() {
        pair()
        world.advance(1_000L)

        assertEquals(phoneOpensOn, watchNode.screen)
        assertEquals(phoneOpensOn, phoneNode.screen)
        assertTrue("the phone kept its own", phoneNode.applied.isEmpty())
    }

    @Test
    fun `a sequence chosen on the phone is on the watch's pre-start screen before Start`() {
        linkUp()

        phoneNode.pick(BuiltInSequences.scholastic)
        world.advance(1_000L)

        assertEquals(SetupChoice(BuiltInSequences.scholastic.id, 0), watchNode.screen)
    }

    @Test
    fun `a Custom length dialled on the watch is on the phone's`() {
        linkUp()

        watchNode.pick(BuiltInSequences.custom(8))
        world.advance(1_000L)

        assertEquals("custom_8m", phoneNode.screen.sequenceId)
    }

    @Test
    fun `a lead-in armed on the phone leaves both pickers on its alert`() {
        linkUp()

        phoneNode.tapStart(withLeadIn(raceManager, 60)!!)
        world.advance(1_000L)

        val armed = SetupChoice(raceManager.id, 60)
        assertEquals(armed, watchNode.screen)
        assertEquals(armed, phoneNode.screen)
    }

    @Test
    fun `a touched setup beats an untouched one, whichever device holds it`() {
        // The watch's officer picks before the phone app is running; the phone opens on its memory.
        present = false
        phoneNode.peer = watchNode
        watchNode.peer = phoneNode
        watchNode.link.onPeers(emptyList())
        watchNode.pick(BuiltInSequences.scholastic)
        present = true

        pair()
        world.advance(1_000L)

        assertEquals(SetupChoice(BuiltInSequences.scholastic.id, 0), phoneNode.screen)
        assertEquals(SetupChoice(BuiltInSequences.scholastic.id, 0), watchNode.screen)
    }

    /** Picks made apart — the watch out of range — by [first] and then by [second], 5 s later. */
    private fun picksApart(first: () -> Unit, second: () -> Unit) {
        linkUp()
        dropLink()
        first()
        world.advance(5_000L)
        second()
        world.advance(5_000L)
        restoreLink()
        world.advance(1_000L)
    }

    @Test
    fun `picks made apart settle on the later when the devices meet, here the watch's`() {
        picksApart({ phoneNode.pick(BuiltInSequences.scholastic) }, { watchNode.pick(BuiltInSequences.custom(7)) })

        assertEquals("custom_7m", phoneNode.screen.sequenceId)
        assertEquals("custom_7m", watchNode.screen.sequenceId)
    }

    @Test
    fun `picks made apart settle on the later when the devices meet, here the phone's`() {
        picksApart({ watchNode.pick(BuiltInSequences.custom(7)) }, { phoneNode.pick(BuiltInSequences.scholastic) })

        assertEquals(BuiltInSequences.scholastic.id, phoneNode.screen.sequenceId)
        assertEquals(BuiltInSequences.scholastic.id, watchNode.screen.sequenceId)
    }

    @Test
    fun `two picks that cross settle on the later on both devices`() {
        linkUp()

        phoneNode.pick(BuiltInSequences.scholastic)
        world.advance(40L) // inside the phone's message's 60 ms trip: the two cross
        watchNode.pick(BuiltInSequences.custom(7))
        world.advance(1_000L)

        assertEquals("custom_7m", phoneNode.screen.sequenceId)
        assertEquals("custom_7m", watchNode.screen.sequenceId)
    }

    @Test
    fun `a device with a race on screen takes no setup, and takes the later pick when its race is over`() {
        raceUnderWay(intoMs = 1_000L)
        watchNode.stop() // the watch is back at its pre-start screen; the phone still races
        watchNode.pick(BuiltInSequences.scholastic)
        world.advance(1_000L)
        assertEquals("the phone's race is untouched", BuiltInSequences.usSailing, phoneNode.engine.loadedSequence)
        assertEquals("and its screen shows its race", BuiltInSequences.usSailing.id, phoneNode.screen.sequenceId)

        phoneNode.stop()
        world.advance(1_000L)

        assertEquals(SetupChoice(BuiltInSequences.scholastic.id, 0), phoneNode.screen)
    }

    @Test
    fun `a setup for a sequence this app does not have is not taken`() {
        pair()
        world.advance(1_000L)

        watchNode.setup.onPeerSetup(PeerSetup(RaceKey(Long.MAX_VALUE, 1L), SetupChoice("a_sequence_from_the_future", 0)))

        assertEquals(phoneOpensOn, watchNode.screen)
    }

    @Test
    fun `with no counterpart a pick sends nothing and the screen is the app's own`() {
        present = false
        phoneNode.peer = watchNode
        phoneNode.link.onPeers(emptyList())

        phoneNode.pick(BuiltInSequences.scholastic)
        world.advance(1_000L)

        assertTrue(sent.none { it.second.startsWith("rtpair1 setup") })
        assertEquals(BuiltInSequences.scholastic.id, phoneNode.screen.sequenceId)
    }

    // --- #221: the words --------------------------------------------------------------------------

    @Test
    fun `the stale-control lines fit the watch's banner`() {
        for (control in PairControl.values()) {
            val line = pairStaleControlLine(control, "Phone")
            assertTrue("\"$line\" is over the $NOTICE_MAX_CHARS-character ceiling", line.length <= NOTICE_MAX_CHARS)
            assertTrue(
                "\"$line\" takes ${MessageSurface.BANNER.linesFor(line)} lines, the banner holds ${MessageSurface.BANNER.maxLines}",
                MessageSurface.BANNER.holds(line),
            )
        }
        assertEquals("Watch synced a race not running here", pairStaleControlLine(PairControl.SYNC, "Watch"))
        assertEquals("Watch ended a race not running here", pairStaleControlLine(PairControl.END_RACE, "Watch"))
    }

    @Test
    fun `a moved gun out of the budget is flagged in a Sync's words, and fits the plate`() {
        assertNull(pairJoinNotice(JoinGun(0L, PAIR_SKEW_BUDGET_MS), "Phone", PairControlledGun.SYNC))
        val lines = listOf(
            pairJoinNotice(JoinGun(0L, 180L), "Phone", PairControlledGun.SYNC)!!,
            pairJoinNotice(JoinGun(0L, 999_999L), "Phone", PairControlledGun.SYNC)!!,
            pairJoinNotice(JoinGun(0L, null), "Phone", PairControlledGun.SYNC)!!,
        )
        assertEquals("Phone sync ±180 ms — tap Sync to confirm", lines[0])
        assertEquals("Phone sync unmeasured — tap Sync to confirm", lines[2])
        for (line in lines) assertTrue("\"$line\" overflows the plate", MessageSurface.STATUS_LINE.holds(line) && line.length <= NOTICE_MAX_CHARS)
    }

    // --- #222: a dropped link, and its return -------------------------------------------------------

    /** The watch walks out of Bluetooth range: nothing crosses, and both ends see it go. */
    private fun walkApart() {
        severed = true
        dropLink()
    }

    /** And back: both ends see the other in range again, and the link carries messages. */
    private fun walkBack() {
        severed = false
        restoreLink()
    }

    private fun checksSentBy(device: Device) = sent.count { it.first == device.id && it.second.startsWith("rtpair1 check") }

    private fun corrected(node: Node) = node.events.filterIsInstance<PairEvent.GunCorrected>()

    /** Both devices' guns as physical instants, read now: what a drop must not move. */
    private fun physicalGuns() = physicalGun(phoneNode) to physicalGun(watchNode)

    /**
     * What a drop and its return must leave, whatever phase it came in: each device counted on to its
     * own gun, unmoved by the drop, and the two fired it together, inside the bound the join reported.
     */
    private fun assertBothFireTogether() {
        val bound = watchNode.book.current!!.gun.errorBoundMs ?: throw AssertionError("the watch's gun was never placed")
        world.advance(15 * 60_000L)
        val phoneGun = phoneNode.gunAt ?: throw AssertionError("the phone never fired its gun")
        val watchGun = watchNode.gunAt ?: throw AssertionError("the watch never fired its gun")
        assertTrue("guns fired ${abs(phoneGun - watchGun)} ms apart, bound $bound", abs(phoneGun - watchGun) <= bound + 2L)
    }

    /** A drop in the middle of a countdown, and the return: the phase AC 1 is about. */
    @Test
    fun `a drop mid-countdown leaves both counting to their own guns, and each says the link is lost`() {
        raceUnderWay(intoMs = 10_000L)
        assertFalse("the positive control: linked, nothing lost", phoneNode.starts.linkLost || watchNode.starts.linkLost)
        val before = physicalGuns()

        walkApart()
        world.advance(60_000L)

        assertTrue("the phone says so", phoneNode.starts.linkLost)
        assertTrue("and so does the watch", watchNode.starts.linkLost)
        // By construction: the engines read nothing the link says after the start, so nothing moved.
        assertEquals("the phone's gun is where it was", before.first, physicalGun(phoneNode), 0.0)
        assertEquals("the watch's gun is where it was", before.second, physicalGun(watchNode), 0.0)
        assertEquals("both still counting", TimerState.RUNNING, watchNode.engine.currentState)

        walkBack()
        world.advance(2_000L)
        assertFalse("back in range, nothing is lost", phoneNode.starts.linkLost || watchNode.starts.linkLost)
        assertTrue("and nothing was flagged", phoneNode.flaggedApart.isEmpty() && watchNode.flaggedApart.isEmpty())
        assertBothFireTogether()
    }

    @Test
    fun `a drop in the lead-in leaves both counting through it, and the return moves nothing beyond D6`() {
        linkUp()
        val armed = withLeadIn(raceManager, 60)!!
        phoneNode.tapStart(armed)
        world.advance(5_000L)
        assertTrue("the positive control: in the lead-in", isInLeadIn(armed, watchNode.engine.remainingMs))
        val before = physicalGuns()

        walkApart()
        world.advance(20_000L)
        assertTrue("still in the lead-in", isInLeadIn(armed, watchNode.engine.remainingMs))
        assertTrue(phoneNode.starts.linkLost && watchNode.starts.linkLost)
        assertEquals(before.first, physicalGun(phoneNode), 0.0)
        assertEquals(before.second, physicalGun(watchNode), 0.0)

        walkBack()
        world.advance(2_000L)
        assertTrue("the return came in the lead-in too", isInLeadIn(armed, watchNode.engine.remainingMs))
        assertFalse(phoneNode.starts.linkLost || watchNode.starts.linkLost)
        assertTrue(phoneNode.flaggedApart.isEmpty() && watchNode.flaggedApart.isEmpty())
        assertBothFireTogether()
    }

    @Test
    fun `a drop past the gun leaves both counting up, and the return moves nothing`() {
        raceUnderWay(intoMs = raceManager.totalMs + 10_000L, sequence = raceManager)
        assertEquals("the positive control: counting up", TimerState.COUNTING_UP, watchNode.engine.currentState)
        val before = physicalGuns()

        walkApart()
        world.advance(30_000L)
        assertTrue("a count-up is a race the link can be lost from", phoneNode.starts.linkLost && watchNode.starts.linkLost)

        walkBack()
        world.advance(2_000L)

        assertFalse(phoneNode.starts.linkLost || watchNode.starts.linkLost)
        assertEquals("both still counting up", TimerState.COUNTING_UP, phoneNode.engine.currentState)
        assertEquals(TimerState.COUNTING_UP, watchNode.engine.currentState)
        // Past the gun there is no countdown to move, so a check changes nothing on either device.
        assertEquals(before.first, physicalGun(phoneNode), 0.0)
        assertEquals(before.second, physicalGun(watchNode), 0.0)
        assertTrue(corrected(phoneNode).isEmpty() && corrected(watchNode).isEmpty())
        assertTrue(phoneNode.flaggedApart.isEmpty() && watchNode.flaggedApart.isEmpty())
    }

    @Test
    fun `a start made before the devices met is taken when they meet, as it would have been on time`() {
        linkUp()
        walkApart()
        phoneNode.tapStart(BuiltInSequences.usSailing)
        world.advance(5_000L)
        assertTrue("the positive control: the watch never heard of it", watchNode.joins.isEmpty())
        assertFalse("a race that never had the link has not lost it", phoneNode.starts.linkLost)

        walkBack()
        world.advance(2_000L)

        val join = watchNode.joins.single()
        assertEquals("the idle watch named nothing; the phone's check did this", 0, checksSentBy(watch))
        assertEquals("joined from idle, as #220's rule says", BuiltInSequences.usSailing, join.sequence)
        assertTrue("placed through the rounds held across the drop", join.race.gun.inBudget)
        assertTrue("guns ${gunGapMs()} ms apart", gunGapMs() <= join.race.gun.errorBoundMs!!)
        assertFalse(phoneNode.starts.linkLost || watchNode.starts.linkLost)
        assertBothFireTogether()
    }

    // D6 at its bound, through the simulated link: a gap of exactly 100 ms is corrected, 101 is not.

    /**
     * A race started on the **watch** and joined by the phone over a symmetric link with no drift, so
     * the join is exact; then taken apart with the phone's gun [skewMs] off, and brought back.
     *
     * Started on the watch on purpose. Under one key, the gun its own device set leads; where nothing
     * says so, the console does. A race started on the phone makes the two rules agree — the phone is
     * both — so a fixture there passes with the wire's anchored bit inverted (*measured*, #222's
     * mutation pass: 1 red against 3 predicted). Here they disagree, and only the anchored rule makes
     * the phone the one that follows.
     */
    private fun meetAgainWithPhoneGunOffBy(skewMs: Long) {
        watchPpm = 0L
        phoneToWatchMs = 30L
        watchToPhoneMs = 30L
        raceUnderWay(intoMs = 10_000L, on = { watchNode })
        assertEquals("the positive control: the fixture joins exactly", 0.0, gunGapMs(), 0.0)
        walkApart()
        phoneNode.skewGun(skewMs)
        world.advance(20_000L)
        walkBack()
        world.advance(2_000L)
    }

    @Test
    fun `a gap of exactly D6's bound is corrected on the device that follows, and said`() {
        for (skew in listOf(PAIR_RECONNECT_BOUND_MS, -PAIR_RECONNECT_BOUND_MS)) {
            val test = PairRaceTest()
            test.meetAgainWithPhoneGunOffBy(skew)

            assertEquals("skew $skew: the phone, which joined, follows", listOf(PairEvent.GunCorrected(-skew)), test.corrected(test.phoneNode))
            assertEquals("skew $skew: back on the watch's gun", 0.0, test.gunGapMs(), 0.0)
            assertTrue("skew $skew: the watch's own gun leads, and nothing moved there", test.watchNode.corrections.isEmpty())
            assertTrue(test.phoneNode.flaggedApart.isEmpty())
            assertEquals("skew $skew: both sent a check on meeting", 1, test.checksSentBy(test.watch))
        }
    }

    @Test
    fun `a gap one millisecond past D6's bound is never moved, and the device that follows is flagged for Sync`() {
        for (skew in listOf(PAIR_RECONNECT_BOUND_MS + 1L, -PAIR_RECONNECT_BOUND_MS - 1L)) {
            val test = PairRaceTest()
            test.meetAgainWithPhoneGunOffBy(skew)

            assertEquals("skew $skew: flagged, with the gap", listOf(-skew), test.phoneNode.flaggedApart)
            assertTrue("skew $skew: nothing corrected", test.phoneNode.corrections.isEmpty() && test.corrected(test.phoneNode).isEmpty())
            assertEquals("skew $skew: the gap stands until the officer syncs", abs(skew).toDouble(), test.gunGapMs(), 0.0)
            assertTrue(test.watchNode.flaggedApart.isEmpty() && test.watchNode.corrections.isEmpty())
        }
    }

    @Test
    fun `a Sync taken while apart is flagged on the device that missed it, and a Sync there settles both`() {
        raceUnderWay(intoMs = 8_000L) // 4:52
        walkApart()
        phoneNode.tapSync() // up to 5:00, 8 s later; the watch never hears of it
        assertEquals("the positive control: the phone's Sync went nowhere", 0, syncsSentBy(phone))
        world.advance(5_000L)
        val watchGun = physicalGun(watchNode)

        walkBack()
        world.advance(2_000L)

        val gap = watchNode.flaggedApart.single()
        assertEquals("the phone's gun is 8 s later", 8_000.0, gap.toDouble(), 30.0)
        assertEquals("beyond D6, the watch's gun is not moved by itself", watchGun, physicalGun(watchNode), 0.0)
        assertTrue("the phone's Sync leads, so the phone flags nothing", phoneNode.flaggedApart.isEmpty())

        watchNode.tapSync()
        world.advance(1_000L)
        val bound = phoneNode.moves.single().errorBoundMs!!
        assertTrue("one Sync on the watch puts both on one gun: ${gunGapMs()} ms, bound $bound", gunGapMs() <= bound)
    }

    @Test
    fun `a restart made while apart takes the other device over when they meet, and the phone offers the choice`() {
        raceUnderWay(intoMs = 5_000L)
        walkApart()
        watchNode.stop()
        watchNode.tapStart(BuiltInSequences.club) // tapped last, and the phone never heard of it
        world.advance(5_000L)
        assertEquals("the positive control: the phone still runs its own", BuiltInSequences.usSailing, phoneNode.engine.loadedSequence)

        walkBack()
        world.advance(2_000L)

        assertEquals("the later tap wins, as #220's rule says", BuiltInSequences.club, phoneNode.engine.loadedSequence)
        assertTrue("guns ${gunGapMs()} ms apart", gunGapMs() <= phoneNode.joins.last().race.gun.errorBoundMs!!)
        val contest = phoneNode.starts.contest ?: throw AssertionError("the phone offers the choice")
        assertEquals(BuiltInSequences.usSailing.id, contest.startedOn(here = true).sequenceId)
    }

    @Test
    fun `an End Race taken while apart is sent back when they meet, and both freeze at its time`() {
        raceUnderWay(intoMs = raceManager.totalMs + 30_000L, sequence = raceManager)
        walkApart()
        phoneNode.tapEndRace()
        val phoneEnd = phoneNode.engine.remainingMs
        world.advance(10_000L)
        assertEquals("the positive control: the watch counts on", TimerState.COUNTING_UP, watchNode.engine.currentState)

        walkBack()
        world.advance(2_000L)

        assertEquals(TimerState.RACE_ENDED, watchNode.engine.currentState)
        assertEquals("both freeze the phone's race time", phoneEnd, watchNode.engine.remainingMs)
        assertTrue("an end sent back is no stale control", watchNode.events.isEmpty())
    }

    @Test
    fun `a race stopped here is not rejoined when the devices meet`() {
        raceUnderWay(intoMs = 5_000L)
        walkApart()
        watchNode.stop() // Stop is local until #328
        world.advance(5_000L)

        walkBack()
        world.advance(2_000L)

        assertEquals(TimerState.IDLE, watchNode.engine.currentState)
        assertEquals("the one join is the race's own", 1, watchNode.joins.size)
        assertEquals("a device running nothing names no race", 0, checksSentBy(watch))
        assertEquals("the positive control: the phone, still racing, did", 1, checksSentBy(phone))
    }

    @Test
    fun `a race that had the peer only while it was being measured has still lost it when it goes`() {
        pair() // found and nearby, and not one round completes: the link says Measuring
        severed = true
        phoneNode.tapStart(BuiltInSequences.usSailing)
        world.advance(3_000L)
        assertEquals("the positive control: still measuring", PairStatus.Measuring, phoneNode.link.status())
        assertFalse(phoneNode.starts.linkLost)

        dropLink()

        assertTrue(phoneNode.starts.linkLost)
    }

    @Test
    fun `a restart from GO while apart is a race that never had the link, whatever the last one lost`() {
        raceUnderWay(intoMs = 10_000L, sequence = BuiltInSequences.club)
        walkApart()
        world.advance(BuiltInSequences.club.totalMs)
        assertNotNull("the positive control: the gun fired, apart", phoneNode.gunAt)
        assertEquals(TimerState.FINISHED, phoneNode.engine.currentState)

        // Start on the GO! screen, before the gun's teardown has ended anything (#327's window).
        phoneNode.tapStart(BuiltInSequences.club)

        assertFalse(phoneNode.starts.linkLost)
    }

    // AC 3: the absence of a pair is a normal state.

    @Test
    fun `a phone with no watch is never told a link is lost`() {
        present = false
        phoneNode.peer = watchNode
        phoneNode.link.onPeers(emptyList())
        phoneNode.link.setActive(true)
        phoneNode.tapStart(BuiltInSequences.usSailing)

        repeat(12) {
            world.advance(30_000L)
            assertFalse("at ${world.now} ms", phoneNode.starts.linkLost)
        }
        assertEquals("and nothing was sent", 0, checksSentBy(phone))
    }

    @Test
    fun `a watch reachable only through the cloud from the start is never a link lost`() {
        // A watch left at home on its charger: the Data Layer finds it through the cloud, never nearby.
        nearby = false
        pair()
        phoneNode.link.setActive(true)
        world.advance(5_000L)
        phoneNode.tapStart(BuiltInSequences.usSailing)

        repeat(12) {
            world.advance(30_000L)
            assertFalse("at ${world.now} ms", phoneNode.starts.linkLost)
        }
    }

    @Test
    fun `a lost link is no longer said once the race is over`() {
        raceUnderWay(intoMs = 10_000L)
        walkApart()
        world.advance(1_000L)
        assertTrue("the positive control", phoneNode.starts.linkLost)

        phoneNode.stop()

        assertFalse(phoneNode.starts.linkLost)
    }

    // D6's judgement at the seam, where the translation's own bound is set by hand.

    private fun raceFor(gunMs: Long, bound: Long?, gunKey: RaceKey = RaceKey(10L, 1L)) =
        PairRace(RaceKey(10L, 1L), BuiltInSequences.usSailing.id, JoinGun(gunMs, bound), startedHere = bound == 0L, gunKey = gunKey)

    private fun checkFor(gunMs: Long, bound: Long?, anchored: Boolean, gunKey: RaceKey = RaceKey(10L, 1L)) =
        PeerCheck(RaceKey(10L, 1L), gunKey, BuiltInSequences.usSailing.id, JoinGun(gunMs, bound), anchored)

    @Test
    fun `the gun set last is the one followed, then the one set on its own device, then the console's`() {
        val joined = raceFor(1_000L, bound = 30L)
        val tapped = raceFor(1_000L, bound = 0L)
        assertTrue("same key: a translated gun follows the peer's own", judgeGunCheck(joined, checkFor(1_050L, 30L, anchored = true), console = false) is GunCheck.Correct)
        assertEquals("same key: a gun set here leads", GunCheck.Leads, judgeGunCheck(tapped, checkFor(1_050L, 30L, anchored = false), console = false))
        assertTrue(
            "a later Sync leads, whoever set the gun",
            judgeGunCheck(tapped, checkFor(1_050L, 30L, anchored = false, gunKey = RaceKey(20L, 2L)), console = true) is GunCheck.Correct,
        )
        assertEquals(
            "an older one follows",
            GunCheck.Leads,
            judgeGunCheck(raceFor(1_000L, 30L, gunKey = RaceKey(20L, 2L)), checkFor(1_050L, 0L, anchored = true), console = false),
        )
        assertEquals("nothing else to go on: the console leads", GunCheck.Leads, judgeGunCheck(joined, checkFor(1_050L, 30L, anchored = false), console = true))
        assertTrue(judgeGunCheck(joined, checkFor(1_050L, 30L, anchored = false), console = false) is GunCheck.Correct)
    }

    @Test
    fun `a gap the link cannot place is flagged only when even its bound cannot bring it inside D6`() {
        val mine = raceFor(10_000L, bound = 30L)
        assertEquals("no rounds: nothing can be said", GunCheck.Unmeasured, judgeGunCheck(mine, checkFor(18_000L, null, anchored = true), console = false))
        // Out of D2's budget at ±300 ms: a 350 ms gap could be as little as 50, and one of 401 is past 100 whatever the error.
        assertEquals(GunCheck.Unmeasured, judgeGunCheck(mine, checkFor(10_350L, 300L, anchored = true), console = false))
        assertEquals(GunCheck.Unmeasured, judgeGunCheck(mine, checkFor(10_400L, 300L, anchored = true), console = false))
        assertEquals(GunCheck.Apart(401L), judgeGunCheck(mine, checkFor(10_401L, 300L, anchored = true), console = false))
        assertEquals("never corrected on a translation it cannot vouch for", GunCheck.Unmeasured, judgeGunCheck(mine, checkFor(10_010L, 300L, anchored = true), console = false))
    }

    // #222: the words.

    @Test
    fun `the lost-link line fits the watch's Tier 3 plate`() {
        val line = pairLinkLostLine("Phone")
        assertEquals("Phone out of range — counting alone", line)
        assertEquals(NOTICE_PAIR_LINK_LOST, line)
        assertTrue(line.length <= NOTICE_MAX_CHARS && MessageSurface.STATUS_LINE.holds(line))
    }

    @Test
    fun `the gap line reads milliseconds under a second and tenths above, and fits the plate`() {
        assertEquals("Phone gun 101 ms apart — tap Sync to confirm", pairGunsApartLine(101L, "Phone"))
        assertEquals("the sign is not the officer's concern", "Phone gun 101 ms apart — tap Sync to confirm", pairGunsApartLine(-101L, "Phone"))
        assertEquals("Watch gun 999 ms apart — tap Sync to confirm", pairGunsApartLine(999L, "Watch"))
        assertEquals("Watch gun 1.0 s apart — tap Sync to confirm", pairGunsApartLine(1_000L, "Watch"))
        assertEquals("Phone gun 8.0 s apart — tap Sync to confirm", pairGunsApartLine(7_981L, "Phone"))
        // A day apart is further than any race runs; it still fits.
        for (gap in listOf(101L, 999L, 59_999L, 86_400_000L)) {
            val line = pairGunsApartLine(gap, "Phone")
            assertTrue("\"$line\" overflows the plate", line.length <= NOTICE_MAX_CHARS && MessageSurface.STATUS_LINE.holds(line))
        }
    }

    @Test
    fun `the correction line fits the watch's banner at the bound`() {
        assertEquals("Phone back — gun matched, 40 ms", pairGunCorrectedLine(-40L, "Phone"))
        val line = pairGunCorrectedLine(PAIR_RECONNECT_BOUND_MS, "Phone")
        assertTrue("\"$line\" overflows the banner", line.length <= NOTICE_MAX_CHARS && MessageSurface.BANNER.holds(line))
    }

    private companion object {
        /** 2026-09-28 in wall-clock milliseconds, near enough: only differences are ever read. */
        const val WALL_EPOCH = 1_790_000_000_000L
    }
}
