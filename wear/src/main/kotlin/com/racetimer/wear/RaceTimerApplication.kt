package com.racetimer.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.racetimer.android.PairRaces

/**
 * Application class: creates the notification channel required for the foreground service, and says
 * how a race the phone started becomes a race here (#220).
 */
class RaceTimerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        installPairStarts()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            TIMER_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the race timer countdown while the screen is off"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    /**
     * Said before anything can build the link, and builds nothing itself. The watch is not the
     * console: it places its own starts on the phone's clock, and offers no choice between two
     * conflicting starts — the owner wanted that on the console, so the watch follows the rule and
     * the phone says so.
     */
    private fun installPairStarts() {
        PairRaces.install(console = false) { context, join, raceRunning ->
            val intent = TimerService.joinIntent(context, join)
            try {
                // A service already foreground takes it as a plain start; an idle one needs the
                // foreground start, which the platform refuses when nothing of this app is on screen.
                if (raceRunning) context.startService(intent) else context.startForegroundService(intent)
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "The phone's race was not joined: ${e.javaClass.simpleName}")
                false
            }
        }
    }

    companion object {
        private const val TAG = "RaceTimerApplication"
        const val TIMER_CHANNEL_ID = "race_timer_channel"
        const val TIMER_NOTIFICATION_ID = 1001
    }
}
