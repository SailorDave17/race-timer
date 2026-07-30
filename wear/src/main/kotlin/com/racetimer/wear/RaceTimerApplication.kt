package com.racetimer.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/** Application class: creates the notification channel required for the foreground service. */
class RaceTimerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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

    companion object {
        const val TIMER_CHANNEL_ID = "race_timer_channel"
        const val TIMER_NOTIFICATION_ID = 1001
    }
}
