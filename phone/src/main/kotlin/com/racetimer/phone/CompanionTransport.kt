package com.racetimer.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.Executor

/** What one send came back with. */
sealed class SendResult {
    /** The companion answered. [status] is the contract's word, or null if the reply carried none. */
    data class Answered(val status: String?) : SendResult()

    /** The companion's process died under the binding. Treated as `no_core`: keep it and rebind. */
    object Dead : SendResult()

    /** There is no connected binding to send on yet. */
    object NotConnected : SendResult()
}

/**
 * How the link reaches the companion. One implementation for the phone, [BinderCompanionTransport],
 * and fakes in the tests. Every call is made on the link's worker thread.
 */
interface CompanionTransport {

    /**
     * True when the companion is installed, visible to race-timer, and signed by a pinned
     * certificate. False means the link sends nothing and keeps nothing (#294 criterion 3).
     */
    fun trusted(): Boolean

    /**
     * Ask for a binding. [listener] hears when it connects and when it goes. False means no binding
     * will come.
     */
    fun bind(listener: Listener): Boolean

    /** Send one event's JSON over the connected binding. */
    fun send(json: String): SendResult

    /** Drop the binding, if there is one. */
    fun unbind()

    interface Listener {
        fun onConnected()

        /** The binding is gone. [rebind] means it will not come back unless asked for again. */
        fun onDisconnected(rebind: Boolean)
    }
}

/**
 * The certificates race-timer trusts the companion to be signed with (#294 criterion 4).
 *
 * Package **and** certificate. A bind by explicit component already names the package, but any app
 * can be installed under a package name, so the name is only half of it. The certificate is what an
 * imposter cannot copy. Pins are SHA-256 digests of the signing certificate, the form
 * `PackageManager.hasSigningCertificate` takes (API 28; race-timer's `minSdk` is 30).
 *
 * The pins are compiled in per build type, as the companion's of race-timer are (ADR 004, "Who trusts
 * whom"). A release build pins [FIELD_KEY] and nothing else. A debug build adds whatever
 * `-PcompanionDebugCerts` named at build time, which is how a developer's own debug-signed companion,
 * or the test harness's stand-in, gets bound. Nothing on the phone can widen the list.
 *
 * Why not a `signature` permission: race-timer reaches a phone from Play, re-signed with Play's key,
 * and the companion is sideloaded with the owner's upload key. One permission needs one signer on
 * both sides, so it would fail on every real phone and pass on every development machine (ADR 004).
 */
class CompanionPins(val packageName: String, val sha256: List<ByteArray>) {

    /** Whether the installed [packageName] is signed by one of [sha256]. False when it is absent. */
    fun trustedBy(packageManager: PackageManager): Boolean = sha256.any { digest ->
        try {
            packageManager.hasSigningCertificate(packageName, digest, PackageManager.CERT_INPUT_SHA256)
        } catch (e: RuntimeException) {
            false
        }
    }

    companion object {
        /**
         * The companion's field key: the owner's upload key, which every field build is signed with
         * (pro-companion groom decision G20; its `docs/field-builds.md`, "The field key"; ADR 004's
         * table, read 2026-10-08).
         *
         * pro-companion #168 is still choosing how a volunteer installs the companion. A route
         * through Play would re-sign it with Play's key for it, and that key goes in beside this one.
         * A pin added later is one line here and a race-timer release.
         */
        const val FIELD_KEY = "8578eb0538b09db7f4df3284049591986aa32e5a338ee513f86e7c754bdc5506"

        /** [FIELD_KEY] plus the build's debug pins, which are empty in a release build. */
        fun forBuild(): CompanionPins =
            of(listOf(FIELD_KEY) + BuildConfig.COMPANION_DEBUG_CERTS.split(','))

        /**
         * Pins from hex digests. Blanks are skipped, and colons and case are ignored so that a
         * digest pasted from `apksigner` or `keytool` works as written. Anything else that is not
         * a 32-byte digest is refused, so a typo fails the build's first run rather than leaving a
         * pin that can never match.
         */
        fun of(hexDigests: List<String>): CompanionPins = CompanionPins(
            CompanionContract.PACKAGE,
            hexDigests.map { it.replace(":", "").trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { hex ->
                    require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
                        "Not a SHA-256 digest: $hex"
                    }
                    ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                },
        )
    }
}

/**
 * The companion over a bound service, as ADR 004 chose (#294).
 *
 * Bound by explicit component in the pinned package, checked against the pins before the bind and
 * again when the binding connects, so a companion replaced in between is never sent anything. Each
 * event is one raw binder transaction, and every transaction returns a status. A bound service, not
 * a broadcast or a content provider, because of how each fails: a broadcast tells the sender `sent`
 * when nothing was stored, and a provider's `call()` blocked its caller for minutes while the
 * companion crash-looped. A transaction on a dead binder throws at once (ADR 004's kill probe).
 *
 * The callbacks arrive on [executor], the link's worker, so every piece of state here is that one
 * thread's.
 */
class BinderCompanionTransport(
    private val context: Context,
    private val pins: CompanionPins,
    private val executor: Executor,
) : CompanionTransport {

    private var listener: CompanionTransport.Listener? = null
    private var binder: IBinder? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (name.packageName != pins.packageName || !trusted()) {
                Log.w(TAG, "Connected to an untrusted ${name.packageName}: unbinding")
                unbind()
                // The link re-checks trust before it binds again, and stops there.
                listener?.onDisconnected(rebind = true)
                return
            }
            binder = service
            listener?.onConnected()
        }

        // The process died. BIND_AUTO_CREATE brings it back and this connection reconnects by itself.
        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
            listener?.onDisconnected(rebind = false)
        }

        // The binding itself is finished and will not reconnect, as after the companion is updated.
        override fun onBindingDied(name: ComponentName) {
            binder = null
            listener?.onDisconnected(rebind = true)
        }

        override fun onNullBinding(name: ComponentName) {
            Log.w(TAG, "The companion returned no binder")
            binder = null
        }
    }

    override fun trusted(): Boolean = pins.trustedBy(context.packageManager)

    override fun bind(listener: CompanionTransport.Listener): Boolean {
        this.listener = listener
        if (bound) return true
        val intent = Intent().setComponent(ComponentName(CompanionContract.PACKAGE, CompanionContract.SERVICE))
        bound = try {
            context.bindService(intent, Context.BIND_AUTO_CREATE, executor, connection)
        } catch (e: SecurityException) {
            Log.w(TAG, "Bind refused", e)
            false
        }
        // The platform asks for an unbind even after a bind that returned false, to release the
        // connection it may still hold.
        if (!bound) runCatching { context.unbindService(connection) }
        return bound
    }

    override fun send(json: String): SendResult {
        val target = binder ?: return SendResult.NotConnected
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CompanionContract.DESCRIPTOR)
            data.writeString(json)
            target.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
            reply.readException()
            SendResult.Answered(reply.readString())
        } catch (e: DeadObjectException) {
            binder = null
            SendResult.Dead
        } catch (e: RemoteException) {
            binder = null
            SendResult.Dead
        } catch (e: RuntimeException) {
            // An exception the companion's side wrote into the reply. Read as an answer with no
            // status, which the link keeps and resends like `no_core`: only `refused` and
            // `malformed` let an event go, because only the companion can say a resend is useless.
            Log.w(TAG, "The companion answered with an exception", e)
            SendResult.Answered(null)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun unbind() {
        binder = null
        if (!bound) return
        bound = false
        runCatching { context.unbindService(connection) }
    }

    private companion object {
        const val TAG = "CompanionTransport"
    }
}
