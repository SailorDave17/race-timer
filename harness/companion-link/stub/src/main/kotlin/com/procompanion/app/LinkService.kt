package com.procompanion.app

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.json.JSONArray
import org.json.JSONObject

/**
 * The stand-in companion's link service (#294), on pro-companion ADR 004's wire.
 *
 * An event is code [TX_EVENT] under interface token [DESCRIPTOR], one string in, and a reply of no
 * exception and one status string. That is the production companion's `LinkService` exactly. What
 * it does with the event is the stand-in's own: it records the caller's uid and the JSON, and
 * answers with the next scripted status, or `accepted` when the script is empty.
 *
 * The control codes are the device test's, under their own token, so they cannot be mistaken for an
 * event: [TX_DUMP] returns everything received, [TX_SCRIPT] sets the next answers, [TX_RESET] clears
 * both. `CompanionLinkDeviceTest` repeats these numbers; the two are one protocol in two files.
 */
class LinkService : Service() {

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TX_EVENT -> {
                    data.enforceInterface(DESCRIPTOR)
                    val status = Received.take(getCallingUid(), data.readString())
                    reply?.writeNoException()
                    reply?.writeString(status)
                }
                TX_DUMP -> {
                    data.enforceInterface(CONTROL)
                    reply?.writeNoException()
                    reply?.writeString(Received.dump())
                }
                TX_SCRIPT -> {
                    data.enforceInterface(CONTROL)
                    Received.script(data.readString().orEmpty())
                    reply?.writeNoException()
                }
                TX_RESET -> {
                    data.enforceInterface(CONTROL)
                    Received.reset()
                    reply?.writeNoException()
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        const val DESCRIPTOR = "com.procompanion.link.ILink"
        const val TX_EVENT = IBinder.FIRST_CALL_TRANSACTION
        const val CONTROL = "racetimer.harness.CompanionStubControl"
        const val TX_DUMP = IBinder.FIRST_CALL_TRANSACTION + 100
        const val TX_SCRIPT = IBinder.FIRST_CALL_TRANSACTION + 101
        const val TX_RESET = IBinder.FIRST_CALL_TRANSACTION + 102
    }
}

/** What the stand-in has received, for the life of its process. Binder threads, so every call locks. */
object Received {
    private val events = mutableListOf<Pair<Int, String?>>()
    private val answers = ArrayDeque<String>()

    @Synchronized
    fun take(uid: Int, json: String?): String {
        events += uid to json
        return answers.removeFirstOrNull() ?: "accepted"
    }

    @Synchronized
    fun dump(): String = JSONArray(events.map { (uid, json) -> JSONObject().put("uid", uid).put("json", json) }).toString()

    @Synchronized
    fun script(statuses: String) {
        answers.clear()
        statuses.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(answers::addLast)
    }

    @Synchronized
    fun reset() {
        events.clear()
        answers.clear()
    }
}
