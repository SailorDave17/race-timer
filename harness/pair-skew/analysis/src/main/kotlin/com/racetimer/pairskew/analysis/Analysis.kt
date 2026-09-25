package com.racetimer.pairskew.analysis

import com.racetimer.pairskew.protocol.Harness
import com.racetimer.pairskew.protocol.Record
import com.racetimer.pairskew.protocol.Round
import com.racetimer.pairskew.protocol.Side
import com.racetimer.pairskew.protocol.floorMs
import com.racetimer.shared.ExchangeClock
import com.racetimer.shared.GunTranslation
import com.racetimer.shared.OffsetSample
import com.racetimer.shared.translateGun
import kotlin.math.abs

/** Which path the Data Layer used for a round, as the requester read the peer's `isNearby` before it. */
enum class Transport(val label: String) {
    BLUETOOTH("Bluetooth (peer nearby)"),
    CLOUD("cloud (peer not nearby)"),
    UNKNOWN("unknown"),
}

fun Round.transport(): Transport = when (nearby) {
    true -> Transport.BLUETOOTH
    false -> Transport.CLOUD
    null -> Transport.UNKNOWN
}

/** θ (the watch's clock minus the phone's) at an instant, and translateGun's worst-case bound on it. */
data class Estimate(val offsetMs: Long, val boundMs: Long)

/** θ at [phoneMs], a phone-clock instant, from [samples]: #217's translateGun, unchanged. */
fun translateAt(phoneMs: Long, samples: List<OffsetSample>, ppm: Long): GunTranslation =
    translateGun(phoneMs, ExchangeClock.REQUESTER, samples, ppm, Harness.NO_BUDGET)

fun GunTranslation.estimateAt(phoneMs: Long): Estimate? = when (this) {
    is GunTranslation.InBudget -> Estimate(gunMs - phoneMs, errorBoundMs)
    is GunTranslation.OutOfBudget -> Estimate(bestEffortGunMs - phoneMs, errorBoundMs)
    GunTranslation.NoSamples, GunTranslation.Inconsistent -> null
}

/**
 * The numbers #218 is written up from, computed over one [Capture].
 *
 * [ppm] is the drift rate stated to translateGun wherever a bound is aged. It defaults to the rate the
 * harness stated on the devices, so the analysis and the on-device translations agree.
 */
class Analysis(
    val capture: Capture,
    val ppm: Long = Harness.STATED_DRIFT_PPM,
    val windowMs: Long = 60_000L,
) {
    val rounds: List<Round> = capture.rounds
    private val windowNs = windowMs * 1_000_000L

    /**
     * One round judged against the rounds around it. [own] is what the round says alone; [reference]
     * is what every *other* round within [windowMs] says, intersected. Independence matters: a
     * reference that included the round would agree with it by construction.
     */
    data class Judged(val round: Round, val own: Estimate, val reference: Estimate?, val referenceSize: Int, val referenceInconsistent: Boolean) {
        /** How far this round's midpoint sits from its neighbours' — its scatter. */
        val errorMs: Long? get() = reference?.let { own.offsetMs - it.offsetMs }

        /**
         * Two sound intervals must overlap. A gap wider than both bounds together means the data
         * contradict each other: a wrong stamp, or a drift faster than [ppm].
         */
        val contradicts: Boolean
            get() = reference != null && abs(own.offsetMs - reference.offsetMs) > own.boundMs + reference.boundMs
    }

    val judged: List<Judged> by lazy {
        val out = ArrayList<Judged>(rounds.size)
        var lo = 0
        var hi = 0
        for ((i, round) in rounds.withIndex()) {
            val at = round.phoneAtNs
            while (rounds[lo].phoneAtNs < at - windowNs) lo++
            while (hi < rounds.size && rounds[hi].phoneAtNs <= at + windowNs) hi++
            val atMs = floorMs(at)
            val own = translateAt(atMs, listOf(round.phoneFrameMs()), ppm).estimateAt(atMs) ?: continue
            val neighbours = (lo until hi).filter { it != i }.map { rounds[it].phoneFrameMs() }
            val reference = translateAt(atMs, neighbours, ppm)
            out += Judged(round, own, reference.estimateAt(atMs), neighbours.size, reference == GunTranslation.Inconsistent)
        }
        out
    }

    /** The relative rate of the two clocks, fitted to the tightest rounds' midpoints. */
    data class Drift(val ppm: Double, val ppmStdErr: Double, val n: Int, val spanMin: Double, val residualRmsMs: Double, val basis: String)

    val drift: Drift? by lazy {
        val bluetooth = rounds.filter { it.transport() == Transport.BLUETOOTH }
        val pool = if (bluetooth.size >= 20) bluetooth else rounds
        if (pool.size < 10) return@lazy null
        val cut = quantile(pool.map { it.rttNs.toDouble() }, 0.25)
        val tight = pool.filter { it.rttNs <= cut }
        if (tight.size < 3) return@lazy null
        val x0 = tight.first().phoneAtNs
        val y0 = tight.first().offsetMidNs
        val xs = tight.map { (it.phoneAtNs - x0) / 1e9 }
        val ys = tight.map { (it.offsetMidNs - y0) / 1e6 }
        if (xs.distinct().size < 2) return@lazy null
        val fit = fitLine(xs, ys)
        // The slope is milliseconds of θ per second of phone time; one ms per s is 1000 ppm.
        Drift(
            ppm = fit.slope * 1000.0,
            ppmStdErr = fit.slopeStdErr * 1000.0,
            n = fit.n,
            spanMin = (xs.max() - xs.min()) / 60.0,
            residualRmsMs = fit.residualRms,
            basis = (if (pool === bluetooth) "Bluetooth rounds" else "all rounds") + " at or under the 25th-percentile round trip",
        )
    }

    /** One burst of back-to-back rounds, asked by one side. */
    data class Burst(val requester: Side, val run: Long, val burst: Long, val rounds: List<Round>) {
        val transport: Transport? get() = rounds.map { it.transport() }.distinct().singleOrNull()
        val endNs: Long get() = rounds.maxOf { it.phoneAtNs }
    }

    val bursts: List<Burst> by lazy {
        rounds.groupBy { Triple(it.requester, it.run, it.burst) }
            .map { (key, members) -> Burst(key.first, key.second, key.third, members) }
            .sortedBy { it.endNs }
    }

    /** The bound a production link would report for a gun [aheadMs] after this burst, from its rounds alone. */
    fun burstBound(burst: Burst, aheadMs: Long, ppm: Long = this.ppm): Long? {
        val atMs = floorMs(burst.endNs) + aheadMs
        return translateAt(atMs, burst.rounds.map { it.phoneFrameMs() }, ppm).estimateAt(atMs)?.boundMs
    }

    /** The bound every round of the last [lookbackMs] gives at the end of [burst]: what accumulating buys. */
    fun lookbackBound(burst: Burst, lookbackMs: Long): Long? {
        val end = burst.endNs
        val window = rounds.filter { it.phoneAtNs in (end - lookbackMs * 1_000_000L)..end }.map { it.phoneFrameMs() }
        val atMs = floorMs(end)
        return translateAt(atMs, window, ppm).estimateAt(atMs)?.boundMs
    }

    /** A stretch with no completed round, long enough that at least one burst went missing. */
    data class Gap(val before: Round, val after: Round) {
        val durationMs: Double get() = (after.phoneAtNs - before.phoneAtNs) / 1e6
    }

    val gaps: List<Gap> by lazy {
        rounds.zipWithNext().filter { (a, b) -> b.phoneAtNs - a.phoneAtNs > GAP_NS }.map { (a, b) -> Gap(a, b) }
    }

    /** θ just before [gap], from the minute of rounds ending at it, and just after, from the minute starting at it. */
    fun edges(gap: Gap): Pair<Estimate?, Estimate?> {
        val beforeNs = gap.before.phoneAtNs
        val afterNs = gap.after.phoneAtNs
        val pre = rounds.filter { it.phoneAtNs in (beforeNs - windowNs)..beforeNs }.map { it.phoneFrameMs() }
        val post = rounds.filter { it.phoneAtNs in afterNs..(afterNs + windowNs) }.map { it.phoneFrameMs() }
        return translateAt(floorMs(beforeNs), pre, ppm).estimateAt(floorMs(beforeNs)) to
            translateAt(floorMs(afterNs), post, ppm).estimateAt(floorMs(afterNs))
    }

    /**
     * A phone-clock instant for [atNs], read on [side]'s clock. The watch's readings are moved by the
     * midpoint of the round nearest in time, which is good to a round's width — ample for placing a
     * log line in a minute of timeline, and never used for a skew.
     */
    fun phoneNs(side: Side, atNs: Long): Long {
        if (side == Side.PHONE || rounds.isEmpty()) return atNs
        val guess = atNs - rounds.first().offsetMidNs.toLong()
        val nearest = rounds.minByOrNull { abs(it.phoneAtNs - guess) }!!
        return atNs - nearest.offsetMidNs.toLong()
    }

    fun phoneNs(record: Record): Long = phoneNs(Side.of(record.text("side")), record.long("at"))

    /** The fresh estimate of θ at a phone-clock instant, from the rounds within [windowMs] either side of it. */
    fun freshAt(phoneMs: Long): Estimate? {
        val atNs = phoneMs * 1_000_000L
        val window = rounds.filter { abs(it.phoneAtNs - atNs) <= windowNs }.map { it.phoneFrameMs() }
        return translateAt(phoneMs, window, ppm).estimateAt(phoneMs)
    }

    companion object {
        /** Bursts start every ~11 s, so 25 s without a completed round means at least one burst was lost. */
        const val GAP_NS = 25_000_000_000L
    }
}
