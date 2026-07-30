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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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

/**
 * Foreground service that keeps the [TimerEngine] alive while the screen is off or the
 * app is backgrounded.
 *
 * Responsibilities:
 * - Run the engine's [TimerEngine.tick] loop every TICK_INTERVAL_MS (≈50 ms).
 * - Keep a [PowerManager.WakeLock] (PARTIAL) so the CPU stays awake for ticks.
 * - Post an Ongoing Activity notification so the countdown shows on the watch face /
 *   system UI.
 * - Persist the gun wall-clock time so state survives process death.
 * - Clear the wake lock and stop itself when the sequence ends or is reset.
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
    private lateinit var prefs: SharedPreferences

    /**
     * Outcome of the most recent restore, or null if the last start was fresh.
     * [RestoreOutcome.DEGRADED] means a reboot or clock step was detected during recovery and the
     * UI should prompt the sailor to re-sync against the Race Committee flag.
     */
    var lastRestoreOutcome: RestoreOutcome? = null
        private set

    // --- Handler tick loop ----------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            engine.tick()
            when (engine.currentState) {
                TimerState.RUNNING -> {
                    engine.pollClockAdjustment()
                    handler.postDelayed(this, TICK_INTERVAL_MS)
                    updateOngoingNotification()
                }
                else -> stopForegroundAndCleanup()
            }
        }
    }

    // --- Wake lock ------------------------------------------------------------

    private var wakeLock: PowerManager.WakeLock? = null

    // --- Service lifecycle ----------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        haptic = HapticManager(this)
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
                } else {
                    engine.load(sequence)
                    engine.start()
                    lastRestoreOutcome = null
                }

                persistSnapshot()
                acquireWakeLock()
                startForegroundWithNotification()
                scheduleTickLoop()
            }
            ACTION_SYNC -> engine.sync()
            ACTION_STOP -> {
                engine.stop()
                clearPersistedState()
                stopForegroundAndCleanup()
            }
            ACTION_RESET -> {
                engine.reset()
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
        releaseWakeLock()
        engine.removeListener(engineListener)
        super.onDestroy()
    }

    // --- Foreground / Ongoing Activity ----------------------------------------

    private fun startForegroundWithNotification() {
        val notification = buildNotification(engine.remainingMs)
        startForeground(RaceTimerApplication.TIMER_NOTIFICATION_ID, notification)
    }

    private fun updateOngoingNotification() {
        val notification = buildNotification(engine.remainingMs)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(RaceTimerApplication.TIMER_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(remainingMs: Long): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayText = formatCountdown(remainingMs)

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
        handler.removeCallbacks(tickRunnable)
        releaseWakeLock()
        clearPersistedState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // --- Wake lock management -------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RaceTimer:TimerWakeLock"
        ).also { it.acquire(6 * 60 * 1000L) }  // max 6 min (longest sequence)
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

    // --- Engine listener ------------------------------------------------------

    private val engineListener = object : TimerListener {
        override fun onCue(cue: SequenceCue) {
            haptic.play(cue.signal, isGun = cue.isGun)
        }

        override fun onGun() {
            // Gun fires: additional long haptic already triggered via onCue
            // Stop the service after a brief delay so the UI can show "GO!"
            handler.postDelayed({ stopForegroundAndCleanup() }, GUN_LINGER_MS)
        }

        override fun onTick(remainingMs: Long) {
            // Notification updated via updateOngoingNotification() in tickRunnable
        }

        override fun onSync(snappedToMs: Long) {
            haptic.playSync()
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
        const val ACTION_RESET = "com.racetimer.wear.ACTION_RESET"
        const val EXTRA_SEQUENCE_ID = "sequence_id"

        private const val PREFS_NAME = "race_timer_state"
        private const val PREF_SEQUENCE_ID = "sequence_id"
        private const val PREF_GUN_ELAPSED = "gun_elapsed_ms"
        private const val PREF_GUN_WALL_CLOCK = "gun_wall_clock_ms"
        private const val PREF_CAPTURED_ELAPSED = "captured_elapsed_ms"

        private const val TICK_INTERVAL_MS = 50L
        private const val GUN_LINGER_MS = 3_000L

        fun startIntent(context: Context, sequenceId: String): Intent =
            Intent(context, TimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SEQUENCE_ID, sequenceId)
            }

        fun syncIntent(context: Context): Intent =
            Intent(context, TimerService::class.java).apply { action = ACTION_SYNC }


        fun stopIntent(context: Context): Intent =
            Intent(context, TimerService::class.java).apply { action = ACTION_STOP }

        fun resetIntent(context: Context): Intent =
            Intent(context, TimerService::class.java).apply { action = ACTION_RESET }
    }
}
