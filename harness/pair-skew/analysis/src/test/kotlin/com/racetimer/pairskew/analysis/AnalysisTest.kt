package com.racetimer.pairskew.analysis

import com.racetimer.pairskew.protocol.Primitive
import com.racetimer.pairskew.protocol.Record
import com.racetimer.pairskew.protocol.Round
import com.racetimer.pairskew.protocol.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The instrument against rounds whose truth is known: two clocks with a set offset and drift, delays
 * that differ each way and each round. If the analysis cannot find the drift it was given, cannot see
 * a delay injected on one leg, or reports sound data as contradicting itself, nothing it says about a
 * real run means anything.
 */
class AnalysisTest {

    private val ms = 1_000_000L
    private val start = 5_000_000L * ms
    private val theta0 = 3_600_000L * ms
    private val ppm = 30.0

    /** θ, the watch's clock minus the phone's, at phone time [p] — drifting at [ppm]. */
    private fun theta(p: Long): Long = theta0 + ((p - start) * ppm / 1e6).toLong()

    /** A deterministic stream of delays, so every run of the suite sees the same session. */
    private class Delays(private var s: Long) {
        fun next(loMs: Int, hiMs: Int): Long {
            s = s * 6364136223846793005L + 1442695040888963407L
            val u = (s ushr 11).toDouble() / (1L shl 53).toDouble()
            return ((loMs + u * (hiMs - loMs)) * 1_000_000).toLong()
        }
    }

    private fun phoneAsks(p1: Long, out: Long, back: Long, id: Long): Round {
        val p2 = p1 + out
        val t2 = p2 + theta(p2)
        val t3 = t2 + 2 * ms
        val t4 = t3 - theta(p2) + back
        return Round(Side.PHONE, 1, id, Primitive.MSG, true, id / 5, (id % 5).toInt() + 1, p1, t2, t3, t4)
    }

    private fun watchAsks(q: Long, out: Long, back: Long, id: Long): Round {
        val t2 = q + out
        val t3 = t2 + 2 * ms
        val arrives = t3 + back
        return Round(Side.WEAR, 1, id, Primitive.RPC, true, id / 5, (id % 5).toInt() + 1, q + theta(q), t2, t3, arrives + theta(arrives))
    }

    /** Thirty minutes, a round every two seconds, the two sides taking turns to ask. */
    private fun session(inject: (Long, Long) -> Long = { _, out -> out }, skip: (Long) -> Boolean = { false }): List<Round> {
        val delays = Delays(218)
        val rounds = ArrayList<Round>()
        for (id in 0L until 900L) {
            val at = start + id * 2_000 * ms
            val out = inject(id, delays.next(5, 30))
            val back = delays.next(5, 30)
            if (skip(at)) continue
            rounds += if (id % 2 == 0L) phoneAsks(at, out, back, id) else watchAsks(at, out, back, id)
        }
        return rounds
    }

    private fun analyse(rounds: List<Round>) =
        Analysis(Capture.of(rounds.map { Record("ROUND", it.fields()) }))

    @Test
    fun `the drift fit recovers the rate the clocks were given`() {
        val drift = analyse(session()).drift!!
        assertEquals(ppm, drift.ppm, 3.0)
    }

    @Test
    fun `sound rounds never contradict their neighbours`() {
        val judged = analyse(session()).judged
        assertEquals(900, judged.size)
        assertEquals(0, judged.count { it.contradicts })
        assertEquals(0, judged.count { it.referenceInconsistent })
    }

    @Test
    fun `a delay injected on one leg of one round shows up as half its size`() {
        val victim = 450L
        val a = analyse(session(inject = { id, out -> if (id == victim) out + 80 * ms else out }))
        val judged = a.judged.single { it.round.id == victim }
        // The round's own asymmetry is (out - back) / 2, within 12.5 ms either way; the injection adds 40.
        assertTrue("error ${judged.errorMs}", judged.errorMs!! in 25L..55L)
        assertEquals(victim, a.judged.maxByOrNull { abs(it.errorMs ?: 0L) }!!.round.id)
    }

    /**
     * Every round but one runs 5 ms out and 25 ms back, so each midpoint sits 10 ms below θ and the
     * neighbours agree on θ − 10. The one round runs 1 ms each way, midpoint on θ, interval ±2 ms.
     * Judged against its neighbours it is 10 ms off them. A reference that let the round vote for
     * itself would clip to its ±2 ms and report 0: agreement by construction, and scatter unmeasured.
     */
    @Test
    fun `a round is judged against its neighbours only, never against itself`() {
        val victim = 450L
        val rounds = (0L until 900L).map { id ->
            val at = start + id * 2_000 * ms
            if (id == victim) phoneAsks(at, 1 * ms, 1 * ms, id) else phoneAsks(at, 5 * ms, 25 * ms, id)
        }
        val judged = analyse(rounds).judged.single { it.round.id == victim }
        assertEquals(10.0, judged.errorMs!!.toDouble(), 2.0)
    }

    @Test
    fun `a gap is found, and its jump is the drift across it`() {
        val from = start + 10 * 60_000 * ms
        val to = start + 12 * 60_000 * ms
        val a = analyse(session(skip = { it in from until to }))
        val gap = a.gaps.single()
        assertEquals(122.0, gap.durationMs / 1000.0, 2.0)
        val (before, after) = a.edges(gap)
        val jump = after!!.offsetMs - before!!.offsetMs
        val drifted = ppm * gap.durationMs / 1e6
        assertTrue("jump $jump against $drifted", abs(jump - drifted) <= before.boundMs + after.boundMs)
    }

    @Test
    fun `a burst is never looser than its tightest round`() {
        val a = analyse(session())
        for (burst in a.bursts) {
            val single = burst.rounds.minOf { r ->
                a.burstBound(Analysis.Burst(r.requester, r.run, r.burst, listOf(r)), 0L)!!
            }
            assertTrue(a.burstBound(burst, 0L)!! <= single)
        }
    }

    @Test
    fun `one round read twice is one round, and a late answer is not also a failure`() {
        val round = session().first()
        val records = listOf(
            Record("ROUND", round.fields().apply { put("late", "1") }),
            Record("ROUND", round.fields().apply { put("late", "1") }),
            Record("SAMPLE", round.fields()),
            Record("FAIL", linkedMapOf("req" to "phone", "run" to "1", "id" to "${round.id}", "reason" to "timeout", "at" to "1", "side" to "phone")),
            Record("FAIL", linkedMapOf("req" to "phone", "run" to "1", "id" to "999", "reason" to "timeout", "at" to "2", "side" to "phone")),
        )
        val capture = Capture.of(records)
        assertEquals(1, capture.rounds.size)
        assertEquals(1, capture.late.size)
        assertEquals(listOf("999"), capture.failures.map { it["id"] })
    }

    @Test
    fun `quantile interpolates between order statistics`() {
        val xs = listOf(4.0, 1.0, 3.0, 2.0)
        assertEquals(1.0, quantile(xs, 0.0), 0.0)
        assertEquals(2.5, quantile(xs, 0.5), 1e-12)
        assertEquals(3.7, quantile(xs, 0.9), 1e-12)
        assertEquals(4.0, quantile(xs, 1.0), 0.0)
    }

    @Test
    fun `a straight line fits exactly`() {
        val fit = fitLine(listOf(0.0, 1.0, 2.0, 3.0), listOf(1.0, 3.0, 5.0, 7.0))
        assertEquals(2.0, fit.slope, 1e-12)
        assertEquals(1.0, fit.intercept, 1e-12)
        assertEquals(0.0, fit.residualRms, 1e-12)
    }
}
