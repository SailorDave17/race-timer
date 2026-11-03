package com.racetimer.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.MonotonicClock
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.TimerListener
import com.racetimer.shared.WallClock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The companion link on a device, against a real second app (#294 criteria 1 to 5).
 *
 * The second app is `harness/companion-link`'s stand-in: the companion's own package and link
 * service, answering ADR 004's wire, built and installed by `harness/companion-link/run.sh` in three
 * states, one at a time:
 *
 * - **TRUSTED**: signed with a key this build pins (`-PcompanionDebugCerts`);
 * - **IMPOSTER**: the same APK re-signed with a key it does not, so only the certificate differs;
 * - **ABSENT**: not installed.
 *
 * Each method is for one state (two for the timing method) and asserts that state first, so a
 * method run against the wrong install fails rather than passing on nothing. The script names the
 * methods per state; a whole-class run fails by design.
 *
 * What only a device shows, and the unit tests cannot: that the platform resolves and binds the
 * companion across two uids, that `<queries>` makes it visible, that `hasSigningCertificate` refuses
 * an imposter, and that the real binder carries the events. The link here is the production class
 * on the production pins and transport; only its outbox file is the test's own.
 */
class CompanionLinkDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val outboxFile = File(context.cacheDir, "companion-device-test-outbox.json")

    @Before
    fun setUp() {
        outboxFile.delete()
    }

    // --- The stand-in's state, and its control channel ----------------------------------------

    private fun state(): String {
        val installed = try {
            context.packageManager.getPackageInfo(CompanionContract.PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        return when {
            !installed -> ABSENT
            CompanionPins.forBuild().trustedBy(context.packageManager) -> TRUSTED
            else -> IMPOSTER
        }
    }

    /**
     * Assert the stand-in is in [expected], and, where the script said which state it installed
     * (`-e stand_in`), that it is in exactly that one.
     *
     * The second half exists because of the timing method, which runs in two states. Asked only
     * "TRUSTED or ABSENT?", its TRUSTED run passed with the companion made invisible (no `<queries>`)
     * by reading ABSENT and running the other arm, so "with and without a receiver" became "without,
     * twice" with every line green (#294's mutation M7, predicted 4 red, measured 3).
     */
    private fun requireState(vararg expected: String) {
        val actual = state()
        assertTrue("this method needs the stand-in ${expected.toList()}, and it is $actual", actual in expected)
        val installed = InstrumentationRegistry.getArguments().getString("stand_in")
        assertEquals("the script installed the stand-in $installed, and this test reads it as $actual", installed ?: actual, actual)
    }

    /** One control transaction on the stand-in, bound directly. The control channel trusts anyone. */
    private fun control(code: Int, argument: String? = null): String? {
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val intent = Intent().setComponent(ComponentName(CompanionContract.PACKAGE, CompanionContract.SERVICE))
        assertTrue("the stand-in can be bound", context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("the stand-in connected", latch.await(10, TimeUnit.SECONDS))
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(STUB_CONTROL)
                argument?.let(data::writeString)
                binder!!.transact(code, data, reply, 0)
                reply.readException()
                return if (code == TX_DUMP) reply.readString() else null
            } finally {
                data.recycle()
                reply.recycle()
            }
        } finally {
            context.unbindService(connection)
        }
    }

    /** Everything the stand-in has received: the caller's uid and the event, in arrival order. */
    private fun received(): List<Pair<Int, JSONObject>> {
        val all = JSONArray(control(TX_DUMP))
        return (0 until all.length()).map { i ->
            val entry = all.getJSONObject(i)
            entry.getInt("uid") to JSONObject(entry.getString("json"))
        }
    }

    private fun awaitReceived(count: Int, timeoutMs: Long = 15_000L): List<Pair<Int, JSONObject>> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var got = received()
        while (got.size < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(200L)
            got = received()
        }
        return got
    }

    // --- The link under test ------------------------------------------------------------------

    private fun newLink(): CompanionLinkEmitter {
        val worker = HandlerLinkWorker()
        return CompanionLinkEmitter(
            transport = BinderCompanionTransport(context, CompanionPins.forBuild(), worker.executor),
            openOutbox = { CompanionOutbox(outboxFile) },
            worker = worker,
            wallClock = WallClock { System.currentTimeMillis() },
        )
    }

    private class StepClock : MonotonicClock {
        @Volatile var nowMs = 0L
        override fun elapsedMs(): Long = nowMs
    }

    /** [sequence] from Start to its gun as fast as the CPU goes: every cue on its own boundary. */
    private fun runToTheGun(link: CompanionLink, sequence: RaceSequence) {
        val clock = StepClock()
        val runner = PhoneRaceRunner(clock = clock, companionLink = link)
        runner.select(sequence)
        runner.start()
        sequence.cues.map { sequence.totalMs - it.offsetMs }.distinct().sorted().forEach { at ->
            clock.nowMs = at
            runner.tick()
        }
    }

    private fun kinds(events: List<Pair<Int, JSONObject>>) = events.map { it.second.getString("kind") }

    // --- TRUSTED --------------------------------------------------------------------------------

    /** Criteria 1 and 5, and 4's positive half: a pinned companion receives the sequence, in order. */
    @Test
    fun trustedCompanionReceivesTheSequenceInOrder() {
        requireState(TRUSTED)
        control(TX_RESET)

        runToTheGun(newLink(), BuiltInSequences.usSailing)
        val got = awaitReceived(7)
        Thread.sleep(1_000L)

        val all = received()
        assertEquals("exactly the expected events, nothing more", got.size, all.size)
        assertEquals(listOf("sequence_start", "signal", "signal", "signal", "signal", "signal", "start"), kinds(all))
        assertEquals(
            listOf("warning", "preparatory", "3 minutes", "2 minutes", "one_minute"),
            all.filter { it.second.has("signal") }.map { it.second.getString("signal") },
        )
        val sequenceId = all.first().second.getString("id")
        assertTrue("every event names the sequence_start", all.all { it.second.getString("sequence") == sequenceId })
        assertEquals("every id unique", all.size, all.map { it.second.getString("id") }.toSet().size)
        assertTrue("sent by race-timer's own uid", all.all { it.first == Process.myUid() })
        assertTrue("no fleet, class or race id", all.none { e -> listOf("fleet", "class", "race").any { e.second.has(it) } })
        assertTrue("everything taken leaves the outbox", CompanionOutbox(outboxFile).isEmpty)
    }

    /** ADR 004 decision 4 over the real binder: no_core is resent under the same id. */
    @Test
    fun noCoreIsResentUnderTheSameId() {
        requireState(TRUSTED)
        control(TX_RESET)
        control(TX_SCRIPT, "no_core,no_core")

        val link = newLink()
        link.sequenceStarted(BuiltInSequences.club)
        val got = awaitReceived(3, timeoutMs = 20_000L)
        link.sequenceEnded()

        assertEquals(3, got.size)
        assertEquals("one event, three times", 1, got.map { it.second.getString("id") }.toSet().size)
        Thread.sleep(500L)
        assertTrue(CompanionOutbox(outboxFile).isEmpty)
    }

    // --- IMPOSTER -------------------------------------------------------------------------------

    /** Criterion 4: a companion package signed by an unpinned key is never bound, so never sent to. */
    @Test
    fun imposterIsSentNothing() {
        requireState(IMPOSTER)
        control(TX_RESET)

        runToTheGun(newLink(), BuiltInSequences.club)
        Thread.sleep(3_000L)

        assertEquals("the imposter received nothing", emptyList<Pair<Int, JSONObject>>(), received())
        assertFalse("and nothing was kept for it", outboxFile.exists())
    }

    // --- ABSENT ---------------------------------------------------------------------------------

    /** Criterion 3: with no companion a race runs, writes nothing and does not crash. */
    @Test
    fun absentCompanionChangesNothing() {
        requireState(ABSENT)

        runToTheGun(newLink(), BuiltInSequences.usSailing)
        Thread.sleep(2_000L)

        assertFalse(outboxFile.exists())
    }

    // --- TRUSTED and ABSENT: criterion 2 ----------------------------------------------------------

    /**
     * Criterion 2: cue timing with the link on the real cue path, run once with the companion
     * installed and once without. A Custom 1:00 race in real time, its cues dispatched by the
     * production scheduler on the main looper, the link sending as it goes.
     *
     * Lateness is the day journal's measure, `offsetMs - remainingMs` on the engine's own anchor,
     * taken in a listener registered after the runner's, so it is read after the link's call and
     * includes that call's cost. The bar is #10's: every cue within 250 ms (docs/timing-accuracy.md).
     */
    @Test
    fun cueTimingHoldsWithTheLink() {
        requireState(TRUSTED, ABSENT)
        val installed = state() == TRUSTED
        if (installed) control(TX_RESET)

        val sequence = BuiltInSequences.custom(1)
        val lateness = mutableListOf<Long>()
        lateinit var runner: PhoneRaceRunner
        instrumentation.runOnMainSync {
            runner = PhoneRaceRunner(cueScheduler = HandlerCueScheduler(), companionLink = newLink())
            runner.engine.addListener(object : TimerListener {
                override fun onCue(cue: SequenceCue) {
                    lateness += cue.offsetMs - runner.engine.remainingMs
                }
                override fun onGun() {}
                override fun onTick(remainingMs: Long) {}
                override fun onSync(snappedToMs: Long) {}
            })
            runner.select(sequence)
            runner.start()
        }
        Thread.sleep(sequence.totalMs + 3_000L)

        val measured = mutableListOf<Long>()
        instrumentation.runOnMainSync { measured += lateness }
        val worst = measured.maxOf { abs(it) }
        Log.i(TIMING_TAG, "companion=${if (installed) "installed" else "absent"} cues=${measured.size} worstMs=$worst lateMs=$measured")

        assertEquals("every cue fired", sequence.cues.size, measured.size)
        assertTrue("worst cue $worst ms late, past #10's 250 ms", worst <= 250L)
        if (installed) {
            assertEquals(listOf("sequence_start", "signal", "start"), kinds(awaitReceived(3)))
        } else {
            assertFalse(outboxFile.exists())
        }
    }

    private companion object {
        const val TRUSTED = "TRUSTED"
        const val IMPOSTER = "IMPOSTER"
        const val ABSENT = "ABSENT"
        const val TIMING_TAG = "CompanionLinkTiming"

        // The stand-in's control protocol: harness/companion-link/stub's LinkService, repeated.
        const val STUB_CONTROL = "racetimer.harness.CompanionStubControl"
        const val TX_DUMP = IBinder.FIRST_CALL_TRANSACTION + 100
        const val TX_SCRIPT = IBinder.FIRST_CALL_TRANSACTION + 101
        const val TX_RESET = IBinder.FIRST_CALL_TRANSACTION + 102
    }
}
