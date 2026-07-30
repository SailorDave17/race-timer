package com.racetimer.wear

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Plays a short, loud alert tone alongside the haptic feedback in [HapticManager].
 *
 * Phase 1 (issue #37) is deliberately one single beep per feedback event — no per-cue patterns and
 * no user-configurable sound. The blast structure of a cue is still carried entirely by the
 * vibration; the beep exists so the sailor notices the cue when they can't feel the watch.
 *
 * The tone is generated with [ToneGenerator] rather than a sound asset: it needs no decoding, so
 * [playBeep] returns in well under the perceptual sync window with the vibration that fires
 * alongside it, and there is no asset to ship or to keep in memory.
 *
 * Audio is best-effort by design. A watch may have no speaker at all, and [ToneGenerator] throws
 * when the platform cannot hand out an audio track. Every failure path here logs and returns, so
 * the caller's vibration is never blocked by a broken beep.
 */
class ToneManager(context: Context) {

    private val appContext = context.applicationContext

    /** Null until the first successful init, and again after an init failure or [release]. */
    private var toneGenerator: ToneGenerator? = null

    /** Set once the constructor has thrown, so a dead audio stack isn't retried on every cue. */
    private var initFailed = false

    private val handler = Handler(Looper.getMainLooper())
    private val releaseRunnable = Runnable { releaseGenerator() }

    /** Uptime the current beep started, so [release] can wait out its tail. */
    private var beepStartedAtMs = 0L

    private val hasAudioOutput: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)

    /**
     * Build the audio track ahead of the first cue. Without this the allocation lands on whichever
     * cue beeps first, delaying that one beep behind its vibration.
     */
    fun prepare() {
        obtainGenerator()
    }

    /**
     * Play the alert beep. Safe to call from the main thread: [ToneGenerator.startTone] hands the
     * tone to the audio flinger and returns immediately rather than blocking for [BEEP_MS].
     */
    fun playBeep() {
        val generator = obtainGenerator() ?: return
        handler.removeCallbacks(releaseRunnable)
        try {
            if (generator.startTone(BEEP_TONE, BEEP_MS)) {
                beepStartedAtMs = SystemClock.uptimeMillis()
            } else {
                Log.w(TAG, "startTone returned false; beep dropped for this cue")
            }
        } catch (e: RuntimeException) {
            // The generator can be torn down underneath us (audio server restart, resource
            // reclaim). Drop it so the next cue rebuilds one instead of reusing a dead handle.
            Log.e(TAG, "startTone failed, discarding tone generator", e)
            releaseGenerator()
        }
    }

    /**
     * Release the audio track. Call from the owner's teardown; [playBeep] re-inits on demand.
     *
     * The gun cue beeps and then immediately tears the timer service down, and
     * [ToneGenerator.release] cuts playback off mid-tone — so if a beep is still sounding, the
     * teardown waits out its remaining duration first. That trailing beep is the start signal, the
     * one cue a sailor most needs to hear in full.
     */
    fun release() {
        initFailed = false
        val tailMs = BEEP_MS - (SystemClock.uptimeMillis() - beepStartedAtMs)
        if (toneGenerator != null && beepStartedAtMs > 0L && tailMs > 0L) {
            handler.postDelayed(releaseRunnable, tailMs)
        } else {
            handler.removeCallbacks(releaseRunnable)
            releaseGenerator()
        }
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
            // STREAM_ALARM so the beep carries outdoors: on Wear it is the loudest stream and is
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
        beepStartedAtMs = 0L
    }

    private companion object {
        const val TAG = "ToneManager"

        /** High, piercing tone — the most audible of the built-ins over wind and water. */
        const val BEEP_TONE = ToneGenerator.TONE_CDMA_HIGH_L

        /** Long enough to register outdoors, short enough to stay clear of the next cue. */
        const val BEEP_MS = 400
    }
}
