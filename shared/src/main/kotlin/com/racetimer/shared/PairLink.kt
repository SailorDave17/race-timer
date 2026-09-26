package com.racetimer.shared

import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * Epic #196 decision D2, ratified at #218's gate on 2026-09-25: the largest worst-case disagreement
 * about one gun that "wrist and console never disagree" allows. Every translation the pair makes is
 * judged against it through [translateGun].
 */
const val PAIR_SKEW_BUDGET_MS: Long = 100L

/**
 * The drift rate the pair states to [translateGun]: three times the −10.1 ppm #218 measured between
 * the owner's SM-S918U and SM-R925U. The second of the three conditions D2 was ratified with, and
 * **provisional until a second phone and watch have been measured** (`docs/pair-skew.md`).
 */
const val PAIR_STATED_DRIFT_PPM: Long = 30L

/**
 * One device the Data Layer reports running the other app. [nearby] is the Data Layer's own
 * `Node.isNearby()`: true when it reaches the device directly over Bluetooth, false when only
 * through the cloud.
 */
data class PeerNode(val id: String, val nearby: Boolean)

/**
 * What [PairLink] needs from the Data Layer, and nothing else. The app modules implement it over
 * `MessageClient` and `CapabilityClient`; the tests implement it over a simulated link.
 *
 * Every callback the transport makes — [onFailed], and the answers to [queryPeers] — must arrive on
 * the thread that owns the [PairLink].
 */
interface PairTransport {

    /** Send [payload] to [peerId]. [onFailed] runs if the Data Layer refuses the send. */
    fun send(peerId: String, payload: ByteArray, onFailed: () -> Unit)

    /**
     * Ask which devices run the other app. The answer arrives later as [PairLink.onPeers], or as
     * [PairLink.onUnavailable] when this device cannot reach the Data Layer at all.
     */
    fun queryPeers()
}

/** Runs [task] on the link's thread after [delayMs]. The returned function cancels it. */
fun interface PairScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/** What this device knows about the pair, as the status row reads it. */
sealed interface PairStatus {

    /** This device cannot reach the Data Layer: no Google Play services, or nothing paired. */
    data object Unavailable : PairStatus

    /** Nothing running the other app is reachable. A phone with no watch spends its life here. */
    data object NoPeer : PairStatus

    /**
     * The other app is reachable only through the cloud. #218 measured that path at a median round
     * trip of ~820 ms with no burst inside 200 ms, so for timing it is no link at all, and nothing
     * is exchanged over it.
     */
    data object NotNearby : PairStatus

    /** The peer is nearby and no round has completed yet. */
    data object Measuring : PairStatus

    /**
     * The peer's clock minus this device's is [offsetMs], out by [errorBoundMs] at worst, from
     * [rounds] kept rounds. [inBudget] is [errorBoundMs] against [PAIR_SKEW_BUDGET_MS].
     */
    data class Linked(
        val offsetMs: Long,
        val errorBoundMs: Long,
        val inBudget: Boolean,
        val rounds: Int,
    ) : PairStatus
}

/**
 * The pair's clock link (#219, epic #196): the offset-exchange protocol, free of every Android type
 * so that all of it runs on the JVM. The Data Layer glue lives in `:shared-android` and does nothing
 * but carry bytes and stamps across.
 *
 * **A round** is #217's [OffsetSample]: this device sends a ping at t1, the peer stamps t2 as it
 * arrives and t3 as it replies, and the reply lands here at t4. Two things about the stamps keep every
 * round sound:
 *  - **Each is read as close to the wire as the glue allows, and a late read only widens.** t2 and
 *    t4 are read by the glue the moment a message is handed over; t1 and t3 are read here immediately
 *    before the send. A stamp read early on the way out, or late on the way in, moves an interval's
 *    edge outward, never inward, so it can cost width and never truth.
 *  - **A reply that arrives after its timeout is still a round.** The burst moves on without it, but
 *    the round is kept when it lands: it is as sound as any other, only wider (#218 measured replies
 *    arriving 30 s late after a reconnect).
 *
 * **Both devices ask, and each sends its completed rounds to the other.** #218 found the same link
 * 45 ms faster at the median when the watch asks and could not say why, so neither side is chosen to
 * lead: each runs its own bursts, and each keeps the other's rounds too. Both devices therefore hold
 * the same rounds, which is what lets their two estimates agree rather than merely overlap.
 *
 * **The frame.** Every kept round is stored with this device as [translateGun]'s
 * [ExchangeClock.REQUESTER] and the peer as its [ExchangeClock.RESPONDER], whoever actually asked.
 * A round the peer asked is the same four stamps with the legs read the other way round (see
 * [onMessage]), so θ is always the peer's clock minus this one's.
 *
 * **Only a nearby peer is asked, and only a nearby peer is answered.** The third condition D2 was
 * ratified with: a peer reachable only through the cloud is no peer for the gun. It is also what the
 * published privacy policy says about where these readings go, so it is enforced at both ends.
 *
 * **A clock that jumps drops the history.** `elapsedRealtime` restarts when a device reboots, so a
 * peer that rebooted makes every earlier round contradict every later one, and [translateGun]
 * reports [GunTranslation.Inconsistent]. The link then keeps only the newest round and starts again
 * from it. A different peer drops the history too; the same peer vanishing for a while and coming
 * back does not, because what it held before the drop is the offset a drop has to be ridden out on.
 *
 * **Not thread-safe, by design.** Every call, and every callback given to the transport and the
 * scheduler, must run on one thread. The glue gives the Data Layer that thread's looper, so incoming
 * messages already arrive on it and nothing is posted between a message landing and its stamp.
 *
 * @param firstRoundId where this process's round ids start. Random in production, so a reply to a
 *                     ping a previous process sent can never complete one this process sent.
 */
class PairLink(
    private val clock: MonotonicClock,
    private val transport: PairTransport,
    private val scheduler: PairScheduler,
    private val onStatus: (PairStatus) -> Unit = {},
    private val log: (String) -> Unit = {},
    firstRoundId: Long = Random.nextLong(),
) {

    private class Pending(val peerId: String, val sentMs: Long)

    private var unavailable = false
    private var peer: PeerNode? = null

    /** The device the kept rounds were measured against. Outlives [peer] going null, deliberately. */
    private var roundsPeerId: String? = null
    private val samples = ArrayDeque<OffsetSample>()
    private val pending = LinkedHashMap<Long, Pending>()
    private var inFlight: Long? = null
    private var burstRemaining = 0
    private var wantBurst = false
    private var active = false
    private var cancelNextBurst: (() -> Unit)? = null
    private var nextId = firstRoundId
    private var published: PairStatus? = null

    /**
     * Whether this device is on a screen that shows the link. While active it measures a burst
     * straight away and another every [BURST_EVERY_MS]. Inactive, it asks nothing, and still answers
     * every ping the peer sends, so one device on screen is enough for both to measure.
     */
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (active) {
            requestBurst()
        } else {
            wantBurst = false
            cancelNextBurst?.invoke()
            cancelNextBurst = null
        }
    }

    /**
     * Measure now: refresh the peer, then run a burst of [BURST_SIZE] rounds against it if it is
     * nearby. Does nothing while a burst is already running.
     */
    fun requestBurst() {
        if (unavailable || burstRemaining > 0) return
        wantBurst = true
        transport.queryPeers()
    }

    /** The devices the Data Layer reports running the other app, this device excluded. */
    fun onPeers(nodes: List<PeerNode>) {
        unavailable = false
        val previous = peer
        val chosen = nodes.firstOrNull { it.nearby } ?: nodes.firstOrNull()
        if (chosen?.id != previous?.id) {
            log("peer ${short(previous?.id)} -> ${short(chosen?.id)}")
        } else if (chosen?.nearby != previous?.nearby) {
            log("peer ${short(chosen?.id)} nearby=${chosen?.nearby}")
        }
        // A peer that vanishes and comes back is the same peer. A Bluetooth drop makes both ends lose
        // each other for about 8 s before the cloud finds them again (#218), and the rounds kept
        // before it are the held offset D6 depends on, so only a different device drops them.
        if (chosen != null && roundsPeerId != null && chosen.id != roundsPeerId) {
            forget()
            log("rounds dropped: a different peer")
        }
        if (chosen != null) roundsPeerId = chosen.id
        peer = chosen
        val becameUsable = chosen?.nearby == true && (previous?.id != chosen.id || previous.nearby != true)
        if (burstRemaining == 0 && (wantBurst || (active && becameUsable))) {
            wantBurst = false
            // With no nearby peer this asks nothing: [askNext] ends the burst before its first round
            // and schedules the next look. That check is the only one, so it also covers a peer that
            // leaves Bluetooth range halfway through a burst.
            beginBurst()
        }
        publish()
    }

    /** This device cannot reach the Data Layer. Nothing is asked or answered until [onPeers]. */
    fun onUnavailable() {
        unavailable = true
        peer = null
        roundsPeerId = null
        wantBurst = false
        forget()
        publish()
    }

    /**
     * A message from [fromId], handed over at [receivedMs] on this device's clock — read by the glue
     * the moment the Data Layer delivered it, before anything else ran.
     */
    fun onMessage(fromId: String, payload: ByteArray, receivedMs: Long) {
        when (val message = PairMessage.decode(payload)) {
            null -> log("unreadable message from ${short(fromId)}")
            is PairMessage.Ping -> answer(fromId, message, receivedMs)
            is PairMessage.Pong -> complete(fromId, message, receivedMs)
            // The peer asked this round, so its stamps are in the peer's frame. Read the other way
            // round they are a round this device asked: this device's reply is the "request" (sent
            // at its t3, received at the peer's t4) and the peer's question is the "reply" (sent at
            // the peer's t1, received at this device's t2). θ is still the peer minus this device:
            // θ ≤ t4 − t3 and θ ≥ t1 − t2, which is exactly what OffsetSample states.
            is PairMessage.Sample -> if (isNearbyPeer(fromId)) {
                keep(
                    OffsetSample(
                        requestSentMs = message.repliedMs,
                        requestReceivedMs = message.replyReceivedMs,
                        replySentMs = message.sentMs,
                        replyReceivedMs = message.receivedMs,
                    ),
                    askedBy = "peer",
                )
            }
        }
    }

    /** The current status, with the bound aged to now. */
    fun status(): PairStatus {
        if (unavailable) return PairStatus.Unavailable
        val p = peer ?: return PairStatus.NoPeer
        if (!p.nearby) return PairStatus.NotNearby
        val nowMs = clock.elapsedMs()
        return when (val t = translate(nowMs, PAIR_SKEW_BUDGET_MS)) {
            is GunTranslation.InBudget ->
                PairStatus.Linked(t.gunMs - nowMs, t.errorBoundMs, inBudget = true, rounds = samples.size)
            is GunTranslation.OutOfBudget ->
                PairStatus.Linked(t.bestEffortGunMs - nowMs, t.errorBoundMs, inBudget = false, rounds = samples.size)
            // No rounds yet. Inconsistent cannot survive [keep], which drops the history on it.
            GunTranslation.NoSamples, GunTranslation.Inconsistent -> PairStatus.Measuring
        }
    }

    // --- asking ---------------------------------------------------------------------------------

    private fun beginBurst() {
        cancelNextBurst?.invoke()
        cancelNextBurst = null
        burstRemaining = BURST_SIZE
        askNext()
    }

    /** The next round of the burst, one at a time, so no round queues behind another. */
    private fun askNext() {
        val p = peer
        if (burstRemaining == 0 || p == null || !p.nearby) {
            endBurst()
            return
        }
        burstRemaining--
        val id = nextId++
        val payload = PairMessage.Ping(id).encode()
        // t1, read after the payload is built and immediately before the send.
        val sentMs = clock.elapsedMs()
        pending.entries.removeAll { sentMs - it.value.sentMs > LATE_REPLY_WINDOW_MS }
        pending[id] = Pending(p.id, sentMs)
        inFlight = id
        transport.send(p.id, payload) { moveOn(id, "refused") }
        scheduler.schedule(ROUND_TIMEOUT_MS) { if (inFlight == id) moveOn(id, "timeout") }
    }

    /**
     * A round that will not be answered in time. A refused send has no reply coming, so it is
     * forgotten; a timed-out one stays pending, because its reply is still a sound round.
     */
    private fun moveOn(id: Long, why: String) {
        if (why == "refused") pending.remove(id)
        log("round $why")
        if (inFlight == id) {
            inFlight = null
            askNext()
        }
    }

    private fun endBurst() {
        burstRemaining = 0
        inFlight = null
        publish()
        if (active) scheduleNextBurst()
    }

    private fun scheduleNextBurst() {
        cancelNextBurst?.invoke()
        cancelNextBurst = scheduler.schedule(BURST_EVERY_MS) {
            cancelNextBurst = null
            if (active) requestBurst()
        }
    }

    /** This device's end of a round it asked: the reply is in, and [replyReceivedMs] is t4. */
    private fun complete(fromId: String, pong: PairMessage.Pong, replyReceivedMs: Long) {
        val asked = pending[pong.id]
        if (asked == null || asked.peerId != fromId) {
            log("reply from ${short(fromId)} matches no round asked")
            return
        }
        pending.remove(pong.id)
        val round = OffsetSample(asked.sentMs, pong.receivedMs, pong.repliedMs, replyReceivedMs)
        // −1 is the floor two floored readings can reach on a true round trip of zero (see
        // translateGun). Below it the four stamps cannot all be true, so the round is not kept.
        if (round.roundTripMs < -1L) {
            log("round refused: round trip ${round.roundTripMs} ms")
        } else if (keep(round, askedBy = "here") && isNearbyPeer(fromId)) {
            val sample = PairMessage.Sample(round.requestSentMs, round.requestReceivedMs, round.replySentMs, round.replyReceivedMs)
            transport.send(fromId, sample.encode()) { log("round not shared: refused") }
        }
        if (inFlight == pong.id) {
            inFlight = null
            askNext()
        }
    }

    // --- answering ------------------------------------------------------------------------------

    /** The peer's ping, which landed at [receivedMs] (t2). t3 is read here, just before the reply. */
    private fun answer(fromId: String, ping: PairMessage.Ping, receivedMs: Long) {
        if (!isNearbyPeer(fromId)) {
            log("ping from ${short(fromId)} not answered: not the nearby peer")
            return
        }
        val repliedMs = clock.elapsedMs()
        transport.send(fromId, PairMessage.Pong(ping.id, receivedMs, repliedMs).encode()) {
            log("reply refused")
        }
    }

    // --- the rounds -----------------------------------------------------------------------------

    /** Keep [round]; false when it was already held. */
    private fun keep(round: OffsetSample, askedBy: String): Boolean {
        if (round in samples) return false
        samples.addLast(round)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        log("round asked-by=$askedBy rtt=${round.roundTripMs} kept=${samples.size}")
        if (translate(clock.elapsedMs(), Long.MAX_VALUE) is GunTranslation.Inconsistent) {
            samples.clear()
            samples.addLast(round)
            log("rounds contradict each other: a clock jumped, history dropped")
        }
        publish()
        return true
    }

    private fun translate(nowMs: Long, budgetMs: Long): GunTranslation = translateGun(
        gunMs = nowMs,
        from = ExchangeClock.REQUESTER,
        samples = samples.toList(),
        maxDriftPpm = PAIR_STATED_DRIFT_PPM,
        budgetMs = budgetMs,
    )

    private fun forget() {
        samples.clear()
        pending.clear()
        inFlight = null
        burstRemaining = 0
    }

    private fun isNearbyPeer(id: String): Boolean = peer?.let { it.id == id && it.nearby } == true

    private fun publish() {
        val now = status()
        if (now != published) {
            published = now
            onStatus(now)
        }
    }

    companion object {
        /** Rounds per burst, back to back: the burst #218's analysis ratified the budget on. */
        const val BURST_SIZE = 5

        /** How long a round may take before the burst moves on without it. #218's harness value. */
        const val ROUND_TIMEOUT_MS = 5_000L

        /** How often an active device measures again. */
        const val BURST_EVERY_MS = 30_000L

        /**
         * The most rounds kept. Two devices each bursting five every 30 s fill it in ten minutes,
         * longer than any start sequence, and an older round only loosens with age anyway.
         */
        const val MAX_SAMPLES = 200

        /** How long a timed-out round waits for a late reply before it is forgotten. */
        const val LATE_REPLY_WINDOW_MS = 60_000L

        /** Node ids are opaque per-pairing tokens; six characters tell two apart in a log. */
        private fun short(id: String?): String = id?.take(6) ?: "-"
    }
}

/**
 * The three messages the pair exchanges, and their wire form: one line of UTF-8 text, a version tag
 * first. A device that reads a tag it does not know drops the message, so two app versions that
 * disagree about the format have no link rather than a wrong one.
 */
internal sealed interface PairMessage {

    fun encode(): ByteArray

    /** This device asks round [id]. */
    data class Ping(val id: Long) : PairMessage {
        override fun encode() = wire("ping", id)
    }

    /** The answer to round [id]: when its ping arrived and when this reply left, on the answering clock. */
    data class Pong(val id: Long, val receivedMs: Long, val repliedMs: Long) : PairMessage {
        override fun encode() = wire("pong", id, receivedMs, repliedMs)
    }

    /** A round the sender asked and completed, as it holds it, so the answering device keeps it too. */
    data class Sample(
        val sentMs: Long,
        val receivedMs: Long,
        val repliedMs: Long,
        val replyReceivedMs: Long,
    ) : PairMessage {
        override fun encode() = wire("sample", sentMs, receivedMs, repliedMs, replyReceivedMs)
    }

    companion object {
        const val VERSION = "rtpair1"

        private fun wire(kind: String, vararg fields: Long): ByteArray =
            (listOf(VERSION, kind) + fields.map { it.toString() }).joinToString(" ").toByteArray(Charsets.UTF_8)

        fun decode(payload: ByteArray): PairMessage? {
            val parts = String(payload, Charsets.UTF_8).split(' ')
            if (parts.size < 2 || parts[0] != VERSION) return null
            val fields = parts.drop(2).map { it.toLongOrNull() ?: return null }
            return when (parts[1]) {
                "ping" -> if (fields.size == 1) Ping(fields[0]) else null
                "pong" -> if (fields.size == 3) Pong(fields[0], fields[1], fields[2]) else null
                "sample" -> if (fields.size == 4) Sample(fields[0], fields[1], fields[2], fields[3]) else null
                else -> null
            }
        }
    }
}

/**
 * The pre-start status row (#219), or null when the row is not drawn.
 *
 * A device with nobody to pair with shows nothing: a sailor with no watch gets the complete timer,
 * not a companion demo (epic #196's third outcome), and a watch-only sailor is not told about a phone
 * app. So [PairStatus.Unavailable] and [PairStatus.NoPeer] draw a row only when [showAbsent] — a
 * debuggable build, where "why can it not see the other device" is the question being asked.
 *
 * @param peerNoun what the other device is called on this screen: "Watch" on the phone, "Phone" on
 *                 the watch.
 */
fun pairStatusLine(status: PairStatus, peerNoun: String, showAbsent: Boolean): String? = when (status) {
    PairStatus.Unavailable -> if (showAbsent) "Pairing unavailable" else null
    PairStatus.NoPeer -> if (showAbsent) "No ${peerNoun.lowercase(Locale.ROOT)} found" else null
    PairStatus.NotNearby -> "$peerNoun out of range"
    PairStatus.Measuring -> "$peerNoun found, measuring"
    is PairStatus.Linked ->
        "$peerNoun ${formatPairOffset(status.offsetMs)} ±${status.errorBoundMs} ms" +
            if (status.inBudget) "" else " wide"
}

/**
 * [offsetMs] as a signed `h:mm:ss.mmm`, hours unbounded. The offset between two `elapsedRealtime`
 * clocks is the difference between the devices' boot times, so it is routinely hours or days: it
 * means nothing to a sailor, and it is the number to hold against `adb shell cat /proc/uptime` on the
 * two devices when checking that the link measured something real.
 */
fun formatPairOffset(offsetMs: Long): String {
    val sign = if (offsetMs < 0L) "-" else "+"
    val magnitude = if (offsetMs == Long.MIN_VALUE) Long.MAX_VALUE else abs(offsetMs)
    return String.format(
        Locale.ROOT,
        "%s%d:%02d:%02d.%03d",
        sign,
        magnitude / 3_600_000L,
        magnitude / 60_000L % 60L,
        magnitude / 1_000L % 60L,
        magnitude % 1_000L,
    )
}
