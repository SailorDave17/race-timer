package com.racetimer.pairskew.analysis

import kotlin.math.floor
import kotlin.math.sqrt

/** The [q]-quantile (0..1) of [values], interpolating between order statistics (R's type 7). */
fun quantile(values: List<Double>, q: Double): Double {
    require(values.isNotEmpty()) { "no values" }
    require(q in 0.0..1.0) { "q must be within 0..1, was $q" }
    val sorted = values.sorted()
    val h = (sorted.size - 1) * q
    val lo = floor(h).toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
}

/** A least-squares line `y = intercept + slope · x`, with the slope's standard error. */
data class Fit(val intercept: Double, val slope: Double, val slopeStdErr: Double, val residualRms: Double, val n: Int)

fun fitLine(xs: List<Double>, ys: List<Double>): Fit {
    require(xs.size == ys.size) { "${xs.size} xs against ${ys.size} ys" }
    require(xs.size >= 3) { "a line and its error need at least three points, had ${xs.size}" }
    val n = xs.size
    val mx = xs.average()
    val my = ys.average()
    var sxx = 0.0
    var sxy = 0.0
    for (i in 0 until n) {
        val dx = xs[i] - mx
        sxx += dx * dx
        sxy += dx * (ys[i] - my)
    }
    require(sxx > 0.0) { "every x is the same, so there is no slope" }
    val slope = sxy / sxx
    val intercept = my - slope * mx
    var ss = 0.0
    for (i in 0 until n) {
        val r = ys[i] - (intercept + slope * xs[i])
        ss += r * r
    }
    return Fit(intercept, slope, sqrt(ss / (n - 2) / sxx), sqrt(ss / n), n)
}
