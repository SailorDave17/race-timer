package com.racetimer.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.racetimer.shared.JoinGun
import com.racetimer.shared.PairContest
import com.racetimer.shared.PairEvent
import com.racetimer.shared.PairJoin
import com.racetimer.shared.PairRace
import com.racetimer.shared.PairRaceBook
import com.racetimer.shared.PairRaceHost
import com.racetimer.shared.PairSetup
import com.racetimer.shared.PairSetupHost
import com.racetimer.shared.PairStarts
import com.racetimer.shared.SetupChoice
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Start on either device (#220), and the race's controls and pre-start setup on either device
 * (#221), on the main thread: the one [PairStarts] and [PairSetup] this process runs, wired to the
 * Data Layer on one side and to whichever service runs the race on the other.
 *
 * The rules themselves are `:shared`'s, where the JVM tests drive them across two simulated devices.
 * What is here is the Android residue, and it is one copy for both apps (D1's reason for this module):
 *
 * - **The link** is [WearablePairLink]. What the peer sends arrives on its thread and is posted here;
 *   what this device does goes back the other way through its announce calls.
 * - **The race** is the app's service. It [attach]es itself as a [RaceService] when it is created,
 *   and tells this object when it [startedHere], [restored], [syncedHere], [endedHere] or [ended] a
 *   race. A peer's Sync or End Race is carried out by that service directly, on the main thread, so
 *   it runs with everything a local Sync or End Race runs with — and a control for a device with no
 *   service is a control for a device with no race, which makes it stale.
 * - **A join** is handed to the [Launcher] the Application [install]ed, which turns it into a service
 *   intent. The app's own `onStartCommand` does the joining, through the same path a Start takes, so
 *   a joined race is armed, persisted and held awake exactly as a tapped one is.
 * - **The setup** is read from and written to the [SetupStore] the Application installed — the
 *   app's own remembered pick and lead-in alert — and put on screen by whoever listens
 *   ([addSetupListener]): the activity, which holds the selection on the watch and shows it on the
 *   phone.
 *
 * **Everything here runs on the main thread**, the thread the services and activities already live
 * on. The only other thread is the link's, and nothing crosses from it except a posted message.
 */
class PairRaces private constructor(private val app: Context, private val link: WearablePairLink) {

    /**
     * Turns a join into a started service. Returns false when it could not be started — the platform
     * refusing a foreground start because nothing of this app is on screen is the expected case — so
     * nothing records a race that is not running.
     *
     * [raceRunning] says whether a race is already running here. A service already in the foreground
     * takes the join as a plain `startService`; one that is not needs `startForegroundService`, and
     * with it a `startForeground` it cannot skip.
     */
    fun interface Launcher {
        fun launch(context: Context, join: PairJoin, raceRunning: Boolean): Boolean
    }

    /**
     * Where an app keeps its pre-start setup (#221): the pick it opens on and the alert its lead-in
     * picker opens on. Each app has its own preferences file and keys, so each supplies this.
     */
    interface SetupStore {
        /** What the app will open on, from its own memory, with its own defaults filled in. */
        fun load(context: Context): SetupChoice

        /** Remember [choice] as a pick made here would be remembered. */
        fun save(context: Context, choice: SetupChoice)
    }

    /**
     * The app's service, as the pair needs it (#221). Implemented by each app's service shell and
     * attached for its life. Every call is on the main thread.
     */
    interface RaceService {
        /** Whether a race is running here: counting down, or counting up past the gun. */
        fun raceRunning(): Boolean

        /** Whether the pre-start screen's state holds: no race running and none on screen. */
        fun atPreStart(): Boolean

        /**
         * The peer's Sync, on this clock: move the countdown's gun, with everything a Sync here does
         * around it — the cues re-aimed, the wake lock re-sized (#126), the race persisted. False
         * when there is no countdown to move.
         */
        fun moveGun(gun: JoinGun): Boolean

        /**
         * The peer's End Race: end the race-manager race here at [elapsedMs], winding down what a
         * local End Race winds down. Returns the elapsed time now frozen, or null with nothing to end.
         */
        fun endRaceAt(elapsedMs: Long): Long?

        /** Put the peer's setup on whatever this service holds of the pre-start screen. */
        fun applySetup(choice: SetupChoice) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var service: RaceService? = null
    private val contestListeners = CopyOnWriteArrayList<(PairContest?) -> Unit>()
    private val setupListeners = CopyOnWriteArrayList<(SetupChoice) -> Unit>()
    private val eventListeners = CopyOnWriteArrayList<(PairEvent) -> Unit>()
    private val book = PairRaceBook()

    private val setup = PairSetup(
        book = book,
        link = link,
        host = object : PairSetupHost {
            override fun atPreStart(): Boolean = service?.atPreStart() ?: true

            override fun apply(choice: SetupChoice) {
                installedStore?.save(app, choice)
                service?.applySetup(choice)
                setupListeners.forEach { it(choice) }
            }
        },
        clock = SystemMonotonicClock,
        console = installedConsole,
        initial = installedStore?.load(app),
        log = { Log.i(TAG, it) },
    )

    private val starts = PairStarts(
        book = book,
        link = link,
        host = object : PairRaceHost {
            override fun raceRunning(): Boolean = service?.raceRunning() == true

            override fun join(join: PairJoin): Boolean {
                val launcher = installedLauncher ?: return false
                return launcher.launch(app, join, raceRunning())
            }

            override fun moveGun(gun: JoinGun): Boolean = service?.moveGun(gun) == true

            override fun endRaceAt(elapsedMs: Long): Long? = service?.endRaceAt(elapsedMs)
        },
        clock = SystemMonotonicClock,
        console = installedConsole,
        onContest = { contest -> contestListeners.forEach { it(contest) } },
        log = { Log.i(TAG, it) },
        setup = setup,
        onEvent = { event -> eventListeners.forEach { it(event) } },
    )

    init {
        link.onPeerStart = { start -> main.post { starts.onPeerStart(start) } }
        link.onPeerSync = { sync -> main.post { starts.onPeerSync(sync) } }
        link.onPeerEnd = { end -> main.post { starts.onPeerEnd(end) } }
        link.onPeerSetup = { peerSetup -> main.post { setup.onPeerSetup(peerSetup) } }
        link.onPeerNearby = { main.post { setup.onPeerNearby() } }
    }

    /** The conflict waiting for the officer on the phone, or null. Always null on the watch. */
    val contest: PairContest? get() = starts.contest

    /** The service that runs races. Detach on destroy. */
    fun attach(service: RaceService) {
        this.service = service
    }

    fun detach() {
        service = null
    }

    /** A race was started here from the top: tell the peer, and measure until its gun. */
    fun startedHere(sequenceId: String, gunMs: Long) = starts.startedHere(sequenceId, gunMs)

    /** A race was resumed here after a process death, and is not announced. */
    fun restored() = starts.restored()

    /** The race here is over. */
    fun ended() = starts.ended()

    /** A Sync taken here moved the gun to [gunMs] on this clock: the peer moves too (#221). */
    fun syncedHere(gunMs: Long) = starts.syncedHere(gunMs)

    /** An End Race taken here froze the race [elapsedMs] past its gun: the peer ends too (#221). */
    fun endedHere(elapsedMs: Long) = starts.endedHere(elapsedMs)

    /** The officer changed the pre-start setup here: the peer's screen follows (#221). */
    fun picked(choice: SetupChoice) = setup.pickedHere(choice)

    /** The officer chose [race] from the [contest]. */
    fun choose(race: PairRace) = starts.choose(race)

    /** Receives every change to the [contest], on the main thread. */
    fun addContestListener(listener: (PairContest?) -> Unit) {
        contestListeners += listener
    }

    fun removeContestListener(listener: (PairContest?) -> Unit) {
        contestListeners -= listener
    }

    /** Receives each setup the peer's put on this device's pre-start screen, on the main thread (#221). */
    fun addSetupListener(listener: (SetupChoice) -> Unit) {
        setupListeners += listener
    }

    fun removeSetupListener(listener: (SetupChoice) -> Unit) {
        setupListeners -= listener
    }

    /** Receives what the peer did here that the officer is owed a word about, on the main thread (#221). */
    fun addEventListener(listener: (PairEvent) -> Unit) {
        eventListeners += listener
    }

    fun removeEventListener(listener: (PairEvent) -> Unit) {
        eventListeners -= listener
    }

    companion object {
        private const val TAG = "RaceTimerPairStart"

        @Volatile
        private var installedLauncher: Launcher? = null

        @Volatile
        private var installedStore: SetupStore? = null

        @Volatile
        private var installedConsole = false

        /**
         * Say how this app joins a race, where it keeps its pre-start setup, and whether it is the
         * console: the phone, whose clock orders every start and pick, and which puts a conflict in
         * front of the officer. Called from the Application's `onCreate`, before anything can build
         * the link, and cheap: it builds nothing itself.
         */
        fun install(console: Boolean, store: SetupStore, launcher: Launcher) {
            installedConsole = console
            installedStore = store
            installedLauncher = launcher
        }

        @Volatile
        private var instance: PairRaces? = null

        /** The process's one instance, built with its link on first use. Main thread only. */
        fun get(context: Context): PairRaces = instance ?: synchronized(this) {
            instance ?: PairRaces(context.applicationContext, WearablePairLink.get(context)).also { instance = it }
        }
    }
}
