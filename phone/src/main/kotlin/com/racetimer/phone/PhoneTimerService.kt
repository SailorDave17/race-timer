package com.racetimer.phone

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import android.os.SystemClock
import com.racetimer.shared.CueTiming
import com.racetimer.shared.LaunchPlan
import com.racetimer.shared.RestoreOutcome
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.StartPlan
import com.racetimer.shared.TimerListener
import com.racetimer.shared.TimerState
import com.racetimer.shared.launchPlan
import com.racetimer.shared.startPlan

/**
 * Foreground service that keeps a running race cueing while the app is backgrounded or the screen
 * is off (#203).
 *
 * Written fresh against the shared engine rather than copied from the watch's `TimerService` (epic
 * #196 decision D1: leaf managers move, each app keeps its own service shell) — but it inherits the
 * watch's hard-won ordering and sizing lessons as *criteria*, so the phone is not born with
 * already-fixed defects:
 *
 * - **Doze survival is the wake lock and nothing else** (#126). `OngoingActivity` is presentation,
 *   a foreground service answers "may this app run", and only a `PARTIAL_WAKE_LOCK` answers "is the
 *   CPU awake". Both the cue scheduler and the tick loop post on the uptime clock, which stops in
 *   suspend, so the lock is load-bearing for every cue.
 * - **The lock is sized to the race and re-sized by anything that moves the gun** (#126 again — the
 *   watch sized it once at Start, sync moved the gun later, and the lock expired silently
 *   mid-race). The arithmetic is [PhoneWakeLock], unit-tested; [onStartCommand]'s `ACTION_SYNC`
 *   branch is the re-compute.
 * - **The arm ordering is engine tick → wake lock → `startForeground`** (#62): the first cue of
 *   every sequence is due the instant the gun anchors, so it fires synchronously ahead of the
 *   startup work, inside [PhoneRaceRunner.start]. The persist-snapshot slot sits between the tick
 *   and the wake lock, reserved for #205; the full ordering gets asserted once persistence lands.
 *
 * ### The race lives here, not in the activity
 *
 * This service owns the [PhoneRaceRunner] — engine, cue scheduling, audio — and the activity binds
 * to read it. While a race is running the service is *started* and foreground, so the officer
 * backgrounding the app or the screen sleeping takes the UI away and nothing else; between races it
 * is a plain bound service that dies with the activity. Surviving *process death* is #205's story,
 * which is what the reserved persist slot is for.
 *
 * ### What happens when the platform refuses the foreground start
 *
 * The race is aborted rather than run blind — a countdown that dies the moment the screen sleeps is
 * the one thing this app exists to prevent, and the watch's #13 named "does not silently start" as
 * the criterion. The refusal is latched in [foregroundStartRefused], **service-side only, on
 * purpose**: the latch clears on the next successful start, and a fresh service (which every
 * rebind after teardown constructs) starts with it false. The activity deliberately holds no copy —
 * the watch's activity-side twin is a one-way latch whose own remedy cannot clear it (#165), and
 * the phone declines to inherit the pattern by not building it.
 */
class PhoneTimerService : Service() {

    inner class LocalBinder : Binder() {
        val service: PhoneTimerService get() = this@PhoneTimerService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    /** The race. Constructed with the real audio path; see [PhoneRaceRunner] for the seams. */
    lateinit var runner: PhoneRaceRunner
        private set

    /**
     * Whether the platform refused to let this service enter the foreground (#13's phone twin).
     *
     * The one readiness condition with no pre-flight check behind it — there is no API answering
     * "would a foreground service be allowed right now?", so the only honest way to know is to have
     * been refused. Latched, not sampled; cleared by the next attempt that succeeds. See the class
     * doc for why no activity-side copy of this may exist (#165).
     */
    @Volatile var foregroundStartRefused = false
        private set

    private lateinit var persistence: PhoneRacePersistence

    /**
     * The restore outcome not yet shown to the officer, or null (#205).
     *
     * Read-and-clear (the watch's `consumeRestoreNotice` shape): a "we resumed a race you did not
     * start just now" notice must fire exactly once per start, and a value the UI polls at 50 ms
     * cannot express that - it would re-announce forever or miss the window.
     */
    private var pendingRestoreNotice: RestoreOutcome? = null

    /** Take the notice owed a message, clearing it. Called from the activity's poll. */
    fun consumeRestoreNotice(): RestoreOutcome? =
        pendingRestoreNotice.also { pendingRestoreNotice = null }

    /**
     * What a launch should open on, decided by the shared plan from persistence alone (#205, #209).
     *
     * Both records go in, and the shared plan ranks them: a saved race outranks a remembered pick,
     * because it *is* a pick, made more recently and with a race attached. #209 supplied the second
     * argument - it was `null` until then, with a comment saying the picker's memory was its own
     * story, and this is that story.
     *
     * The fall-through is what makes the two independent (#88): a saved race whose id resolves to
     * nothing does not drag the pick down with it.
     */
    fun launchPlan(): LaunchPlan = launchPlan(
        snapshot = persistence.saved(),
        pickedSequenceId = persistence.pickedSequenceId(),
        nowElapsedMs = SystemClock.elapsedRealtime(),
        nowWallMs = System.currentTimeMillis(),
    )

    /**
     * Remember the sequence the officer just chose, so the next cold launch opens on it (#209).
     *
     * Called for every selection including Custom, whose id carries its own duration - which is
     * what makes a re-run of last week's 8-minute start a single tap rather than a re-dial.
     */
    fun savePickedSequence(sequenceId: String) = persistence.savePickedSequenceId(sequenceId)

    private val handler = Handler(Looper.getMainLooper())

    /** How long the cue that fired most recently occupies the speaker — sizes the gun teardown. */
    private var lastCueDurationMs = 0L

    /** Set while the post-gun teardown is scheduled, so the tick loop does not race it. */
    private var gunTeardownPending = false

    private var wakeLock: PowerManager.WakeLock? = null

    /** The countdown text currently posted, so a tick that renders the same string skips the post. */
    private var postedNotificationText: String? = null

    /**
     * Drives the notification text and backstops the cue scheduler while a race runs.
     *
     * The cues do not need this loop — they land on their own boundaries through the runner's
     * scheduler (#202) — but a poll is what recovers a missed wake-up, and the notification shows
     * one new value per second that something has to render. Same division of labour as the watch.
     */
    private val tickRunnable = object : Runnable {
        override fun run() {
            val readout = runner.tick()
            when (runner.engine.currentState) {
                // COUNTING_UP rides the same loop as RUNNING (#206): a race-manager sequence's
                // work is not finished at the gun, and the notification has a live elapsed time to
                // show for as long as the committee is racing. Nothing here ends it — the loop
                // stops when End Race tears the foreground down, which is an officer's tap and not
                // a timer, because a count-up has no bound to schedule against.
                TimerState.RUNNING, TimerState.COUNTING_UP -> {
                    updateNotification(readout.text)
                    handler.postDelayed(this, TICK_INTERVAL_MS)
                }
                // The gun leaves the engine FINISHED on the same tick it fires; the teardown that
                // respects the gun cue's own tail is already scheduled by onGun. Anything else —
                // a stop that raced this post, an abort — cleans up here.
                else -> if (!gunTeardownPending) stopForegroundAndCleanup()
            }
        }
    }

    /**
     * Runs once the gun cue has finished sounding and "GO!" has had [GUN_LINGER_MS] on screen.
     *
     * Deliberately does **not** reset the engine, where the watch does: the phone's timer screen
     * keeps "GO!" up with a Stop control, and yanking the state from under a bound activity would
     * blank the one number the officer is reading. What ends here is the *foreground-ness* — the
     * wake lock and the notification have no race left to protect. The engine returns to the top
     * when the officer taps Stop, or with the service itself when the last client unbinds.
     */
    private val gunTeardownRunnable = Runnable {
        gunTeardownPending = false
        stopForegroundAndCleanup()
    }

    private val engineListener = object : TimerListener {
        override fun onCue(cue: SequenceCue) {
            lastCueDurationMs = CueTiming.durationMs(cue.signal, isGun = cue.isGun)
        }

        override fun onGun() {
            if (runner.engine.loadedSequence?.countUpAfterFinish == true) {
                // Race-manager mode (#206): the engine has gone straight to COUNTING_UP rather than
                // FINISHED, so there is no teardown to schedule — the service and its notification
                // stay up until End Race. Two things deliberately do NOT happen here:
                //
                //  - **The snapshot is not cleared.** Past the gun is where a count-up race lives,
                //    and the shared recoverability rule says so in as many words — a
                //    `countUpAfterFinish` race with a passed gun is still recoverable, where a
                //    countdown would be spent. Clearing at the gun would make the one race long
                //    enough to outlive a process the one race that could not be restored.
                //  - **The wake lock is not held.** Its job was to keep cue timing steady through
                //    the countdown and there are no cues left to protect; elapsed time is read from
                //    the monotonic gun anchor on every tick rather than accumulated, so however
                //    coarsely Doze defers this loop the number stays correct. Holding a
                //    PARTIAL_WAKE_LOCK across an unbounded count-up is the battery cost the epic's
                //    "lasts the start day" condition is about, and the watch released it here for
                //    the same reason.
                releaseWakeLock()
                return
            }
            // Hold the service open for the gun cue's own length plus the linger, rather than a
            // flat constant: the gun is a three-second sustained cue and a hardcoded delay that
            // happens to match it today would cut a longer one short (watch lesson, #61).
            gunTeardownPending = true
            handler.postDelayed(gunTeardownRunnable, lastCueDurationMs + GUN_LINGER_MS)
        }

        override fun onTick(remainingMs: Long) {}

        override fun onSync(snappedToMs: Long) {
            // The snap moved the gun; a snapshot describing the old anchor would restore the race
            // the officer just corrected away (#205).
            persistSnapshot()
        }

        override fun onClockAdjusted(remainingMs: Long) {
            // The monotonic countdown is unaffected; the persisted wall-clock anchor is what a
            // reboot restore reconstructs from, so it follows the corrected wall clock.
            persistSnapshot()
        }
    }

    override fun onCreate() {
        super.onCreate()
        persistence = PhoneRacePersistence(this)
        runner = PhoneRaceRunner(
            cueSounder = PhoneCueSounder(this),
            cueScheduler = HandlerCueScheduler(),
        )
        runner.engine.addListener(engineListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val freshStart = intent.getBooleanExtra(EXTRA_FRESH_START, false)
                // Start over, explicitly asked for: the officer saw the offer and declined it, so
                // the saved race is discarded rather than offered again on the next launch. The
                // decision below takes freshStart as an input of its own - nothing rests on this
                // clear having happened first (the watch's #64 ordering lesson).
                if (freshStart) persistence.clear()

                // Resume or run from the top - decided in shared/, from the same persisted reading
                // the launch offer was built on, so the offer and what the tap does cannot disagree.
                when (val plan = startPlan(
                    freshStart = freshStart,
                    saved = persistence.saved(),
                    requestedSequenceId = runner.selected.id,
                    engineState = runner.engine.currentState,
                )) {
                    is StartPlan.Resume -> {
                        val outcome = runner.restore(runner.selected, plan.snapshot)
                        pendingRestoreNotice = outcome
                        if (outcome == RestoreOutcome.EXPIRED) {
                            // The gun fired while the process was dead: there is no race to
                            // resume, and the officer tapped for a race - so give them one from
                            // the top, saying so, rather than a dead screen (the watch's choice).
                            persistence.clear()
                            runner.start()
                        }
                    }
                    StartPlan.FromTheTop -> {
                        // The #62 ordering, phone edition: the cue due at the anchor instant fires
                        // synchronously inside start(); everything below must not delay it.
                        runner.start()
                    }
                }
                // The persist slot between the first cue's dispatch and the wake lock (#205,
                // completing the slot #203 reserved): a process death from here on restores.
                persistSnapshot()
                acquireWakeLock()
                startForegroundWithNotification()
                if (!foregroundStartRefused) scheduleTickLoop()
            }
            ACTION_SYNC -> {
                runner.sync()
                // The re-compute #126 exists for: a sync can move the gun later, and the lock's
                // timeout was sized from the remaining time at the moment it was acquired. Re-size
                // from what is remaining *now*; unconditional within RUNNING because the engine
                // refuses a sync on its own terms and a redundant re-acquire costs one release.
                if (runner.engine.currentState == TimerState.RUNNING) acquireWakeLock()
            }
            ACTION_END_RACE -> {
                // Freezes the elapsed time into RACE_ENDED (see TimerEngine.endRace) — the engine
                // is left holding the final race time so the bound activity keeps showing it, and
                // it is the officer's Done tap, not this, that returns to the top.
                //
                // What ends here is the *foreground-ness*. There is no cue left to protect, no
                // countdown to keep the CPU awake for, and the frozen number needs neither: the
                // teardown releases the lock, leaves the foreground, and clears the snapshot. That
                // last one is the shared rule doing real work rather than tidying — the
                // recoverability rule would happily restore a `countUpAfterFinish` race past its
                // gun, so a snapshot surviving End Race would come back as a *running* count-up and
                // un-freeze the very race the committee just closed.
                runner.endRace()
                stopForegroundAndCleanup()
            }
            ACTION_STOP -> {
                runner.stop()
                gunTeardownPending = false
                handler.removeCallbacks(gunTeardownRunnable)
                stopForegroundAndCleanup()
            }
        }
        // NOT_STICKY for the watch's reason (see race-timer CLAUDE.md): a sticky restart arrives
        // with a null intent, matches no branch above, and Android 12+ kills the process for the
        // startForeground that never came. #205's restore path recovers the race instead.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(gunTeardownRunnable)
        releaseWakeLock()
        runner.engine.removeListener(engineListener)
        runner.release()
        super.onDestroy()
    }

    // --- Wake lock -------------------------------------------------------------

    /**
     * (Re)acquire the lock, sized by [PhoneWakeLock.timeoutMs] from what is left to run right now.
     *
     * Release-first, because ACTION_START can arrive with a lock already held (a double tap, a
     * restart straight after the gun) and overwriting the field would orphan the old lock until its
     * own timeout ran out — the watch found that one too.
     */
    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).also {
            it.acquire(PhoneWakeLock.timeoutMs(runner.engine.remainingMs))
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // --- Foreground / notification ---------------------------------------------

    /**
     * Enter the foreground, or abort the race rather than run one that cannot survive the screen.
     *
     * `RuntimeException` covers both refusals the platform actually throws —
     * `ForegroundServiceStartNotAllowedException` (Android 12+) and the `SecurityException`
     * Android 14+ raises when the declared FGS type is not permitted — the same supertype the
     * watch catches, because the exact subclass varies by API level and the response does not.
     */
    private fun startForegroundWithNotification() {
        val displayText = runner.readout().text
        postedNotificationText = displayText
        try {
            startForeground(RaceTimerPhoneApplication.TIMER_NOTIFICATION_ID, buildNotification(displayText))
            foregroundStartRefused = false
        } catch (e: RuntimeException) {
            Log.e(TAG, "Foreground service refused; aborting the race rather than running it blind", e)
            foregroundStartRefused = true
            // Back to the pre-start screen's state: engine idle at the top of the sequence, no
            // pending cue dispatch, no lock. The countdown must not keep running on a service the
            // platform will kill at the first screen-off.
            runner.stop()
            stopForegroundAndCleanup()
        }
    }

    private fun updateNotification(displayText: String) {
        if (displayText == postedNotificationText) return
        postedNotificationText = displayText
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.notify(RaceTimerPhoneApplication.TIMER_NOTIFICATION_ID, buildNotification(displayText))
    }

    private fun buildNotification(displayText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, RaceTimerPhoneApplication.TIMER_CHANNEL_ID)
            // The alpha-only notification mark, NOT the adaptive launcher foreground: a small icon
            // is rendered from its alpha channel alone and tinted, so the launcher layer would
            // arrive as a featureless disc (cairn android-notification-small-icon-alpha).
            .setSmallIcon(R.drawable.ic_stat_race_timer)
            .setContentTitle(getString(R.string.notification_content_title))
            .setContentText(displayText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .build()
    }

    private fun stopForegroundAndCleanup() {
        handler.removeCallbacks(tickRunnable)
        releaseWakeLock()
        // Every way a race ends comes through here - the gun, Stop, an abort - and a race that
        // ended is not a race to restore (#205). By key, never clear(): see PhoneRacePersistence.
        persistence.clear()
        postedNotificationText = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleTickLoop() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    /** Write the race in flight to disk, or nothing when the engine holds no snapshot. */
    private fun persistSnapshot() {
        val snap = runner.engine.snapshot() ?: return
        persistence.persist(snap)
    }

    companion object {
        private const val TAG = "PhoneTimerService"

        const val ACTION_START = "com.racetimer.phone.ACTION_START"
        const val ACTION_SYNC = "com.racetimer.phone.ACTION_SYNC"
        const val ACTION_END_RACE = "com.racetimer.phone.ACTION_END_RACE"
        const val ACTION_STOP = "com.racetimer.phone.ACTION_STOP"

        /** Set on [ACTION_START] to discard the saved race and run from the top (#205). */
        const val EXTRA_FRESH_START = "fresh_start"

        const val TICK_INTERVAL_MS = 50L

        const val GUN_LINGER_MS = 3_000L

        const val WAKE_LOCK_TAG = "RaceTimer:PhoneTimerWakeLock"

        /**
         * Start a race in the service, from the officer's tap. Resumes a saved one when the
         * shared plan says so; [freshStart] declines the saved race explicitly (#205).
         */
        fun start(context: Context, freshStart: Boolean = false) {
            context.startForegroundService(
                Intent(context, PhoneTimerService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_FRESH_START, freshStart),
            )
        }

        /**
         * End a race-manager count-up, freezing the final time on screen (#206).
         *
         * `startService`, not `startForegroundService`: by the time this is tapped the service is
         * already started and foreground, and this intent is what takes it *out* of that state.
         */
        fun endRace(context: Context) {
            context.startService(
                Intent(context, PhoneTimerService::class.java).setAction(ACTION_END_RACE),
            )
        }

        /** Stop the race and return the service to a plain bound one. */
        fun stop(context: Context) {
            context.startService(Intent(context, PhoneTimerService::class.java).setAction(ACTION_STOP))
        }

        /** Snap the running countdown to the flag (#204 wires the control). */
        fun sync(context: Context) {
            context.startService(Intent(context, PhoneTimerService::class.java).setAction(ACTION_SYNC))
        }
    }
}
