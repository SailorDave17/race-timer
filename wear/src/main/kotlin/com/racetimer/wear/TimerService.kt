package com.racetimer.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.RestoreOutcome
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.TimerEngine
import com.racetimer.shared.TimerListener
import com.racetimer.shared.TimerState
import com.racetimer.shared.formatCountdown
import com.racetimer.shared.formatElapsed

/**
 * Foreground service that keeps the [TimerEngine] alive while the screen is off or the
 * app is backgrounded.
 *
 * Responsibilities:
 * - Drive the engine's [TimerEngine.tick] two ways: on each cue's own boundary via [cueRunnable],
 *   which is what makes a cue land when it is due, and every TICK_INTERVAL_MS (≈50 ms) via
 *   [tickRunnable], which refreshes the display and backstops the first.
 * - Keep a [PowerManager.WakeLock] (PARTIAL) so the CPU stays awake for ticks.
 * - Post an Ongoing Activity notification so the countdown shows on the watch face /
 *   system UI.
 * - Persist the gun wall-clock time so state survives process death.
 * - Clear the wake lock and stop itself when the sequence ends or is stopped — except a
 *   [com.racetimer.shared.RaceSequence.countUpAfterFinish] sequence, whose service instead keeps
 *   running as an elapsed-time stopwatch past the gun (see [engineListener]'s onGun handling).
 */
class TimerService : Service() {

    // --- Binder ---------------------------------------------------------------

    inner class LocalBinder : Binder() {
        val service: TimerService get() = this@TimerService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // --- Engine & helpers -----------------------------------------------------

    val engine = TimerEngine(SystemMonotonicClock)
    private lateinit var haptic: HapticManager
    private lateinit var tone: ToneManager
    private lateinit var prefs: SharedPreferences

    /**
     * Outcome of the most recent restore, or null if the last start was fresh.
     * [RestoreOutcome.DEGRADED] means a reboot or clock step was detected during recovery and the
     * UI should prompt the sailor to re-sync against the Race Committee flag.
     */
    var lastRestoreOutcome: RestoreOutcome? = null
        private set

    /** The outcome of the last start that has not yet been shown to the sailor. */
    private var pendingRestoreNotice: RestoreOutcome? = null

    /**
     * Read-and-clear the restore outcome still owed a message on screen.
     *
     * Separate from [lastRestoreOutcome], which the UI samples continuously to decide whether the
     * re-sync prompt belongs on screen. A "we resumed a race you did not start just now" notice must
     * fire exactly once per start, and a value the UI polls cannot express that: the activity refreshes
     * every 100 ms and would either re-announce forever or miss the change while the service was still
     * processing the intent. The service hands the notice over once instead.
     */
    fun consumeRestoreNotice(): RestoreOutcome? =
        pendingRestoreNotice.also { pendingRestoreNotice = null }

    // --- Handler tick loop ----------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            engine.tick()
            scheduleNextCue()
            when (engine.currentState) {
                // COUNTING_UP keeps the same tick loop as RUNNING — a race-manager sequence's job
                // isn't done at the gun, and the notification still needs updating (with elapsed
                // time rather than a countdown; see updateOngoingNotification). pollClockAdjustment
                // is a no-op outside RUNNING, so calling it here unconditionally is harmless.
                TimerState.RUNNING, TimerState.COUNTING_UP -> {
                    engine.pollClockAdjustment()
                    handler.postDelayed(this, TICK_INTERVAL_MS)
                    updateOngoingNotification()
                }
                // The final elapsed time is frozen (see TimerEngine.endRace) and there are no more
                // cues or ticks to drive anything — just stop rescheduling. Unlike every other
                // non-running state this is deliberately *not* torn down here: the whole point of
                // RACE_ENDED is to keep the notification (and the service, and the activity if it's
                // still open) showing the final time until the race committee taps Done
                // (ACTION_STOP). The one explicit notification refresh happens in the ACTION_END_RACE
                // handler below, since this branch itself won't run again to do it.
                TimerState.RACE_ENDED -> Unit
                // The gun leaves the engine FINISHED on the same tick it fires, so tearing down
                // here would cut the gun cue off within one tick of it starting. When onGun has
                // already scheduled the teardown, let that one run instead.
                else -> if (!gunTeardownPending) stopForegroundAndCleanup()
            }
        }
    }

    /**
     * Wakes the engine exactly when the next cue is due, rather than letting [tickRunnable] find it
     * on its next poll.
     *
     * [tickRunnable] alone put every cue up to [TICK_INTERVAL_MS] late, at random — measured on an
     * SM-R925U (#58), one-second cue spacing came out between 923 and 1096 ms, about half of that
     * from poll granularity. The countdown itself was never wrong: [TimerEngine] reads a monotonic
     * anchor, so the cue's *intended* time is exact and only the moment of noticing it was coarse.
     *
     * The poll stays, as a backstop and to drive the display. Both paths call [TimerEngine.tick],
     * which dequeues under its own guard, so whichever arrives first fires the cue and the other
     * finds nothing to do — there is no double-fire.
     */
    private val cueRunnable = Runnable {
        engine.tick()
        scheduleNextCue()
    }

    /** How long the cue that fired most recently occupies the wrist and speaker. */
    private var lastCueDurationMs = 0L

    /** Set while [gunTeardownRunnable] is posted, so the tick loop doesn't tear down ahead of it. */
    private var gunTeardownPending = false

    /**
     * Runs once the gun cue has finished sounding and "GO!" has had its [GUN_LINGER_MS] on screen.
     *
     * [TimerEngine.reset] is what puts the screen back to the pre-race countdown. The timer face has
     * no Reset button — before a race there is nothing to reset — so nothing else would ever clear
     * FINISHED, and the sailor would be stranded on "GO!" until they force-stopped the app. Resetting
     * here rather than in the UI also keeps it true when the activity is not even bound.
     */
    private val gunTeardownRunnable = Runnable {
        engine.reset()
        stopForegroundAndCleanup()
    }

    // --- Wake lock ------------------------------------------------------------

    private var wakeLock: PowerManager.WakeLock? = null

    // --- Service lifecycle ----------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        haptic = HapticManager(this)
        tone = ToneManager(this).also { it.prepare() }
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        engine.addListener(engineListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sequenceId = intent.getStringExtra(EXTRA_SEQUENCE_ID) ?: BuiltInSequences.usSailing.id
                val sequence = findSequence(sequenceId)

                // Check if we should restore from saved state
                val savedGunElapsed = prefs.getLong(PREF_GUN_ELAPSED, -1L)
                val savedGunWall = prefs.getLong(PREF_GUN_WALL_CLOCK, -1L)
                val savedCapturedElapsed = prefs.getLong(PREF_CAPTURED_ELAPSED, Long.MIN_VALUE)
                val savedSeqId = prefs.getString(PREF_SEQUENCE_ID, null)

                if (savedGunWall > 0 && savedCapturedElapsed != Long.MIN_VALUE &&
                    savedSeqId == sequenceId && engine.currentState == TimerState.IDLE) {
                    val snapshot = TimerEngine.Snapshot(
                        sequenceId = savedSeqId,
                        gunElapsedMs = savedGunElapsed,
                        gunWallMs = savedGunWall,
                        capturedElapsedMs = savedCapturedElapsed,
                    )
                    lastRestoreOutcome = engine.restore(sequence, snapshot)
                    if (lastRestoreOutcome == RestoreOutcome.EXPIRED) {
                        // The gun fired while the process was dead, so there is no race to resume —
                        // and restore leaves the engine FINISHED, which with no Reset button would
                        // strand the screen on "GO!". The sailor tapped Start, so give them a start:
                        // drop the spent snapshot and run the sequence from the top. The UI says so
                        // (see consumeRestoreNotice) rather than silently substituting a fresh race.
                        clearPersistedState()
                        engine.load(sequence)
                        engine.start()
                    }
                } else {
                    engine.load(sequence)
                    engine.start()
                    lastRestoreOutcome = null
                }
                pendingRestoreNotice = lastRestoreOutcome

                persistSnapshot()
                acquireWakeLock()
                startForegroundWithNotification()
                scheduleTickLoop()
            }
            ACTION_SYNC -> {
                engine.sync()
                // A snap re-anchors the gun and re-queues the unfired cues, so whatever was armed is
                // now aimed at the wrong moment.
                scheduleNextCue()
                // The intent can land on a service the system created just to deliver it — the app
                // was killed, or the race has already ended. There is then nothing to sync and no
                // countdown to hold open, so don't leave a started service sitting idle. COUNTING_UP
                // and RACE_ENDED are excepted: unlike FINISHED/IDLE there *is* something to hold open
                // in either — an active race, or its just-frozen summary — even though the current UI
                // never actually sends ACTION_SYNC once the gun has fired (no Sync button is shown
                // past RUNNING; see TimerScreen).
                if (engine.currentState != TimerState.RUNNING &&
                    engine.currentState != TimerState.COUNTING_UP &&
                    engine.currentState != TimerState.RACE_ENDED
                ) {
                    stopSelf()
                }
            }
            ACTION_END_RACE -> {
                // Freezes the elapsed time and moves to RACE_ENDED (see TimerEngine.endRace) rather
                // than tearing down — the whole point is to leave the final time on screen for the
                // race committee to read. The tick loop's RACE_ENDED branch won't post this update
                // itself (it never runs again after this call), so do it once here.
                engine.endRace()
                updateOngoingNotification()
            }
            ACTION_STOP -> {
                // Also the way out of a restored race the sailor did not want, or a RACE_ENDED
                // summary the race committee is done reading: Stop clears the snapshot, so the next
                // Start runs the sequence from the top rather than resuming.
                engine.stop()
                clearPersistedState()
                stopForegroundAndCleanup()
            }
        }
        // NOT_STICKY, deliberately: a sticky restart arrives with a null intent, which matches no
        // branch above - so startForeground() never runs and Android 12+ kills the process with
        // ForegroundServiceDidNotStartInTimeException. There is nothing to gain by restarting
        // either, since a race is recovered from the persisted snapshot when the sailor next taps
        // Start (see the ACTION_START restore path), not by the service coming back on its own.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(cueRunnable)
        releaseWakeLock()
        tone.release()
        engine.removeListener(engineListener)
        super.onDestroy()
    }

    // --- Foreground / Ongoing Activity ----------------------------------------

    /** The countdown text currently posted, so a tick that renders the same string can skip the post. */
    private var postedNotificationText: String? = null

    /** The launch intent never varies, so it is built once rather than on every notification. */
    private val contentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * The countdown text pre-gun, or the elapsed race time once [TimerState.COUNTING_UP] or
     * [TimerState.RACE_ENDED] (live in the former, frozen in the latter — `engine.remainingMs`
     * already reads as frozen there, see its doc) — the one place that distinction is made, so
     * [startForegroundWithNotification] and [updateOngoingNotification] (and a restore straight
     * into COUNTING_UP) can't render it two different ways.
     */
    private fun currentDisplayText(): String =
        if (engine.currentState == TimerState.COUNTING_UP || engine.currentState == TimerState.RACE_ENDED) {
            formatElapsed(-engine.remainingMs)
        } else {
            formatCountdown(engine.remainingMs)
        }

    private fun startForegroundWithNotification() {
        val displayText = currentDisplayText()
        postedNotificationText = displayText
        startForeground(RaceTimerApplication.TIMER_NOTIFICATION_ID, buildNotification(displayText))
    }

    /**
     * Re-post the ongoing notification, but only when the countdown it renders has actually changed.
     *
     * The tick loop runs at [TICK_INTERVAL_MS] — no longer because cues need that resolution, since
     * [cueRunnable] now lands each cue on its own boundary, but because it drives the display and
     * backstops a missed wake-up. The notification shows M:SS, which yields one new value per
     * second. Posting on every tick meant roughly 6 000
     * binder round-trips to NotificationManagerService per race with 19 of every 20 re-rendering a
     * string identical to the one already on screen — and a package enqueueing at that rate gets
     * throttled by the framework regardless.
     */
    private fun updateOngoingNotification() {
        val displayText = currentDisplayText()
        if (displayText == postedNotificationText) return
        postedNotificationText = displayText
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(RaceTimerApplication.TIMER_NOTIFICATION_ID, buildNotification(displayText))
    }

    private fun buildNotification(displayText: String): Notification {
        val pendingIntent = contentIntent

        val builder = NotificationCompat.Builder(this, RaceTimerApplication.TIMER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notification_content_title))
            .setContentText(displayText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)

        // Attach Wear OS Ongoing Activity so the timer appears on the watch face
        val ongoingActivityStatus = Status.forPart(
            Status.TextPart(displayText)
        )
        val ongoingActivity = OngoingActivity.Builder(
            applicationContext,
            RaceTimerApplication.TIMER_NOTIFICATION_ID,
            builder
        )
            .setStaticIcon(R.drawable.ic_launcher)
            .setTouchIntent(pendingIntent)
            .setStatus(ongoingActivityStatus)
            .build()
        ongoingActivity.apply(applicationContext)

        return builder.build()
    }

    private fun stopForegroundAndCleanup() {
        gunTeardownPending = false
        handler.removeCallbacks(gunTeardownRunnable)
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(cueRunnable)
        releaseWakeLock()
        clearPersistedState()
        postedNotificationText = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Wake lock management -------------------------------------------------

    private fun acquireWakeLock() {
        // Release first. ACTION_START can arrive while a lock is already held — a double tap, or a
        // restart straight after the gun — and overwriting the field orphaned the previous lock:
        // releaseWakeLock() could then only ever see the newest one, leaving the old held until its
        // own timeout ran out.
        releaseWakeLock()
        val pm = getSystemService(PowerManager::class.java)
        // Sized to what is actually left to run rather than a constant "longest built-in sequence":
        // BuiltInSequences.custom() accepts any duration, and a restore resumes partway through. The
        // margin covers the gun cue itself plus the teardown linger that follows it.
        val timeoutMs = engine.remainingMs.coerceAtLeast(0L) + WAKE_LOCK_MARGIN_MS
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RaceTimer:TimerWakeLock"
        ).also { it.acquire(timeoutMs) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // --- Tick loop ------------------------------------------------------------

    private fun scheduleTickLoop() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    /**
     * (Re)arm [cueRunnable] for the next cue boundary. Safe to call redundantly.
     *
     * Must run after anything that moves the queue or the anchor — a start, a [TimerEngine.sync]
     * re-queue, a restore, or a tick that fired something — because all of those change which cue is
     * next and when. [TimerEngine.msUntilNextCue] returns null once there is nothing left, which
     * leaves nothing armed.
     *
     * The conversion is monotonic-to-uptime and the two clocks diverge in deep sleep, where
     * `elapsedRealtime` keeps counting and `uptimeMillis` does not. That is safe here for the same
     * reason [tickRunnable]'s `postDelayed` already was: the countdown holds a partial wake lock, so
     * the device is not suspending while cues are pending. If one were ever missed anyway, the poll
     * still catches it on its next pass — which is the other reason to keep the poll.
     */
    private fun scheduleNextCue() {
        handler.removeCallbacks(cueRunnable)
        val dueInMs = engine.msUntilNextCue() ?: return
        handler.postAtTime(cueRunnable, SystemClock.uptimeMillis() + dueInMs.coerceAtLeast(0L))
    }

    // --- Engine listener ------------------------------------------------------

    private val engineListener = object : TimerListener {
        override fun onCue(cue: SequenceCue) {
            // Vibration first, always: audio is best-effort and must never gate the haptic.
            haptic.play(cue.signal, isGun = cue.isGun)
            tone.playCue(cue.signal)
            lastCueDurationMs = CueTiming.durationMs(cue.signal, isGun = cue.isGun)
        }

        override fun onGun() {
            // Gun fires: the haptic and tone were already triggered via onCue, which runs first.
            if (engine.loadedSequence?.countUpAfterFinish == true) {
                // Race-manager mode: the engine has moved straight to COUNTING_UP (see
                // TimerEngine.tick()) rather than FINISHED, so there is no teardown to schedule —
                // the service keeps ticking indefinitely until the race committee taps End Race
                // (ACTION_END_RACE, which freezes the time into RACE_ENDED rather than tearing down;
                // Done/ACTION_STOP is the actual teardown after that). Elapsed time is read from the
                // monotonic gun anchor on every tick, not accumulated, so it stays correct regardless
                // of how coarsely Doze defers this handler once the screen sleeps — the wake lock's
                // job was to hold cue timing steady through the countdown, and there are no more
                // cues left to protect, so release it and let the watch sleep.
                releaseWakeLock()
            } else {
                // Hold the service open for the gun cue's own length before the usual "GO!" linger,
                // rather than a flat constant: the Scholastic gun is a three-second sustained cue and
                // a hardcoded delay that happens to match it today would cut the next one short.
                gunTeardownPending = true
                handler.postDelayed(gunTeardownRunnable, lastCueDurationMs + GUN_LINGER_MS)
            }
        }

        override fun onTick(remainingMs: Long) {
            // Notification updated via updateOngoingNotification() in tickRunnable
        }

        override fun onSync(snappedToMs: Long) {
            haptic.playSync()
            tone.playBeep()
            persistSnapshot()
        }

        override fun onClockAdjusted(remainingMs: Long) {
            // Wall clock jumped (e.g. NTP correction). The monotonic countdown is unaffected,
            // but the persisted wall-clock anchor must be refreshed so a restore-after-death
            // stays correct.
            persistSnapshot()
        }
    }

    // --- State persistence ----------------------------------------------------

    private fun persistSnapshot() {
        val snap = engine.snapshot() ?: return
        prefs.edit()
            .putString(PREF_SEQUENCE_ID, snap.sequenceId)
            .putLong(PREF_GUN_ELAPSED, snap.gunElapsedMs)
            .putLong(PREF_GUN_WALL_CLOCK, snap.gunWallMs)
            .putLong(PREF_CAPTURED_ELAPSED, snap.capturedElapsedMs)
            .apply()
    }

    private fun clearPersistedState() {
        prefs.edit()
            .remove(PREF_SEQUENCE_ID)
            .remove(PREF_GUN_ELAPSED)
            .remove(PREF_GUN_WALL_CLOCK)
            .remove(PREF_CAPTURED_ELAPSED)
            .apply()
    }

    // --- Helpers --------------------------------------------------------------

    private fun findSequence(id: String): RaceSequence =
        BuiltInSequences.all.firstOrNull { it.id == id } ?: BuiltInSequences.usSailing

    companion object {
        const val ACTION_START = "com.racetimer.wear.ACTION_START"
        const val ACTION_SYNC = "com.racetimer.wear.ACTION_SYNC"
        const val ACTION_STOP = "com.racetimer.wear.ACTION_STOP"
        const val ACTION_END_RACE = "com.racetimer.wear.ACTION_END_RACE"
        const val EXTRA_SEQUENCE_ID = "sequence_id"

        private const val PREFS_NAME = "race_timer_state"
        private const val PREF_SEQUENCE_ID = "sequence_id"
        private const val PREF_GUN_ELAPSED = "gun_elapsed_ms"
        private const val PREF_GUN_WALL_CLOCK = "gun_wall_clock_ms"
        private const val PREF_CAPTURED_ELAPSED = "captured_elapsed_ms"

        private const val TICK_INTERVAL_MS = 50L

        /** Time the UI keeps showing "GO!" *after* the gun cue itself has finished sounding. */
        private const val GUN_LINGER_MS = 3_000L

        /** Slack added to the wake-lock timeout to cover the gun cue and the teardown that follows. */
        private const val WAKE_LOCK_MARGIN_MS = 30_000L

        fun startIntent(context: Context, sequenceId: String): Intent =
            Intent(context, TimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SEQUENCE_ID, sequenceId)
            }

        fun syncIntent(context: Context): Intent =
            Intent(context, TimerService::class.java).apply { action = ACTION_SYNC }

        fun endRaceIntent(context: Context): Intent =
            Intent(context, TimerService::class.java).apply { action = ACTION_END_RACE }

        fun stopIntent(context: Context): Intent =
            Intent(context, TimerService::class.java).apply { action = ACTION_STOP }
    }
}
