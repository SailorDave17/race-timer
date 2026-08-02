package com.racetimer.wear

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import com.racetimer.shared.CueTiming
import com.racetimer.shared.CueVoice
import com.racetimer.shared.CueWaveform
import com.racetimer.shared.SignalPattern

/**
 * Plays the audible half of a race cue, alongside the haptic half in [HapticManager].
 *
 * A cue's tones land on the same blast boundaries as its vibration — both channels read
 * [CueTiming] — so three long blasts are three tones against three buzzes, aligned, rather than one
 * beep laid over a pattern the ear cannot resolve.
 *
 * ### One submission per cue, not one call per blast (#61)
 *
 * The whole cue — every tone and every silence between them — is rendered to PCM by [CueWaveform]
 * and written to a single [AudioTrack] in one piece. That mirrors what [HapticManager] has always
 * done with `VibrationEffect.createWaveform`, and it is what finally makes the two channels agree.
 *
 * The path this replaced called [ToneGenerator.startTone] once per blast. `startTone`'s duration is
 * a *cap* rather than a length: measured on an SM-R925U, 60 ms delivered 80 ms, 150 ms delivered
 * 160 ms, and 500 ms delivered 512 ms five times and 520 ms once **in the same race**. Every tone
 * therefore ran past the buzz beside it and ate the silence that followed, in the same direction
 * every time. Neither cheap fix survived measurement — the CDMA and DTMF families overshoot a short
 * request identically, so swapping tone family bought nothing, and a call that returns two different
 * lengths from identical arguments cannot be padded.
 *
 * A sample is a sample, so a rendered buffer has no such freedom. The blast lengths are asserted
 * against [CueTiming] in `CueWaveformTest`, in a module CI runs.
 *
 * A second, quieter benefit: the beat *inside* a cue no longer depends on this class getting
 * scheduled. The old path woke a thread on every blast boundary and was only as even as those
 * wake-ups; a cue is now clocked by the audio hardware off one buffer. What scheduling still decides
 * is when the cue *starts*, which shifts it as a whole — the far more forgiving error.
 *
 * ### One track for the race, not one per cue
 *
 * The track is built once, in [prepare], and reused. That is not tidiness: building one per cue was
 * measured on an SM-R925U at **89-182 ms** of `getOutputForAttr` / `releaseOutput` round trips per
 * cue, against 1-30 ms for the path it replaced. A cue's audio would have trailed its buzz by a
 * fifth of a second — inaudible as a *timing* error, but plainly audible as the tone answering the
 * buzz rather than arriving with it, which is the thing [LEAD_IN_MS] exists to prevent.
 *
 * So the expensive parts happen off the deadline. The track is created before the race, the buffer
 * is written during [LEAD_IN_MS], and the only thing waiting on a clock is [AudioTrack.play].
 *
 * `MODE_STREAM` rather than `MODE_STATIC`, given the track has to be reused: a static track is
 * written once and replayed, which cannot express a different cue each time. The underrun risk that
 * normally argues against streaming does not apply here, because the whole cue is written before
 * playback starts rather than fed in as it drains.
 *
 * It brings one trap with it, and it is the kind that ships: a streaming track does not start when
 * `play()` is called, it starts when the buffer reaches a *start threshold* that defaults to the
 * whole buffer. Get that wrong and every call succeeds, the track reports itself as playing, and no
 * sound comes out. See `armStartThreshold`.
 *
 * ### Why the cue is still clocked off the main thread
 *
 * Everything runs on a dedicated [HandlerThread] at [Process.THREAD_PRIORITY_URGENT_AUDIO] that
 * nothing else posts to, because the main Looper is recomposing the timer screen on every tick and
 * its binder calls into the audio server reach the speaker later than an idle thread's. That was
 * measured under #58 and it cost a whole build to learn: dispatch accuracy is not sound, and the
 * version reporting `lateMs=0` was the one that sounded worst.
 *
 * Timing is measurable rather than assumed: `adb shell setprop log.tag.ToneManager DEBUG` logs how
 * late each cue started and how long its preparation took. Note what that does and does not cover —
 * it measures when [AudioTrack.play] was *called*, never when sound emerged. Anything about audible
 * evenness has to be confirmed on a wrist.
 *
 * Audio is best-effort by design. A watch may have no speaker at all, and the audio stack can refuse
 * to hand out a track. Every failure path here logs and returns, so the caller's vibration is never
 * blocked by a broken tone.
 */
class ToneManager(context: Context) {

    private val appContext = context.applicationContext

    /** Guards [track] and [keepAliveGenerator], which teardown and the tone thread both reach. */
    private val audioLock = Any()

    /** The one track every cue is played through. Null until [prepare], and again after [release]. */
    private var track: AudioTrack? = null

    /** Samples [track] can hold, so a cue too long for it is split rather than truncated. */
    private var trackCapacitySamples = 0

    /** Tail of a cue too long to prefill, written once playback has made room. */
    private var pendingSamples: ShortArray? = null

    private var pendingFrom = 0

    /**
     * Rendered PCM per cue shape, so a cue is never synthesised on its own deadline. See [warmUp].
     *
     * Written by the render thread and read by the tone thread, hence the concurrent map. A race has
     * a handful of distinct shapes and each holds a few hundred kilobytes at most, so this is bounded
     * by the sequence rather than by the number of cues in it — the ten one-second ticks of a final
     * minute share a single buffer.
     */
    private val cueBuffers = ConcurrentHashMap<CueShape, ShortArray>()

    /** The feedback beep, which carries no [SignalPattern] and so needs its own slot. */
    @Volatile private var beepBuffer: ShortArray? = null

    /**
     * Where [warmUp] does its synthesising — deliberately *not* the tone thread.
     *
     * Rendering a race's cues takes a couple of seconds on this hardware, dominated by the
     * three-second gun. Run on the tone thread, that work sits in the same queue as the cues and the
     * first cue of the race lands behind all of it: **measured at 1510 ms late**, against a first cue
     * that is due the instant the engine starts. Cancelling the warm-up instead would only move the
     * cost back onto the cues.
     *
     * So the two run in parallel and the tone thread never renders anything it can avoid. A cue that
     * arrives before its buffer is ready renders inline and is merely as slow as it used to be,
     * rather than blocked.
     *
     * Default priority rather than `THREAD_PRIORITY_BACKGROUND`, which would be the obvious choice
     * and is wrong: Android puts background-priority threads in a cgroup with a small share of a
     * core, and the warm-up is racing the first cues of a race. It sits below the tone thread's
     * [Process.THREAD_PRIORITY_URGENT_AUDIO] either way.
     */
    @Volatile private var renderThread: HandlerThread? = null

    @Volatile private var renderHandler: Handler? = null

    /**
     * A [ToneGenerator] at volume zero whose only job is to keep the audio output open.
     *
     * Kept from #58 rather than removed with the rest of the `ToneGenerator` path, and the reasoning
     * changed with the fix rather than disappearing. The platform closes the output when it goes
     * idle and charges ~54 ms of `startOutput` plus speaker routing to whatever plays next — measured
     * on an SM-R925U from `APM_AudioPolicyManager: startOutput` to
     * `audio_hw_playback: transited to Ready`.
     *
     * What that cost used to do was land on an unpredictable *blast inside* a cue: within one
     * `3 short`, blast 0 and blast 2 each paid it and blast 1 did not, turning a dispatched
     * 400 / 400 ms beat into an audible 346 / 454 ms. One buffer per cue removes that outright — a
     * cue cannot pay a restart in its middle.
     *
     * It would still land on the *start* of a cue, and the gaps between cues run to 60 s in the
     * Scholastic sequence. Left in place because ~54 ms of delay on a cue a sailor is timing a start
     * off is worth more than the power saved, and because #61 is not the issue in which to retire a
     * fix that was verified by ear on the water.
     *
     * Re-armed *after* a cue has started rather than before it, so its own cost cannot land on the
     * cue it is there to protect.
     */
    private var keepAliveGenerator: ToneGenerator? = null

    /** Set once the audio stack has refused, so a dead one isn't retried on every cue. */
    private var initFailed = false

    /** The cue-audio thread and its handler, or null before first use and after [release] runs. */
    @Volatile private var toneThread: HandlerThread? = null

    @Volatile private var toneHandler: Handler? = null

    /** Uptime the current cue's audio finishes, so [release] can wait out its real tail. */
    @Volatile private var soundingUntilMs = 0L

    /** True while the track holds no queued audio, so a load can skip the pause-and-flush IPC. */
    private var trackIsEmpty = false

    /** Playback head when the current cue started, so its delivered frames can be counted. */
    private var headAtCueStart = 0

    private val releaseRunnable = Runnable { releaseAudioAndQuit() }

    /** Marks the messages belonging to a cue, so cancelling one cannot cancel [warmUp]. */
    private val cueToken = Any()

    private val hasAudioOutput: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)

    /**
     * The output's own sample rate.
     *
     * Read from the device rather than assumed, and used unchanged, because anything else would be
     * resampled on the way out — and a resampler is exactly the kind of thing that would quietly put
     * back the rounding this fix removed. 48 kHz on the SM-R925U.
     */
    private val sampleRateHz: Int =
        try {
            AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_ALARM)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Native output rate unavailable; falling back to $FALLBACK_SAMPLE_RATE_HZ", e)
            FALLBACK_SAMPLE_RATE_HZ
        }

    /**
     * Build the track and open the audio output ahead of the first cue.
     *
     * Without this, both costs land on whichever cue sounds first — and the first cue of a race
     * fires within a couple of hundred milliseconds of the service starting, so it is the least
     * affordable place to put them.
     */
    fun prepare() {
        obtainHandler().post {
            synchronized(audioLock) { obtainTrackLocked() }
            armKeepAlive()
            beepBuffer()
        }
    }

    /**
     * Render every cue of the sequence about to run, before it runs.
     *
     * Rendering is not free on a watch. Measured on an SM-R925U on the tone thread, a 400 ms cue took
     * **27 ms when the thread had a core to itself and 861 ms when it did not** — and the moment it
     * does not is precisely the start of a race, with the service starting and the timer screen
     * composing for the first time. Left on the cue's own path that put the first cues of a race
     * hundreds of milliseconds behind their buzz.
     *
     * So it happens once, here, and a cue then costs a map lookup and a buffer write. Call this as
     * soon as the sequence is known and before the race starts. Rendering in cue order matters: the
     * first cue of a race is due the instant the engine starts, so it is the one with the least time
     * to spare and the one that must be rendered first.
     *
     * Safe to call repeatedly — a pattern already rendered is not rendered again — and safe to skip,
     * since [playCue] still renders on demand for anything missing.
     */
    fun warmUp(patterns: List<SignalPattern>) {
        obtainRenderHandler().post {
            for (pattern in patterns) cueFor(pattern)
        }
    }

    /**
     * Play [pattern] as one rendered cue.
     *
     * Returns immediately. The cue's timing base is read here, on the caller's thread, so it is the
     * moment the cue fired — the same moment [HapticManager.play] submitted the vibration.
     */
    fun playCue(pattern: SignalPattern) {
        val baseMs = SystemClock.uptimeMillis()
        val durationMs = CueTiming.durationMs(pattern)
        if (durationMs <= 0L) return
        submit(baseMs, durationMs) { cueFor(pattern) }
    }

    /**
     * Play a single feedback beep, for events that carry no blast pattern — a sync snap, say.
     */
    fun playBeep() {
        val baseMs = SystemClock.uptimeMillis()
        submit(baseMs, BEEP_MS) { beepBuffer() }
    }

    /**
     * Release the audio track and stop the tone thread. Call from the owner's teardown; the next
     * [playCue] rebuilds both on demand.
     *
     * The gun cue sounds and the timer service then tears down on the same tick, and releasing the
     * track cuts playback off mid-tone — so while a cue is still sounding, the teardown waits out
     * whatever is left *of that cue*, not of a fixed beep length. The gun is a three-second sustained
     * tone: a constant here would be wrong by most of it, and it is the one cue a sailor most needs
     * to hear in full.
     */
    fun release() {
        val handler = toneHandler ?: return
        handler.post {
            val live = synchronized(audioLock) {
                initFailed = false
                track != null || keepAliveGenerator != null
            }
            val tailMs = soundingUntilMs - SystemClock.uptimeMillis()
            if (live && tailMs > 0L) {
                handler.postDelayed(releaseRunnable, tailMs)
            } else {
                handler.removeCallbacksAndMessages(null)
                releaseAudioAndQuit()
            }
        }
    }

    // --- Internals ------------------------------------------------------------

    /**
     * What makes two cues sound the same.
     *
     * Deliberately *not* [SignalPattern] itself. That carries a [SignalPattern.label] for the UI,
     * which is part of its `equals` and has no effect whatever on the audio — so keying the cache on
     * the pattern rendered the identical 60 ms tick five separate times in one warm-up, once per
     * label, and left the race paying for renders it already had.
     */
    private data class CueShape(
        val longBlasts: Int,
        val shortBlasts: Int,
        val sustainedMs: Long,
        val voice: CueVoice,
    )

    private fun shapeOf(pattern: SignalPattern) = CueShape(
        longBlasts = pattern.longBlasts,
        shortBlasts = pattern.shortBlasts,
        sustainedMs = pattern.sustainedMs,
        voice = pattern.voice,
    )

    /**
     * The rendered buffer for [pattern], synthesising it on first use.
     */
    private fun cueFor(pattern: SignalPattern): ShortArray {
        val shape = shapeOf(pattern)
        cueBuffers[shape]?.let { return it }
        // Reached from both the render thread and, on a miss, the tone thread. Rendering the same
        // shape twice is wasted work rather than a fault, so this races harmlessly.
        val rendered = timedRender { CueWaveform.render(pattern, sampleRateHz) }
        return cueBuffers.putIfAbsent(shape, rendered) ?: rendered
    }

    /** The rendered feedback beep, synthesising it on first use. */
    private fun beepBuffer(): ShortArray =
        beepBuffer ?: timedRender { CueWaveform.renderBeep(BEEP_MS, sampleRateHz) }
            .also { beepBuffer = it }

    /**
     * Render and, under debug logging, report what it cost.
     *
     * Worth keeping visible: this number is the reason [warmUp] exists, and it varies by more than an
     * order of magnitude with how contended the thread is, so a future change that quietly moves a
     * render back onto the cue path would otherwise only show up as a cue that sounds late.
     */
    private inline fun timedRender(render: () -> ShortArray): ShortArray {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return render()
        val startedAtMs = SystemClock.uptimeMillis()
        return render().also {
            Log.d(TAG, "rendered ${it.size} samples in ${SystemClock.uptimeMillis() - startedAtMs}ms")
        }
    }

    /**
     * Render [pcm], load it into the track, and start it [LEAD_IN_MS] after [baseMs].
     *
     * Three posts, and the ordering is the whole point. The render and the buffer write go out
     * immediately so they run *during* the lead-in. [AudioTrack.play] is queued on the deadline as
     * its own message rather than nested inside the preparation, so that preparation running long
     * delays the cue by its overrun rather than by its whole duration. Re-arming the keep-alive goes
     * *after* the cue, where its cost cannot reach the cue it protects.
     *
     * A [Handler] runs its queue in time order, so the deadline message cannot overtake the
     * preparation message that is already due.
     */
    private fun submit(baseMs: Long, durationMs: Long, pcm: () -> ShortArray) {
        val handler = obtainHandler()
        // Drops the previous cue's pending play in one call, from whichever thread got here;
        // Handler's queue is synchronized where this class is not.
        //
        // Scoped to [cueToken] rather than clearing the queue outright, because [warmUp]'s rendering
        // sits on the same queue and a blanket clear would cancel it — leaving every cue of the race
        // to synthesise itself on its own deadline, which is the exact cost warmUp exists to avoid.
        handler.removeCallbacksAndMessages(cueToken)

        val startAtMs = baseMs + LEAD_IN_MS
        soundingUntilMs = startAtMs + durationMs

        handler.postAtTime({ loadTrack(pcm) }, cueToken, SystemClock.uptimeMillis())
        handler.postAtTime({ startTrack(startAtMs) }, cueToken, startAtMs)
        // Housekeeping for the *next* cue, deliberately done once this one has finished sounding.
        // The keep-alive is re-armed here so it cannot lapse mid-race between two widely spaced cues,
        // and the track is reset here because `pause` and `flush` are round trips to the audio server
        // — 50-130 ms of them, measured — which is more than the whole of [LEAD_IN_MS] and would
        // otherwise land between a cue firing and its sound starting.
        //
        // [CUE_TAIL_MARGIN_MS] past the cue's nominal end, not on it: the hardware is still draining
        // buffered audio at that point, and flushing into the tail would clip the end off a cue —
        // which for the gun would be the worst possible thing to shorten.
        handler.postAtTime(
            {
                logDelivered()
                armKeepAlive()
                resetTrack()
            },
            cueToken,
            startAtMs + durationMs + CUE_TAIL_MARGIN_MS,
        )
    }

    /**
     * Render the cue and hand it to the track, ready for [AudioTrack.play] on the deadline.
     *
     * `pause` then `flush` rather than `stop`: it discards whatever the previous cue left without
     * tearing the output port down, which is the expensive part and the reason this class keeps one
     * track for the whole race.
     */
    private fun loadTrack(pcm: () -> ShortArray): Unit = synchronized(audioLock) {
        val startedAtMs = SystemClock.uptimeMillis()
        val audioTrack = obtainTrackLocked() ?: return
        val samples = pcm()
        if (samples.isEmpty()) return

        pendingSamples = null
        pendingFrom = 0
        try {
            // Skipped whenever [resetTrack] already emptied the track when the previous cue ended,
            // which is the normal case and the whole reason it is done there. Still needed when a cue
            // is cancelled mid-flight by the next one, because that skips the housekeeping — and
            // writing onto an unflushed track would append this cue to the tail of the last one.
            if (!trackIsEmpty) {
                if (audioTrack.playState != AudioTrack.PLAYSTATE_STOPPED) audioTrack.pause()
                audioTrack.flush()
            }
            trackIsEmpty = false

            // A cue longer than the buffer is written in two halves, the second once playback has
            // made room. No built-in cue reaches this - the three-second gun is the longest and the
            // buffer holds MAX_PREFILL_MS - but a sustained cue's length is data, and a silently
            // truncated gun would be the worst possible way to find that out.
            val prefill = minOf(samples.size, trackCapacitySamples)
            val written = audioTrack.write(samples, 0, prefill)
            if (written <= 0) {
                Log.w(TAG, "AudioTrack write returned $written; cue dropped")
                return
            }
            if (written < samples.size) {
                pendingSamples = samples
                pendingFrom = written
            }
            armStartThreshold(audioTrack, written)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioTrack load failed, discarding track", e)
            releaseTrackLocked()
            return
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "loaded ${samples.size} samples in ${SystemClock.uptimeMillis() - startedAtMs}ms")
        }
    }

    /**
     * Make the track start on the cue we just wrote, rather than waiting for a full buffer.
     *
     * This is the subtlest thing in the class and it fails **silently**, so it is worth stating
     * plainly. A `MODE_STREAM` track does not begin mixing the moment [AudioTrack.play] is called: it
     * waits until the buffer holds a *start threshold* of frames, which defaults to the whole buffer.
     * The buffer here is [MAX_PREFILL_MS] so that any cue fits in one write, so a 400 ms cue filled
     * a tenth of it and the mixer waited for the rest forever.
     *
     * What that looks like from outside is the worst kind of bug: `play()` returns cleanly, the track
     * reports `PLAYSTATE_PLAYING`, nothing throws, nothing logs — and **no sound comes out**. It was
     * caught by reading [AudioTrack.getPlaybackHeadPosition], which sat at 0 half a second after
     * playback was supposed to have started. Before that it was caught by a person listening, which
     * is the only reason it was caught at all.
     *
     * On API 31+ the threshold is simply set to what was written. Below that the setter does not
     * exist, so the buffer is topped up with silence instead until it reaches the default threshold —
     * the same effect, at the cost of a larger write. `minSdk` is 30, so that path is real and not
     * theoretical, even though the watch this was developed on is API 36.
     */
    private fun armStartThreshold(audioTrack: AudioTrack, writtenSamples: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioTrack.startThresholdInFrames = writtenSamples
                return
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Start threshold rejected; padding with silence instead", e)
            }
        }
        var remaining = trackCapacitySamples - writtenSamples
        if (remaining <= 0) return
        val silence = ShortArray(minOf(remaining, SILENCE_CHUNK_SAMPLES))
        while (remaining > 0) {
            val chunk = minOf(remaining, silence.size)
            val written = audioTrack.write(silence, 0, chunk)
            if (written <= 0) return
            remaining -= written
        }
    }

    /**
     * Stop the track and empty it, once a cue has finished sounding.
     *
     * Purely so the next cue's load does not have to. See the posting in [submit] for why that
     * matters — this is IPC, and it costs more than the lead-in it would otherwise eat.
     */
    private fun resetTrack(): Unit = synchronized(audioLock) {
        val audioTrack = track ?: return
        try {
            if (audioTrack.playState != AudioTrack.PLAYSTATE_STOPPED) audioTrack.pause()
            audioTrack.flush()
            trackIsEmpty = true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack reset failed", e)
        }
        return
    }

    /** Start the loaded cue, and log how far its start missed [startAtMs]. */
    private fun startTrack(startAtMs: Long): Unit = synchronized(audioLock) {
        val audioTrack = track ?: return
        try {
            audioTrack.play()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioTrack play failed, discarding track", e)
            releaseTrackLocked()
            return
        }
        logDispatch(startAtMs, audioTrack)

        val remainder = pendingSamples ?: return
        pendingSamples = null
        // Blocking, on the tone thread, and only for a cue longer than the buffer. Playback is
        // already under way by here, so the write drains as it goes.
        try {
            audioTrack.write(remainder, pendingFrom, remainder.size - pendingFrom)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Tail write failed; cue truncated", e)
        }
        return
    }

    /**
     * Report how far the cue's start missed its deadline.
     *
     * Off unless someone asks for it: `adb shell setprop log.tag.ToneManager DEBUG`. Worth less than
     * it was before #61 — a late start shifts the whole cue, where the old per-blast version of this
     * was measuring the thing that could distort one. Kept because it is still the only way to tell a
     * late cue from a late *race*, and because it is what caught the per-cue track build.
     */
    private fun logDispatch(startAtMs: Long, audioTrack: AudioTrack) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        Log.d(TAG, "cue lateMs=${SystemClock.uptimeMillis() - startAtMs} rateHz=$sampleRateHz")
        headAtCueStart = audioTrack.playbackHeadPosition
    }

    /**
     * Report the frames the hardware actually consumed for the cue just finished.
     *
     * This is the measurement #61 asks for — delivered frames against the output rate — and the only
     * one here that can tell a cue that *sounded* from a cue that was merely submitted. Everything
     * else in this class reports on calls that returned cleanly, which a stalled track also does.
     *
     * Must be read before [resetTrack], because `flush` returns the playback head to zero.
     */
    private fun logDelivered() {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val head = synchronized(audioLock) { track?.playbackHeadPosition } ?: return
        val delivered = head - headAtCueStart
        Log.d(TAG, "delivered $delivered frames = ${delivered * 1000L / sampleRateHz}ms")
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

    /** The render thread's handler, starting the thread if it is not running. See [renderThread]. */
    @Synchronized
    private fun obtainRenderHandler(): Handler {
        renderHandler?.let { return it }
        val thread = HandlerThread(RENDER_THREAD_NAME, Process.THREAD_PRIORITY_DEFAULT)
        thread.start()
        renderThread = thread
        return Handler(thread.looper).also { renderHandler = it }
    }

    /** Call with [audioLock] held. */
    private fun obtainTrackLocked(): AudioTrack? {
        track?.let { return it }
        if (initFailed) return null

        if (!hasAudioOutput) {
            Log.w(TAG, "No audio output on this device; running vibration-only")
            initFailed = true
            return null
        }

        val minBytes = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val prefillBytes = (MAX_PREFILL_MS * sampleRateHz / 1000L).toInt() * BYTES_PER_SAMPLE
        val bufferBytes = maxOf(prefillBytes, if (minBytes > 0) minBytes else 0)

        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_ALARM so the cue carries outdoors: on Wear it is the loudest stream
                        // and the one users leave up for alerts, unlike media or notification.
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also {
                    it.setVolume(AudioTrack.getMaxVolume())
                    trackCapacitySamples = bufferBytes / BYTES_PER_SAMPLE
                    track = it
                }
        } catch (e: RuntimeException) {
            // UnsupportedOperationException from the builder, IllegalStateException from a stack
            // that is mid-restart. Vibration still carries the cue.
            Log.e(TAG, "AudioTrack unavailable; running vibration-only", e)
            initFailed = true
            null
        }
    }

    /**
     * (Re)start the silent tone that holds the audio output open. See [keepAliveGenerator].
     *
     * Best-effort like everything else here: a device with no usable audio stack simply runs
     * vibration-only, and a failure to hold the output open costs a cue's start latency, not the cue.
     */
    private fun armKeepAlive() {
        synchronized(audioLock) {
            if (!hasAudioOutput) return
            val generator = keepAliveGenerator ?: try {
                ToneGenerator(AudioManager.STREAM_ALARM, 0).also { keepAliveGenerator = it }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Keep-alive generator unavailable; cues may start late", e)
                return
            }
            try {
                generator.startTone(KEEP_ALIVE_TONE, KEEP_ALIVE_MS)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Keep-alive startTone failed", e)
            }
        }
    }

    /** Call with [audioLock] held. */
    private fun releaseTrackLocked() {
        val audioTrack = track ?: return
        try {
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) audioTrack.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack stop failed", e)
        }
        try {
            audioTrack.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "AudioTrack release failed", e)
        }
        track = null
        trackCapacitySamples = 0
        pendingSamples = null
        pendingFrom = 0
        trackIsEmpty = false
    }

    /** Call with [audioLock] held. */
    private fun releaseAudioLocked() {
        releaseTrackLocked()
        try {
            // Goes with the teardown, not with the cue: leaving it running would hold the audio
            // output open past the race that needed it.
            keepAliveGenerator?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Keep-alive release failed", e)
        }
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
    private fun releaseAudioAndQuit() {
        synchronized(audioLock) { releaseAudioLocked() }
        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null
        toneThread?.quitSafely()
        toneThread = null
        toneHandler = null
    }

    private companion object {
        const val TAG = "ToneManager"

        const val THREAD_NAME = "RaceTimerTone"

        const val RENDER_THREAD_NAME = "RaceTimerRender"

        const val BYTES_PER_SAMPLE = 2

        /** Chunk used to pad the buffer on API 30. See `armStartThreshold`. */
        const val SILENCE_CHUNK_SAMPLES = 4_096

        /** Grace after a cue's nominal end before the track is reset, so its tail is not clipped. */
        const val CUE_TAIL_MARGIN_MS = 300L

        /** Only reached if the platform will not say what its output rate is. */
        const val FALLBACK_SAMPLE_RATE_HZ = 48_000

        /**
         * Track buffer, in milliseconds of audio.
         *
         * Comfortably over the longest cue in any sequence — the three-second sustained gun — so a
         * whole cue is in the buffer before playback starts and nothing has to be fed in as it
         * drains. That is what makes an underrun impossible mid-cue, which is the one way a
         * streaming track could reintroduce the gap this change removes.
         */
        const val MAX_PREFILL_MS = 4_000L

        /**
         * How far ahead of the cue the audio is scheduled, so it does not race a thread wake-up.
         *
         * Sized off the measured worst case for work that was due the moment it was posted — 30 ms
         * on an SM-R925U under recomposition — with a little room over, and now also covering the
         * render and the buffer write.
         *
         * The cost is that a cue's audio trails its vibration by this much, since [HapticManager]
         * submits at the unshifted moment. That is a deliberate trade: a constant offset between the
         * channels is far less noticeable than an uneven beat within one of them, and at this size it
         * stays well inside the window where the two still read as one event. Do not grow it to fix
         * something else — past roughly 50 ms the tone starts to sound like it is answering the buzz
         * rather than arriving with it.
         */
        const val LEAD_IN_MS = 40L

        /** Long enough to register outdoors, short enough to stay clear of the next cue. */
        const val BEEP_MS = 400L

        /**
         * Tone used for the silent keep-alive. DTMF because it must hold for an arbitrary requested
         * length — a `TONE_CDMA_*` pattern would stop at its own segment and let the output close.
         *
         * The last [ToneGenerator] in the class, and it is here for what it does to the *output*
         * rather than for the sound it makes, which is why the duration cap that ruled it out for
         * cues does not matter: nothing is listening to it.
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
