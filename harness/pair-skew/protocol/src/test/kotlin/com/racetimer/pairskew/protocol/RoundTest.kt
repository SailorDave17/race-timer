package com.racetimer.pairskew.protocol

import com.racetimer.shared.ExchangeClock
import com.racetimer.shared.GunTranslation
import com.racetimer.shared.OffsetSample
import com.racetimer.shared.translateGun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundTest {

    private val ms = 1_000_000L

    /** θ, the watch's clock minus the phone's, for every round below. */
    private val theta = 7_000_000L * ms

    @Test
    fun `a phone-initiated round is its own stamps, floored as elapsedRealtime floors`() {
        val r = Round(Side.PHONE, 1, 1, Primitive.MSG, true, 1, 1, 1_999_999, 5_000_000_001, 5_000_300_000, 45_999_999)
        assertEquals(OffsetSample(1, 5_000, 5_000, 45), r.phoneFrameMs())
    }

    @Test
    fun `floorMs floors below zero too, as a monotonic difference can be negative`() {
        assertEquals(-1L, floorMs(-1L))
        assertEquals(-1L, floorMs(-1_000_000L))
        assertEquals(0L, floorMs(999_999L))
    }

    /**
     * The watch asks at phone time 100 s. Its request takes 40 ms to reach the phone, the phone takes
     * 3 ms to answer, and the answer takes 200 ms back — so the midpoint sits (200 − 40) / 2 = 80 ms off
     * the true θ, toward the slower phone-to-watch leg, exactly as a phone-initiated round with the same
     * directional delays would.
     */
    private fun watchAsks(phoneAt: Long, watchToPhone: Long, phoneToWatch: Long): Round {
        val t1 = phoneAt + theta
        val t2 = phoneAt + watchToPhone
        val t3 = t2 + 3 * ms
        val t4 = t3 + phoneToWatch + theta
        return Round(Side.WEAR, 1, 1, Primitive.RPC, true, 1, 1, t1, t2, t3, t4)
    }

    @Test
    fun `a watch-initiated round keeps its round trip and its interval holds the true offset`() {
        val r = watchAsks(100_000 * ms, 40 * ms, 200 * ms)
        assertEquals(240 * ms, r.rttNs)
        assertEquals(240L, r.phoneFrameMs().roundTripMs)
        assertEquals((theta + 80 * ms).toDouble(), r.offsetMidNs, 0.0)

        val atMs = floorMs(r.phoneAtNs)
        val t = translateGun(atMs, ExchangeClock.REQUESTER, listOf(r.phoneFrameMs()), 0, Long.MAX_VALUE)
        t as GunTranslation.InBudget
        val estimate = t.gunMs - atMs
        assertTrue("bound ${t.errorBoundMs} must cover the 80 ms asymmetry", t.errorBoundMs >= 120)
        assertTrue("θ must lie inside the interval", kotlin.math.abs(estimate - theta / ms) <= t.errorBoundMs)
    }

    @Test
    fun `the two directions of asking agree about the same offset`() {
        val watch = watchAsks(100_000 * ms, 10 * ms, 10 * ms)
        val phone = Round(
            Side.PHONE, 1, 2, Primitive.MSG, true, 1, 2,
            t1Ns = 100_050 * ms,
            t2Ns = 100_060 * ms + theta,
            t3Ns = 100_061 * ms + theta,
            t4Ns = 100_071 * ms,
        )
        val samples = listOf(watch.phoneFrameMs(), phone.phoneFrameMs())
        val t = translateGun(100_060, ExchangeClock.REQUESTER, samples, 0, Long.MAX_VALUE)
        t as GunTranslation.InBudget
        assertEquals(theta / ms, t.gunMs - 100_060)
    }

    @Test
    fun `a round survives its own record`() {
        val r = watchAsks(100_000 * ms, 40 * ms, 200 * ms).copy(nearby = null)
        assertEquals(r, Round.of(Record("SAMPLE", r.fields())))
    }
}
