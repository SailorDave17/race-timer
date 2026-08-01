package com.racetimer.wear

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.racetimer.shared.CueVoice
import com.racetimer.shared.SignalPattern

/**
 * Plays the audible half of a race cue, alongside the haptic half in [HapticManager].
 *
 * A cue's tones land on the same blast boundaries as its vibration — both channels read
 * [CueTiming] — so three long blasts are three tones against three buzzes, aligned, rather than one
 * beep laid over a pattern the ear cannot resolve. A cue carrying [SignalPattern.sustainedMs] plays
 * as a single continuous tone of that length.
 *
 * Tones are generated with [ToneGenerator] rather than a sound asset: there is nothing to decode, so
 * a cue starts sounding well inside the perceptual sync window with the vibration firing alongside
 * it, and there is no asset to ship or hold in memory.
 *
 * ### Why the later blasts are clocked off the main thread
 *
 * The two channels of a cue are delivered by mechanisms with very different timing guarantees.
 * [HapticManager] submits the whole pattern as one `VibrationEffect.createWaveform` call and the
 * vibrator service runs it on its own timeline, so its blast-to-blast spacing cannot drift. Audio
 * has no equivalent — each blast is a separate [ToneGenerator.startTone] call that something has to
 * clock.
 *
 * That something used to be the main Looper, which is also recomposing the timer screen on every
 * tick. Blasts now go to a dedicated [HandlerThread] at [Process.THREAD_PRIORITY_URGENT_AUDIO] that
 * nothing else posts to, scheduled with [Handler.postAtTime] against one base captured when the cue
 * fired. Measured on an SM-R925U under recomposition (#58), that holds a blast to **1–5 ms** of its
 * intended offset on both the 750 ms beat of `3 long` and the 300 ms beat of `3 short`.
 *
 * ### Every blast, including the first — and why [LEAD_IN_MS] exists
 *
 * The first blast used to run inline on the caller's thread while the rest were posted. Two
 * measurements killed that, in order, and both are worth keeping because the second one contradicts
 * what the first appeared to show.
 *
 * The inline version was adopted because posting blast 0 made it *dispatch* late — it is due the
 * instant it is posted, so it has to wake an idle thread, where every later blast is already queued
 * against a thread that is awake by the time it comes due:
 *
 * ```
 * blast=0   lateMs: 1, 2, 5, 30, 11, 3, 3, 4, 16, 2, 2, 2, 1     max 30
 * blast>=1  lateMs: 1, 1, 1, 2, 2, 5                              max  5
 * ```
 *
 * Running it inline drove that to `lateMs=0`. But dispatch is not sound. Listened to on hardware,
 * the inline version was *audibly* uneven — on `3 short` the first two blasts sat closer together
 * than the last, and on `3 long` the first two ran into each other — while the log said the three
 * dispatches were 400 / 400 ms and 900 / 900 ms apart to within 2 ms. The thread ids in that same
 * log are what gives it away: blast 0 went out from the main thread, blasts 1..n from this one. The
 * main thread is recomposing the timer screen, so its binder call into the audio server reaches the
 * speaker later than the same call from an idle [Process.THREAD_PRIORITY_URGENT_AUDIO] thread. Blast
 * 0 *sounds* late and closes up the first interval, and no amount of dispatch accuracy shows it.
 *
 * So every blast now takes the identical path, and [LEAD_IN_MS] buys back what the inline version
 * was really for: with the whole cue scheduled a few tens of milliseconds out, blast 0 is queued
 * ahead of time exactly like its successors rather than racing a thread wake-up. Uniformity is the
 * point — a cue that is a few ms late as a whole is inaudible, where a cue whose first interval is
 * short is exactly what a sailor reported hearing.
 *
 * Dispatch lateness is measurable rather than assumed: enable it with
 * `adb shell setprop log.tag.ToneManager DEBUG` and each blast logs its intended offset from the
 * start of the cue against how late it actually ran. Note what that does and does not cover — it
 * measures when [ToneGenerator.startTone] was *called*, never when sound emerged, which is the whole
 * reason the inline version's `lateMs=0` was so misleading. Anything about audible evenness has to
 * be confirmed on a wrist.
 *
 * Audio is best-effort by design. A watch may have no speaker at all, and [ToneGenerator] throws
 * when the platform cannot hand out an audio track. Every failure path here logs and returns, so the
 * caller's vibration is never blocked by a broken tone.
 */
class ToneManager(context: Context) {

    private val appContext = context.applicationContext

    /** Guards [toneGenerator] and [initFailed], which [startTone] reaches from two threads. */
    private val generatorLock = Any()

    /** Null until the first successful init, and again after an init failure or [release]. */
    private var toneGenerator: ToneGenerator? = null

    /**
     * A second generator, at volume zero, whose only job is to keep the audio output open.
     *
     * The platform closes the output when it goes idle, and re-opening it costs ~54 ms of
     * `startOutput` plus speaker routing before any sound can flow — measured on an SM-R925U from
     * `APM_AudioPolicyManager: startOutput` to `audio_hw_playback: transited to Ready` (#58). That
     * cost lands on whichever blast happens to follow a close, which is not a fixed blast: within one
     * `3 short` cue, blast 0 and blast 2 each paid it and blast 1 did not, turning a dispatched
     * 400 / 400 ms beat into an audible 346 / 454 ms. A sailor hears the first two blasts crowding
     * the third.
     *
     * Holding a silent tone open for the race removes the close, so no blast pays for a restart and
     * the dispatched beat is the beat that sounds. It costs keeping the output powered while a race
     * runs — which the service is already holding a wake lock through.
     */
    private var keepAliveGenerator: ToneGenerator? = null

    /** Set once the constructor has thrown, so a dead audio stack isn't retried on every cue. */
    private var initFailed = false

    /** The cue-audio thread and its handler, or null before first use and after [release] runs. */
    @Volatile private var toneThread: HandlerThread? = null

    @Volatile private var toneHandler: Handler? = null

    /** Uptime the current cue's audio finishes, so [release] can wait out its real tail. */
    @Volatile private var soundingUntilMs = 0L

    private val releaseRunnable = Runnable { releaseGeneratorAndQuit() }

    private val hasAudioOutput: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)

    /**
     * Build the audio track ahead of the first cue. Without this the allocation lands on whichever
     * cue sounds first, delaying that one cue behind its vibration. Also starts the tone thread, so
     * the first cue does not pay for that either.
     */
    fun prepare() {
        obtainHandler().post {
            obtainGenerator()
            // Before the first cue rather than with it: the very first cue of a race fires within a
            // couple of hundred ms of the service starting, and opening the output is exactly the
            // cost this is here to avoid paying on a blast.
            armKeepAlive()
        }
    }

    /**
     * Play [pattern] as tones on its blast boundaries.
     *
     * Returns immediately. The first blast sounds inline — [ToneGenerator.startTone] hands the tone
     * to the audio server rather than blocking for its duration — and the rest are handed to the
     * tone thread.
     *
     * The cue's timing base is read here, on the caller's thread, so it is the moment the cue fired
     * — the same moment [HapticManager.play] submitted the vibration.
     */
    fun playCue(pattern: SignalPattern) {
        val baseMs = SystemClock.uptimeMillis()
        val blasts = blastsOf(pattern)
        if (blasts.isEmpty()) return

        val handler = obtainHandler()
        // Drops the previous cue's unsounded blasts and any pending teardown in one call, from
        // whichever thread got here; Handler's queue is synchronized where this class is not.
        handler.removeCallbacksAndMessages(null)

        // Re-armed every cue so it cannot lapse mid-race between two widely spaced cues.
        handler.postAtTime({ armKeepAlive() }, baseMs)

        val startAtMs = baseMs + LEAD_IN_MS
        var offsetMs = 0L
        for ((index, blast) in blasts.withIndex()) {
            val intendedOffsetMs = offsetMs
            handler.postAtTime(
                {
                    logDispatch(index, intendedOffsetMs, startAtMs)
                    startTone(blast.toneType, blast.onMs)
                },
                startAtMs + intendedOffsetMs,
            )
            offsetMs += blast.onMs + blast.offMs
        }
        soundingUntilMs = startAtMs + offsetMs
    }

    /**
     * Play a single feedback beep, for events that carry no blast pattern — a sync snap, say.
     */
    fun playBeep() {
        val startAtMs = SystemClock.uptimeMillis() + LEAD_IN_MS
        val handler = obtainHandler()
        handler.removeCallbacksAndMessages(null)
        // On the tone thread like every blast, though a lone beep has no beat to keep: it is the
        // last thing that would otherwise call into the audio server from the main thread.
        handler.postAtTime({ startTone(BLAST_TONE, BEEP_MS) }, startAtMs)
        soundingUntilMs = startAtMs + BEEP_MS
    }

    /**
     * Release the audio track and stop the tone thread. Call from the owner's teardown; the next
     * [playCue] rebuilds both on demand.
     *
     * The gun cue sounds and the timer service then tears down on the same tick, and
     * [ToneGenerator.release] cuts playback off mid-tone — so while a cue is still sounding, the
     * teardown waits out whatever is left *of that cue*, not of a fixed beep length. The gun is a
     * three-second sustained tone: a constant here would be wrong by most of it, and it is the one
     * cue a sailor most needs to hear in full.
     */
    fun release() {
        val handler = toneHandler ?: return
        handler.post {
            val live = synchronized(generatorLock) {
                initFailed = false
                toneGenerator != null
            }
            val tailMs = soundingUntilMs - SystemClock.uptimeMillis()
            if (live && tailMs > 0L) {
                handler.postDelayed(releaseRunnable, tailMs)
            } else {
                handler.removeCallbacksAndMessages(null)
                releaseGeneratorAndQuit()
            }
        }
    }

    // --- Internals ------------------------------------------------------------

    /** One tone of a cue: [onMs] of sound, then [offMs] of silence before the next. */
    private data class Blast(val toneType: Int, val onMs: Int, val offMs: Long)

    private fun blastsOf(pattern: SignalPattern): List<Blast> {
        if (pattern.sustainedMs > 0L) {
            return listOf(Blast(SUSTAINED_TONE, pattern.sustainedMs.toInt(), 0L))
        }
        // The voice picks the tone and the boundaries together. A sync tick is a different pitch on
        // a shorter beat than any blast, which is what stops a sailor hearing it as a signal.
        val tone = when (pattern.voice) {
            CueVoice.SYNC -> SYNC_TONE
            CueVoice.BLAST -> BLAST_TONE
        }
        val blasts = mutableListOf<Blast>()
        repeat(pattern.longBlasts) {
            blasts += Blast(
                tone,
                CueTiming.onMs(pattern, long = true).toInt(),
                CueTiming.offMs(pattern, long = true),
            )
        }
        repeat(pattern.shortBlasts) {
            blasts += Blast(
                tone,
                CueTiming.onMs(pattern, long = false).toInt(),
                CueTiming.offMs(pattern, long = false),
            )
        }
        return blasts
    }

    /**
     * Report how far a blast's dispatch missed its intended position in the cue.
     *
     * Off unless someone asks for it: `adb shell setprop log.tag.ToneManager DEBUG`. This is the
     * measurement that tells a late *schedule* apart from a long *tone*; the second one is not
     * visible from here at all and has to be read from delivered `AudioTrack` frames.
     */
    private fun logDispatch(index: Int, intendedOffsetMs: Long, baseMs: Long) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val actualOffsetMs = SystemClock.uptimeMillis() - baseMs
        Log.d(
            TAG,
            "blast=$index intendedOffsetMs=$intendedOffsetMs actualOffsetMs=$actualOffsetMs " +
                "lateMs=${actualOffsetMs - intendedOffsetMs}",
        )
    }

    /**
     * Sound one blast. Reached from the caller's thread for the first blast of a cue and from the
     * tone thread for the rest, hence the lock — and the lock is held across [startTone] itself
     * rather than just the lookup, because [releaseGenerator] running in between would leave this
     * calling into a freed native object. Contention is a non-issue in practice: blasts within a cue
     * are at least [CueTiming.SYNC_OFF] apart and `startTone` hands off to the audio server rather
     * than blocking for the tone's duration.
     */
    private fun startTone(toneType: Int, durationMs: Int) = synchronized(generatorLock) {
        val generator = obtainGeneratorLocked() ?: return
        try {
            if (!generator.startTone(toneType, durationMs)) {
                Log.w(TAG, "startTone returned false; blast dropped for this cue")
            }
        } catch (e: RuntimeException) {
            // The generator can be torn down underneath us (audio server restart, resource
            // reclaim). Drop it so the next cue rebuilds one instead of reusing a dead handle.
            Log.e(TAG, "startTone failed, discarding tone generator", e)
            releaseGeneratorLocked()
        }
    }

    /**
     * The tone thread's handler, starting the thread if it is not running.
     *
     * Synchronized because it is reached both from the public methods on the caller's thread and
     * from the work those post; the critical section is a null check and a thread start.
     */
    @Synchronized
    private fun obtainHandler(): Handler {
        toneHandler?.let { return it }
        val thread = HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_URGENT_AUDIO)
        thread.start()
        toneThread = thread
        return Handler(thread.looper).also { toneHandler = it }
    }

    private fun obtainGenerator(): ToneGenerator? =
        synchronized(generatorLock) { obtainGeneratorLocked() }

    /**
     * (Re)start the silent tone that holds the audio output open. See [keepAliveGenerator].
     *
     * Best-effort like everything else here: a device with no usable audio stack simply runs
     * vibration-only, and a failure to hold the output open costs evenness, not the cue.
     */
    private fun armKeepAlive() {
        synchronized(generatorLock) {
            if (!hasAudioOutput) return
            val generator = keepAliveGenerator ?: try {
                ToneGenerator(AudioManager.STREAM_ALARM, 0).also { keepAliveGenerator = it }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Keep-alive generator unavailable; blasts may sound uneven", e)
                return
            }
            try {
                generator.startTone(KEEP_ALIVE_TONE, KEEP_ALIVE_MS)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Keep-alive startTone failed", e)
            }
        }
    }

    /** Call with [generatorLock] held. */
    private fun obtainGeneratorLocked(): ToneGenerator? {
        toneGenerator?.let { return it }
        if (initFailed) return null

        if (!hasAudioOutput) {
            Log.w(TAG, "No audio output on this device; running vibration-only")
            initFailed = true
            return null
        }

        return try {
            // STREAM_ALARM so the tone carries outdoors: on Wear it is the loudest stream and is
            // the one users leave up for alerts, unlike STREAM_MUSIC or STREAM_NOTIFICATION.
            ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME).also {
                toneGenerator = it
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "ToneGenerator unavailable; running vibration-only", e)
            initFailed = true
            null
        }
    }

    /** Call with [generatorLock] held. */
    private fun releaseGeneratorLocked() {
        try {
            toneGenerator?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneGenerator release failed", e)
        }
        try {
            // Goes with the teardown, not with the cue: leaving it running would hold the audio
            // output open past the race that needed it.
            keepAliveGenerator?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Keep-alive release failed", e)
        }
        toneGenerator = null
        keepAliveGenerator = null
        soundingUntilMs = 0L
    }

    /**
     * Teardown proper: drop the audio track, then stop the thread it was driven from.
     *
     * The thread is stopped rather than left idle because a [ToneManager] does not outlive its
     * service, so anything still running after [release] is a leak. `quitSafely` from the thread's
     * own runnable is fine — it stops the looper after this message, and by here there is nothing
     * left queued.
     */
    @Synchronized
    private fun releaseGeneratorAndQuit() {
        synchronized(generatorLock) { releaseGeneratorLocked() }
        toneThread?.quitSafely()
        toneThread = null
        toneHandler = null
    }

    private companion object {
        const val TAG = "ToneManager"

        const val THREAD_NAME = "RaceTimerTone"

        /**
         * How far ahead of the cue every blast is scheduled, so none of them races a thread wake-up.
         *
         * Sized off the measured worst case for a blast that was due the moment it was posted —
         * 30 ms on an SM-R925U under recomposition (see the class doc) — with a little room over.
         * Below that, blast 0 starts paying a wake-up its successors do not and the first interval
         * of the cue closes up, which is audible on a 400 ms beat.
         *
         * The cost is that a cue's audio trails its vibration by this much, since [HapticManager]
         * submits at the unshifted moment. That is a deliberate trade: a constant offset between the
         * channels is far less noticeable than an uneven beat within one of them, and at this size it
         * stays well inside the window where the two still read as one event. Do not grow it to fix
         * something else — past roughly 50 ms the tone starts to sound like it is answering the buzz
         * rather than arriving with it.
         */
        const val LEAD_IN_MS = 40L

        /**
         * Tone for a blast. High and piercing — the most audible of the built-ins over wind and
         * water.
         *
         * A `TONE_CDMA_*` tone is a *pattern* with its own internal segment lengths, so a request is
         * a cap rather than a length that is honoured. That is fatal for a sustained cue (see
         * [SUSTAINED_TONE]) but survivable here, where every request is short enough to truncate the
         * pattern rather than outrun it.
         *
         * **Do not swap this to a DTMF tone to chase the overshoot.** That was tried and measured on
         * an SM-R925U under #58, and the overshoot is not a property of the tone family:
         *
         * | Requested | Tone | Delivered |
         * |---|---|---|
         * | 150 ms | `TONE_CDMA_HIGH_L` | 160.0 ms |
         * | 150 ms | `TONE_DTMF_D` | 160.0 ms |
         * | 500 ms | `TONE_CDMA_HIGH_L` | 520.0 ms |
         * | 500 ms | `TONE_DTMF_D` | 512.0 ms **and** 520.0 ms, same race |
         *
         * The last row is the important one: identical calls delivered different lengths, so the
         * overshoot cannot be tuned away by picking a tone or by shortening the request either. What
         * remains is a tone running ~10–20 ms past the buzz beside it, in the same direction every
         * time, which is a real but so-far-unfixed defect. Confirm any change here against delivered
         * frame count, never by ear.
         */
        const val BLAST_TONE = ToneGenerator.TONE_CDMA_HIGH_L

        /**
         * Tone for a sustained cue.
         *
         * Not [BLAST_TONE]: the CDMA tones are patterns, and asking one for three seconds does not
         * buy three seconds of sound — it stops when its own segment ends. The DTMF tones are single
         * continuous segments, and at this length one tracks the request closely (3000 ms requested,
         * 3000.2 ms delivered, #42), which is the whole requirement for the gun. Note that the same
         * tone is *not* accurate at short lengths — see [SYNC_TONE] — so this reasoning does not
         * transfer to blasts. Confirm any change here against delivered frame count, never by ear.
         */
        const val SUSTAINED_TONE = ToneGenerator.TONE_DTMF_D

        /**
         * Tone for a [CueVoice.SYNC] tick.
         *
         * Chosen for *pitch* distance from [BLAST_TONE], not timbre: `TONE_DTMF_1` is the lowest
         * DTMF pair (697 + 1209 Hz) against a high CDMA blast, and pitch is what survives wind and
         * water on the water.
         *
         * Known defect, measured under #58: at [CueTiming.SYNC_ON] this delivers **80 ms for a 60 ms
         * request** — a third longer than the buzz beside it, and the worst proportional overshoot
         * in the app. It is not fixable by tone choice ([BLAST_TONE] has the evidence); closing it
         * means driving [HapticManager] from measured delivered lengths rather than requested ones.
         * Confirm any change here against delivered frame count, never by ear.
         */
        const val SYNC_TONE = ToneGenerator.TONE_DTMF_1

        /** Long enough to register outdoors, short enough to stay clear of the next cue. */
        const val BEEP_MS = 400

        /**
         * Tone used for the silent keep-alive. DTMF because it must hold for an arbitrary requested
         * length — a `TONE_CDMA_*` pattern would stop at its own segment and let the output close.
         */
        const val KEEP_ALIVE_TONE = ToneGenerator.TONE_DTMF_0

        /**
         * How long each keep-alive request runs.
         *
         * Comfortably longer than the widest gap between two cues in any built-in sequence (60 s,
         * the Scholastic 3:00→2:00 leg) so the output never closes mid-race, and re-armed on every
         * cue so the window keeps sliding forward. Bounded rather than indefinite so a leaked
         * generator cannot hold the output open forever.
         */
        const val KEEP_ALIVE_MS = 120_000
    }
}
