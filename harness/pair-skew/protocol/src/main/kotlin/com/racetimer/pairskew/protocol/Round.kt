package com.racetimer.pairskew.protocol

import com.racetimer.shared.OffsetSample

enum class Side(val wire: String) {
    PHONE("phone"),
    WEAR("wear");

    companion object {
        fun of(wire: String): Side = entries.firstOrNull { it.wire == wire } ?: throw MalformedRecord("no side '$wire'")
    }
}

/** Which Data Layer call carried a round: a message each way, or one request and its reply. */
enum class Primitive(val wire: String) {
    MSG("msg"),
    RPC("rpc");

    companion object {
        fun of(wire: String): Primitive =
            entries.firstOrNull { it.wire == wire } ?: throw MalformedRecord("no primitive '$wire'")
    }
}

fun Boolean?.wire(): String = when (this) {
    true -> "1"
    false -> "0"
    null -> "-"
}

/** `elapsedRealtime()` is `elapsedRealtimeNanos()` floored to whole milliseconds; so is this. */
fun floorMs(ns: Long): Long = Math.floorDiv(ns, 1_000_000L)

/**
 * One completed offset-exchange round. [t1Ns] and [t4Ns] were read on the [requester]'s clock, [t2Ns]
 * and [t3Ns] on the other device's, all four from `SystemClock.elapsedRealtimeNanos()`.
 *
 * A round is identified by who asked and when: [key]. [id] is a per-process counter for a reader's
 * convenience. It restarts with the app, which is why [run], the requesting process's start, is part
 * of every reference to a round.
 *
 * [nearby] is the requester's reading of the peer's `Node.isNearby()` just before [t1Ns]: true when
 * the Data Layer reaches it directly (Bluetooth), false when only through the cloud.
 */
data class Round(
    val requester: Side,
    val run: Long,
    val id: Long,
    val primitive: Primitive,
    val nearby: Boolean?,
    val burst: Long,
    val seq: Int,
    val t1Ns: Long,
    val t2Ns: Long,
    val t3Ns: Long,
    val t4Ns: Long,
) {
    val key: Triple<Side, Long, Long> get() = Triple(requester, run, t1Ns)

    /** Time in flight, in nanoseconds: the requester's whole round less the responder's turnaround. */
    val rttNs: Long get() = (t4Ns - t1Ns) - (t3Ns - t2Ns)

    /**
     * The round as #217 reads one, in the harness's single frame, floored to whole milliseconds exactly
     * as production's `elapsedRealtime()` reads would be. **In that frame the phone is always the
     * requester clock and the watch the responder**, whichever device actually asked, so one list of
     * samples serves every translation in either direction.
     *
     * A watch-initiated round is the same four stamps with the legs read the other way round. θ is the
     * watch's clock minus the phone's throughout. The watch asked at t1 and the phone heard it at t2,
     * so θ ≥ t1 − t2. The phone answered at t3 and the watch heard it at t4, so θ ≤ t4 − t3. #217's
     * sample states θ ≤ requestReceived − requestSent and θ ≥ replySent − replyReceived, which this
     * round meets with the phone's answer as the "request" (sent t3, received t4) and the watch's
     * question as the "reply" (sent t1, received t2). The interval, and so the round trip, is unchanged.
     */
    fun phoneFrameMs(): OffsetSample = when (requester) {
        Side.PHONE -> OffsetSample(floorMs(t1Ns), floorMs(t2Ns), floorMs(t3Ns), floorMs(t4Ns))
        Side.WEAR -> OffsetSample(floorMs(t3Ns), floorMs(t4Ns), floorMs(t1Ns), floorMs(t2Ns))
    }

    /** The phone-clock instant this round measured at: halfway between the phone's two stamps. */
    val phoneAtNs: Long
        get() = when (requester) {
            Side.PHONE -> t1Ns + (t4Ns - t1Ns) / 2
            Side.WEAR -> t2Ns + (t3Ns - t2Ns) / 2
        }

    /**
     * The RTT-halving estimate of θ (watch minus phone) at full resolution, in nanoseconds — the
     * midpoint of the same interval [phoneFrameMs] states, before any flooring.
     */
    val offsetMidNs: Double
        get() {
            val high: Long
            val low: Long
            when (requester) {
                Side.PHONE -> { high = t2Ns - t1Ns; low = t3Ns - t4Ns }
                Side.WEAR -> { high = t4Ns - t3Ns; low = t1Ns - t2Ns }
            }
            return (high.toDouble() + low.toDouble()) / 2.0
        }

    fun fields(): LinkedHashMap<String, String> = linkedMapOf(
        "req" to requester.wire,
        "run" to "$run",
        "id" to "$id",
        "prim" to primitive.wire,
        "near" to nearby.wire(),
        "burst" to "$burst",
        "seq" to "$seq",
        "t1" to "$t1Ns",
        "t2" to "$t2Ns",
        "t3" to "$t3Ns",
        "t4" to "$t4Ns",
    )

    companion object {
        /** A `ROUND` line (the requester's) or a `SAMPLE` line (the responder's copy of it). */
        fun of(record: Record): Round = Round(
            requester = Side.of(record.text("req")),
            run = record.long("run"),
            id = record.long("id"),
            primitive = Primitive.of(record.text("prim")),
            nearby = record.flag("near"),
            burst = record.long("burst"),
            seq = record.long("seq").toInt(),
            t1Ns = record.long("t1"),
            t2Ns = record.long("t2"),
            t3Ns = record.long("t3"),
            t4Ns = record.long("t4"),
        )
    }
}
