package com.racetimer.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * Application class: creates the notification channel the foreground service posts on (#203).
 *
 * At Application init rather than in the service, per the watch's pattern and for the watch's
 * reason: a channel must exist before the first `startForeground` renders against it, and the
 * Application's `onCreate` is the one hook that runs before any component can need it — whatever
 * order the activity, the binding and the service land in.
 */
class RaceTimerPhoneApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            TIMER_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            // LOW: the countdown is glanced at, never heralded. A sound or a heads-up from the
            // notification would collide with the cue audio that is the actual product.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val TIMER_CHANNEL_ID = "race_timer_channel"
        const val TIMER_NOTIFICATION_ID = 1001
    }
}
