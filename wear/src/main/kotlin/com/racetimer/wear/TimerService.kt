package com.racetimer.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.CueStream
import com.racetimer.shared.CueTiming
import com.racetimer.shared.cueStream
import com.racetimer.shared.DEFAULT_BOX_ALERT_SECONDS
import com.racetimer.shared.isValidBoxAlert
import com.racetimer.shared.NO_CAPTURED_ELAPSED_MS
import com.racetimer.shared.NO_GUN_ELAPSED_MS
import com.racetimer.shared.NO_GUN_WALL_MS
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.raisedCueVolume
import com.racetimer.shared.RestoreOutcome
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.StartPlan
import com.racetimer.shared.TimerEngine
import com.racetimer.shared.TimerListener
import com.racetimer.shared.TimerState
import com.racetimer.shared.formatCountdown
import com.racetimer.shared.formatElapsed
import com.racetimer.shared.snapshotFrom
import com.racetimer.shared.startPlan

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
     * Whether the platform refused to raise the cue stream for this race (#95).
     *
     * True only after a [SecurityException] from `setStreamVolume`, which in practice means Do Not
     * Disturb without `ACCESS_NOTIFICATION_POLICY`. Kept rather than discarded because it is the one
     * case where the app knows the cues may be too quiet and cannot fix it — which is exactly what #96
     * has to tell the sailor *before* the start rather than at it. Nothing reads it yet; it exists so
     * that the condition is observable instead of being swallowed at the point it is discovered.
     */
    @Volatile var cueVolumeRefused = false
        private set

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
        // Pay back a volume a previous process raised and never got to restore (#95). This is the
        // recovery half of the persist-before-writing ordering in [ensureCueStreamAudible], and it is
        // what makes raising a device volume mid-race a keepable promise rather than the unkeepable
        // one #65 rejected for screen brightness. No-op in the ordinary case, where teardown already
        // discharged it.
        //
        // Here rather than in the activity because the service is what raised it: a watch whose app
        // was killed mid-race and never reopened still gets its volume back the next time anything
        // starts this service, and `onCreate` is the earliest point that is true.
        //
        // **Before** the route is read below, and that order matters. A stranded raise on the alarm
        // stream would otherwise have `currentCueStream` read the volume *we* left behind and conclude
        // the alarm path is healthy — deciding the route from this app's own residue rather than from
        // the sailor's setting. The race-arm re-read would correct it, so this is a transiently wrong
        // warm-up rather than a wrong race, but there is no reason to accept even that when the fix is
        // two lines higher up.
        restoreRaisedCueVolume()
        tone = ToneManager(this).also { it.prepare(currentCueStream()) }
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        engine.addListener(engineListener)
        warmUpPickedSequence()
    }

    /**
     * Which output the cues should use right now (#95).
     *
     * Read at the moment it is asked for rather than cached, because both of its device inputs are
     * things the sailor can change between the service being created and a race being armed — putting
     * the watch on silent while walking to the boat is the ordinary case, not an edge one.
     *
     * Falls back to [CueStream.ALARM] when there is no [AudioManager] to ask. That is the pre-#95
     * behaviour, and it is the right way to be wrong here: an unnecessary reroute would move every
     * race onto the media slider on the strength of a failed system-service lookup.
     */
    private fun currentCueStream(): CueStream {
        val audio = getSystemService(AudioManager::class.java) ?: return CueStream.ALARM
        return cueStream(
            ringerSilenced = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL,
            alarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM),
        )
    }

    /**
     * Raise the cue stream to an audible floor for this race, remembering what it was (#95).
     *
     * ### Why this exists at all
     *
     * [currentCueStream] picks a stream the ringer has not muted. That is necessary and not
     * sufficient: the stream it picks has a volume slider of its own, and on a silenced watch the
     * answer is [CueStream.MEDIA] — a slider the sailor has no particular reason to have raised, and
     * every reason to have lowered at some point for something unrelated. A cue routed perfectly onto
     * a stream at zero is exactly as silent as the defect this story started from.
     *
     * ### The ordering is the whole design, not an implementation detail
     *
     * The previous volume is written to disk **before** the new one is written to the device. That
     * ordering is what makes this safe to do at all, and it is the reason #65 rejected the equivalent
     * move for screen brightness: a process killed between the two writes leaves the watch changed
     * with nothing left that knows what it was. Persisting first turns that from an unkeepable promise
     * into a recoverable one — [restoreRaisedCueVolume] runs at teardown in the ordinary case, and at
     * [onCreate] for the case where there was no ordinary case.
     *
     * A crash *before* the device write leaves a stranded record and an unchanged volume, which the
     * restore then "corrects" to the value it already holds. Harmless, and the right way round: the
     * failure that costs nothing is the one to have.
     *
     * ### Nothing to do is the common case
     *
     * [raisedCueVolume] returns null when the stream is already at or above the floor, and null means
     * *touch nothing* — no device write, no persisted record, nothing to restore. A watch with its
     * volume up runs a race exactly as it did before this story, which is what makes "nothing outside
     * a race changes" true rather than merely intended.
     */
    private fun ensureCueStreamAudible(route: CueStream) {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val stream = legacyStreamFor(route)
        val target = raisedCueVolume(
            currentVolume = audio.getStreamVolume(stream),
            maxVolume = audio.getStreamMaxVolume(stream),
        ) ?: return

        val previous = audio.getStreamVolume(stream)
        // Disk first. See the ordering note above — this is the line that makes the next one safe.
        saveRaisedCueVolume(this, stream, previous)
        if (!setStreamVolumeOrNull(audio, stream, target)) {
            // The platform refused. Do not leave a record claiming we changed something we did not:
            // a stranded record would have the next launch "restore" a volume the sailor has since
            // set themselves.
            clearRaisedCueVolume(this)
        }
    }

    /**
     * Put back whatever [ensureCueStreamAudible] raised, if anything.
     *
     * Idempotent by construction — with no persisted record there is nothing to do — because it is
     * called from three places that can each run without the others: the ordinary teardown, service
     * destruction, and [onCreate] recovering from a process that never reached either.
     *
     * The sailor's own volume changes during a race are deliberately **not** preserved. Restoring the
     * value from before the race is the promise made, and reading the volume again at the end to see
     * whether it moved would need a way to tell the sailor's change from ours, which the platform does
     * not offer. Putting back what we found is the honest version.
     */
    private fun restoreRaisedCueVolume() {
        val record = raisedCueVolumeRecord(this) ?: return
        val audio = getSystemService(AudioManager::class.java)
        // Clear the record either way. A record we cannot act on is one that will be acted on at some
        // arbitrary later launch, against a volume that has moved on since — worse than losing it.
        clearRaisedCueVolume(this)
        if (audio != null) setStreamVolumeOrNull(audio, record.stream, record.previousVolume)
    }

    /**
     * `setStreamVolume`, returning whether it took.
     *
     * The refusal that matters is Do Not Disturb: with zen mode on, the platform throws
     * [SecurityException] unless the app holds `ACCESS_NOTIFICATION_POLICY` — a permission the sailor
     * has to grant through a system settings screen, and the same one this story already rejected for
     * the ringer approach. So DND is an accepted limit rather than something to force, and this
     * records it instead of crashing under it: a race that cannot be made louder still runs, at
     * whatever volume the watch is at, and [cueVolumeRefused] is what #96 will read to tell the sailor
     * before the start rather than at it.
     */
    private fun setStreamVolumeOrNull(audio: AudioManager, stream: Int, volume: Int): Boolean =
        try {
            audio.setStreamVolume(stream, volume, 0)
            cueVolumeRefused = false
            true
        } catch (e: SecurityException) {
            // Not rethrown and not silent. The race is more important than the volume, and losing the
            // race to an exception raised while trying to make it louder would be the worst possible
            // trade.
            Log.w(TAG, "Refused permission to set stream $stream volume (Do Not Disturb?)", e)
            cueVolumeRefused = true
            false
        }

    /**
     * Start rendering the sequence the sailor last picked, at the earliest moment in the process that
     * anything knows what it is (#98).
     *
     * This is the earliest hook that works, and the two obvious ones do not. `onStartCommand` is far
     * too late — it fires the first cue itself, a few lines below its own `warmUp` call. And the
     * activity's `onServiceConnected` *looks* early but is not: it is a main-thread callback, so it
     * queues behind Compose's first composition. Measured on an SM-R925U, warming up from there
     * started the first render **453 ms after first paint** — in other words after the Start button
     * the sailor is about to tap already existed, which is exactly the window that had to be beaten.
     * This method runs in `onCreate`, ~1.3 s earlier, and gets the first cue rendered before first
     * paint rather than after it.
     *
     * Reads the persisted pick rather than taking a parameter because there is nobody to pass one: the
     * service is created by the activity's `bindService` before the activity has said anything. Where
     * the two disagree — a saved race on a different sequence, or the sailor changing the pick — the
     * activity calls [warmUpCues] and this render was a cheap wrong guess, not a wasted one, since
     * cue shapes are shared across sequences and cached by shape.
     *
     * **Falls back to the default rather than returning**, which is the whole reason this reads a
     * default at all. `PREF_PICKED_SEQUENCE_ID` is written by exactly one caller —
     * `MainActivity.applySelection` — and on a first-ever launch the activity never reaches it: with
     * no pick and no saved race, `launchPlan` returns an all-null plan, so `plan.sequence?.let` never
     * fires. Nor can the race snapshot backfill it, since `clearPersistedState()` runs at every
     * post-gun teardown. So a sailor who only ever races the default would have skipped this warm-up
     * on **every** launch, not just the first, and silently fallen back to the activity's
     * `onServiceConnected` call — the path measured at 453 ms *after* first paint, which is the
     * window this method exists to beat. Defaulting here matches what `onStartCommand` and
     * `MainActivity.selectedSequence` already assume, and costs one `warmUp` whose shapes are cached.
     *
     * Silent on an unresolvable id on purpose. Nothing here is required for a race to run correctly —
     * the activity warms up again on binding, and `playCue` still renders on demand below that.
     */
    private fun warmUpPickedSequence() {
        val id = pickedSequenceId(this) ?: BuiltInSequences.usSailing.id
        val sequence = BuiltInSequences.resolve(id) ?: return
        tone.warmUp(sequence.cues.map { it.signal })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sequenceId = intent.getStringExtra(EXTRA_SEQUENCE_ID) ?: BuiltInSequences.usSailing.id
                val sequence = findSequence(sequenceId)

                // Re-decide which output the cues go to, now, while there is still time to rebuild the
                // track if the answer moved (#95). `onCreate` already made this call, but that was as
                // much as several minutes ago and the two inputs it reads — ringer mode and the alarm
                // slider — belong to the sailor, not to us. A watch put on silent between launching the
                // app and pressing Start is the exact scenario this story exists for, and reading the
                // route only at `onCreate` would miss it while looking like it was handled.
                //
                // Posted to the tone thread ahead of the `engine.tick()` below, so a rebuild that is
                // needed happens before the first cue rather than under it. In the ordinary case the
                // route has not moved and this costs one comparison.
                val route = currentCueStream()
                tone.prepare(route)

                // Then make sure the stream just chosen can actually be heard (#95). Routing alone
                // leaves one hole: the media slider is a separate control and can be at zero, so a
                // correctly-rerouted cue still makes no sound. This is the half that closes it, and it
                // runs here rather than in `onCreate` for the same reason the route does — the sailor
                // may have touched the volume since.
                ensureCueStreamAudible(route)

                // Backstop only. The render that matters was posted when the sailor picked the
                // sequence (see [warmUpCues]) — this call is here for the paths that never went
                // through the activity's selection, and costs a map lookup per cue when they did.
                //
                // It cannot be the primary warm-up, which is what #98 measured: `engine.tick()` a few
                // lines below fires the first cue on *this* thread, so the render thread this posts to
                // has no head start at all and the tone thread renders the cue inline anyway.
                tone.warmUp(sequence.cues.map { it.signal })

                // Start over, explicitly asked for. The sailor was shown the saved race and chose
                // not to resume it, so it is discarded here rather than left to be offered again on
                // the next launch. It is no longer what stops the restore below from firing —
                // [startPlan] takes `freshStart` as an input of its own, so that guard no longer
                // rests on these two statements keeping their order (#64).
                val freshStart = intent.getBooleanExtra(EXTRA_FRESH_START, false)
                if (freshStart) clearPersistedState()

                // Restore or run from the top — decided in `shared/`, from the same reading of the
                // persisted keys the pre-start screen's Resume offer is built on, so the offer and
                // what the tap does cannot disagree.
                when (val plan = startPlan(freshStart, savedSnapshot(prefs), sequenceId, engine.currentState)) {
                    is StartPlan.Resume -> {
                        lastRestoreOutcome = engine.restore(sequence, plan.snapshot)
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
                    }
                    StartPlan.FromTheTop -> {
                        engine.load(sequence)
                        engine.start()
                        lastRestoreOutcome = null
                    }
                }
                pendingRestoreNotice = lastRestoreOutcome

                // Sound whatever is already due, synchronously, ahead of the startup work below (#62).
                //
                // The first cue of every sequence we ship sits at `offsetMs == totalMs` — Scholastic's
                // 3:00, US Sailing's 5:00, a Custom race's whole-minute top — so it comes due at the
                // instant `start()` anchors the gun, with no slack at all. It used to be dispatched by
                // the first `tickRunnable` pass, which put it behind persist + wake lock +
                // `startForeground`: measured ~170 ms late against an anchor the countdown display had
                // right to the millisecond.
                //
                // It has to be a direct call, not a re-ordering of the four lines below. Both
                // `scheduleTickLoop` and `scheduleNextCue` *post* to the main Looper and this method is
                // already running on it, so nothing they queue can be picked up until `onStartCommand`
                // returns — hoisting either above the block would move code and change no timing. Only
                // work done on this thread gets ahead of the startup path.
                //
                // Safe to put ahead of `startForeground`, whose Android 12+ deadline is the reason not
                // to delay it casually (see the START_NOT_STICKY note further down for prior art on
                // that class of problem): what runs on *this* thread is one vibrator binder call plus a
                // couple of handler posts — `ToneManager.playCue` hands the cue to the tone thread and
                // returns — single-digit milliseconds against a multi-second budget.
                //
                // Firing this early leaves the `warmUp` above no time at all to render — but since #98
                // that no longer decides anything, because the render that matters was posted long
                // before this method ran: `warmUpPickedSequence()` in `onCreate`, and the activity's
                // own calls on binding and on every pick. `cueFor` normally finds the buffer already in
                // its map, so this cue costs a lookup rather than a synthesis.
                //
                // (Before #98 this paragraph read the other way — that `warmUp` "will now usually lose"
                // the race and the cost was one duplicated render. That was accurate while
                // `onStartCommand` held the only `warmUp` call site, and it is what the fix disproved:
                // the render thread was started a few lines above, so it did not lose a race it started
                // behind — it started *level*, and finished 291 ms after the tone thread had already
                // rendered the same buffer inline.)
                //
                // The old reasoning still describes the fallback: if no pre-render reached the map,
                // losing is not a wait — `ToneManager.cueFor` reads a `ConcurrentHashMap` and re-renders
                // on the tone thread rather than blocking on the render thread, costing one duplicated
                // render, which that class already accounts wasted work rather than a fault.
                //
                // The restore path wants this too, and said so first: `TimerEngine.restore` keeps cues
                // with `offsetMs <= remaining` precisely so that "a cue sitting exactly on `remaining`
                // is still to come and tick() should sound it at once".
                engine.tick()

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

    /**
     * Render [sequence]'s cues now, ahead of any race that might run them (#98).
     *
     * Called by the activity the moment it knows which sequence is selected — on binding, and again
     * on every pick — because that is seconds before Start and the render needs seconds.
     *
     * `onStartCommand` warms up too and that call is now a backstop rather than the real one. It
     * could not be the real one: it runs on the main thread a few lines above the `engine.tick()`
     * that fires the first cue, so the render thread it posts to starts *level* with the cue it is
     * meant to be ahead of. Measured on an SM-R925U with `log.tag.ToneManager DEBUG`, the two threads
     * began the same 108000-sample buffer within ~32 ms of each other and the render thread — at
     * `THREAD_PRIORITY_DEFAULT` against the tone thread's `URGENT_AUDIO` — finished 291 ms *after*
     * the tone thread had already rendered it inline. The first cue of a cold race came out 607-675
     * ms late across four runs, every millisecond of it spent rendering rather than scheduling.
     *
     * So the fix is a head start, not more priority: a faster loser is still a loser when both start
     * together. The activity binds with `BIND_AUTO_CREATE` in `onStart`, and the watch takes ~3.3 s
     * from launch to first paint, so by the time there is a Start button to tap the work is long
     * since posted.
     *
     * Safe to call as often as the selection changes — [ToneManager.warmUp] renders each distinct cue
     * shape once and the shapes are shared across sequences, so flicking through the picker re-renders
     * almost nothing.
     */
    fun warmUpCues(sequence: RaceSequence) {
        tone.warmUp(sequence.cues.map { it.signal })
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(cueRunnable)
        releaseWakeLock()
        // Third and last of the restore points (#95), for the paths that reach destruction without
        // going through [stopForegroundAndCleanup] — the system reclaiming the service, or the sailor
        // swiping the app away mid-race. Idempotent: with the obligation already discharged there is
        // no record left to act on.
        restoreRaisedCueVolume()
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
        // The ordinary end of a race, and so the ordinary place the borrowed volume goes back (#95).
        // Before `clearPersistedState()` only for readability — the two touch different keys, since
        // this obligation is deliberately not part of the race snapshot.
        restoreRaisedCueVolume()
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

    /**
     * Forget the race in flight. **Not** the sailor's chosen sequence — see [PREF_PICKED_SEQUENCE_ID].
     *
     * Removes the four snapshot keys by name on purpose. `edit().clear()` would be shorter and would
     * silently take the remembered pick with it, reintroducing #88 the next time someone tidies this
     * up: a Stop would once again send the next cold launch back to US Sailing.
     */
    private fun clearPersistedState() {
        prefs.edit()
            .remove(PREF_SEQUENCE_ID)
            .remove(PREF_GUN_ELAPSED)
            .remove(PREF_GUN_WALL_CLOCK)
            .remove(PREF_CAPTURED_ELAPSED)
            .apply()
    }

    // --- Helpers --------------------------------------------------------------

    /**
     * The sequence [id] names, falling back to the default only when nothing answers to it.
     *
     * The fallback used to search [BuiltInSequences.all] alone, which does not contain a custom
     * sequence — so a saved `custom_8m` race resolved to US Sailing and resumed at the wrong
     * duration with the wrong cues, silently. [BuiltInSequences.resolve] rebuilds a custom sequence
     * from its id instead, and returns null rather than substituting.
     *
     * The fallback that remains is defensive: every id reaching here came from a live [RaceSequence]
     * the activity is holding, so it resolves. An id that *cannot* be resolved is caught one step
     * earlier, where the activity re-selects the persisted sequence on launch, and is announced
     * there rather than silently absorbed here.
     */
    private fun findSequence(id: String): RaceSequence =
        BuiltInSequences.resolve(id) ?: BuiltInSequences.usSailing

    companion object {
        private const val TAG = "TimerService"

        const val ACTION_START = "com.racetimer.wear.ACTION_START"
        const val ACTION_SYNC = "com.racetimer.wear.ACTION_SYNC"
        const val ACTION_STOP = "com.racetimer.wear.ACTION_STOP"
        const val ACTION_END_RACE = "com.racetimer.wear.ACTION_END_RACE"
        const val EXTRA_SEQUENCE_ID = "sequence_id"

        /** Set on [ACTION_START] to run the sequence from the top instead of resuming a saved race. */
        const val EXTRA_FRESH_START = "fresh_start"

        private const val PREFS_NAME = "race_timer_state"
        private const val PREF_SEQUENCE_ID = "sequence_id"
        private const val PREF_GUN_ELAPSED = "gun_elapsed_ms"
        private const val PREF_GUN_WALL_CLOCK = "gun_wall_clock_ms"
        private const val PREF_CAPTURED_ELAPSED = "captured_elapsed_ms"

        /**
         * The sequence the sailor last chose — **not** part of the race snapshot, and deliberately not
         * cleared with it.
         *
         * The four keys above describe a race in flight; this one describes a preference that outlives
         * every race. They were the same thing until #88, which is the whole defect: [clearPersistedState]
         * drops `PREF_SEQUENCE_ID` on Stop and at the post-gun teardown, so the app remembered the
         * sequence exactly while a race was running and forgot it in every ordinary case — a cold launch
         * reverted to US Sailing however many Club races had just been run.
         *
         * Shares the prefs file rather than opening a second one: same owner, same lifetime as the app,
         * and one file is one less thing to keep consistent. What matters is that no clear path touches
         * it.
         */
        private const val PREF_PICKED_SEQUENCE_ID = "picked_sequence_id"

        /**
         * The lead time the race manager last armed a race with (#104).
         *
         * A preference, not race state, so it sits beside [PREF_PICKED_SEQUENCE_ID] and outside
         * [clearPersistedState] for the same reason that key does: a club runs the same signal box
         * every week, and "the picker opens on the last-used value" has to survive a Stop and a cold
         * launch or it only ever means "this session".
         *
         * It is *not* what makes a lead-in race restorable — the lead lives inside the sequence id
         * (see `LeadIn.kt`), which is what the snapshot already carries. This key would be safe to
         * lose; the race would not.
         */
        private const val PREF_LAST_BOX_ALERT = "last_box_alert_seconds"

        /**
         * The stream whose volume a running race raised, and what it was before (#95).
         *
         * Two keys rather than one because the restore has to name a stream: the route is decided per
         * race, so the race that raised the volume may have been on a different stream from whatever
         * the next launch would choose. Restoring the right value onto the wrong stream is a way of
         * being wrong that reads as working.
         *
         * These are **not** preferences and not race state — they are an obligation. They exist only
         * between a race raising a volume and something putting it back, and their whole purpose is to
         * survive the case where "something putting it back" never runs because the process died. They
         * therefore sit outside [clearPersistedState] like the preferences above, but for the opposite
         * reason: a preference must outlive a race because the sailor chose it, while this must outlive
         * a race because nobody chose for it to end that way.
         *
         * `NO_RAISED_VOLUME` rather than `-1` scattered at the call sites, because "no record" has to be
         * distinguishable from a genuine volume of 0 — which is precisely the value this feature exists
         * to encounter.
         */
        private const val PREF_RAISED_STREAM = "raised_cue_stream"
        private const val PREF_RAISED_PREVIOUS_VOLUME = "raised_cue_previous_volume"
        private const val NO_RAISED_VOLUME = -1

        private const val TICK_INTERVAL_MS = 50L

        /** Time the UI keeps showing "GO!" *after* the gun cue itself has finished sounding. */
        private const val GUN_LINGER_MS = 3_000L

        /** Slack added to the wake-lock timeout to cover the gun cue and the teardown that follows. */
        private const val WAKE_LOCK_MARGIN_MS = 30_000L

        /**
         * The race still persisted from an earlier process, or null if there is none to come back to.
         *
         * Read by the activity on launch, for two things it cannot do without it.
         *
         * The first is picking the right sequence. Without this the restore path could not fire at
         * all after process death: the activity comes back holding the default sequence,
         * [ACTION_START] carries *that* id, and the guard in `onStartCommand` requires the persisted
         * id to match before it will restore — so the snapshot was skipped and a fresh default race
         * started over the top of it. Invisible until now only because the default is what most
         * races use.
         *
         * The second is showing the sailor what resuming would actually give them, via
         * [com.racetimer.shared.remainingFromSnapshot], instead of the sequence's full duration.
         *
         * Returns the whole snapshot rather than the id alone so both readings come from one load of
         * the same four keys — they are written together by `persistSnapshot` and cleared together,
         * and a caller that re-read them separately could catch them mid-edit.
         */
        fun savedSnapshot(context: Context): TimerEngine.Snapshot? =
            savedSnapshot(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        /**
         * The same reading, for a caller that already holds the prefs — `onStartCommand`, which
         * restores under it.
         *
         * The service used to read the four keys inline and apply its own copy of the guards. Two
         * copies of one rule is the shape every defect in this path has had, so there is now one
         * read here and one decision in [snapshotFrom]; the activity's offer and the service's
         * restore cannot disagree about whether a race exists, because they are the same call.
         */
        private fun savedSnapshot(prefs: SharedPreferences): TimerEngine.Snapshot? = snapshotFrom(
            sequenceId = prefs.getString(PREF_SEQUENCE_ID, null),
            gunElapsedMs = prefs.getLong(PREF_GUN_ELAPSED, NO_GUN_ELAPSED_MS),
            gunWallMs = prefs.getLong(PREF_GUN_WALL_CLOCK, NO_GUN_WALL_MS),
            capturedElapsedMs = prefs.getLong(PREF_CAPTURED_ELAPSED, NO_CAPTURED_ELAPSED_MS),
        )

        /**
         * @param freshStart true to discard any saved race and run [sequenceId] from the top. False
         *   resumes a saved race when one matches, which is the behaviour every caller had before
         *   the pre-start screen started offering the choice.
         */
        /**
         * Remember [sequenceId] as the sailor's current choice, to be reopened on the next launch.
         *
         * Stores the id rather than the sequence because the id *is* the whole record — every built-in
         * answers to its own, and a custom id encodes its duration (`custom_8m`), so
         * [BuiltInSequences.resolve] rebuilds either from this one string. That is the same property
         * the race snapshot relies on (#51), and it is guarded by `resolve rebuilds a custom sequence
         * from its id alone` in `RaceSequenceTest` — persistence here adds no new assumption.
         */
        fun savePickedSequenceId(context: Context, sequenceId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_PICKED_SEQUENCE_ID, sequenceId)
                .apply()
        }

        /** The sequence id last passed to [savePickedSequenceId], or null on a first-ever launch. */
        fun pickedSequenceId(context: Context): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_PICKED_SEQUENCE_ID, null)

        /** A volume a race raised and owes back: which stream, and what it held before (#95). */
        data class RaisedCueVolume(val stream: Int, val previousVolume: Int)

        /**
         * Record what the cue stream held before a race raised it — **before** raising it.
         *
         * `commit()`, not `apply()`, and this is the one place in this file where that is worth the
         * blocking write. `apply()` returns immediately and persists on a background thread, which
         * would leave exactly the window this record exists to close: the device volume changed and
         * the note of what it was still in flight when the process dies. The write is a few
         * milliseconds, once per race that needs it, on the way into a countdown that has not started.
         */
        fun saveRaisedCueVolume(context: Context, stream: Int, previousVolume: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_RAISED_STREAM, stream)
                .putInt(PREF_RAISED_PREVIOUS_VOLUME, previousVolume)
                .commit()
        }

        /** The outstanding volume obligation, or null when a race owes nothing. */
        fun raisedCueVolumeRecord(context: Context): RaisedCueVolume? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val previous = prefs.getInt(PREF_RAISED_PREVIOUS_VOLUME, NO_RAISED_VOLUME)
            if (previous == NO_RAISED_VOLUME) return null
            val stream = prefs.getInt(PREF_RAISED_STREAM, NO_RAISED_VOLUME)
            if (stream == NO_RAISED_VOLUME) return null
            return RaisedCueVolume(stream, previous)
        }

        /** Discharge the obligation. Removed **by name**, per the #88 rule this file already carries. */
        fun clearRaisedCueVolume(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_RAISED_STREAM)
                .remove(PREF_RAISED_PREVIOUS_VOLUME)
                .apply()
        }

        /** Remember [seconds] as the lead the lead-time picker should reopen on. */
        fun saveLastBoxAlertSeconds(context: Context, seconds: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_LAST_BOX_ALERT, seconds)
                .apply()
        }

        /**
         * The lead last armed, or [DEFAULT_BOX_ALERT_SECONDS] when there has never been one.
         *
         * Range-checked on the way out rather than trusted: this is the one lead-in value that is
         * *not* reconstructed from a sequence id, so a stored number out of bounds would otherwise
         * open the stepper somewhere the picker can never produce.
         */
        fun lastBoxAlertSeconds(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_LAST_BOX_ALERT, DEFAULT_BOX_ALERT_SECONDS)
                .takeIf { isValidBoxAlert(it) }
                ?: DEFAULT_BOX_ALERT_SECONDS

        fun startIntent(context: Context, sequenceId: String, freshStart: Boolean = false): Intent =
            Intent(context, TimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SEQUENCE_ID, sequenceId)
                putExtra(EXTRA_FRESH_START, freshStart)
            }

        fun syncIntent(context: Context): Intent =
            Intent(context, TimerService::class.java).apply { action = ACTION_SYNC }

        fun endRaceIntent(context: Context): Intent =
            Intent(context, TimerService::class.java).apply { action = ACTION_END_RACE }

        fun stopIntent(context: Context): Intent =
            Intent(context, TimerService::class.java).apply { action = ACTION_STOP }
    }
}
