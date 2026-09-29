package com.racetimer.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.racetimer.shared.PairContest
import com.racetimer.shared.PairJoin
import com.racetimer.shared.PairRace
import com.racetimer.shared.PairRaceBook
import com.racetimer.shared.PairRaceHost
import com.racetimer.shared.PairStarts
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Start on either device (#220), on the main thread: the one [PairStarts] this process runs, wired
 * to the Data Layer on one side and to whichever service runs the race on the other.
 *
 * The rule itself is `:shared`'s, where the JVM tests drive it across two simulated devices. What is
 * here is the Android residue, and it is one copy for both apps (D1's reason for this module):
 *
 * - **The link** is [WearablePairLink]. Starts from the peer arrive on its thread and are posted
 *   here; starts this device makes go back the other way through its [WearablePairLink.announce].
 * - **The race** is the app's service. It [attach]es a reading of whether a race is running when it
 *   is created, and tells this object when it [startedHere], [restored] or [ended] one.
 * - **A join** is handed to the [Launcher] the Application [install]ed, which turns it into a service
 *   intent. The app's own `onStartCommand` does the joining, through the same path a Start takes, so
 *   a joined race is armed, persisted and held awake exactly as a tapped one is.
 *
 * **Everything here runs on the main thread**, the thread the services and activities already live
 * on. The only other thread is the link's, and nothing crosses from it except a posted start.
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

    private val main = Handler(Looper.getMainLooper())
    private var raceRunning: (() -> Boolean)? = null
    private val contestListeners = CopyOnWriteArrayList<(PairContest?) -> Unit>()

    private val starts = PairStarts(
        book = PairRaceBook(),
        link = link,
        host = object : PairRaceHost {
            override fun raceRunning(): Boolean = this@PairRaces.raceRunning?.invoke() == true

            override fun join(join: PairJoin): Boolean {
                val launcher = installedLauncher ?: return false
                return launcher.launch(app, join, raceRunning())
            }
        },
        clock = SystemMonotonicClock,
        console = installedConsole,
        onContest = { contest -> contestListeners.forEach { it(contest) } },
        log = { Log.i(TAG, it) },
    )

    init {
        link.onPeerStart = { start -> main.post { starts.onPeerStart(start) } }
    }

    /** The conflict waiting for the officer on the phone, or null. Always null on the watch. */
    val contest: PairContest? get() = starts.contest

    /** The service that runs races, and how to ask it whether one is running. Detach on destroy. */
    fun attach(raceRunning: () -> Boolean) {
        this.raceRunning = raceRunning
    }

    fun detach() {
        raceRunning = null
    }

    /** A race was started here from the top: tell the peer, and measure until its gun. */
    fun startedHere(sequenceId: String, gunMs: Long) = starts.startedHere(sequenceId, gunMs)

    /** A race was resumed here after a process death, and is not announced. */
    fun restored() = starts.restored()

    /** The race here is over. */
    fun ended() = starts.ended()

    /** The officer chose [race] from the [contest]. */
    fun choose(race: PairRace) = starts.choose(race)

    /** Receives every change to the [contest], on the main thread. */
    fun addContestListener(listener: (PairContest?) -> Unit) {
        contestListeners += listener
    }

    fun removeContestListener(listener: (PairContest?) -> Unit) {
        contestListeners -= listener
    }

    companion object {
        private const val TAG = "RaceTimerPairStart"

        @Volatile
        private var installedLauncher: Launcher? = null

        @Volatile
        private var installedConsole = false

        /**
         * Say how this app joins a race, and whether it is the console: the phone, whose clock orders
         * every start and which puts a conflict in front of the officer. Called from the
         * Application's `onCreate`, before anything can build the link, and cheap: it builds nothing
         * itself.
         */
        fun install(console: Boolean, launcher: Launcher) {
            installedConsole = console
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
