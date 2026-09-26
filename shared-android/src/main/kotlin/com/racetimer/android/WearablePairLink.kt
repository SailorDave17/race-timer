package com.racetimer.android

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.racetimer.shared.PairLink
import com.racetimer.shared.PairScheduler
import com.racetimer.shared.PairStatus
import com.racetimer.shared.PairTransport
import com.racetimer.shared.PeerNode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * The Wearable Data Layer glue for the pair's clock link (#219, epic #196). It carries
 * [PairLink]'s bytes over `MessageClient`, hands it the peers `CapabilityClient` reports, and reads
 * each arriving message's stamp. The protocol itself is `:shared`'s, where the JVM tests reach it.
 *
 * **One copy, here, for both apps** (owner decision at #219 pickup, 2026-09-25). The adapter is the
 * same on a wrist and on a console, which is D1's reason for this module; `:wear` and `:phone` each
 * declare `play-services-wearable` as well, because each ships it.
 *
 * **One thread owns everything.** The Data Layer is given this class's looper
 * ([Wearable.WearableOptions]), so incoming messages and capability changes already arrive on the
 * thread that owns the [PairLink], and the arrival stamp is the first thing read — nothing is posted
 * between a message landing and its t2 or t4. #218's harness measured that arrangement holding.
 *
 * **Play services is asked first, and nothing is built without it.** A phone with no Google Play
 * services, or an out-of-date copy, gets [PairStatus.Unavailable] and never a Wearable client: a
 * client left to discover the problem itself goes through Play services' own error handling, and a
 * standalone timer has no business raising Play services' prompts. A phone with Play services but no
 * paired watch builds the clients and finds no peer.
 *
 * Process-wide, like the sequence and the race: [get] builds it once and it lives until the process
 * dies. Answering the peer does not depend on any screen — only asking does ([setActive]).
 */
class WearablePairLink private constructor(private val app: Context) {

    private val thread = HandlerThread("race-timer-pair").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val onLink = Executor { handler.post(it) }
    private val listeners = CopyOnWriteArrayList<(PairStatus) -> Unit>()

    // Touched on [thread] only.
    private var messages: MessageClient? = null
    private var capabilities: CapabilityClient? = null
    private var localId: String? = null

    /** The latest status the link published, readable from any thread. */
    @Volatile
    var status: PairStatus = PairStatus.NoPeer
        private set

    private val link = PairLink(
        clock = SystemMonotonicClock,
        transport = object : PairTransport {
            override fun send(peerId: String, payload: ByteArray, onFailed: () -> Unit) {
                val client = messages ?: return onFailed()
                client.sendMessage(peerId, PATH, payload).addOnFailureListener(onLink) { e ->
                    Log.w(TAG, "send refused: ${reason(e)}")
                    onFailed()
                }
            }

            override fun queryPeers() = query()
        },
        scheduler = PairScheduler { delayMs, task ->
            val runnable = Runnable { task() }
            handler.postDelayed(runnable, delayMs)
            val cancel: () -> Unit = { handler.removeCallbacks(runnable) }
            cancel
        },
        onStatus = { published ->
            status = published
            main.post { listeners.forEach { it(published) } }
        },
        log = { Log.i(TAG, it) },
    )

    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        // t2 or t4: read before anything else, whatever the message turns out to be.
        val receivedMs = SystemMonotonicClock.elapsedMs()
        if (event.path == PATH) {
            onThread { link.onMessage(event.sourceNodeId, event.data, receivedMs) }
        }
    }

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
        onThread { if (localId != null) link.onPeers(peersOf(info.nodes)) }
    }

    /**
     * Whether a screen showing the link is on view. Active, the link measures now and every 30 s;
     * inactive, it asks nothing and still answers the peer.
     */
    fun setActive(active: Boolean) {
        handler.post { link.setActive(active) }
    }

    /** Receives every status on the main thread, starting with the current one. */
    fun addStatusListener(listener: (PairStatus) -> Unit) {
        listeners += listener
        val now = status
        main.post { if (listener in listeners) listener(now) }
    }

    fun removeStatusListener(listener: (PairStatus) -> Unit) {
        listeners -= listener
    }

    private fun start() {
        if (!playServicesUsable()) {
            Log.i(TAG, "Google Play services unusable here: no pair link")
            link.onUnavailable()
            return
        }
        val options = Wearable.WearableOptions.Builder().setLooper(thread.looper).build()
        val messageClient = Wearable.getMessageClient(app, options)
        val capabilityClient = Wearable.getCapabilityClient(app, options)
        messages = messageClient
        capabilities = capabilityClient
        messageClient.addListener(messageListener).addOnFailureListener(onLink) { failed("addListener", it) }
        capabilityClient.addListener(capabilityListener, CAPABILITY)
            .addOnFailureListener(onLink) { failed("addCapabilityListener", it) }
        Wearable.getNodeClient(app, options).localNode
            .addOnSuccessListener(onLink) { node ->
                localId = node.id
                query()
            }
            .addOnFailureListener(onLink) { failed("localNode", it) }
    }

    /**
     * Which reachable devices run the other app. Waits for this device's own node id, which the
     * answer has to be filtered by: without it this device could find itself and ping its own clock.
     */
    private fun query() {
        val client = capabilities ?: return
        if (localId == null) return
        client.getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).addOnCompleteListener(onLink) { task ->
            if (task.isSuccessful) {
                link.onPeers(peersOf(task.result.nodes))
            } else {
                failed("getCapability", task.exception)
            }
        }
    }

    private fun peersOf(nodes: Set<Node>): List<PeerNode> =
        nodes.filter { it.id != localId }.map { PeerNode(it.id, it.isNearby) }

    /**
     * A Data Layer call that failed. API_NOT_CONNECTED is the Data Layer saying it is not on this
     * device at all — a phone with Play services and no Wear OS pairing — and becomes
     * [PairStatus.Unavailable]. Anything else is logged and the next query tries again.
     */
    private fun failed(what: String, e: Exception?) {
        Log.w(TAG, "$what failed: ${reason(e)}")
        if ((e as? ApiException)?.statusCode == CommonStatusCodes.API_NOT_CONNECTED) link.onUnavailable()
    }

    private fun playServicesUsable(): Boolean = try {
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS
    } catch (e: RuntimeException) {
        // It throws, rather than answering, when the manifest's Play services version entry is
        // missing — which is itself an answer.
        Log.w(TAG, "Play services check threw: ${e.javaClass.simpleName}")
        false
    }

    private fun onThread(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) block() else handler.post(block)
    }

    private fun reason(e: Exception?): String = when (e) {
        null -> "unknown"
        is ApiException -> "api${e.statusCode}"
        else -> e.javaClass.simpleName
    }

    companion object {
        /**
         * The capability both apps advertise in `res/values/pair_capability.xml`, so each finds the
         * device running the other app rather than whatever else is paired. The two must match.
         */
        const val CAPABILITY = "race_timer_pair"

        /** The one Data Layer path the link's messages travel on. */
        const val PATH = "/race-timer/pair"

        private const val TAG = "RaceTimerPair"

        @Volatile
        private var instance: WearablePairLink? = null

        fun get(context: Context): WearablePairLink = instance ?: synchronized(this) {
            instance ?: WearablePairLink(context.applicationContext).also {
                instance = it
                it.handler.post { it.start() }
            }
        }
    }
}
