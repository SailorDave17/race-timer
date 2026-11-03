package com.racetimer.phone

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.WallClock
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

/**
 * What the race tells the companion (#294): a sequence started or resumed, a cue fired, the race over.
 *
 * Called from the cue path, on the main thread, so every implementation returns at once. The link
 * never holds the thread that fires the cues (#294 criterion 2). [OFF] is what every test, preview
 * and service without the Application gets.
 */
interface CompanionLink {

    /** The officer started [sequence], or this phone joined the watch's run of it. */
    fun sequenceStarted(sequence: RaceSequence)

    /** A race saved before a process death is running again, under the sequence it was saved as. */
    fun sequenceResumed()

    /** The engine fired [cue] of [sequence]. Most cues are not sent; see [companionReportOf]. */
    fun cueFired(sequence: RaceSequence, cue: SequenceCue)

    /** The race is over without its gun, or after it: stopped, abandoned or ended. */
    fun sequenceEnded()

    object OFF : CompanionLink {
        override fun sequenceStarted(sequence: RaceSequence) {}
        override fun sequenceResumed() {}
        override fun cueFired(sequence: RaceSequence, cue: SequenceCue) {}
        override fun sequenceEnded() {}
    }
}

/** Where the link's work runs: one thread, in order, with a clock for the linger after a race. */
interface LinkWorker {
    val executor: Executor
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
    fun nowMs(): Long
}

/**
 * The link's own thread, at background priority, so neither a binder transaction that waits on the
 * companion nor an outbox write can delay a cue. The companion bounds its own wait at 2 s (ADR 004),
 * and only this thread waits.
 */
class HandlerLinkWorker : LinkWorker {
    private val thread = HandlerThread("companion-link", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)

    override val executor: Executor = Executor { handler.post(it) }
    override fun post(task: Runnable) { handler.post(task) }
    override fun postDelayed(task: Runnable, delayMs: Long) { handler.postDelayed(task, delayMs) }
    override fun cancel(task: Runnable) = handler.removeCallbacks(task)
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * The companion link: each sequence race-timer runs, sent to the companion as ADR 004's events (#294).
 *
 * ### On the cue path, a timestamp and a post
 *
 * The calls arrive on the main thread, from the runner's cue listener, after the cue's buzz and
 * sound have been asked for. Each one reads the wall clock, which is the event's `at_ms`, the moment
 * race-timer fired the signal, and posts the rest to [worker]. A cue the companion is not told about
 * costs one comparison and no post. Everything else (the id, the outbox write, the trust check, the
 * bind and the transaction) happens on the worker.
 *
 * ### Delivery: at least once, in order, kept until taken
 *
 * Every event goes into the [CompanionOutbox] before it is sent, and leaves it only when the
 * companion answers `accepted`, or `refused` or `malformed`, which a resend cannot change. Anything
 * else (`no_core`, a dead binder, no answer at all) keeps it, and the link tries again after a
 * backoff, oldest first, under the same id. The companion stores one event per id.
 *
 * The binding lasts from a sequence's start until its gun or its end, as ADR 004 decision 1 says,
 * and then for as long as events are still waiting, up to [LINGER_MS]. After that the link lets go
 * and the events stay in the file for the next sequence's bind. That covers the case ADR 004 names:
 * the companion's core host down until the PRO next opens the app.
 *
 * ### A companion that is absent or untrusted gets nothing
 *
 * At each sequence's start the transport checks that the companion is installed and signed by a
 * pinned certificate. If not, the link does nothing for that sequence, writes nothing, binds
 * nothing, and forgets any backlog, so a phone without the companion runs exactly as before (#294
 * criterion 3).
 */
class CompanionLinkEmitter(
    private val transport: CompanionTransport,
    openOutbox: () -> CompanionOutbox,
    private val worker: LinkWorker,
    private val wallClock: WallClock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : CompanionLink {

    // Read on the worker, at its first use, so the main thread never waits on the file.
    private val outbox by lazy(LazyThreadSafetyMode.NONE, openOutbox)

    // Everything below is the worker's alone.
    private var recording = false
    private var live = false
    private var lingerUntilMs = 0L
    private var bound = false
    private var backoffStep = 0

    private val retry = Runnable {
        if (!live && worker.nowMs() >= lingerUntilMs) letGo() else connectAndDrain()
    }

    private val listener = object : CompanionTransport.Listener {
        override fun onConnected() = drain()

        override fun onDisconnected(rebind: Boolean) {
            if (rebind) {
                transport.unbind()
                bound = false
            }
            if (!outbox.isEmpty || live) scheduleRetry()
        }
    }

    override fun sequenceStarted(sequence: RaceSequence) {
        val atMs = wallClock.nowMs()
        val name = sequence.name
        worker.post {
            if (!begin()) return@post
            // One id for both: the contract's `sequence` is the id of the sequence_start, which
            // names itself.
            val startId = newId()
            outbox.beginSequence(startId)
            outbox.add(CompanionEvent(startId, CompanionKind.SEQUENCE_START, atMs, startId, sequenceName = name).toJson())
            connectAndDrain()
        }
    }

    override fun sequenceResumed() {
        worker.post {
            if (!begin()) return@post
            if (outbox.sequence == null) outbox.beginSequence(newId())
            connectAndDrain()
        }
    }

    override fun cueFired(sequence: RaceSequence, cue: SequenceCue) {
        val report = companionReportOf(sequence, cue) ?: return
        val atMs = wallClock.nowMs()
        worker.post {
            if (!recording) return@post
            val sequenceId = outbox.sequence ?: newId().also(outbox::beginSequence)
            val event = when (report) {
                is CueReport.Signal -> CompanionEvent(
                    newId(), CompanionKind.SIGNAL, atMs, sequenceId,
                    signal = report.name, secondsToStart = report.secondsToStart,
                )
                CueReport.Start -> CompanionEvent(newId(), CompanionKind.START, atMs, sequenceId)
            }
            outbox.add(event.toJson())
            connectAndDrain()
            // The gun is the last event a sequence has: race-timer fires nothing after it.
            if (report == CueReport.Start) finish()
        }
    }

    override fun sequenceEnded() {
        worker.post { finish() }
    }

    /** Decide whether this sequence is sent at all. False forgets everything. */
    private fun begin(): Boolean {
        if (!transport.trusted()) {
            forget()
            return false
        }
        recording = true
        live = true
        return true
    }

    /** The companion is absent or no longer trusted: nothing waiting has anywhere to go. */
    private fun forget() {
        recording = false
        live = false
        letGo()
        outbox.clear()
    }

    private fun finish() {
        if (!live) return
        live = false
        lingerUntilMs = worker.nowMs() + LINGER_MS
        if (outbox.isEmpty) letGo() else scheduleRetry()
    }

    private fun connectAndDrain() {
        if (!bound) {
            // Every bind, not only a sequence's first: the companion can be replaced mid-race.
            if (!transport.trusted()) {
                forget()
                return
            }
            bound = transport.bind(listener)
            if (!bound) {
                // A refusal can pass, as when the companion is reinstalled across the gun, so it goes
                // up the same ladder as a no_core and lets go at the linger's end like one. Nothing is
                // lost either way: the outbox keeps it for the next sequence.
                Log.w(TAG, "No binding to the companion; ${outbox.pending.size} event(s) waiting")
                scheduleRetry()
                return
            }
        }
        drain()
    }

    private fun drain() {
        worker.cancel(retry)
        while (true) {
            val json = outbox.oldest() ?: break
            when (val result = transport.send(json)) {
                is SendResult.Answered -> when (result.status) {
                    CompanionContract.ACCEPTED -> {
                        outbox.remove(json)
                        backoffStep = 0
                    }
                    CompanionContract.REFUSED, CompanionContract.MALFORMED -> {
                        Log.w(TAG, "The companion answered ${result.status}; not resending")
                        outbox.remove(json)
                    }
                    else -> {
                        scheduleRetry()
                        return
                    }
                }
                SendResult.Dead -> {
                    transport.unbind()
                    bound = false
                    scheduleRetry()
                    return
                }
                // Bound and waiting for the connection, whose callback drains.
                SendResult.NotConnected -> return
            }
        }
        backoffStep = 0
        if (!live) letGo()
    }

    private fun scheduleRetry() {
        worker.cancel(retry)
        if (!live && worker.nowMs() >= lingerUntilMs) {
            letGo()
            return
        }
        val delayMs = BACKOFF_MS[backoffStep.coerceAtMost(BACKOFF_MS.lastIndex)]
        backoffStep++
        worker.postDelayed(retry, delayMs)
    }

    private fun letGo() {
        worker.cancel(retry)
        if (bound) transport.unbind()
        bound = false
    }

    companion object {
        private const val TAG = "CompanionLink"

        /** How long after a race the link keeps resending before it leaves the rest for the next. */
        const val LINGER_MS = 5 * 60_000L

        /** The waits between resends, the last repeated. */
        val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

        /** The process's link: the real transport, the build's pins, the outbox in private storage. */
        fun forProcess(context: Context): CompanionLink {
            val app = context.applicationContext
            val worker = HandlerLinkWorker()
            return CompanionLinkEmitter(
                transport = BinderCompanionTransport(app, CompanionPins.forBuild(), worker.executor),
                openOutbox = { CompanionOutbox(File(app.filesDir, CompanionOutbox.FILE_NAME)) },
                worker = worker,
                wallClock = WallClock { System.currentTimeMillis() },
            )
        }
    }
}
