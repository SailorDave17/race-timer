package com.racetimer.phone.ui

import android.content.Context
import android.text.format.DateFormat
import com.racetimer.shared.formatTimeOfDay
import java.util.Calendar

/**
 * The phone's wall-clock time as the timer screen's clock writes it (#304).
 *
 * The 12/24-hour choice is the phone's own setting, read through [DateFormat.is24HourFormat] on
 * every call rather than once. The officer can change it in Settings mid-session, and the clock
 * should follow the status bar's on the next pass, not on the next launch. The writing itself is
 * `formatTimeOfDay` in `shared/`, which the watch's clock agrees with.
 *
 * @param now the instant to write, in the phone's time zone. A parameter so a test can fix it.
 */
fun timeOfDayText(context: Context, now: Calendar = Calendar.getInstance()): String =
    formatTimeOfDay(
        hourOfDay = now.get(Calendar.HOUR_OF_DAY),
        minute = now.get(Calendar.MINUTE),
        is24Hour = DateFormat.is24HourFormat(context),
    )
