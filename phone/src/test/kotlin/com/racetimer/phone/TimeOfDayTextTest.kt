package com.racetimer.phone

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.racetimer.phone.ui.timeOfDayText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

/**
 * The phone's clock follows the phone's own 12/24-hour setting (#304 AC 1).
 *
 * How the time is written is `TimeOfDayTest`'s, in `shared/`. This covers the one thing only the
 * phone knows: which of the two the device is set to, read from the same setting the status bar
 * reads, and read again on every call, so a change in Settings reaches the clock without a relaunch.
 */
@RunWith(RobolectricTestRunner::class)
class TimeOfDayTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun at(hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }

    private fun setClockFormat(value: String) {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, value)
    }

    @Test
    fun `a phone set to 24-hour reads HH colon mm`() {
        setClockFormat("24")
        assertEquals("13:05", timeOfDayText(context, at(13, 5)))
    }

    @Test
    fun `a phone set to 12-hour reads h colon mm`() {
        setClockFormat("12")
        assertEquals("1:05", timeOfDayText(context, at(13, 5)))
    }

    @Test
    fun `changing the setting changes the next reading, with no relaunch`() {
        val instant = at(18, 40)
        setClockFormat("24")
        assertEquals("18:40", timeOfDayText(context, instant))
        setClockFormat("12")
        assertEquals("6:40", timeOfDayText(context, instant))
    }
}
