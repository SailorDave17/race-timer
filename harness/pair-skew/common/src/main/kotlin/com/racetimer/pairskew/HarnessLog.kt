package com.racetimer.pairskew

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.racetimer.pairskew.protocol.Harness
import com.racetimer.pairskew.protocol.Record
import com.racetimer.pairskew.protocol.Side
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Every line goes to logcat under [Harness.TAG], which is the capture #218 names, and to a file in the
 * app's own storage. The file exists because a wireless adb link can drop mid-run, and a line logged
 * while it is down survives in logcat only until the ring buffer turns over.
 *
 * Each line is stamped with its own `at` (this device's `elapsedRealtimeNanos`) when [write] is
 * called, then written on a thread of its own, so no file write ever sits between a message arriving
 * and its stamp being read.
 */
class HarnessLog(context: Context, private val side: Side) {

    private val file = File(context.filesDir, FILE)
    private val io = Executors.newSingleThreadExecutor()

    fun write(kind: String, fields: Map<String, String>) {
        val all = LinkedHashMap<String, String>()
        all["side"] = side.wire
        all["at"] = SystemClock.elapsedRealtimeNanos().toString()
        for ((key, value) in fields) all[key] = Record.safe(value)
        val line = Record(kind, all).format()
        io.execute {
            Log.i(Harness.TAG, line)
            try {
                file.appendText(line + "\n")
            } catch (e: IOException) {
                Log.w(Harness.TAG, "could not append to $FILE: $e")
            }
        }
    }

    fun write(kind: String, vararg fields: Pair<String, Any?>) =
        write(kind, fields.associate { (key, value) -> key to (value?.toString() ?: "-") })

    companion object {
        const val FILE = "pairskew.log"
    }
}
