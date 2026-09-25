package com.racetimer.pairskew

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.racetimer.pairskew.protocol.Harness
import com.racetimer.pairskew.protocol.MalformedRecord
import com.racetimer.pairskew.protocol.Primitive
import com.racetimer.pairskew.protocol.Record
import com.racetimer.pairskew.protocol.Round
import com.racetimer.pairskew.protocol.Side
import com.racetimer.pairskew.protocol.wire
import com.racetimer.shared.ExchangeClock
import com.racetimer.shared.GunTranslation
import com.racetimer.shared.translateGun
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Both devices run this, unchanged. It asks and answers offset-exchange rounds over the Wearable Data
 * Layer, keeps every completed round, holds a gun on its own clock, and logs everything as [Record]s.
 *
 * **One thread owns all state.** The Data Layer calls every listener and RPC on the looper given to it
 * in [Wearable.WearableOptions], which is [thread]'s, so incoming messages already arrive on the
 * thread that owns [rounds]. The one callback that does not, a request's reply, is completed on
 * whatever thread GMS completes it on. It reads its clock first and posts the rest.
 *
 * **Every stamp is read first in its handler, and the log is written on a thread of its own**, so a
 * slow write widens no round. Nothing here can make a round unsound, only wider: a stamp read late
 * moves an interval's edge outward, never inward.
 *
 * **Each side keeps every round, whoever asked.** The requester sends each completed round back to the
 * responder, so both hold the same samples. That is what lets either device translate a gun, and
 * keep the offset it last knew, with no fresh exchange (#218, third criterion).
 */
class Engine private constructor(private val app: Context) {

    val side: Side =
        if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) Side.WEAR else Side.PHONE

    /** This process's start on its own clock: the `run` that keeps two processes' rounds apart. */
    private val run: Long = SystemClock.elapsedRealtime()

    private val log = HarnessLog(app, side)
    private val thread = HandlerThread("pairskew").apply { start() }
    private val handler = Handler(thread.looper)
    private val onEngine = Executor { handler.post(it) }
    private val direct = Executor { it.run() }
    private val options = Wearable.WearableOptions.Builder().setLooper(thread.looper).build()
    private val messages: MessageClient = Wearable.getMessageClient(app, options)
    private val capabilities: CapabilityClient = Wearable.getCapabilityClient(app, options)

    // Everything from here to the controls is touched on [thread] only.
    private val rounds = ArrayList<Round>()
    private val seen = HashSet<Triple<Side, Long, Long>>()
    private val pending = HashMap<Long, Ping>()
    private var inFlight: Long? = null
    private var peer: Node? = null
    private var localId: String? = null
    private var auto = false
    private var burstActive = false
    private var burst = 0L
    private var seq = 0
    private var nextId = 1L
    private var lastProgressMs = 0L
    private var okCount = 0
    private var failCount = 0
    private var lastRound: Round? = null
    private var lastRoundAtMs = 0L
    private var gun: Gun? = null
    private var gunsArmed = 0
    private var bt = "?"
    private var flashUntilMs = 0L

    /** What the screen shows: rebuilt on [thread], read on the main thread. */
    @Volatile
    var snapshot = Snapshot()
        private set

    data class Snapshot(val text: String = "starting", val auto: Boolean = false, val flashUntilMs: Long = 0L)

    /** A round this side has asked and not yet finished. */
    private class Ping(
        val id: Long,
        val nodeId: String?,
        val primitive: Primitive,
        val nearby: Boolean?,
        val burst: Long,
        val seq: Int,
        val t1Ns: Long,
    ) {
        var failed = false
    }

    /**
     * A gun held on this device's clock at [ownMs]. [src] armed it at [srcMs] on its own clock; when
     * that is the other device, [ownMs] is the translation this side adopted, out by [boundMs] at worst.
     */
    private class Gun(val id: String, val src: Side, val srcMs: Long, val ownMs: Long, val boundMs: Long) {
        var fired = false
    }

    // --- controls, callable from any thread -----------------------------------------------------

    fun toggleAuto() {
        handler.post { setAuto(!auto) }
    }

    fun burstOnce() {
        handler.post { if (!burstActive) beginBurst() }
    }

    fun armGun() {
        handler.post { arm() }
    }

    fun refresh() {
        handler.post { publish() }
    }

    // --- start ----------------------------------------------------------------------------------

    private fun start() {
        handler.post {
            log.write(
                "HELLO",
                "run" to run,
                "maker" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "sdk" to Build.VERSION.SDK_INT,
                "app" to app.packageName,
                "ver" to versionName(),
                "frame" to "phone-requester",
                "clock" to "elapsedRealtimeNanos",
                "ppm" to Harness.STATED_DRIFT_PPM,
                "burst_size" to Harness.BURST_SIZE,
                "burst_gap_ms" to Harness.BURST_GAP_MS,
            )
            messages.addListener(listener).addOnFailureListener(onEngine) { error("addListener", it) }
            messages.addRpcService(rpc, Harness.PATH_RPC).addOnFailureListener(onEngine) { error("addRpcService", it) }
            capabilities.addListener(capabilityListener, Harness.CAPABILITY)
                .addOnFailureListener(onEngine) { error("addCapabilityListener", it) }
            Wearable.getNodeClient(app, options).localNode
                .addOnSuccessListener(onEngine) { node ->
                    localId = node.id
                    log.write("NODE", "local" to short(node.id))
                    refreshPeer { publish() }
                }
                .addOnFailureListener(onEngine) { error("localNode", it) }
            watchBluetooth()
            handler.postDelayed(stateTick, Harness.STATE_EVERY_MS)
            publish()
        }
    }

    // --- asking ---------------------------------------------------------------------------------

    private fun setAuto(on: Boolean) {
        auto = on
        log.write("AUTO", "on" to on.wire())
        if (on && !burstActive) beginBurst()
        publish()
    }

    private fun beginBurst() {
        burst++
        seq = 0
        burstActive = true
        lastProgressMs = SystemClock.elapsedRealtime()
        next()
    }

    /** The next round of the current burst, or the end of the burst. */
    private fun next() {
        if (!burstActive) return
        if (seq >= Harness.BURST_SIZE) {
            burstActive = false
            if (auto) handler.postDelayed({ if (auto && !burstActive) beginBurst() }, Harness.BURST_GAP_MS)
            publish()
            return
        }
        seq++
        // Alternate the primitive within a burst, and which one opens it, so neither always gets the
        // cold first slot.
        val primitive = if ((burst + seq) % 2L == 0L) Primitive.MSG else Primitive.RPC
        refreshPeer { node -> ask(node, primitive) }
    }

    private fun ask(node: Node?, primitive: Primitive) {
        lastProgressMs = SystemClock.elapsedRealtime()
        val id = nextId++
        if (node == null) {
            failed(Ping(id, null, primitive, null, burst, seq, now()), "no-peer")
            return
        }
        // t1 is read as the Ping is built, immediately before the payload and the send.
        val ping = Ping(id, node.id, primitive, node.isNearby, burst, seq, now())
        pending[id] = ping
        inFlight = id
        val payload = Record(
            "PING",
            linkedMapOf("side" to side.wire, "run" to "$run", "id" to "$id", "t1" to "${ping.t1Ns}"),
        ).bytes()
        when (primitive) {
            Primitive.MSG ->
                messages.sendMessage(node.id, Harness.PATH_PING, payload)
                    .addOnFailureListener(onEngine) { failed(ping, "send-" + reason(it)) }
            Primitive.RPC ->
                messages.sendRequest(node.id, Harness.PATH_RPC, payload)
                    .addOnCompleteListener(direct) { task ->
                        val t4 = now()
                        handler.post {
                            val reply = if (task.isSuccessful) parse(task.result) else null
                            when {
                                reply != null -> complete(id, reply, t4)
                                task.isSuccessful -> failed(ping, "rpc-empty")
                                else -> failed(ping, "rpc-" + reason(task.exception))
                            }
                        }
                    }
        }
        handler.postDelayed({ if (!ping.failed && pending.containsKey(id)) failed(ping, "timeout") }, Harness.ROUND_TIMEOUT_MS)
    }

    /** The requester's end of a round: the reply is in, and [t4] was read the moment it arrived. */
    private fun complete(id: Long, reply: Record, t4: Long) {
        val ping = pending.remove(id) ?: return
        val round = try {
            Round(side, run, id, ping.primitive, ping.nearby, ping.burst, ping.seq, ping.t1Ns, reply.long("t2"), reply.long("t3"), t4)
        } catch (e: MalformedRecord) {
            failed(ping, "bad-reply")
            return
        }
        if (keep(round)) {
            // A round answered after its timeout is still a sound round, only a wide one. `late`
            // says so, and the FAIL already logged for it is the analysis's to discount.
            log.write("ROUND", round.fields().apply { put("late", ping.failed.wire()); put("rtt_us", "${round.rttNs / 1000}") })
            ping.nodeId?.let { messages.sendMessage(it, Harness.PATH_SAMPLE, Record("SAMPLE", round.fields()).bytes()) }
            retranslate()
            publish()
        }
        lastProgressMs = SystemClock.elapsedRealtime()
        if (inFlight == id) {
            inFlight = null
            next()
        }
    }

    private fun failed(ping: Ping, reason: String) {
        if (ping.failed) return
        ping.failed = true
        failCount++
        // A timed-out round stays pending: its reply may still come, and would still be sound.
        if (reason != "timeout") pending.remove(ping.id)
        log.write(
            "FAIL",
            "req" to side.wire,
            "run" to run,
            "id" to ping.id,
            "prim" to ping.primitive.wire,
            "near" to ping.nearby.wire(),
            "burst" to ping.burst,
            "seq" to ping.seq,
            "reason" to reason,
        )
        lastProgressMs = SystemClock.elapsedRealtime()
        if (inFlight == ping.id || ping.nodeId == null) {
            if (inFlight == ping.id) inFlight = null
            next()
        }
        publish()
    }

    private fun keep(round: Round): Boolean {
        if (!seen.add(round.key)) return false
        rounds += round
        okCount++
        lastRound = round
        lastRoundAtMs = SystemClock.elapsedRealtime()
        return true
    }

    // --- answering ------------------------------------------------------------------------------

    private val listener = MessageClient.OnMessageReceivedListener { event ->
        val t = now()
        when (event.path) {
            Harness.PATH_PING ->
                answer(event.data, Primitive.MSG, t)?.let { messages.sendMessage(event.sourceNodeId, Harness.PATH_PONG, it) }
            Harness.PATH_PONG -> onThread { parse(event.data)?.let { reply -> reply["id"]?.toLongOrNull()?.let { complete(it, reply, t) } } }
            Harness.PATH_SAMPLE -> onThread { takeSample(event.data) }
            Harness.PATH_GUN -> onThread { adopt(event.data) }
        }
    }

    private val rpc = MessageClient.RpcService { _, _, request ->
        val t2 = now()
        Tasks.forResult(answer(request, Primitive.RPC, t2) ?: ByteArray(0))
    }

    /** The responder's end: [t2] was read on arrival, t3 is read here, and the reply carries both. */
    private fun answer(data: ByteArray?, primitive: Primitive, t2: Long): ByteArray? {
        val ping = parse(data)
        val t3 = now()
        if (ping == null) {
            log.write("ERROR", "what" to "ping", "reason" to "unparsed")
            return null
        }
        return try {
            val reply = Record("PONG", linkedMapOf("id" to ping.text("id"), "t2" to "$t2", "t3" to "$t3")).bytes()
            log.write(
                "RESP",
                "req" to ping.text("side"),
                "run" to ping.text("run"),
                "id" to ping.text("id"),
                "prim" to primitive.wire,
                "t1" to ping.text("t1"),
                "t2" to t2,
                "t3" to t3,
            )
            reply
        } catch (e: MalformedRecord) {
            log.write("ERROR", "what" to "ping", "reason" to e.message)
            null
        }
    }

    /** The requester's copy of a round this side answered. */
    private fun takeSample(data: ByteArray?) {
        val record = parse(data) ?: return
        val round = try {
            Round.of(record)
        } catch (e: MalformedRecord) {
            log.write("ERROR", "what" to "sample", "reason" to e.message)
            return
        }
        if (!keep(round)) return
        log.write("SAMPLE", round.fields().apply { put("rtt_us", "${round.rttNs / 1000}") })
        retranslate()
        publish()
    }

    // --- the peer and the link ------------------------------------------------------------------

    private fun refreshPeer(then: (Node?) -> Unit) {
        capabilities.getCapability(Harness.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnCompleteListener(onEngine) { task ->
                if (task.isSuccessful) {
                    notePeer(task.result.nodes, "query")
                } else {
                    log.write("PEER", "peer" to short(peer?.id), "why" to "query", "error" to reason(task.exception))
                }
                then(peer)
            }
    }

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
        onThread {
            notePeer(info.nodes, "changed")
            publish()
        }
    }

    private fun notePeer(nodes: Set<Node>, why: String) {
        val others = nodes.filter { it.id != localId }
        val chosen = others.firstOrNull { it.isNearby } ?: others.firstOrNull()
        if (chosen?.id != peer?.id || chosen?.isNearby != peer?.isNearby) {
            log.write("PEER", "peer" to short(chosen?.id), "near" to chosen?.isNearby.wire(), "reachable" to others.size, "why" to why)
        }
        peer = chosen
    }

    private fun watchBluetooth() {
        bt = try {
            when (app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled) {
                true -> "on"
                false -> "off"
                null -> "none"
            }
        } catch (e: SecurityException) {
            "unreadable"
        }
        log.write("LINK", "bt" to bt, "why" to "start")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                handler.post {
                    bt = when (state) {
                        BluetoothAdapter.STATE_ON -> "on"
                        BluetoothAdapter.STATE_OFF -> "off"
                        BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
                        BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
                        else -> "state_$state"
                    }
                    log.write("LINK", "bt" to bt, "why" to "broadcast")
                    publish()
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        // A protected broadcast: only the system can send it, so exporting the receiver admits no one.
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
    }

    // --- the gun --------------------------------------------------------------------------------

    private fun arm() {
        val gunMs = SystemClock.elapsedRealtime() + Harness.GUN_LEAD_MS
        val armed = Gun("${side.wire}-${++gunsArmed}", side, gunMs, gunMs, 0L)
        gun = armed
        log.write("GUN", "event" to "armed", "gun" to armed.id, "src" to side.wire, "src_ms" to gunMs, "own_ms" to gunMs)
        schedule(armed)
        refreshPeer { node ->
            if (node == null) {
                log.write("GUN", "event" to "unsent", "gun" to armed.id, "reason" to "no-peer")
            } else {
                val payload = Record("GUN", linkedMapOf("gun" to armed.id, "src" to side.wire, "src_ms" to "$gunMs")).bytes()
                messages.sendMessage(node.id, Harness.PATH_GUN, payload)
                    .addOnSuccessListener(onEngine) { log.write("GUN", "event" to "sent", "gun" to armed.id) }
                    .addOnFailureListener(onEngine) { log.write("GUN", "event" to "unsent", "gun" to armed.id, "reason" to reason(it)) }
            }
        }
        publish()
    }

    /** The other device armed a gun: hold it on this clock, through the product's own translation. */
    private fun adopt(data: ByteArray?) {
        val record = parse(data) ?: return
        val id: String
        val src: Side
        val srcMs: Long
        try {
            id = record.text("gun")
            src = Side.of(record.text("src"))
            srcMs = record.long("src_ms")
        } catch (e: MalformedRecord) {
            log.write("ERROR", "what" to "gun", "reason" to e.message)
            return
        }
        val t = translate(srcMs, src)
        val fields = linkedMapOf("event" to "adopted", "gun" to id, "src" to src.wire, "src_ms" to "$srcMs", "n" to "${rounds.size}", "result" to name(t))
        if (t is GunTranslation.InBudget) {
            val adopted = Gun(id, src, srcMs, t.gunMs, t.errorBoundMs)
            gun = adopted
            fields["own_ms"] = "${t.gunMs}"
            fields["bound_ms"] = "${t.errorBoundMs}"
            log.write("GUN", fields)
            schedule(adopted)
        } else {
            log.write("GUN", fields)
        }
        publish()
    }

    /**
     * What a fresh translation says about the held gun now that another round is in. The held gun does
     * not move: the harness keeps what it adopted and logs how far a fresh exchange would put it.
     */
    private fun retranslate() {
        val g = gun ?: return
        if (g.src == side || g.fired) return
        val t = translate(g.srcMs, g.src)
        val fields = linkedMapOf("event" to "retranslated", "gun" to g.id, "n" to "${rounds.size}", "result" to name(t))
        if (t is GunTranslation.InBudget) {
            fields["own_ms"] = "${t.gunMs}"
            fields["bound_ms"] = "${t.errorBoundMs}"
            fields["moved_ms"] = "${t.gunMs - g.ownMs}"
        }
        log.write("GUN", fields)
    }

    private fun schedule(g: Gun) {
        handler.postDelayed({ fire(g) }, (g.ownMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    private fun fire(g: Gun) {
        if (g.fired) return
        g.fired = true
        val atMs = SystemClock.elapsedRealtime()
        log.write("GUN", "event" to "fired", "gun" to g.id, "own_ms" to g.ownMs, "late_ms" to atMs - g.ownMs, "current" to (gun === g).wire())
        if (gun === g) flashUntilMs = atMs + 3_000L
        publish()
    }

    /** [atMs], read on [clock], onto the other device's clock: #217's translateGun, unchanged. */
    private fun translate(atMs: Long, clock: Side): GunTranslation = translateGun(
        gunMs = atMs,
        from = if (clock == Side.PHONE) ExchangeClock.REQUESTER else ExchangeClock.RESPONDER,
        samples = rounds.map { it.phoneFrameMs() },
        maxDriftPpm = Harness.STATED_DRIFT_PPM,
        budgetMs = Harness.NO_BUDGET,
    )

    // --- state ----------------------------------------------------------------------------------

    private val stateTick = object : Runnable {
        override fun run() {
            logState()
            watchdog()
            handler.postDelayed(this, Harness.STATE_EVERY_MS)
        }
    }

    /**
     * The offset this side holds right now, with its bound aged to this instant, and no exchange
     * needed to say it. Logged every few seconds whether rounds are flowing or not, which is what shows
     * each side keeping its last-known offset through a dropped link.
     */
    private fun logState() {
        val nowMs = SystemClock.elapsedRealtime()
        val fields = linkedMapOf(
            "auto" to auto.wire(),
            "bt" to bt,
            "peer" to short(peer?.id),
            "near" to peer?.isNearby.wire(),
            "samples" to "${rounds.size}",
            "ok" to "$okCount",
            "fail" to "$failCount",
            "age_ms" to if (lastRoundAtMs == 0L) "-" else "${nowMs - lastRoundAtMs}",
        )
        val t = translate(nowMs, side)
        fields["result"] = name(t)
        if (t is GunTranslation.InBudget) {
            fields["offset_ms"] = "${offsetOf(nowMs, t.gunMs)}"
            fields["bound_ms"] = "${t.errorBoundMs}"
        }
        gun?.let { g ->
            fields["gun"] = g.id
            fields["gun_own_ms"] = "${g.ownMs}"
            fields["gun_left_ms"] = "${g.ownMs - nowMs}"
            fields["gun_fired"] = g.fired.wire()
        }
        log.write("STATE", fields)
    }

    /** A burst whose peer query never came back would stall for good. Restart it instead. */
    private fun watchdog() {
        if (burstActive && inFlight == null && SystemClock.elapsedRealtime() - lastProgressMs > STALL_MS) {
            log.write("ERROR", "what" to "burst", "reason" to "stalled")
            burstActive = false
            if (auto) beginBurst()
        }
    }

    private fun publish() {
        val nowMs = SystemClock.elapsedRealtime()
        val lines = ArrayList<String>()
        lines += "peer ${short(peer?.id)} " + when (peer?.isNearby) {
            true -> "near"
            false -> "cloud"
            null -> ""
        }
        lines += "bt $bt  auto ${if (auto) "ON" else "off"}"
        lines += "ok $okCount  fail $failCount"
        lastRound?.let { lines += String.format(Locale.US, "rtt %.1f ms %s", it.rttNs / 1e6, it.primitive.wire) }
        val t = translate(nowMs, side)
        lines += if (t is GunTranslation.InBudget) "± ${t.errorBoundMs} ms  n=${rounds.size}" else "${name(t)}  n=${rounds.size}"
        gun?.let { g ->
            lines += when {
                g.fired -> "gun ${g.id} FIRED"
                g.src == side -> String.format(Locale.US, "gun %s %.0f s", g.id, (g.ownMs - nowMs) / 1000.0)
                else -> String.format(Locale.US, "gun %s %.0f s ±%d", g.id, (g.ownMs - nowMs) / 1000.0, g.boundMs)
            }
        }
        snapshot = Snapshot(lines.joinToString("\n"), auto, flashUntilMs)
    }

    // --- helpers --------------------------------------------------------------------------------

    /** θ, the watch's clock minus the phone's, from an instant on this device and its translation. */
    private fun offsetOf(ownMs: Long, otherMs: Long): Long = if (side == Side.PHONE) otherMs - ownMs else ownMs - otherMs

    private fun onThread(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) block() else handler.post(block)
    }

    private fun now(): Long = SystemClock.elapsedRealtimeNanos()

    private fun parse(data: ByteArray?): Record? = data?.let { Record.parse(String(it, Charsets.UTF_8)) }

    private fun Record.bytes(): ByteArray = format().toByteArray(Charsets.UTF_8)

    private fun error(what: String, e: Exception?) = log.write("ERROR", "what" to what, "reason" to reason(e))

    private fun reason(e: Exception?): String = when (e) {
        null -> "unknown"
        is ApiException -> "api${e.statusCode}"
        else -> e.javaClass.simpleName
    }

    private fun name(t: GunTranslation): String = when (t) {
        is GunTranslation.InBudget -> "in_budget"
        is GunTranslation.OutOfBudget -> "out_of_budget"
        GunTranslation.NoSamples -> "no_samples"
        GunTranslation.Inconsistent -> "inconsistent"
    }

    /** Node ids are opaque per-pairing tokens. Six characters tell two apart, and the log is public. */
    private fun short(id: String?): String = id?.take(6) ?: "-"

    private fun versionName(): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "-"
    } catch (e: PackageManager.NameNotFoundException) {
        "-"
    }

    companion object {
        private const val STALL_MS = 15_000L

        @Volatile
        private var instance: Engine? = null

        fun get(context: Context): Engine = instance ?: synchronized(this) {
            instance ?: Engine(context.applicationContext).also {
                instance = it
                it.start()
            }
        }
    }
}
