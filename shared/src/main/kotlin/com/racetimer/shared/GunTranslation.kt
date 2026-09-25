package com.racetimer.shared

import kotlin.math.abs

/**
 * One round of an offset exchange between two devices, as each read its own monotonic clock.
 *
 * The requester stamps [requestSentMs] and [replyReceivedMs] on its clock; the responder stamps
 * [requestReceivedMs] and [replySentMs] on its own. The two clocks are `elapsedRealtime` on two
 * different devices, so they share no epoch and no boot — a reading on one is only ever compared with
 * a reading on the same clock, except where the offset between them is formed.
 */
data class OffsetSample(
    val requestSentMs: Long,
    val requestReceivedMs: Long,
    val replySentMs: Long,
    val replyReceivedMs: Long,
) {
    /**
     * Time the round spent in flight: the requester's whole round less the responder's turnaround.
     * Each half is a difference on one clock, so neither needs the offset.
     */
    val roundTripMs: Long
        get() = (replyReceivedMs - requestSentMs) - (replySentMs - requestReceivedMs)
}

/** Which of an exchange's two clocks an instant was read on. */
enum class ExchangeClock { REQUESTER, RESPONDER }

/** What [translateGun] can say about a gun instant on the other clock. */
sealed interface GunTranslation {

    /** The gun on the other clock, out by [errorBoundMs] at worst — within the caller's budget. */
    data class InBudget(val gunMs: Long, val errorBoundMs: Long) : GunTranslation

    /**
     * The samples cannot place the gun inside the budget: the tightest they allow is [errorBoundMs],
     * which exceeds [budgetMs].
     *
     * [bestEffortGunMs] is the same midpoint [InBudget] would have carried, under a name no caller can
     * use without saying so. Whether a race may run on it, degraded and visibly so, or must not run at
     * all is the caller's decision (#220) — and it can only make it if the miss arrives as a result,
     * not as an exception or as a number indistinguishable from a good one.
     */
    data class OutOfBudget(
        val bestEffortGunMs: Long,
        val errorBoundMs: Long,
        val budgetMs: Long,
    ) : GunTranslation

    /** No samples: nothing is known about the offset, so there is no bound to judge. */
    data object NoSamples : GunTranslation

    /**
     * The samples admit no offset at all. One round is impossible on its own terms, or two cannot
     * both be true at the stated drift rate: a responder that rebooted between rounds, stamps
     * swapped in transit, or a rate stated far too low.
     *
     * **Not a drift detector.** A rate stated only a little too low leaves the rounds overlapping,
     * and comes back as a falsely tight [InBudget] instead of here — see [translateGun].
     */
    data object Inconsistent : GunTranslation
}

/**
 * Translate [gunMs], read on the [from] clock, onto the other clock of the exchange that produced
 * [samples], with the worst-case error of the translation.
 *
 * **Each round is an interval, and the rounds are intersected.** Write θ for the responder's clock
 * minus the requester's. A request cannot arrive before it was sent, so
 * `θ ≤ requestReceived − requestSent`; a reply the same way gives `θ ≥ replySent − replyReceived`.
 * The interval between them is exactly [OffsetSample.roundTripMs] wide and its midpoint is the
 * RTT-halving estimate. The four stamps cannot say where in the round trip the delay fell — 40 ms
 * out and 200 ms back looks identical to 120 each way, and the midpoint is then 80 ms out — so the
 * only honest bound is the one that holds for every split, which is half the width. Every round's
 * interval contains the true offset, so their intersection must too, and it is never wider than the
 * best round's: another round can tighten the bound and cannot loosen it.
 *
 * **Two widenings make the bound worst-case rather than typical.**
 *  - [CLOCK_READ_RESOLUTION_MS] on each side, because a reading floors to whole milliseconds.
 *  - [maxDriftPpm] of the distance from [gunMs] to the farther end of the round's own exchange, read
 *    on the same clock. Two crystals never tick at quite the same rate, so the offset a round
 *    measured is not the offset at a gun minutes later: at 40 ppm the gun is 12 ms further out per
 *    five minutes. An old round loosens with age, so a fresh one soon decides the bound.
 *
 * **The bound is only as honest as [maxDriftPpm].** A rate stated too low narrows every aged
 * interval, and the intersection can then settle confidently in the wrong place. Only a rate low
 * enough to pull two rounds apart surfaces as [GunTranslation.Inconsistent], so the rate has no
 * default — the natural one, zero, is the one rate two real crystals never share (#217).
 *
 * The bound is judged against [budgetMs] inclusively: a bound exactly equal to the budget is in it.
 * The pair's budget is epic #196's decision D2, ratified from the skew harness's measurements (#218).
 *
 * @param maxDriftPpm the most the two clocks' rates can differ, in parts per million. Not negative.
 * @param budgetMs    the largest worst-case error the caller accepts. Not negative.
 */
fun translateGun(
    gunMs: Long,
    from: ExchangeClock,
    samples: List<OffsetSample>,
    maxDriftPpm: Long,
    budgetMs: Long,
): GunTranslation {
    require(maxDriftPpm >= 0L) { "maxDriftPpm must not be negative, was $maxDriftPpm" }
    require(budgetMs >= 0L) { "budgetMs must not be negative, was $budgetMs" }
    if (samples.isEmpty()) return GunTranslation.NoSamples

    // θ = responder clock − requester clock, as it stands at the gun.
    var low = Long.MIN_VALUE
    var high = Long.MAX_VALUE
    for (s in samples) {
        val aging = driftAllowanceMs(maxDriftPpm, distanceToExchangeMs(gunMs, from, s))
        low = maxOf(low, s.replySentMs - s.replyReceivedMs - CLOCK_READ_RESOLUTION_MS - aging)
        high = minOf(high, s.requestReceivedMs - s.requestSentMs + CLOCK_READ_RESOLUTION_MS + aging)
    }
    // `>=`, not `>`. Every constraint above is strict, since a floored reading always sits a little
    // under the instant it names, so intervals that merely touch have no offset in common. It is also
    // why a single round trip of −1 ms passes here while −2 ms does not: two floored differences
    // can understate a true round trip of zero by just under 2 ms, never by 2.
    if (low >= high) return GunTranslation.Inconsistent

    // The same interval moved onto the target clock. Going the other way negates the offset, so its
    // ends swap.
    val (targetLow, targetHigh) = when (from) {
        ExchangeClock.REQUESTER -> (gunMs + low) to (gunMs + high)
        ExchangeClock.RESPONDER -> (gunMs - high) to (gunMs - low)
    }
    // Midpoint rounded toward the low end, so an odd width leaves the spare millisecond on the high
    // side, and the bound is read from there: ceil(width / 2), exact for the midpoint returned.
    val translated = targetLow + (targetHigh - targetLow) / 2
    val errorBound = targetHigh - translated
    return if (errorBound <= budgetMs) {
        GunTranslation.InBudget(translated, errorBound)
    } else {
        GunTranslation.OutOfBudget(translated, errorBound, budgetMs)
    }
}

/**
 * How coarsely the clocks in this app are read. `SystemClock.elapsedRealtime()` floors to whole
 * milliseconds, so a reading sits up to just under one millisecond before the instant it names, and a
 * difference of two readings can be out by just under one either way. Every offset interval widens by
 * this much on each side, so a floored reading cannot make a bound falsely tight.
 */
const val CLOCK_READ_RESOLUTION_MS: Long = 1L

/**
 * How far [instantMs], read on [clock], sits from the farther end of [sample]'s exchange as that same
 * clock saw it: the longest the offset can have drifted between being measured and being used.
 * Measured on one clock throughout — a requester instant against the requester's stamps, a responder
 * instant against the responder's — because across clocks the difference would be the offset itself.
 */
private fun distanceToExchangeMs(instantMs: Long, clock: ExchangeClock, sample: OffsetSample): Long =
    when (clock) {
        ExchangeClock.REQUESTER ->
            maxOf(abs(instantMs - sample.requestSentMs), abs(instantMs - sample.replyReceivedMs))
        ExchangeClock.RESPONDER ->
            maxOf(abs(instantMs - sample.requestReceivedMs), abs(instantMs - sample.replySentMs))
    }

/** [ppm] parts per million of [spanMs], rounded up so the allowance is never short. */
private fun driftAllowanceMs(ppm: Long, spanMs: Long): Long = (ppm * spanMs + 999_999L) / 1_000_000L
