package com.racetimer.wear

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
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
 * Tones are generated with [ToneGenerator] rather than a sound asset: there is nothing to decode,
 * so [playCue] returns well inside the perceptual sync window with the vibration firing alongside
 * it, and there is no asset to ship or hold in memory. Every blast after the first is
 * Handler-posted, so the call returns immediately however long the pattern runs and never delays
 * the vibration.
 *
 * Audio is best-effort by design. A watch may have no speaker at all, and [ToneGenerator] throws
 * when the platform cannot hand out an audio track. Every failure path here logs and returns, so
 * the caller's vibration is never blocked by a broken tone.
 */
class ToneManager(context: Context) {

    private val appContext = context.applicationContext

    /** Null until the first successful init, and again after an init failure or [release]. */
    private var toneGenerator: ToneGenerator? = null

    /** Set once the constructor has thrown, so a dead audio stack isn't retried on every cue. */
    private var initFailed = false

    private val handler = Handler(Looper.getMainLooper())
    private val releaseRunnable = Runnable { releaseGenerator() }

    /** Blasts of the current cue not yet sounded, so the next cue can cancel what it supersedes. */
    private val pendingBlasts = mutableListOf<Runnable>()

    /** Uptime the current cue's audio finishes, so [release] can wait out its real tail. */
    private var soundingUntilMs = 0L

    private val hasAudioOutput: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)

    /**
     * Build the audio track ahead of the first cue. Without this the allocation lands on whichever
     * cue sounds first, delaying that one cue behind its vibration.
     */
    fun prepare() {
        obtainGenerator()
    }

    /**
     * Play [pattern] as tones on its blast boundaries.
     *
     * Safe to call from the main thread and returns immediately: [ToneGenerator.startTone] hands
     * the tone to the audio flinger rather than blocking for its duration, and the blasts after the
     * first are posted rather than waited on.
     */
    fun playCue(pattern: SignalPattern) {
        val blasts = blastsOf(pattern)
        if (blasts.isEmpty()) return

        cancelPendingBlasts()
        handler.removeCallbacks(releaseRunnable)
        if (obtainGenerator() == null) return

        var offsetMs = 0L
        for (blast in blasts) {
            if (offsetMs == 0L) {
                startTone(blast.toneType, blast.onMs)
            } else {
                val runnable = Runnable { startTone(blast.toneType, blast.onMs) }
                pendingBlasts += runnable
                handler.postDelayed(runnable, offsetMs)
            }
            offsetMs += blast.onMs + blast.offMs
        }
        soundingUntilMs = SystemClock.uptimeMillis() + offsetMs
    }

    /**
     * Play a single feedback beep, for events that carry no blast pattern — a sync snap, say.
     */
    fun playBeep() {
        cancelPendingBlasts()
        handler.removeCallbacks(releaseRunnable)
        if (obtainGenerator() == null) return

        startTone(BLAST_TONE, BEEP_MS)
        soundingUntilMs = SystemClock.uptimeMillis() + BEEP_MS
    }

    /**
     * Release the audio track. Call from the owner's teardown; [playCue] re-inits on demand.
     *
     * The gun cue sounds and the timer service then tears down on the same tick, and
     * [ToneGenerator.release] cuts playback off mid-tone — so while a cue is still sounding, the
     * teardown waits out whatever is left *of that cue*, not of a fixed beep length. The gun is a
     * three-second sustained tone: a constant here would be wrong by most of it, and it is the one
     * cue a sailor most needs to hear in full.
     */
    fun release() {
        initFailed = false
        val tailMs = soundingUntilMs - SystemClock.uptimeMillis()
        if (toneGenerator != null && tailMs > 0L) {
            handler.postDelayed(releaseRunnable, tailMs)
        } else {
            cancelPendingBlasts()
            handler.removeCallbacks(releaseRunnable)
            releaseGenerator()
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

    private fun startTone(toneType: Int, durationMs: Int) {
        val generator = obtainGenerator() ?: return
        try {
            if (!generator.startTone(toneType, durationMs)) {
                Log.w(TAG, "startTone returned false; blast dropped for this cue")
            }
        } catch (e: RuntimeException) {
            // The generator can be torn down underneath us (audio server restart, resource
            // reclaim). Drop it so the next cue rebuilds one instead of reusing a dead handle.
            Log.e(TAG, "startTone failed, discarding tone generator", e)
            releaseGenerator()
        }
    }

    private fun cancelPendingBlasts() {
        pendingBlasts.forEach { handler.removeCallbacks(it) }
        pendingBlasts.clear()
    }

    private fun obtainGenerator(): ToneGenerator? {
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

    private fun releaseGenerator() {
        try {
            toneGenerator?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneGenerator release failed", e)
        }
        toneGenerator = null
        soundingUntilMs = 0L
    }

    private companion object {
        const val TAG = "ToneManager"

        /** High, piercing tone — the most audible of the built-ins over wind and water. */
        const val BLAST_TONE = ToneGenerator.TONE_CDMA_HIGH_L

        /**
         * Tone for a sustained cue.
         *
         * Not [BLAST_TONE]: the CDMA tones are *patterns* with their own internal segment lengths,
         * and asking one for three seconds does not buy three seconds of sound — it stops when its
         * own segment ends. The DTMF tones are single continuous segments, so they run for exactly
         * the duration requested, which is the whole requirement for the gun. Confirm any change
         * here against delivered frame count, never by ear.
         */
        const val SUSTAINED_TONE = ToneGenerator.TONE_DTMF_D

        /**
         * Tone for a [CueVoice.SYNC] tick.
         *
         * Chosen for *pitch* distance from [BLAST_TONE], not timbre: `TONE_DTMF_1` is the lowest
         * DTMF pair (697 + 1209 Hz) against a high CDMA blast, and pitch is what survives wind and
         * water on the water. DTMF for the same reason [SUSTAINED_TONE] is — a single continuous
         * segment honours the requested length, where a `TONE_CDMA_*` pattern stops at its own.
         * At [CueTiming.SYNC_ON] this matters: a truncated tick would land shorter than the buzz
         * beside it. Confirm any change here against delivered frame count, never by ear.
         */
        const val SYNC_TONE = ToneGenerator.TONE_DTMF_1

        /** Long enough to register outdoors, short enough to stay clear of the next cue. */
        const val BEEP_MS = 400
    }
}
