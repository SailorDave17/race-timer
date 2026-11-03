package com.racetimer.phone

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The events the companion has not yet taken, kept on disk until it does (#294).
 *
 * ADR 004 decision 4 makes delivery at least once. race-timer keeps every event the companion could
 * not take and resends it under the same id. The ADR also says nothing is lost while the
 * companion's core host is down, *provided race-timer keeps every unanswered event*. That host
 * stays down until the PRO opens the app, which can be long after the race and after this process
 * is gone. So the outbox is a file, not a list in memory: the owner's decision at #294's pickup. It
 * is drained at every later bind, and the companion orders a late backlog by each event's own time
 * (pro-companion #33's review), so arriving after the next race's events does it no harm.
 *
 * Each entry is the event's JSON exactly as first written. A resend sends the same bytes, so the
 * same id, and nothing can re-encode an event into something the companion has not seen.
 *
 * Beside the queue it keeps [sequence], the id of the sequence now running. A race restored after a
 * process death carries on under the id its earlier signals named, rather than looking to the
 * companion like a second sequence with no start.
 *
 * Only ever touched from the link's worker thread, so it takes no lock. Every change is written
 * before the call returns, to a temporary file that then replaces this one in a single atomic move,
 * so a process death between two events leaves the file as it was after the first one and never
 * half-written. Not `android.util.AtomicFile`: on a Windows test JVM its replace failed silently and
 * only the file's first write ever survived (#294, measured), so its unit tests could not have
 * told a working outbox from a broken one on the machine this is developed on.
 */
class CompanionOutbox(private val file: File) {

    private val staging = File(file.path + ".new")
    private val queue = ArrayDeque<String>()

    /** The id of the sequence now running, or null before the first. */
    var sequence: String? = null
        private set

    init {
        load()
    }

    val pending: List<String> get() = queue.toList()

    val isEmpty: Boolean get() = queue.isEmpty()

    /**
     * How many times this outbox has written its file. Read by the link's tests: a write that
     * [clear] deletes moments later leaves no file to find, so "nothing was written" is only
     * checkable as a count (#294's review).
     */
    var writes: Int = 0
        private set

    fun oldest(): String? = queue.firstOrNull()

    /**
     * Queue [json] behind everything already waiting.
     *
     * Bounded at [MAX_PENDING], dropping the oldest. A companion that is installed and never answers
     * would otherwise grow this file by a sequence's worth of events every race for as long as the
     * phone is used. A day of starts is far below the bound, so in practice the bound means a
     * companion that has not answered for many days.
     */
    fun add(json: String) {
        queue.addLast(json)
        while (queue.size > MAX_PENDING) {
            queue.removeFirst()
            Log.w(TAG, "Outbox past $MAX_PENDING events: the oldest was dropped")
        }
        save()
    }

    /** Remove [json], which the companion has taken or turned away for good. */
    fun remove(json: String) {
        if (queue.remove(json)) save()
    }

    /** Start a new sequence under [id]. Waiting events are kept: they belong to earlier sequences. */
    fun beginSequence(id: String) {
        sequence = id
        save()
    }

    /**
     * Forget everything: the companion is gone, or no longer trusted, so nothing here has anywhere
     * to go. A companion installed later is a fresh install with a fresh log, which has no use for a
     * backlog from before it existed.
     */
    fun clear() {
        if (queue.isEmpty() && sequence == null && !file.exists()) return
        queue.clear()
        sequence = null
        file.delete()
        staging.delete()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            sequence = root.optString(KEY_SEQUENCE).ifEmpty { null }
            val events = root.optJSONArray(KEY_PENDING) ?: JSONArray()
            for (i in 0 until events.length()) queue.addLast(events.getString(i))
        } catch (e: IOException) {
            Log.w(TAG, "Outbox unreadable, starting empty", e)
        } catch (e: JSONException) {
            Log.w(TAG, "Outbox unreadable, starting empty", e)
        }
    }

    private fun save() {
        val root = JSONObject()
        sequence?.let { root.put(KEY_SEQUENCE, it) }
        root.put(KEY_PENDING, JSONArray(queue.toList()))
        writes++
        try {
            FileOutputStream(staging).use { out ->
                out.write(root.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            staging.delete()
            Log.w(TAG, "Outbox not written", e)
        }
    }

    companion object {
        private const val TAG = "CompanionOutbox"

        /** The file's name in the app's private storage. Listed in the published privacy policy. */
        const val FILE_NAME = "companion_outbox.json"

        const val MAX_PENDING = 500

        private const val KEY_SEQUENCE = "sequence"
        private const val KEY_PENDING = "pending"
    }
}
