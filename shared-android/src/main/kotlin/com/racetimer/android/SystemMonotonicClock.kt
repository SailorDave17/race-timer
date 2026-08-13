package com.racetimer.android

import android.os.SystemClock
import com.racetimer.shared.MonotonicClock

/**
 * Production [MonotonicClock] implementation backed by [SystemClock.elapsedRealtime].
 *
 * Uses the device's monotonic clock so the countdown is unaffected by NTP corrections
 * or the user manually adjusting the wall clock.
 */
object SystemMonotonicClock : MonotonicClock {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
}
