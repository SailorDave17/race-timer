package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Covers [translateGun], the arithmetic under epic #196's pair (#217).
 *
 * Every case is built from a simulated physical exchange rather than from hand-picked stamps, so the
 * true answer is known independently of the code under test: [TwoClocks] boots two devices at
 * unrelated times, runs a round with chosen delays, and reads each clock the way `elapsedRealtime`
 * does. A translation is judged against where the gun really is on the other clock, not against a
 * restatement of the same algebra.
 *
 * The exact numbers asserted are worked in the comments beside them. Most cases also check
 * containment — the true instant inside the bound — because an exact-value assertion alone would
 * pin a wrong bound just as firmly as a right one.
 */
class GunTranslationTest {

    /**
     * Two devices booted at unrelated times. At one physical instant, zero, the requester's clock reads
     * [requesterAtZero] and the responder's [responderAtZero]; the responder's crystal runs
     * [responderFastPpm] fast. Physical time is in whole milliseconds and the requester is its
     * reference, so only the responder's readings ever floor.
     */
    private class TwoClocks(
        val requesterAtZero: Long = REQUESTER_AT_ZERO,
        val responderAtZero: Long = RESPONDER_AT_ZERO,
        val responderFastPpm: Long = 0L,
    ) {
        fun requesterAt(physicalMs: Long): Long = requesterAtZero + physicalMs

        fun responderAt(physicalMs: Long): Long =
            responderAtZero + physicalMs + physicalMs * responderFastPpm / 1_000_000L

        /** The responder's reading before it floors: the truth a translation is judged against. */
        fun responderExactlyAt(physicalMs: Long): Double =
            responderAtZero + physicalMs + physicalMs * responderFastPpm / 1_000_000.0

        /** A round sent at physical [sentAt]: [outMs] in flight, [turnaroundMs] at the responder, [backMs] home. */
        fun round(sentAt: Long, outMs: Long, backMs: Long, turnaroundMs: Long = 5L): OffsetSample {
            val received = sentAt + outMs
            val replied = received + turnaroundMs
            return OffsetSample(
                requestSentMs = requesterAt(sentAt),
                requestReceivedMs = responderAt(received),
                replySentMs = responderAt(replied),
                replyReceivedMs = requesterAt(replied + backMs),
            )
        }
    }

    private fun GunTranslation.inBudget(): GunTranslation.InBudget =
        this as? GunTranslation.InBudget ?: throw AssertionError("expected InBudget, was $this")

    /** The one property every translation owes: the true instant lies within the bound. */
    private fun assertCovers(truth: Double, t: GunTranslation.InBudget) {
        assertTrue(
            "the true gun $truth lies outside ${t.gunMs} ± ${t.errorBoundMs}",
            abs(t.gunMs - truth) <= t.errorBoundMs,
        )
    }

    /** Three recorded rounds whose intervals intersect at [θ − 21, θ + 61]; see the case that uses them first. */
    private fun threeRounds(clocks: TwoClocks) = listOf(
        clocks.round(sentAt = 0L, outMs = 60L, backMs = 120L),      // 180 ms: [θ − 121, θ + 61]
        clocks.round(sentAt = 1_000L, outMs = 70L, backMs = 20L),   //  90 ms: [θ −  21, θ + 71]
        clocks.round(sentAt = 2_000L, outMs = 150L, backMs = 100L), // 250 ms: [θ − 101, θ + 151]
    )

    // --- One round: an interval, not a point ----------------------------------

    @Test fun `a symmetric round translates exactly and is bounded by half its round trip`() {
        val clocks = TwoClocks()
        val round = clocks.round(sentAt = 0L, outMs = 100L, backMs = 100L)
        val gun = clocks.requesterAt(300_000L)

        val t = translateGun(gun, ExchangeClock.REQUESTER, listOf(round), maxDriftPpm = NO_DRIFT, budgetMs = NO_BUDGET)
            .inBudget()

        // Interval [θ − 101, θ + 101]: the 200 ms round trip, plus the read resolution on each side. A
        // literal, not `200 / 2 + CLOCK_READ_RESOLUTION_MS`, so a change to the constant cannot carry
        // this expectation along with it.
        assertEquals(gun + OFFSET, t.gunMs)
        assertEquals(101L, t.errorBoundMs)
        assertCovers(clocks.responderExactlyAt(300_000L), t)
    }

    @Test fun `a round 40 ms out and 200 ms back is read 80 ms off, and the bound covers it`() {
        // AC 2's own example.
        val clocks = TwoClocks()
        val round = clocks.round(sentAt = 0L, outMs = 40L, backMs = 200L)
        val gun = clocks.requesterAt(300_000L)

        val t = translateGun(gun, ExchangeClock.REQUESTER, listOf(round), maxDriftPpm = NO_DRIFT, budgetMs = NO_BUDGET)
            .inBudget()

        // Interval [θ − 201, θ + 41]. Its midpoint, the RTT-halving estimate, sits at θ − 80: half the
        // 160 ms asymmetry, which nothing in the four stamps can see.
        assertEquals(gun + OFFSET - 80L, t.gunMs)
        // Half the 240 ms round trip plus the read resolution: wide enough for the 80 ms it is out by.
        assertEquals(121L, t.errorBoundMs)
        assertCovers(clocks.responderExactlyAt(300_000L), t)
    }

    @Test fun `an odd-width interval keeps the spare millisecond on the bound's side`() {
        // A 241 ms round trip widens to 243: no whole-millisecond midpoint sits in the middle. The
        // midpoint rounds toward the low end and the bound is read from the high one, so it is
        // ceil(243 / 2) = 122. Half the width rounded down would be 121, and 1 ms falsely tight.
        val clocks = TwoClocks()
        val round = clocks.round(sentAt = 0L, outMs = 41L, backMs = 200L) // [θ − 201, θ + 42]
        val gun = clocks.requesterAt(300_000L)

        val t = translateGun(gun, ExchangeClock.REQUESTER, listOf(round), maxDriftPpm = NO_DRIFT, budgetMs = NO_BUDGET)
            .inBudget()

        assertEquals(gun + OFFSET - 80L, t.gunMs)
        assertEquals(122L, t.errorBoundMs)
        assertCovers(clocks.responderExactlyAt(300_000L), t)
    }

    @Test fun `one bound covers every split of the round trip, and the extreme splits reach it`() {
        // The stamps cannot tell 0 ms out and 240 back from 240 out and 0 back, so the bound must hold
        // for every split. It should be no wider than that needs, either: the two extremes land within
        // the read resolution of its edge. That is the difference between worst-case and padded.
        val clocks = TwoClocks()
        val gun = clocks.requesterAt(300_000L)
        val truth = clocks.responderExactlyAt(300_000L)

        val errors = (0L..240L step 40L).map { out ->
            val round = clocks.round(sentAt = 0L, outMs = out, backMs = 240L - out)
            val t = translateGun(gun, ExchangeClock.REQUESTER, listOf(round), maxDriftPpm = NO_DRIFT, budgetMs = NO_BUDGET)
                .inBudget()
            assertEquals("split $out/${240L - out}", 121L, t.errorBoundMs)
            assertCovers(truth, t)
            t.gunMs - truth
        }

        assertEquals(7, errors.size)
        assertEquals(-120.0, errors.first(), 0.0) // 0 out, 240 back
        assertEquals(120.0, errors.last(), 0.0)   // 240 out, 0 back
    }

    @Test fun `thirty identical asymmetric rounds agree perfectly and are still 80 ms out`() {
        // The trap AC 2 names. Thirty rounds with one asymmetry give thirty identical RTT-halving
        // estimates, so a bound read off their spread would call the offset exact. It is 80 ms out: a
        // consistent asymmetry is a bias, agreement between rounds cannot reveal a bias, and only the
        // round trip bounds it.
        val clocks = TwoClocks()
        val rounds = (0L until 30L).map { clocks.round(sentAt = it * 1_000L, outMs = 40L, backMs = 200L) }
        val estimates = rounds.map {
            ((it.requestReceivedMs - it.requestSentMs) + (it.replySentMs - it.replyReceivedMs)) / 2
        }
        assertEquals("the rounds agree to the millisecond", 1, estimates.distinct().size)
        val gun = clocks.requesterAt(300_000L)

        val t = translateGun(gun, ExchangeClock.REQUESTER, rounds, maxDriftPpm = NO_DRIFT, budgetMs = NO_BUDGET)
            .inBudget()

        assertEquals(gun + OFFSET - 80L, t.gunMs)
        assertEquals(121L, t.errorBoundMs)
        assertCovers(clocks.responderExactlyAt(300_000L), t)
    }

    // --- Many rounds: the intersection ----------------------------------------

    @Test fun `recorded rounds between unrelated boots bound the gun by their round trips`() {
        // AC 1: an hour-old boot and a three-day-old one, three rounds of 180, 90 and 250 ms.
        val clocks = TwoClocks()
        val rounds = threeRounds(clocks)
        val gun = clocks.requesterAt(300_000L)

        val t = translateGun(gun, ExchangeClock.REQUESTER, rounds, maxDriftPpm = NO_DRIFT, budgetMs = NO_BUDGET)
            .inBudget()

        // Intersection [θ − 21, θ + 61]: the low end from the 90 ms round, the high end from the 180 ms
        // one. Half its 82 ms width is the bound, which beats the best single round's own 90 / 2 + 1.
        assertEquals(gun + OFFSET + 20L, t.gunMs)
        assertEquals(41L, t.errorBoundMs)
        assertTrue(t.errorBoundMs < rounds.minOf { it.roundTripMs } / 2 + CLOCK_READ_RESOLUTION_MS)
        assertCovers(clocks.responderExactlyAt(300_000L), t)
    }

    @Test fun `translating from the responder's clock is the same interval with its ends swapped`() {
        val clocks = TwoClocks()
        val gun = clocks.responderAt(300_000L)

        val t = translateGun(gun, ExchangeClock.RESPONDER, threeRounds(clocks), maxDriftPpm = NO_DRIFT, budgetMs = NO_BUDGET)
            .inBudget()

        // Requester = responder − θ, so [θ − 21, θ + 61] becomes [gun − θ − 61, gun − θ + 21].
        assertEquals(gun - OFFSET - 20L, t.gunMs)
        assertEquals(41L, t.errorBoundMs)
        assertCovers(clocks.requesterAt(300_000L).toDouble(), t)
    }

    @Test fun `a slow lopsided round leaves the bound where the good ones put it`() {
        // 1.9 s out and 0.1 s back — the Data Layer on a bad day. An average of midpoints would move
        // 900 ms toward it; an intersection cannot move at all, so a bad round costs nothing.
        val clocks = TwoClocks()
        val rounds = threeRounds(clocks) + clocks.round(sentAt = 3_000L, outMs = 1_900L, backMs = 100L)
        val gun = clocks.requesterAt(300_000L)

        val t = translateGun(gun, ExchangeClock.REQUESTER, rounds, maxDriftPpm = NO_DRIFT, budgetMs = NO_BUDGET)
            .inBudget()

        assertEquals(gun + OFFSET + 20L, t.gunMs)
        assertEquals(41L, t.errorBoundMs)
    }

    // --- Drift: the bound holds at the gun, not only at the exchange -----------

    @Test fun `the bound widens by the stated drift rate over the time to the gun, rounded up`() {
        val clocks = TwoClocks()
        val round = clocks.round(sentAt = 0L, outMs = 50L, backMs = 50L) // [θ − 51, θ + 51]
        val gun = clocks.requesterAt(300_000L)                            // five minutes after it was sent

        fun boundAt(ppm: Long) =
            translateGun(gun, ExchangeClock.REQUESTER, listOf(round), maxDriftPpm = ppm, budgetMs = NO_BUDGET)
                .inBudget().errorBoundMs

        assertEquals(51L, boundAt(0L))
        assertEquals(51L + 12L, boundAt(40L)) // 40 ppm of 300_000 ms: 12 ms exactly
        assertEquals(51L + 13L, boundAt(41L)) // 12.3 ms, rounded up: an allowance is never short
    }

    @Test fun `a responder really running 40 ppm fast is covered only when the drift is stated`() {
        // The case the drift allowance exists for. A tight 4 ms round measures the offset to ±3 ms, and
        // ten minutes later the responder has gained 24 ms. With no drift stated the bound still says
        // ±3 and the gun is 24 ms out — falsely tight, with nothing in the answer to say so.
        val clocks = TwoClocks(responderFastPpm = 40L)
        val round = clocks.round(sentAt = 0L, outMs = 2L, backMs = 2L) // [θ − 3, θ + 3]
        val gun = clocks.requesterAt(600_000L)
        val truth = clocks.responderExactlyAt(600_000L)                  // gun + θ + the 24 ms gained

        val unstated = translateGun(gun, ExchangeClock.REQUESTER, listOf(round), maxDriftPpm = 0L, budgetMs = NO_BUDGET)
            .inBudget()
        assertEquals(3L, unstated.errorBoundMs)
        assertEquals(24.0, truth - unstated.gunMs, 0.0)
        assertTrue(
            "stating no drift must leave this case uncovered, or the case proves nothing",
            abs(unstated.gunMs - truth) > unstated.errorBoundMs,
        )

        val stated = translateGun(gun, ExchangeClock.REQUESTER, listOf(round), maxDriftPpm = 40L, budgetMs = NO_BUDGET)
            .inBudget()
        assertEquals(gun + OFFSET, stated.gunMs) // the midpoint stays put: the drift's sign is unknown
        assertEquals(3L + 24L, stated.errorBoundMs)
        assertCovers(truth, stated)
    }

    @Test fun `a gun read on the responder's clock is aged against the responder's own stamps`() {
        // Aged against the requester's stamps instead, the distance would be the offset itself — three
        // days — and the bound about ten seconds.
        val clocks = TwoClocks(responderFastPpm = 40L)
        val round = clocks.round(sentAt = 0L, outMs = 2L, backMs = 2L) // [θ − 3, θ + 3]
        val gun = clocks.responderAt(600_000L)                           // the responder gained 24 ms getting there

        val t = translateGun(gun, ExchangeClock.RESPONDER, listOf(round), maxDriftPpm = 40L, budgetMs = NO_BUDGET)
            .inBudget()

        // From the gun back to the round's far end on the responder's clock is 600_024 − 2 = 600_022 ms,
        // and 40 ppm of that, rounded up, is 25.
        assertEquals(gun - OFFSET, t.gunMs)
        assertEquals(3L + 25L, t.errorBoundMs)
        assertCovers(clocks.requesterAt(600_000L).toDouble(), t)
    }

    @Test fun `an old tight round loosens with age, and a fresh one tightens the bound again`() {
        // What re-exchanging mid-race (#222) will lean on. A 4 ms round from 25 minutes before the gun is
        // worth ±63 by then; a 20 ms round from five minutes before is worth ±23.
        val clocks = TwoClocks(responderFastPpm = 40L)
        val old = clocks.round(sentAt = 0L, outMs = 2L, backMs = 2L)
        val fresh = clocks.round(sentAt = 1_200_000L, outMs = 10L, backMs = 10L)
        val gun = clocks.requesterAt(1_500_000L)
        val truth = clocks.responderExactlyAt(1_500_000L) // θ + 60 by now

        fun translate(vararg rounds: OffsetSample) =
            translateGun(gun, ExchangeClock.REQUESTER, rounds.toList(), maxDriftPpm = 40L, budgetMs = NO_BUDGET)
                .inBudget()

        // old:   [θ −  3, θ +  3] aged 60 ms → [θ − 63, θ + 63]
        // fresh: [θ + 37, θ + 59] aged 12 ms → [θ + 25, θ + 71] — the responder had gained 48 ms by then
        assertEquals(63L, translate(old).errorBoundMs)
        assertEquals(23L, translate(fresh).errorBoundMs)
        // Together: [θ + 25, θ + 63], tighter than either alone.
        val both = translate(old, fresh)
        assertEquals(gun + OFFSET + 44L, both.gunMs)
        assertEquals(19L, both.errorBoundMs)
        listOf(translate(old), translate(fresh), both).forEach { assertCovers(truth, it) }
    }

    @Test fun `understating drift badly enough pulls two rounds apart into Inconsistent`() {
        // Twenty minutes of 40 ppm moved the offset 48 ms between these rounds. Stating no drift leaves
        // [θ − 3, θ + 3] and [θ + 37, θ + 59] with nothing in common.
        val clocks = TwoClocks(responderFastPpm = 40L)
        val rounds = listOf(clocks.round(0L, 2L, 2L), clocks.round(1_200_000L, 10L, 10L))
        val gun = clocks.requesterAt(1_500_000L)

        assertEquals(
            GunTranslation.Inconsistent,
            translateGun(gun, ExchangeClock.REQUESTER, rounds, maxDriftPpm = 0L, budgetMs = NO_BUDGET),
        )
    }

    @Test fun `understating drift by half is not caught, and the bound comes back falsely tight`() {
        // The limit [GunTranslation.Inconsistent] states, pinned so nobody mistakes it for a drift
        // detector. At 20 ppm the aged rounds still overlap, just in the wrong place: [θ − 33, θ + 33]
        // and [θ + 31, θ + 65] meet at [θ + 31, θ + 33], a ±1 ms answer 28 ms from the truth. The bound
        // is only as honest as the stated rate, which is why the rate has no default.
        val clocks = TwoClocks(responderFastPpm = 40L)
        val rounds = listOf(clocks.round(0L, 2L, 2L), clocks.round(1_200_000L, 10L, 10L))
        val gun = clocks.requesterAt(1_500_000L)

        val t = translateGun(gun, ExchangeClock.REQUESTER, rounds, maxDriftPpm = 20L, budgetMs = NO_BUDGET)
            .inBudget()

        assertEquals(gun + OFFSET + 32L, t.gunMs)
        assertEquals(1L, t.errorBoundMs)
        assertEquals(28.0, clocks.responderExactlyAt(1_500_000L) - t.gunMs, 0.0)
    }

    // --- The budget ------------------------------------------------------------
    //
    // Inclusive at the boundary, and both sides are asserted: a `<` / `<=` slip is invisible from either
    // one alone.

    @Test fun `a bound exactly equal to the budget is in budget`() {
        val clocks = TwoClocks()
        val gun = clocks.requesterAt(300_000L)
        assertEquals(
            GunTranslation.InBudget(gunMs = gun + OFFSET + 20L, errorBoundMs = 41L),
            translateGun(gun, ExchangeClock.REQUESTER, threeRounds(clocks), maxDriftPpm = NO_DRIFT, budgetMs = 41L),
        )
    }

    @Test fun `a bound one millisecond over the budget is out of it, with the same gun as best effort`() {
        val clocks = TwoClocks()
        val gun = clocks.requesterAt(300_000L)
        assertEquals(
            GunTranslation.OutOfBudget(bestEffortGunMs = gun + OFFSET + 20L, errorBoundMs = 41L, budgetMs = 40L),
            translateGun(gun, ExchangeClock.REQUESTER, threeRounds(clocks), maxDriftPpm = NO_DRIFT, budgetMs = 40L),
        )
    }

    @Test fun `rounds too slow for the budget come back as OutOfBudget data, not an exception`() {
        // AC 3. Every round took 400 ms, so the best any can do is ±201 against a 100 ms budget. That is
        // for the caller to act on — refuse the start, run it visibly degraded, or exchange again — so it
        // arrives as a result: no exception, and no bare number that reads like a good one.
        val clocks = TwoClocks()
        val rounds = (0L until 5L).map { clocks.round(sentAt = it * 1_000L, outMs = 200L, backMs = 200L) }
        val gun = clocks.requesterAt(300_000L)

        assertEquals(
            GunTranslation.OutOfBudget(bestEffortGunMs = gun + OFFSET, errorBoundMs = 201L, budgetMs = 100L),
            translateGun(gun, ExchangeClock.REQUESTER, rounds, maxDriftPpm = NO_DRIFT, budgetMs = 100L),
        )
    }

    // --- No offset at all --------------------------------------------------------

    @Test fun `no rounds is NoSamples`() {
        assertEquals(
            GunTranslation.NoSamples,
            translateGun(1_000L, ExchangeClock.REQUESTER, emptyList(), maxDriftPpm = NO_DRIFT, budgetMs = NO_BUDGET),
        )
    }

    @Test fun `a responder that rebooted between rounds is Inconsistent, not averaged`() {
        val before = TwoClocks()
        // Rebooted 60 s after physical zero: its elapsedRealtime read zero then, so it would have read
        // −60_000 at zero had it been running.
        val after = TwoClocks(responderAtZero = -60_000L)
        val rounds = listOf(before.round(0L, 50L, 50L), after.round(120_000L, 50L, 50L))

        assertEquals(
            GunTranslation.Inconsistent,
            translateGun(before.requesterAt(300_000L), ExchangeClock.REQUESTER, rounds, maxDriftPpm = NO_DRIFT, budgetMs = NO_BUDGET),
        )
    }

    // --- The round trip -----------------------------------------------------------
    //
    // −1 ms passes and −2 ms does not, and that line does not follow from reading the code: floored
    // readings can understate a true round trip by just under 2 ms, so a sub-millisecond round can read
    // as −1, while −2 needs the round to have finished before it started.

    @Test fun `the round trip excludes the responder's turnaround`() {
        val round = TwoClocks().round(sentAt = 0L, outMs = 40L, backMs = 200L, turnaroundMs = 37L)
        assertEquals(240L, round.roundTripMs)
    }

    @Test fun `a round trip of minus one millisecond is a legal floored reading`() {
        val round = OffsetSample(
            requestSentMs = 1_000L, requestReceivedMs = 5_000L, replySentMs = 5_001L, replyReceivedMs = 1_000L,
        )
        assertEquals(-1L, round.roundTripMs)

        // Interval [4_000, 4_001]: one millisecond wide, so the bound is one.
        assertEquals(
            GunTranslation.InBudget(gunMs = 10_000L + 4_000L, errorBoundMs = 1L),
            translateGun(10_000L, ExchangeClock.REQUESTER, listOf(round), maxDriftPpm = NO_DRIFT, budgetMs = NO_BUDGET),
        )
    }

    @Test fun `a round trip of minus two milliseconds is impossible and comes back Inconsistent`() {
        val round = OffsetSample(
            requestSentMs = 1_000L, requestReceivedMs = 5_000L, replySentMs = 5_002L, replyReceivedMs = 1_000L,
        )
        assertEquals(-2L, round.roundTripMs)

        assertEquals(
            GunTranslation.Inconsistent,
            translateGun(10_000L, ExchangeClock.REQUESTER, listOf(round), maxDriftPpm = NO_DRIFT, budgetMs = NO_BUDGET),
        )
    }

    // --- Caller bugs ----------------------------------------------------------------

    @Test fun `a negative drift rate or budget is a caller bug, thrown rather than returned`() {
        // The counterpart to AC 3: a miss on the budget is data, a malformed call is not.
        val round = TwoClocks().round(sentAt = 0L, outMs = 50L, backMs = 50L)
        assertThrows(IllegalArgumentException::class.java) {
            translateGun(0L, ExchangeClock.REQUESTER, listOf(round), maxDriftPpm = -1L, budgetMs = 100L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            translateGun(0L, ExchangeClock.REQUESTER, listOf(round), maxDriftPpm = 40L, budgetMs = -1L)
        }
    }

    private companion object {
        const val REQUESTER_AT_ZERO = 3_600_000L   // booted an hour ago
        const val RESPONDER_AT_ZERO = 259_212_345L // booted three days ago, give or take
        const val OFFSET = RESPONDER_AT_ZERO - REQUESTER_AT_ZERO // θ, 255_612_345 ms

        /** The round-trip cases simulate clocks with no drift, so stating none is exact for them. */
        const val NO_DRIFT = 0L

        /** No bound in the cases outside the budget section comes near it. */
        const val NO_BUDGET = Long.MAX_VALUE
    }
}
