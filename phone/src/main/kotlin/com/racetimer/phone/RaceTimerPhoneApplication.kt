package com.racetimer.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Application class: creates the notification channel the foreground service posts on (#203), and
 * owns the one [ViewModelStore] whose lifetime is the process (#281).
 *
 * At Application init rather than in the service, per the watch's pattern and for the watch's
 * reason: a channel must exist before the first `startForeground` renders against it, and the
 * Application's `onCreate` is the one hook that runs before any component can need it — whatever
 * order the activity, the binding and the service land in.
 *
 * ### Why this class holds a ViewModelStore (#281)
 *
 * #225 ratified the officer's display answers as lasting **for the life of the process** — the right
 * answer is a property of the day, so it must survive being picked up and turned, and must not
 * survive to the next race day. `DisplayChoiceViewModel`'s own KDoc says exactly that, and until
 * #281 it was **false**: an activity-scoped `viewModel()` dies with the *activity*, and #281
 * measured a recreated activity mid-race re-asking "Screen for today" while the process was very
 * much alive with a foreground service ticking. An Application-owned store is process scope for
 * real, and it is the whole mechanism — nothing is written to disk, so
 * `ModuleBoundaryTest#the display choice is written to no persistent store` still holds, and the
 * "dies with the process" half is unchanged because this object dies with it.
 *
 * The store is deliberately **not** cleared anywhere. A `ViewModelStore` cleared on some lifecycle
 * event would reintroduce exactly the bug above through a different door, and the process ending
 * is the only event that should end this state.
 */
class RaceTimerPhoneApplication : Application(), ViewModelStoreOwner {

    override val viewModelStore: ViewModelStore = ViewModelStore()

    /**
     * The start-day recorder (#216), or [DayJournal.OFF] on every build nobody armed.
     *
     * Here because the process is the right lifetime for it: the day is four processes (see
     * `docs/start-day-battery.md`), the battery broadcast arrives whether or not a race is running,
     * and a journal owned by the service would have a hole in it across exactly the gaps between
     * races that the day is mostly made of.
     */
    val journal: DayJournal by lazy { DayJournal.forProcess(this) }

    override fun onCreate() {
        super.onCreate()

        // Before anything the journal could be asked to record, so a run's first line always names
        // the artefact it was taken on. An unarmed build pays one `Log.isLoggable` and registers
        // no receiver.
        if (journal.isArmed) {
            journal.recordSession(this)
            BatteryJournalReceiver(journal).register(this)
        }

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
