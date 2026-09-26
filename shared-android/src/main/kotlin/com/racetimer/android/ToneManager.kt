package com.racetimer.android

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import com.racetimer.shared.CueLoss
import com.racetimer.shared.CueStream
import com.racetimer.shared.CueTiming
import com.racetimer.shared.CueTrackPacing
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
 * So the expensive parts happen off the deadline. The track is created before the race, the cue is
 * rendered during [LEAD_IN_MS], and the only thing waiting on a clock is the buffer write.
 *
 * ### One `play()` for the race, not one per cue (#114)
 *
 * That last sentence read "the only thing waiting on a clock is [AudioTrack.play]" until #114, and the
 * measurement that changed it is worth keeping. Pausing and flushing the track after each cue — which
 * this class did, deliberately, to keep that IPC off the next cue's load — meant every cue's `play()`
 * re-paid `startOutput`. On an SM-R925U that is **3-45 ms mid-race and 138-297 ms on the first cue of
 * every race**, because the first cue is the one that lands while the service is calling
 * `startForeground` and the audio server is contended by its binder traffic.
 *
 * There is nowhere on the cue's own path to put that. [LEAD_IN_MS] is 40 ms and cannot grow — past
 * roughly 50 ms the tone stops arriving with the buzz and starts answering it. So the cost has to be
 * paid *before the race*, which means the track has to still be playing when the cue arrives:
 *
 * - [startIdling] runs from [prepare], well ahead of Start, and leaves the track in
 *   `PLAYSTATE_PLAYING` with nothing queued.
 * - A cue is then only [writeCue] — a memcpy into a track that is already running. Nothing on the
 *   deadline talks to the audio server.
 * - [keepMixedRunnable] writes a little silence between cues, because a track that is playing with
 *   nothing to play is dropped from AudioFlinger's active list after about a second, which calls
 *   `stopOutput` and hands the whole cost back. See there for the sizing.
 *
 * **The failure mode moved, and it moved the right way.** The old path's way of going wrong was
 * [armStartThreshold] — a track that never reaches its start threshold plays *nothing* while every
 * call reports success, which is the #61 class and the worst thing this file can do. The new path's
 * way of going wrong is queued silence a cue has to drain before it sounds, which is a few
 * milliseconds late and audible as nothing at all. Where the fast path cannot be used the code falls
 * back to the old one — see [writeCue].
 *
 * **API 31+ only.** [AudioTrack.setStartThresholdInFrames] arrived in S, and without it a track's
 * start threshold is its whole buffer — so idling one means queueing [MAX_PREFILL_MS] of silence for
 * a cue to drain through. `minSdk` is 30, so below S this class keeps exactly the behaviour it had
 * before #114: [startIdling] returns without doing anything and every cue takes the flush-and-play
 * path.
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
 * ### Which output the cue goes to (#95)
 *
 * Not a constant. `USAGE_ALARM` is the right answer on a healthy device and the *silent* one on a
 * watch whose alarm stream is aliased into the ringer-affected set — which is the case on the
 * SM-R925U this app is developed against, where vibrate mode silences every cue while the countdown
 * and the haptics carry on as normal. The decision is a pure rule in `shared/CueAudioRoute.kt`; this
 * class is told the answer through [prepare] and rebuilds its track when it changes, because the
 * stream is fixed at track-build time and cannot be moved on a live one.
 *
 * What a route *means* to the platform — which `AudioAttributes` usage, which legacy stream — is a
 * second question, and its answer was measured on one watch rather than derived. Since #200 it
 * arrives as [audioProfile] rather than being written here, so a second form factor is made to
 * measure its own instead of inheriting this one's. See [CueAudioProfile].
 *
 * Audio is best-effort by design. A watch may have no speaker at all, and the audio stack can refuse
 * to hand out a track. Every failure path here logs and returns, so the caller's vibration is never
 * blocked by a broken tone.
 */
class ToneManager(
    context: Context,
    private val audioProfile: CueAudioProfile,
) {

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

    /**
     * Set once the audio stack has refused, so a dead one isn't retried on every cue.
     *
     * `@Volatile` since #13, which is the first thing to read it from another thread. Every write is
     * still made under [audioLock] on the tone or render thread; the activity reads it through
     * [audioUnavailable] on the main thread to decide whether to warn the sailor, and a stale read
     * there would show a "the gun will be silent" panel one refresh late — or, worse, not at all.
     */
    @Volatile private var initFailed = false

    /**
     * Whether this watch's audio stack has refused to give the app a track (#13).
     *
     * An **observation**, in the sense #96 established: it is only ever true because an
     * `AudioTrack` build already failed or because the device reported no audio output at all. This
     * deliberately does not attempt to predict audibility — that is a separate question, it belongs
     * to [ToneManager.route] and #95, and the two must not grow a second copy of one rule.
     *
     * False before anything has tried, which is the right way to be wrong: the app module's service
     * warms a cue up on creation — `TimerService.onCreate` on the watch — so by the time a sailor can
     * reach the Start button the audio path has been exercised and this answers for real. A watch that somehow reaches Start first is warned at the
     * first cue instead of ahead of it, which is the pre-#13 behaviour rather than a regression.
     */
    val audioUnavailable: Boolean get() = initFailed

    /**
     * A cue this race has lost, waiting to be told to the sailor, or null when there is nothing owed
     * (#161).
     *
     * ### Why an atomic and not a field under [audioLock]
     *
     * The three writes all happen inside [writeCue], which already holds [audioLock] on the tone
     * thread. The **read** is the problem: it comes from `MainActivity`'s 100 ms refresh on the main
     * thread, and a main thread blocking on the lock that the audio path holds while it talks to the
     * audio server is exactly the stall this class is built to avoid. An [AtomicReference] is
     * lock-free on both sides, so the tone thread is never made to wait for the UI and the UI is
     * never made to wait for the tone thread.
     *
     * ### Why read-and-clear rather than a flag the UI samples
     *
     * The same reason `TimerService.consumeRestoreNotice` is shaped that way, and the issue named it
     * as the precedent: a lost cue is *news*, and it has to be announced exactly once. A `@Volatile
     * Boolean` sampled at 100 ms — the shape [initFailed] and `cueVolumeRefused` correctly use for
     * *standing conditions* — cannot express that. It would either re-announce on every refresh for
     * as long as it stayed true, or need a second field remembering whether it had been said.
     *
     * [getAndSet] is that read-and-clear, and it is atomic, so two refreshes racing each other can
     * never both collect the same loss.
     *
     * A second loss arriving before the first has been collected overwrites it rather than queueing.
     * That is rule 6 of `docs/message-surface.md` — one message at a time — and it is unreachable in
     * practice besides: [writeCue] can lose at most one cue per call, and no sequence this app ships
     * puts two cues within the 100 ms the activity takes to come back.
     */
    private val pendingCueLoss = AtomicReference<CueLoss?>(null)

    /**
     * Take the cue loss owed a message, clearing it — null when nothing is owed.
     *
     * Called from the main thread every refresh while a race is under way, and from the arm path to
     * discard a loss left over from a race that has already finished. Takes no lock; see
     * [pendingCueLoss].
     */
    fun consumeCueLoss(): CueLoss? = pendingCueLoss.getAndSet(null)

    /**
     * Whether to fail the next cue write on purpose, read once at construction (#161).
     *
     * ### This exists because the alternative was shipping a banner nobody had seen
     *
     * None of the three loss paths has ever been observed on this hardware, so without a way to
     * force one, #161's banner could only be *reviewed*. #102 is what that costs: a Tier 1 banner
     * whose code was correct, that no sailor ever saw, for two reasons neither review had caught.
     * Armed over adb with no root and no debug build:
     *
     * ```
     * adb shell setprop log.tag.RaceTimerCueFault VERBOSE
     * ```
     *
     * [Log.isLoggable] against a `log.tag.*` property is this class's own idiom for a debug switch —
     * [timedRender], [logDispatch] and [logDelivered] are all gated the same way — so this adds a
     * mechanism the file already depends on rather than a new one.
     *
     * ### Read once, deliberately
     *
     * [writeCue] is the most timing-sensitive method in the app and it runs under [audioLock] on the
     * deadline. Reading the property per write would put a property lookup on that path to buy a
     * convenience nobody needs; a switch you arm before launching the app is no harder to use. What
     * the hot path pays is one boolean test.
     *
     * ### It ships, and that is the point
     *
     * Not gated on a debug build, so the artifact a sailor installs is the artifact the banner was
     * proven on. Arming it needs adb access and a deliberate `setprop`, and the worst it can do to
     * somebody who does both is silence cues on a watch they are holding a debugger against.
     */
    private val cueFaultArmed: Boolean = Log.isLoggable(FAULT_TAG, Log.VERBOSE)

    /** The cue-audio thread and its handler, or null before first use and after [release] runs. */
    @Volatile private var toneThread: HandlerThread? = null

    @Volatile private var toneHandler: Handler? = null

    /** Uptime the current cue's audio finishes, so [release] can wait out its real tail. */
    @Volatile private var soundingUntilMs = 0L

    /**
     * True once [AudioTrack.play] has been called on [track] and nothing has paused it since.
     *
     * The whole of #114 in one boolean: while this holds, a cue costs a buffer write and no round trip
     * to the audio server. False means the next cue pays `startOutput` itself, which is correct and
     * merely slow — it is the state below API 31, after a flush, and before [startIdling] has run.
     */
    private var trackStarted = false

    /**
     * Frames handed to [track] since it was built or last flushed, cue audio and keep-alive silence
     * alike.
     *
     * Counted here because [AudioTrack] will not say: `getPlaybackHeadPosition` reports what the
     * hardware has *consumed*, and the gap between the two is exactly what a cue written now has to
     * wait through. An [Int] rather than a [Long] on purpose, so it wraps in step with the playback
     * head — see [CueTrackPacing.framesBetween].
     */
    private var framesWritten = 0

    /**
     * Value of [framesWritten] immediately before the current cue was written — which is the playback
     * head position at which that cue's first frame sounds.
     *
     * Not a reading of the playback head, which is where the audio is *now* and would count the
     * keep-alive silence still ahead of the cue as part of it.
     */
    private var cueStartFrame = 0

    /**
     * The cue about to be written, resolved during the lead-in so the deadline carries no rendering.
     *
     * [submit] posts the resolve and the write as separate messages precisely so a cache miss — which
     * renders, and took 477-2237 ms when the thread was contended (#98) — cannot land on the deadline.
     */
    private var stagedCue: ShortArray? = null

    /** Reused silence for the heartbeat, so it allocates once rather than every 300 ms. */
    private var keepMixedSilence: ShortArray? = null

    private val releaseRunnable = Runnable { releaseAudioAndQuit() }

    /**
     * What runs once a cue has finished sounding. See the posting in [submit] for why each part is
     * here rather than on the cue's own path.
     *
     * A field rather than a lambda at the post site so [startTrack] can move it: it is posted against
     * the cue's *nominal* start, and a cue that starts late has to take its housekeeping with it or
     * the reset lands inside the sound (#98).
     */
    private val housekeepingRunnable = Runnable {
        logDelivered()
        armKeepAlive()
        scheduleKeepMixed()
    }

    /**
     * Writes a little silence so AudioFlinger keeps the track in its active list, then re-posts itself.
     *
     * The tax on never pausing (#114). A track that is playing with nothing to play is underrunning,
     * and AudioFlinger gives an underrunning track a bounded number of mixer periods before it drops it
     * out of the active list and calls `stopOutput` — at which point the next write has to restart it
     * and the cue pays exactly the cost this change removed, only now on the deadline instead of a few
     * lines earlier. Mixing anything at all resets that count, so this is a heartbeat and not a stream:
     * see [KEEP_MIXED_CHUNK_MS] for why it is as small as it is.
     *
     * Deliberately **not** running while a cue is in flight. [submit] cancels it and the cue's
     * housekeeping starts it again, so silence can never be appended to a cue that is still sounding —
     * which would leave [logDelivered] counting that silence as part of the cue and reporting a cue
     * longer than the one [CueTiming] describes.
     */
    private val keepMixedRunnable = Runnable {
        writeKeepMixedSilence()
        scheduleKeepMixed()
    }

    /** Marks the messages belonging to a cue, so cancelling one cannot cancel [warmUp]. */
    private val cueToken = Any()

    private val hasAudioOutput: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)

    /**
     * Which output the cues are currently built for (#95). Decided by [com.racetimer.shared.cueStream]
     * and handed in by [prepare]; see there for why it can change and what changing it costs.
     *
     * [CueStream.ALARM] until told otherwise, so a caller that never routes gets exactly the
     * behaviour this class had before #95.
     */
    @Volatile private var route: CueStream = CueStream.ALARM

    /**
     * The output's own sample rate.
     *
     * Read from the device rather than assumed, and used unchanged, because anything else would be
     * resampled on the way out — and a resampler is exactly the kind of thing that would quietly put
     * back the rounding this fix removed. 48 kHz on the SM-R925U.
     *
     * A `var` since #95, because it is a property of the stream and the stream can change. In practice
     * both report the same rate on this hardware, but "in practice" is not a thing to render a buffer
     * against: [CueWaveform] renders at whatever this says, so a rate that moved under a cached buffer
     * would play it at the wrong pitch *and* the wrong length, with nothing throwing. [prepare] drops
     * the cache when it changes rather than assuming it cannot.
     */
    @Volatile private var sampleRateHz: Int = nativeRateFor(CueStream.ALARM)

    /**
     * The device's native output rate for a route's stream, or [FALLBACK_SAMPLE_RATE_HZ].
     *
     * An instance member rather than a companion function since #200, because the stream a route
     * means is [audioProfile]'s answer and not this class's. Nothing else moved: it is still read
     * once at construction and again whenever [prepare] changes the route.
     */
    private fun nativeRateFor(route: CueStream): Int =
        try {
            // A platform can answer 0 rather than throw — measured under the phone module's
            // Robolectric harness, and nothing rules a broken real device out. A zero rate is not
            // usable and must not be rendered against: it reached CueWaveform's own guard and
            // killed the tone thread, after which every later post lands on a dead looper (#202).
            AudioTrack.getNativeOutputSampleRate(audioProfile.legacyStreamFor(route))
                .takeIf { it > 0 }
                ?: FALLBACK_SAMPLE_RATE_HZ.also {
                    Log.w(TAG, "Native output rate not positive; falling back to $it")
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Native output rate unavailable; falling back to $FALLBACK_SAMPLE_RATE_HZ", e)
            FALLBACK_SAMPLE_RATE_HZ
        }

    /**
     * Build the track and open the audio output ahead of the first cue, for [newRoute].
     *
     * Without this, both costs land on whichever cue sounds first — and the first cue of a race
     * fires within a couple of hundred milliseconds of the service starting, so it is the least
     * affordable place to put them.
     *
     * ### Why this takes a route rather than reading one
     *
     * The stream is baked into the [AudioTrack] at build time — it is an [AudioAttributes] on the
     * builder, not a property that can be set on a live track — so "which stream" has to be known
     * before there is a track, and a change means building a new one. That is why the decision is
     * made by the caller (which has the platform's `AudioManager` and the sailor's setting) and
     * arrives here rather than being read from a preference by this class.
     *
     * ### Safe to call repeatedly, and worth calling twice
     *
     * A call naming the route already in force does nothing but the ordinary prepare. That matters
     * because the service calls this **twice**: once in `onCreate`, which is early enough for the
     * build to be free, and again when a race is armed, because the sailor can flip the watch to
     * vibrate mode in between and the track built in `onCreate` would then be the silent one.
     *
     * The second call costs nothing in the normal case and costs a track rebuild — measured at
     * 89-182 ms of `getOutputForAttr` / `releaseOutput` round trips — when the route actually moved.
     * That lands ahead of the first cue and will delay it, which is a deliberate trade: the
     * alternative is not a punctual cue, it is no cue at all. It cannot be deferred to *after* the
     * first cue either, since that is the cue this exists to make audible.
     */
    fun prepare(newRoute: CueStream) {
        obtainHandler().post {
            if (newRoute != route) {
                route = newRoute
                val newRate = nativeRateFor(newRoute)
                if (newRate != sampleRateHz) {
                    // Every cached buffer was rendered against the old rate. Dropping them costs a
                    // re-render; keeping them would play the gun at the wrong speed and report success.
                    Log.w(TAG, "Output rate changed $sampleRateHz -> $newRate with the stream; re-rendering")
                    sampleRateHz = newRate
                    cueBuffers.clear()
                    beepBuffer = null
                }
                // The track carries the old stream in its attributes and the keep-alive holds the old
                // output open, so both have to go. They are rebuilt immediately below rather than
                // lazily on the first cue, which is the whole point of preparing.
                synchronized(audioLock) {
                    releaseAudioLocked()
                    initFailed = false
                }
            }
            synchronized(audioLock) {
                obtainTrackLocked()
                startIdling()
            }
            armKeepAlive()
            beepBuffer()
            scheduleKeepMixed()
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
        handler.removeCallbacks(keepMixedRunnable)
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
     * Render [pcm], then write it to the track [LEAD_IN_MS] after [baseMs].
     *
     * Three posts, and the ordering is the whole point. The render goes out immediately so it runs
     * *during* the lead-in — a cache miss costs hundreds of milliseconds on a contended thread (#98)
     * and must never land on a deadline. The buffer write is queued on the deadline as its own message
     * rather than nested inside the render, so that a render running long delays the cue by its
     * overrun rather than by its whole duration. Re-arming the keep-alive goes *after* the cue, where
     * its cost cannot reach the cue it protects.
     *
     * The write is what waits on the clock, rather than [AudioTrack.play], because the track is already
     * playing by the time a race starts — that is #114, and the reasoning is on the class.
     *
     * A [Handler] runs its queue in time order, so the deadline message cannot overtake the render
     * message that is already due.
     */
    private fun submit(baseMs: Long, durationMs: Long, pcm: () -> ShortArray) {
        val handler = obtainHandler()
        // Drops the previous cue's pending write in one call, from whichever thread got here;
        // Handler's queue is synchronized where this class is not.
        //
        // Scoped to [cueToken] rather than clearing the queue outright, because [warmUp]'s rendering
        // sits on the same queue and a blanket clear would cancel it — leaving every cue of the race
        // to synthesise itself on its own deadline, which is the exact cost warmUp exists to avoid.
        handler.removeCallbacksAndMessages(cueToken)
        // The keep-alive heartbeat stops for the duration of the cue and is started again by the cue's
        // own housekeeping. See [keepMixedRunnable] for why silence must not land inside a cue.
        handler.removeCallbacks(keepMixedRunnable)

        val startAtMs = baseMs + LEAD_IN_MS
        soundingUntilMs = startAtMs + durationMs

        handler.postAtTime({ stageCue(pcm) }, cueToken, SystemClock.uptimeMillis())
        handler.postAtTime({ writeCue(startAtMs, durationMs) }, cueToken, startAtMs)
        // Housekeeping for the *next* cue, deliberately done once this one has finished sounding.
        // The keep-alive is re-armed here so it cannot lapse mid-race between two widely spaced cues,
        // the cue's delivered frames are read here because the reading has to happen after the sound,
        // and the silence heartbeat is started again here for the same reason.
        //
        // [CUE_TAIL_MARGIN_MS] past the cue's nominal end, not on it: the hardware is still draining
        // buffered audio at that point, so a delivered-frame count taken on the nominal end would
        // under-report the cue — and before #114 this was also where the track was flushed, which
        // would have clipped the tail outright.
        //
        // Posted here against the *nominal* end so it is scheduled unconditionally — including on the
        // paths where the cue never starts at all, which still need the keep-alive re-armed and the
        // heartbeat restarted. [writeCue] moves it when the start actually slipped; see there for why
        // a margin measured from a time the cue did not start at is not a margin.
        handler.postAtTime(
            housekeepingRunnable,
            cueToken,
            CueTiming.resetAtMs(startAtMs, startAtMs, durationMs, CUE_TAIL_MARGIN_MS),
        )
    }

    /**
     * Render the cue during the lead-in and hold it, so the deadline carries only the buffer write.
     *
     * The one thing on the cue path that can take hundreds of milliseconds is a render — 477-2237 ms
     * on a contended thread, measured under #98 — and [cueFor] still renders inline when [warmUp] has
     * not reached that shape yet. Resolving it here means the miss costs the *lead-in*, which it may
     * well overrun, rather than the cue.
     */
    private fun stageCue(pcm: () -> ShortArray) {
        val samples = pcm()
        stagedCue = if (samples.isEmpty()) null else samples
    }

    /**
     * Write the staged cue to the track on its deadline, starting playback if the track is not already
     * running.
     *
     * ### The fast path, which is the point of #114
     *
     * [startIdling] left the track playing before the race, so in the ordinary case this is a memcpy
     * into a running track and nothing here talks to the audio server. The cue sounds once whatever is
     * already queued ahead of it drains — at most one [KEEP_MIXED_CHUNK_MS] of keep-alive silence, and
     * usually none, since the heartbeat is stopped the moment a cue is submitted.
     *
     * ### The slow path, which is the old behaviour applied where it is needed
     *
     * Two states cannot append: a track that is not playing (below API 31, or after a route change),
     * and a track still holding a previous cue that the next one cancelled mid-flight. Appending in the
     * second case would play the two cues back to back — the sailor would hear a signal nobody gave.
     * Both fall back to flush, write, [armStartThreshold], play: the pre-#114 path, paying
     * `startOutput` on the deadline. That is a late cue rather than a wrong one, and
     * [CueTrackPacing.needsFlushBeforeCue] is what keeps the keep-alive silence from tripping it.
     *
     * Also carries the cue's housekeeping forward when the start slipped. [submit] posts that against
     * the cue's *nominal* end, which is the only time it knows — but a cue that starts late still
     * sounds for its full [durationMs], so the delivered-frame reading would be taken while the cue was
     * still playing and would under-report it. Before #114 the same message flushed the track, and the
     * consequence was worse than a bad reading: measured on an SM-R925U, a first cue starting 645 ms
     * late delivered **92160 of 108000 frames — 1920 ms of a 2250 ms cue** — cut off silently, with
     * every call reporting success (#98).
     */
    private fun writeCue(startAtMs: Long, durationMs: Long): Unit = synchronized(audioLock) {
        val enteredMs = SystemClock.uptimeMillis()
        val samples = stagedCue ?: return
        stagedCue = null
        val audioTrack = obtainTrackLocked() ?: return

        pendingSamples = null
        pendingFrom = 0
        val queuedMs: Long
        val foundMs: Long
        val flushed: Boolean
        val writtenAtMs: Long
        try {
            var queuedFrames =
                CueTrackPacing.framesBetween(audioTrack.playbackHeadPosition, framesWritten)
            // Kept separately from [queuedMs] so the log can tell the two states apart. Reporting
            // only the post-flush value makes "nothing was queued" and "a whole cue was queued and
            // this took the slow path" print identically — and they are the two explanations for a
            // slow write, wanting opposite responses. Measured 2026-08-11: one cue at writeMs=43
            // that this logging could not attribute, which is the defect the split fixes.
            foundMs = CueTrackPacing.framesToMs(queuedFrames, sampleRateHz)
            flushed = CueTrackPacing.needsFlushBeforeCue(foundMs, MAX_QUEUED_MS)
            if (flushed) {
                flushTrackLocked(audioTrack)
                queuedFrames = 0
            }
            queuedMs = CueTrackPacing.framesToMs(queuedFrames, sampleRateHz)

            // Where the cue's first frame lands on the playback head. Taken from what has been
            // *written* rather than from the head itself, so keep-alive silence still ahead of the cue
            // is not counted as part of it by [logDelivered].
            cueStartFrame = framesWritten

            // A cue longer than the buffer is written in two halves, the second once playback has
            // made room. No built-in cue reaches this - the three-second gun is the longest and the
            // buffer holds MAX_PREFILL_MS - but a sustained cue's length is data, and a silently
            // truncated gun would be the worst possible way to find that out.
            val prefill = minOf(samples.size, trackCapacitySamples)
            // The fault switch stands in for the write rather than wrapping it, so an armed run
            // reaches the failure branch by the same route a real refusal would. See [cueFaultArmed].
            val written =
                if (cueFaultArmed) AudioTrack.ERROR_INVALID_OPERATION
                else audioTrack.write(samples, 0, prefill)
            if (written <= 0) {
                Log.w(TAG, "AudioTrack write returned $written; cue dropped")
                // The cue does not sound. Tell the sailor rather than only the log (#161).
                pendingCueLoss.set(CueLoss.DROPPED)
                return
            }
            framesWritten += written
            if (written < samples.size) {
                pendingSamples = samples
                pendingFrom = written
            }
            if (!trackStarted) {
                armStartThreshold(audioTrack, written)
                audioTrack.play()
                trackStarted = true
            }
            writtenAtMs = SystemClock.uptimeMillis()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioTrack write failed, discarding track", e)
            // Same consequence as the branch above from where the sailor is standing — the cue does
            // not sound — so the same notice, per [CueLoss] (#161).
            pendingCueLoss.set(CueLoss.DROPPED)
            releaseTrackLocked()
            return
        }

        val audibleAtMs = CueTrackPacing.audibleAtMs(writtenAtMs, queuedMs)
        logDispatch(startAtMs, enteredMs, writtenAtMs, queuedMs, foundMs, flushed, samples.size)
        rescheduleHousekeeping(startAtMs, audibleAtMs, durationMs)

        val remainder = pendingSamples ?: return
        pendingSamples = null
        // Blocking, on the tone thread, and only for a cue longer than the buffer. Playback is
        // already under way by here, so the write drains as it goes.
        try {
            framesWritten += audioTrack.write(remainder, pendingFrom, remainder.size - pendingFrom)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Tail write failed; cue truncated", e)
            // The cue is already sounding and will stop early. A different notice from the two
            // above, because a blast cut short is a blast the sailor can misread (#161).
            pendingCueLoss.set(CueLoss.TRUNCATED)
        }
        return
    }

    /**
     * Put the track back in a state where the next write starts a cue cleanly.
     *
     * `pause` then `flush` rather than `stop`: it discards what is queued without tearing the output
     * port down, which is the expensive part and the reason this class keeps one track for the whole
     * race. Both counters go with it — [AudioTrack.flush] returns the playback head to zero, so a
     * [framesWritten] that survived would make every subsequent queue-depth reading wrong by a cue.
     *
     * Leaves [trackStarted] false: a flushed track has to be played again, and on API 30 it has to
     * reach its start threshold first. Call with [audioLock] held.
     */
    private fun flushTrackLocked(audioTrack: AudioTrack) {
        if (audioTrack.playState != AudioTrack.PLAYSTATE_STOPPED) audioTrack.pause()
        audioTrack.flush()
        framesWritten = 0
        cueStartFrame = 0
        trackStarted = false
    }

    /**
     * Start the track playing with nothing queued, so a cue costs a buffer write and no more (#114).
     *
     * Called from [prepare], which runs at service creation and again when a race is armed — both of
     * them seconds or minutes ahead of the first cue, which is the whole point: `startOutput` is a
     * round trip into the audio server and it is not affordable anywhere near a deadline.
     *
     * **Returns without doing anything below API 31.** [AudioTrack.setStartThresholdInFrames] is the
     * only way to say "start on the first frame written"; without it a `MODE_STREAM` track waits for
     * its whole buffer, so idling one would mean queueing [MAX_PREFILL_MS] of silence and making every
     * cue drain through it. `minSdk` is 30, so this is a live path and not a formality — below S every
     * cue takes [writeCue]'s flush-and-play branch, which is exactly what this class did before #114.
     *
     * Call with [audioLock] held.
     */
    private fun startIdling() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val audioTrack = track ?: return
        if (trackStarted) return
        try {
            audioTrack.startThresholdInFrames = 1
            audioTrack.play()
            trackStarted = true
        } catch (e: RuntimeException) {
            // IllegalArgumentException from the threshold, IllegalStateException from play(). Neither
            // is fatal: the track simply stays unstarted and cues take the slow path.
            Log.w(TAG, "Could not idle the cue track; cues will pay startOutput each time", e)
            return
        }
        // Something has to be mixed for AudioFlinger to treat the track as active at all — a track that
        // is playing and has never had a frame is indistinguishable to it from one that has underrun.
        writeKeepMixedSilence()
    }

    /**
     * Post the next silence heartbeat, replacing any already queued.
     *
     * Idempotent on purpose: it is called from [prepare], from a cue's housekeeping, and from the
     * heartbeat itself, and two heartbeats running would double the silence a cue has to drain.
     */
    private fun scheduleKeepMixed() {
        val handler = toneHandler ?: return
        handler.removeCallbacks(keepMixedRunnable)
        handler.postDelayed(keepMixedRunnable, KEEP_MIXED_INTERVAL_MS)
    }

    /** One heartbeat's worth of silence. See [keepMixedRunnable]. */
    private fun writeKeepMixedSilence(): Unit = synchronized(audioLock) {
        val audioTrack = track ?: return
        if (!trackStarted) return
        val frames = CueTrackPacing.msToFrames(KEEP_MIXED_CHUNK_MS, sampleRateHz)
        val silence = keepMixedChunk(frames)
        try {
            // Non-blocking: this runs on the thread the cues are dispatched from, and a heartbeat is
            // never worth making a cue wait. A short write is fine — the track only has to be mixed,
            // not fed a particular amount.
            val written = audioTrack.write(silence, 0, frames, AudioTrack.WRITE_NON_BLOCKING)
            if (written > 0) framesWritten += written
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Keep-mixed write failed; cues may pay startOutput", e)
        }
        return
    }

    /** The reusable silence buffer, grown if the sample rate ever asks for more. */
    private fun keepMixedChunk(frames: Int): ShortArray {
        val existing = keepMixedSilence
        if (existing != null && existing.size >= frames) return existing
        return ShortArray(frames).also { keepMixedSilence = it }
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
     *
     * Only reached from [writeCue]'s slow path since #114; an idling track has already started and its
     * threshold no longer gates anything. The padding is counted into [framesWritten] because it is
     * queued audio like any other — a cue's own trailing silence, in effect — and a queue depth that
     * did not know about it would read low by most of a buffer.
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
            framesWritten += written
            remaining -= written
        }
    }

    /**
     * Move this cue's housekeeping to sit after the sound it is actually going to make.
     *
     * In practice this reposts on **every** cue that sounds, and the early return is close to
     * unreachable: [submit] posts [writeCue] with `postAtTime`, which cannot dispatch before
     * [startAtMs], so `startedMs >= startAtMs` always and the guard is exact equality rather than a
     * tolerance — it would need the thread wake *and* the buffer write to land inside one millisecond.
     * The smallest slip measured across #98's runs was 4 ms. The genuinely exceptional path is the
     * opposite one: this is skipped only when [writeCue] returns before reaching it, which is the
     * "cue never starts at all" case [submit]'s own posting covers.
     *
     * Since #114 [startedMs] is the moment the cue becomes *audible* rather than the moment
     * [AudioTrack.play] returned — the write plus whatever keep-alive silence was queued ahead of it.
     * That is the time the sound actually occupies, so it is the one the delivered-frame reading has to
     * be placed after.
     *
     * The `<=` and [CueTiming.resetAtMs]'s `maxOf` are still deliberate — they keep a cue that somehow
     * ran early from pulling its own reset forward into itself — but that is future-proofing, not the
     * common case.
     *
     * [toneHandler] is read directly rather than through [obtainHandler] on purpose: this runs with
     * [audioLock] held, `obtainHandler` is `@Synchronized` on the instance, and [releaseAudioAndQuit]
     * takes those two in the opposite order — so calling it here would be the one place in the class
     * that could deadlock. The field is `@Volatile` and this method only ever runs *on* the tone
     * thread, so it cannot be null by the time we are here.
     */
    private fun rescheduleHousekeeping(startAtMs: Long, startedMs: Long, durationMs: Long) {
        if (startedMs <= startAtMs) return
        val handler = toneHandler ?: return
        soundingUntilMs = startedMs + durationMs
        handler.removeCallbacks(housekeepingRunnable)
        handler.postAtTime(
            housekeepingRunnable,
            cueToken,
            CueTiming.resetAtMs(startAtMs, startedMs, durationMs, CUE_TAIL_MARGIN_MS),
        )
    }

    /**
     * Report how far the cue's start missed its deadline, and which half of the path it lost it in.
     *
     * Off unless someone asks for it: `adb shell setprop log.tag.ToneManager DEBUG`. Worth less than
     * it was before #61 — a late start shifts the whole cue, where the old per-blast version of this
     * was measuring the thing that could distort one. Kept because it is still the only way to tell a
     * late cue from a late *race*, and because it is what caught the per-cue track build.
     *
     * `lateMs` alone was not enough to close #98. It reports the miss and says nothing about where the
     * miss came from, and the candidates want different fixes. Three components since #114, and each
     * one names a different suspect:
     *
     * - `wakeMs` — the tone thread failed to run this message at [startAtMs]. Scheduling or
     *   contention; fixed by taking work off the queue ahead of it.
     * - `writeMs` — the buffer write itself blocked. On the fast path this is a memcpy and reads 0-1
     *   ms; anything larger means the track was not idling and the write paid `startOutput` to restart
     *   it, which is the regression #114 was about and the number to watch.
     * - `queuedMs` — keep-alive silence the cue has to drain before it sounds. Bounded by
     *   [KEEP_MIXED_CHUNK_MS].
     * - `foundMs` / `flushed` — what was queued *before* the flush valve was consulted, and whether
     *   it fired. These exist because `queuedMs` alone cannot explain a slow `writeMs`: a flush
     *   zeroes the queue, so a cue that took the slow path and a cue with nothing queued print the
     *   same. `flushed=1` attributes the cost to the valve; `flushed=0 writeMs` high is contention
     *   or a lapsed track, which want opposite fixes.
     *
     * `playMs` was the third of these before #114 and is gone with the per-cue `play()`. Do not compare
     * a `lateMs` from before that change against one from after it without reading both definitions:
     * the old one timed when `play()` returned, this one estimates when the sound *starts*. Neither
     * times when it emerged from the speaker, which only an ear settles.
     */
    private fun logDispatch(
        startAtMs: Long,
        enteredMs: Long,
        writtenAtMs: Long,
        queuedMs: Long,
        foundMs: Long,
        flushed: Boolean,
        cueSamples: Int,
    ) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        Log.d(
            TAG,
            "cue lateMs=${writtenAtMs + queuedMs - startAtMs} wakeMs=${enteredMs - startAtMs} " +
                "writeMs=${writtenAtMs - enteredMs} queuedMs=$queuedMs " +
                "foundMs=$foundMs flushed=${if (flushed) 1 else 0} " +
                "samples=$cueSamples rateHz=$sampleRateHz",
        )
    }

    /**
     * Report the frames the hardware actually consumed for the cue just finished.
     *
     * This is the measurement #61 asks for — delivered frames against the output rate — and the only
     * one here that can tell a cue that *sounded* from a cue that was merely submitted. Everything
     * else in this class reports on calls that returned cleanly, which a stalled track also does.
     *
     * Counted from [cueStartFrame], which is where the cue's first frame sits on the playback head,
     * rather than from a reading of the head taken when the cue was written — those differ by exactly
     * the keep-alive silence that was still queued, and using the reading would report a cue longer
     * than [CueTiming] describes and hide a real truncation behind it.
     *
     * Runs before the heartbeat is started again, deliberately: silence written after the cue would be
     * counted into it just the same.
     */
    private fun logDelivered() {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val head = synchronized(audioLock) { track?.playbackHeadPosition } ?: return
        val delivered = CueTrackPacing.framesBetween(cueStartFrame, head)
        Log.d(
            TAG,
            "delivered $delivered frames = ${CueTrackPacing.framesToMs(delivered, sampleRateHz)}ms",
        )
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
                        // Which usage a route means is [audioProfile]'s answer, not this class's:
                        // it was measured per device and does not transfer between form factors
                        // (#200). See [CueAudioProfile] for why, and [route] and #95 for the
                        // decision that picks the route in the first place.
                        .setUsage(audioProfile.audioUsageFor(route))
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
                // The same stream the cues are on, or it holds the wrong output open and the cue pays
                // the `startOutput` this exists to prevent — silently, since a keep-alive that works
                // and a keep-alive on the wrong stream look identical from here (#95).
                ToneGenerator(audioProfile.legacyStreamFor(route), 0).also { keepAliveGenerator = it }
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
        stagedCue = null
        // Both counters belong to the track that has just gone. Carrying them onto the next one would
        // make its first queue-depth reading wrong by however much the old track had played.
        trackStarted = false
        framesWritten = 0
        cueStartFrame = 0
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

        /**
         * Log tag that exists only to be a switch, never to be logged against (#161).
         *
         * Separate from [TAG] so that arming the fault does not also turn on this class's debug
         * logging, and — the direction that matters — so that turning debug logging on to watch cue
         * timing does not silently start dropping cues. See `cueFaultArmed`.
         */
        const val FAULT_TAG = "RaceTimerCueFault"

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
         * The three numbers that pace the shared track live in [CueTrackPacing], not here.
         *
         * They are only meaningful against each other and against [CueTiming] — the heartbeat must
         * stay under the flush budget and the budget must stay under the shortest cue — and nothing
         * on this side of the module boundary can say so. `:shared-android` has no test source set by
         * decision, and `wear/`'s (#160) is scoped away from the cue path, so `shared` is still the
         * only place these can be asserted. Deliberately re-exported as aliases rather than inlined at
         * the call sites, so that a reader of this class can still see what the cue path depends on.
         */
        const val KEEP_MIXED_INTERVAL_MS = CueTrackPacing.KEEP_MIXED_INTERVAL_MS

        /** See [CueTrackPacing.KEEP_MIXED_CHUNK_MS]. Bounds how late queued silence can make a cue. */
        const val KEEP_MIXED_CHUNK_MS = CueTrackPacing.KEEP_MIXED_CHUNK_MS

        /** See [CueTrackPacing.MAX_QUEUED_MS]. Above this a cue flushes instead of appending. */
        const val MAX_QUEUED_MS = CueTrackPacing.MAX_QUEUED_MS

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
