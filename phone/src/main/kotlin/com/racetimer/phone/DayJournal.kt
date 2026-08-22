package com.racetimer.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The start-day recorder (#216): an append-only journal of what the console phone did all day, and
 * what it cost the battery while doing it.
 *
 * `docs/start-day-battery.md` is the scenario this serves, the procedure for arming it and the list
 * of what it cannot see. Read that first — this file is the mechanism and deliberately argues none
 * of the product questions.
 *
 * ### It is not app state, and that boundary is asserted
 *
 * The journal is **write-only**: nothing in this module reads it back, no behaviour depends on it,
 * and deleting the file mid-day costs the officer nothing but the record. That is what makes it a
 * different kind of thing from `PhoneRacePersistence`, which is the app's one sanctioned store, and
 * it is why `ModuleBoundaryTest`'s persistence guard is unchanged by this story rather than widened
 * to accommodate it. `DayJournalTest` holds the boundary by scanning for a read path, because a
 * diagnostic that quietly becomes state is exactly the drift a paragraph cannot prevent.
 *
 * ### Inert unless armed, and armed from outside the app
 *
 * Gated on [Log.isLoggable] against [TAG] — `adb shell setprop log.tag.RaceDayJournal DEBUG` — so the
 * instrument compiles into every build including release, and **the artefact the day is measured on
 * is the artefact that ships**. The alternative, `BuildConfig.DEBUG`, certifies a build nobody
 * receives. The pattern and its trap are cairn's `android-forcing-a-failure-you-cannot-observe`:
 * `setprop log.tag.X ""` is *rejected* and silently leaves the old value, so disarming is `ASSERT`.
 *
 * [armed] is read **once, at construction**. A race must not pay a property lookup per cue, and a
 * flag that could change mid-day would make a journal with a hole in it look like a day with a hole
 * in it.
 *
 * ### Nothing here creates the drain it measures
 *
 * Battery samples ride `ACTION_BATTERY_CHANGED`, which the system broadcasts anyway; there is no
 * poll, no alarm and no wake lock in this file. Records are queued in memory and written by
 * [executor] on another thread, never on the caller's — the cue path is the one path in this app
 * with a deadline, and an instrument that wrote a file at the moment of a gun would be measuring
 * itself.
 *
 * The queue is drained on every battery sample, so a process death costs at most the records since
 * the last percent change rather than the day.
 */
class DayJournal(
    private val armed: Boolean,
    private val sink: JournalSink,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val uptimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val executor: Executor = defaultExecutor(),
) {

    private val pending = ConcurrentLinkedQueue<String>()

    /** True when this journal will write anything at all. Read by the tests, not by behaviour. */
    val isArmed: Boolean get() = armed

    /**
     * Queue one record. Cheap enough for the cue path: a formatted string and an enqueue.
     *
     * Fields whose value is null are dropped rather than written as the word "null", so a record's
     * shape says what was actually known at the time.
     */
    fun record(kind: String, vararg fields: Pair<String, Any?>) {
        if (!armed) return
        pending += journalLine(wallClockMs(), uptimeMs(), kind, fields.toList())
    }

    /** Write everything queued. Called on each battery sample and at the end of a race. */
    fun flush() {
        if (!armed || pending.isEmpty()) return
        val batch = generateSequence { pending.poll() }.toList()
        if (batch.isEmpty()) return
        executor.execute { sink.append(batch) }
    }

    /**
     * Record what this build and this device are, so the run names its own artefact (#216 AC 2, and
     * #279's AC 1 requirement that the phone model be named).
     *
     * `docs/battery-baseline.md` lists "the build under test was not recorded" among the watch run's
     * limits. This is that limit, closed by construction rather than by remembering.
     */
    fun recordSession(context: Context) {
        if (!armed) return
        val pkg = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        record(
            "session",
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "release" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "versionName" to pkg?.versionName,
            "versionCode" to pkg?.longVersionCode,
            "journal" to JOURNAL_FORMAT,
        )
        flush()
    }

    /**
     * Sample the battery from an `ACTION_BATTERY_CHANGED` intent, and drain the queue.
     *
     * The charge counter is the whole reason this story is instrumented rather than watched: it is
     * microamp-hours, where the percentage the watch baseline had to use is one part in a hundred.
     * It is read from [BatteryManager] rather than the intent because the intent does not carry it.
     *
     * `status` is recorded so a charger plugged in by accident is *visible*. A day that quietly
     * charged for twenty minutes and still passed is not a day that passed.
     */
    fun recordBattery(context: Context, intent: Intent) {
        if (!armed) return
        val manager = context.getSystemService(BatteryManager::class.java)
        val uah = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        record(
            "battery",
            "level" to intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            "scale" to intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            "uah" to uah,
            "status" to intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            "plugged" to intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
            "tempDeciC" to intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE),
        )
        flush()
    }

    companion object {

        /** The log tag that arms this. Short on purpose — see the class doc. */
        const val TAG = "RaceDayJournal"

        /**
         * The journal's record format, written into every `session` record.
         *
         * The parser refuses a version it does not know rather than guessing at a field it cannot
         * find. A day's journal is unrepeatable, so a parser reading an unfamiliar format must stop
         * and say so instead of returning a plausible number.
         */
        const val JOURNAL_FORMAT = 1

        const val FILE_NAME = "race-day-journal.log"

        /**
         * A journal that does nothing, for tests, previews and every unarmed build.
         *
         * Its clocks are constants rather than the real ones. That is not tidiness: the default
         * `uptimeMs` is `SystemClock::elapsedRealtime`, which throws `Stub!` on a plain JVM, so
         * this object's inertness would otherwise rest entirely on [record] returning before it
         * evaluates anything. *Measured*: with the armed guard mutated away, **23** of the 141
         * tests died — every pure-JVM test that drives a runner, none of which is about the
         * journal. The guard is still what makes it silent; this makes it silent for a second,
         * independent reason, so a future record path that reads a clock before checking the flag
         * fails on its own merits rather than in twenty-three unrelated places.
         */
        val OFF = DayJournal(
            armed = false,
            sink = JournalSink.NONE,
            wallClockMs = { 0L },
            uptimeMs = { 0L },
            executor = Executor(Runnable::run),
        )

        private fun defaultExecutor(): Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, TAG).apply { isDaemon = true }
        }

        /**
         * Build the journal for this process, armed or not, and never throw.
         *
         * An instrument that crashed the app it instruments would be worse than no instrument, and
         * the failure would arrive on a committee boat.
         */
        fun forProcess(context: Context): DayJournal {
            if (!Log.isLoggable(TAG, Log.DEBUG)) return OFF
            val sink = runCatching { FileJournalSink(journalFile(context)) }.getOrNull()
                ?: return OFF
            return DayJournal(armed = true, sink = sink)
        }

        /**
         * Where the journal lands.
         *
         * The app's external files directory, which needs no permission and — unlike `filesDir` —
         * is reachable with a plain `adb pull` from a **release** build, where `run-as` is not. That
         * matters: the day should be run on the artefact that ships. Falls back to internal storage
         * when there is no external volume, which is a state a phone can be in and a day should not
         * fail on.
         */
        fun journalFile(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)
    }
}

/** Where a [DayJournal]'s records go. Separated so the formatting is testable with no filesystem. */
interface JournalSink {

    fun append(lines: List<String>)

    object NONE : JournalSink {
        override fun append(lines: List<String>) {}
    }
}

/** Appends to a file, creating it on first write, and swallows an IO failure. */
class FileJournalSink(private val file: File) : JournalSink {
    override fun append(lines: List<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
        }
    }
}

/**
 * One journal record, as a line.
 *
 * `<ISO-8601 UTC> <elapsedRealtimeMs> <kind> key=value key=value …`
 *
 * Two clocks are written and they are **not** for comparing with each other. The wall clock is how a
 * human finds the moment in the day; `elapsedRealtime` is monotonic and is what every duration in
 * the parse is computed from, because a wall clock can step. Cairn's
 * `a-scenario-can-relax-to-fit-its-instrument` records a measurement ruined by subtracting two
 * timestamps that came from different clocks, so the parse subtracts only within the second column.
 *
 * Values are whitespace-collapsed, because cue labels contain spaces ("3 long") and the record is
 * space-delimited. Sanitising here rather than at every call site is what keeps a new record kind
 * from silently producing an unparseable line.
 *
 * A pure function of its arguments, so the format is provable off the JVM with no device, no
 * filesystem and no clock.
 */
internal fun journalLine(
    wallMs: Long,
    elapsedMs: Long,
    kind: String,
    fields: List<Pair<String, Any?>>,
): String = buildString {
    append(Instant.ofEpochMilli(wallMs).toString())
    append(' ')
    append(elapsedMs)
    append(' ')
    append(sanitiseJournalValue(kind))
    fields.forEach { (key, value) ->
        if (value == null) return@forEach
        append(' ')
        append(sanitiseJournalValue(key))
        append('=')
        append(sanitiseJournalValue(value.toString()))
    }
}

/**
 * Make a value safe for a space-delimited, `=`-separated record.
 *
 * Whitespace becomes an underscore and `=` becomes a colon. Deliberately lossy and deliberately
 * total: there is no escaping scheme to get wrong, and the parser never has to guess where a field
 * ended. An empty value becomes a single underscore so a key is never followed by nothing.
 */
internal fun sanitiseJournalValue(raw: String): String =
    raw.map { char ->
        when {
            char.isWhitespace() -> '_'
            char == '=' -> ':'
            else -> char
        }
    }.joinToString(separator = "").ifEmpty { "_" }

/**
 * Feeds [DayJournal] the battery broadcasts the system was already sending (#216).
 *
 * Registered by [RaceTimerPhoneApplication] for the life of the process, and only when the journal
 * is armed — an unarmed build registers nothing at all, so the instrument costs a shipped app
 * exactly one boolean at startup.
 */
class BatteryJournalReceiver(private val journal: DayJournal) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        journal.recordBattery(context, intent)
    }

    fun register(context: Context) {
        runCatching {
            context.registerReceiver(this, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }
}
