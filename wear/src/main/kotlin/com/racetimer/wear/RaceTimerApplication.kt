package com.racetimer.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.racetimer.android.PairRaces
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.SetupChoice
import com.racetimer.shared.leadInBaseId

/**
 * Application class: creates the notification channel required for the foreground service, and says
 * how a race the phone started becomes a race here (#220) and where the setup the phone mirrors is
 * kept (#221).
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
     * console: it places its own starts and picks on the phone's clock, and offers no choice between
     * two conflicting starts — the owner wanted that on the console, so the watch follows the rule
     * and the phone says so. Its pre-start setup, which the phone mirrors (#221), is kept where #88
     * and #104 already keep it.
     */
    private fun installPairStarts() {
        PairRaces.install(console = false, store = WearSetupStore) { context, join, raceRunning ->
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

/**
 * The watch's pre-start setup as the pair reads and writes it (#221): the pick it opens on (#88) and
 * the alert its lead-in picker opens on (#104), through [TimerService]'s own helpers for both.
 */
internal object WearSetupStore : PairRaces.SetupStore {

    /**
     * What the watch opens on: the remembered pick when it still names a sequence, and otherwise
     * US Sailing — the launch's own fallback (`MainActivity.selectedSequence`), so the two agree.
     */
    override fun load(context: Context): SetupChoice {
        val picked = TimerService.pickedSequenceId(context)?.takeIf { BuiltInSequences.resolve(it) != null }
            ?: BuiltInSequences.usSailing.id
        return SetupChoice(leadInBaseId(picked), TimerService.lastBoxAlertSeconds(context))
    }

    override fun save(context: Context, choice: SetupChoice) {
        TimerService.savePickedSequenceId(context, choice.sequenceId)
        TimerService.saveLastBoxAlertSeconds(context, choice.boxAlertSeconds)
    }
}
