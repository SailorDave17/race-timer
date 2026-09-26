package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue

/**
 * Covers [PairLink], the pair's offset-exchange protocol (#219), over a fake transport.
 *
 * Two devices run the real protocol against each other through [Air], a simulated Data Layer in
 * virtual time. Each device's clock is booted at an unrelated instant and may run fast, so the true
 * offset between them is known independently of the code under test, and an estimate is judged
 * against it rather than against a restatement of the same algebra — the approach
 * [GunTranslationTest] takes for the arithmetic underneath.
 */
class PairLinkTest {

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
    private class Device(
        val id: String,
        private val world: World,
        private val bootReading: Long,
        private val fastPpm: Long = 0L,
    ) : MonotonicClock {
        var jumpMs = 0L
        override fun elapsedMs(): Long = exactAt(world.now).toLong()
        fun exactAt(physical: Long): Double = bootReading + jumpMs + physical + physical * fastPpm / 1_000_000.0
    }

    /** The simulated Data Layer between [a] and [b]: per-direction delay, drops, and who is nearby. */
    private inner class Air(val world: World) {
        var aToBMs = 60L
        var bToAMs = 90L
        var nearby = true
        var available = true
        /** False when the other device is not running the app at all: a query then finds nobody. */
        var present = true
        var dropNext: ((from: String, payload: String) -> Boolean) = { _, _ -> false }
        val sent = mutableListOf<Pair<String, String>>()
        lateinit var a: Side
        lateinit var b: Side

        fun other(side: Side): Side = if (side === a) b else a

        fun transportFor(side: Side): PairTransport = object : PairTransport {
            override fun send(peerId: String, payload: ByteArray, onFailed: () -> Unit) {
                val text = String(payload, Charsets.UTF_8)
                sent += side.device.id to text
                val target = other(side)
                if (peerId != target.device.id || dropNext(side.device.id, text)) return
                val delay = if (side === a) aToBMs else bToAMs
                world.at(delay) { target.link.onMessage(side.device.id, payload, target.device.elapsedMs()) }
            }

            override fun queryPeers() {
                world.at(0L) {
                    if (available) {
                        side.link.onPeers(if (present) listOf(PeerNode(other(side).device.id, nearby)) else emptyList())
                    } else {
                        side.link.onUnavailable()
                    }
                }
            }
        }
    }

    private inner class Side(val device: Device, air: Air, world: World, firstId: Long) {
        val statuses = mutableListOf<PairStatus>()
        val link = PairLink(
            clock = device,
            transport = air.transportFor(this),
            scheduler = PairScheduler { delayMs, task -> world.at(delayMs, task) },
            onStatus = { statuses += it },
            firstRoundId = firstId,
        )
    }

    private val world = World()
    private val air = Air(world)
    // A phone up for ten hours and a watch up for three, the watch 10 ppm slow: #218's pair.
    private val phone = Device("phone-node", world, bootReading = 36_000_000L)
    private val watch = Device("watch-node", world, bootReading = 10_800_000L, fastPpm = -10L)

    init {
        air.a = Side(phone, air, world, firstId = 1_000L)
        air.b = Side(watch, air, world, firstId = 2_000L)
    }

    private val a: Side get() = air.a
    private val b: Side get() = air.b

    /** Both devices learn about each other, as the glue's start-up query does. */
    private fun discover() {
        a.link.onPeers(listOf(PeerNode(watch.id, air.nearby)))
        b.link.onPeers(listOf(PeerNode(phone.id, air.nearby)))
    }

    private fun PairStatus.linked(): PairStatus.Linked =
        this as? PairStatus.Linked ?: throw AssertionError("expected Linked, was $this")

    /** The true offset, [of]'s clock minus [from]'s, at this instant. */
    private fun trueOffset(of: Device, from: Device): Double = of.exactAt(world.now) - from.exactAt(world.now)

    private fun assertContainsTruth(status: PairStatus, of: Device, from: Device) {
        val linked = status.linked()
        val error = kotlin.math.abs(linked.offsetMs - trueOffset(of, from))
        assertTrue("offset ${linked.offsetMs} is ${error} ms from the truth, bound ${linked.errorBoundMs}", error <= linked.errorBoundMs)
    }

    private fun pingsFrom(device: Device) = air.sent.count { it.first == device.id && it.second.startsWith("rtpair1 ping") }

    // --- the link measures something real -------------------------------------------------------

    @Test
    fun `an active device measures the peer's offset and the truth lies inside the bound`() {
        discover()
        a.link.setActive(true)
        world.advance(5_000L)

        assertContainsTruth(a.link.status(), of = watch, from = phone)
        // 60 ms out and 90 ms back is a 150 ms round trip: ±75 ms plus a millisecond of reading
        // resolution each side. Five identical rounds intersect to the same interval, less the few
        // milliseconds of drift allowance at 30 ppm over under a second.
        val linked = a.link.status().linked()
        assertEquals(5, linked.rounds)
        assertTrue("bound ${linked.errorBoundMs}", linked.errorBoundMs in 76L..78L)
        assertTrue(linked.inBudget)
    }

    @Test
    fun `the answering device keeps the rounds too, and the two estimates agree to within their bounds`() {
        discover()
        a.link.setActive(true)
        world.advance(5_000L)

        // b never asked a round, and still holds all five, read from its own side.
        val fromWatch = b.link.status().linked()
        assertEquals(5, fromWatch.rounds)
        assertContainsTruth(fromWatch, of = phone, from = watch)
        val fromPhone = a.link.status().linked()
        assertTrue(kotlin.math.abs(fromPhone.offsetMs + fromWatch.offsetMs) <= 2L)
    }

    @Test
    fun `a delay that is all one way still leaves the truth inside the bound`() {
        // The midpoint is out by 80 ms here, which is the case the bound exists for (#217).
        air.aToBMs = 40L
        air.bToAMs = 200L
        discover()
        a.link.setActive(true)
        world.advance(5_000L)

        assertContainsTruth(a.link.status(), of = watch, from = phone)
        assertContainsTruth(b.link.status(), of = phone, from = watch)
    }

    @Test
    fun `both devices asking fills both stores with both directions`() {
        discover()
        a.link.setActive(true)
        b.link.setActive(true)
        world.advance(5_000L)

        assertEquals(5, pingsFrom(phone))
        assertEquals(5, pingsFrom(watch))
        assertEquals(10, a.link.status().linked().rounds)
        assertEquals(10, b.link.status().linked().rounds)
        assertContainsTruth(a.link.status(), of = watch, from = phone)
    }

    @Test
    fun `a drifting pair measured for ten minutes still contains the truth`() {
        discover()
        a.link.setActive(true)
        world.advance(600_000L)

        assertContainsTruth(a.link.status(), of = watch, from = phone)
        assertContainsTruth(b.link.status(), of = phone, from = watch)
    }

    // --- cadence --------------------------------------------------------------------------------

    // The windows below are written out, not derived from PairLink's constants: a wait computed from
    // the constant under test passes at every value of it (cairn prove-tests, shape 15). Five rounds
    // of 150 ms end the first burst at 750 ms, so the next is due at 30 750 ms exactly.

    @Test
    fun `an active device asks a burst of five, then another thirty seconds after it ends`() {
        discover()
        a.link.setActive(true)
        world.advance(30_749L)
        assertEquals(5, pingsFrom(phone))

        world.advance(1L)
        assertEquals(6, pingsFrom(phone))
        world.advance(5_000L)
        assertEquals(10, pingsFrom(phone))
    }

    @Test
    fun `an inactive device asks nothing and still answers`() {
        discover()
        b.link.setActive(true)
        world.advance(5_000L)
        a.link.setActive(true)
        a.link.setActive(false)
        world.advance(5_000L)
        val asked = pingsFrom(phone)

        world.advance(10 * PairLink.BURST_EVERY_MS)
        assertEquals(asked, pingsFrom(phone))
        assertTrue(air.sent.count { it.first == phone.id && it.second.startsWith("rtpair1 pong") } >= 10)
    }

    // --- rounds that go wrong -------------------------------------------------------------------

    @Test
    fun `a round with no reply times out and the burst moves on`() {
        discover()
        var dropped = 0
        air.dropNext = { from, text -> from == watch.id && text.startsWith("rtpair1 pong") && dropped++ < 1 }
        a.link.setActive(true)
        world.advance(4_999L)
        assertEquals(1, pingsFrom(phone))

        world.advance(1L)
        assertEquals(2, pingsFrom(phone))
        world.advance(5_000L)
        assertEquals(5, pingsFrom(phone))
        assertEquals(4, a.link.status().linked().rounds)
    }

    @Test
    fun `a reply that lands after its timeout is still kept, as a wide round`() {
        discover()
        air.bToAMs = 7_000L
        a.link.setActive(true)
        world.advance(60_000L)

        val linked = a.link.status().linked()
        assertTrue("rounds ${linked.rounds}", linked.rounds > 0)
        assertContainsTruth(linked, of = watch, from = phone)
        assertTrue(!linked.inBudget)
    }

    @Test
    fun `a reply from a device other than the one asked completes nothing`() {
        discover()
        a.link.setActive(true)
        world.advance(1L)
        a.link.onMessage("stranger", PairMessage.Pong(1_000L, 5L, 6L).encode(), phone.elapsedMs())
        world.advance(5_000L)

        assertEquals(5, a.link.status().linked().rounds)
        assertContainsTruth(a.link.status(), of = watch, from = phone)
    }

    @Test
    fun `a peer that reboots drops the history rather than going inconsistent`() {
        discover()
        a.link.setActive(true)
        world.advance(5_000L)
        // elapsedRealtime restarts at boot: the watch's clock falls three hours.
        watch.jumpMs = -10_000_000L
        world.advance(PairLink.BURST_EVERY_MS + 5_000L)

        val linked = a.link.status().linked()
        assertContainsTruth(linked, of = watch, from = phone)
        assertTrue("rounds ${linked.rounds}", linked.rounds in 1..5)
    }

    @Test
    fun `a peer that drops out and comes back keeps the rounds held before it went`() {
        discover()
        a.link.setActive(true)
        world.advance(5_000L)
        a.link.onPeers(emptyList())
        assertEquals(PairStatus.NoPeer, a.link.status())

        // The same device, back: the offset is held with no fresh round yet (#218's third
        // criterion). Read before any time passes, so no new round can have completed.
        a.link.onPeers(listOf(PeerNode(watch.id, nearby = true)))
        val held = a.link.status().linked()
        assertEquals(5, held.rounds)
        assertContainsTruth(held, of = watch, from = phone)
    }

    @Test
    fun `a new peer drops the old one's rounds`() {
        discover()
        a.link.setActive(true)
        world.advance(5_000L)
        a.link.onPeers(listOf(PeerNode("another-watch", nearby = true)))

        assertEquals(PairStatus.Measuring, a.link.status())
    }

    // --- nearby only ----------------------------------------------------------------------------

    @Test
    fun `a peer reachable only through the cloud is neither asked nor answered`() {
        air.nearby = false
        discover()
        a.link.setActive(true)
        world.advance(5_000L)
        assertEquals(0, pingsFrom(phone))
        assertEquals(PairStatus.NotNearby, a.link.status())

        b.link.onMessage(phone.id, PairMessage.Ping(7L).encode(), watch.elapsedMs())
        world.advance(1_000L)
        assertEquals(0, air.sent.count { it.first == watch.id })
    }

    @Test
    fun `a peer that comes into range is measured at once`() {
        air.nearby = false
        discover()
        a.link.setActive(true)
        world.advance(5_000L)

        air.nearby = true
        discover()
        world.advance(5_000L)
        assertEquals(5, pingsFrom(phone))
    }

    @Test
    fun `a peer that leaves range halfway through a burst is asked nothing more`() {
        discover()
        a.link.setActive(true)
        // The first round completes at 150 ms and the second ping leaves at once; the capability
        // change lands while it is in flight.
        world.advance(200L)
        a.link.onPeers(listOf(PeerNode(watch.id, nearby = false)))
        world.advance(20_000L)

        assertEquals(2, pingsFrom(phone))
        assertEquals(PairStatus.NotNearby, a.link.status())
    }

    @Test
    fun `a device without the Data Layer reports it and sends nothing`() {
        air.available = false
        a.link.setActive(true)
        world.advance(60_000L)

        assertEquals(PairStatus.Unavailable, a.link.status())
        assertTrue(air.sent.isEmpty())
    }

    @Test
    fun `no peer at all is NoPeer, nothing is asked, and the link keeps looking`() {
        // The watch knows the phone; the phone's queries find nobody until the watch app appears.
        b.link.onPeers(listOf(PeerNode(phone.id, nearby = true)))
        air.present = false
        a.link.setActive(true)
        world.advance(60_000L)
        assertEquals(PairStatus.NoPeer, a.link.status())
        assertEquals(0, pingsFrom(phone))

        air.present = true
        world.advance(35_000L)
        assertEquals(5, pingsFrom(phone))
        assertContainsTruth(a.link.status(), of = watch, from = phone)
    }

    // --- the wire -------------------------------------------------------------------------------

    @Test
    fun `every message survives the wire`() {
        val messages = listOf(
            PairMessage.Ping(-3L),
            PairMessage.Pong(Long.MAX_VALUE, 12L, 13L),
            PairMessage.Sample(1L, -2L, 3L, 4L),
        )
        messages.forEach { assertEquals(it, PairMessage.decode(it.encode())) }
    }

    @Test
    fun `a message that is not exactly one of the three is dropped`() {
        val rejected = listOf("", "rtpair1", "rtpair2 ping 1", "rtpair1 ping", "rtpair1 ping 1 2", "rtpair1 ping x", "rtpair1 pong 1 2", "rtpair1 gun 1")
        rejected.forEach { assertNull(it, PairMessage.decode(it.toByteArray(Charsets.UTF_8))) }
    }

    // --- the status row -------------------------------------------------------------------------

    @Test
    fun `the row stays off a device with nobody to pair with, except on a debuggable build`() {
        assertNull(pairStatusLine(PairStatus.NoPeer, "Watch", showAbsent = false))
        assertNull(pairStatusLine(PairStatus.Unavailable, "Watch", showAbsent = false))
        assertEquals("No watch found", pairStatusLine(PairStatus.NoPeer, "Watch", showAbsent = true))
        assertEquals("Pairing unavailable", pairStatusLine(PairStatus.Unavailable, "Watch", showAbsent = true))
    }

    @Test
    fun `a found peer is always reported`() {
        assertEquals("Phone out of range", pairStatusLine(PairStatus.NotNearby, "Phone", showAbsent = false))
        assertEquals("Phone found, measuring", pairStatusLine(PairStatus.Measuring, "Phone", showAbsent = false))
        assertEquals(
            "Watch -7:12:05.042 ±31 ms",
            pairStatusLine(PairStatus.Linked(-25_925_042L, 31L, inBudget = true, rounds = 5), "Watch", showAbsent = false),
        )
        assertEquals(
            "Watch +0:00:00.000 ±143 ms wide",
            pairStatusLine(PairStatus.Linked(0L, 143L, inBudget = false, rounds = 1), "Watch", showAbsent = false),
        )
    }

    @Test
    fun `an offset of days is still hours`() {
        assertEquals("+49:00:00.001", formatPairOffset(176_400_001L))
        assertEquals("-0:00:00.999", formatPairOffset(-999L))
    }
}
