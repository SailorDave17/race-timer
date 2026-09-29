package com.racetimer.shared

import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * How recently a cue may have come due and still sound when a device joins the other's race (#220).
 *
 * The start reaches the joining device tens to hundreds of milliseconds after the other anchored its
 * gun — #218 measured Bluetooth round trips of 144–198 ms at the median and 330–650 ms at p90, so one
 * way is about half that — and the first cue of every sequence is due at the very instant of the
 * anchor. Without a grace the joining device would drop its warning signal every time. Half a second
 * covers the p90 one-way leg; a start that took longer than that sounds nothing it has missed, because
 * a signal seconds late is a wrong signal.
 *
 * Applied only when the joining device was idle. A device that is switching from a race of its own
 * sounded that race's first cue already, and a grace would sound it twice (see [PairStarts]).
 */
const val JOIN_LATE_CUE_GRACE_MS: Long = 500L

/**
 * How one start is ordered against another (#220): [stamp] first, [raceId] to break a tie.
 *
 * **The start tapped last wins** — the owner's rule at #220's pickup, 2026-09-28. [stamp] is the
 * instant of the tap **on the console's clock** — the phone's `elapsedRealtime` — raised past every
 * start the starting device has already seen ([PairRaceBook]), so a start made after seeing another
 * always outranks it. The phone reads its own clock; the watch moves its tap onto the phone's through
 * the offset the link measured. Two starts that cross in flight are therefore ordered as they were
 * tapped, to within the link's bound — tens of milliseconds.
 *
 * *Not the wall clock, measured.* This was the tap's time of day until the owner's pair was tried on
 * hardware: the SM-R925U's wall clock ran 3.2–3.4 s behind the SM-S918U's, with automatic time on
 * both, so every crossing went to the phone however late its tap — "Watch started too, 0.1 s later —
 * both following the phone". A crossing can only happen inside one message's trip, about 0.1–0.3 s,
 * so a skew ten times that decided every one of them.
 *
 * **Both devices compare the same two keys, carried in the messages, so they always agree.** That
 * is why the stamp is fixed by the device that tapped, rather than each device placing the other's
 * tap on its own clock: two translations carry two error bounds, and two taps closer together than
 * the bounds would be ordered one way on the wrist and the other on the console. Two devices that
 * disagree about the winner each switch to the other's race, and never converge.
 */
data class RaceKey(val stamp: Long, val raceId: Long) : Comparable<RaceKey> {
    override fun compareTo(other: RaceKey): Int =
        compareValuesBy(this, other, RaceKey::stamp, RaceKey::raceId)
}

/**
 * A gun on this device's clock, and the worst case it can be out by: zero for a gun this device
 * anchored itself, the translation's bound for one that came across the link, and **null when the
 * link had no rounds to translate through** — the gun is then anchored to the start's arrival and
 * nothing bounds it (see `PairLink`).
 */
data class JoinGun(val gunMs: Long, val errorBoundMs: Long?) {

    /** Within D2's budget: the only gun a device runs without saying so (#220 AC 5). */
    val inBudget: Boolean get() = errorBoundMs != null && errorBoundMs <= PAIR_SKEW_BUDGET_MS
}

/** A race this device knows: its order, what it runs, its gun here, and whether it was tapped here. */
data class PairRace(
    val key: RaceKey,
    val sequenceId: String,
    val gun: JoinGun,
    val startedHere: Boolean,
)

/** A start the nearby peer announced, its gun already moved onto this device's clock. */
data class PeerStart(val key: RaceKey, val sequenceId: String, val gun: JoinGun)

/** What a device does with the peer's start, decided by [PairRaceBook.decide]. */
sealed interface StartVerdict {

    /**
     * Run [race]. [displaces] is whether a race was running here — [replaced], when this device knows
     * which, and null for one it cannot name (a race restored after a process death carries no key).
     */
    data class Join(val race: PairRace, val displaces: Boolean, val replaced: PairRace?) : StartVerdict

    /** Keep [mine], which outranks [beaten], and send it again so the peer converges on it. */
    data class Keep(val mine: PairRace, val beaten: PairRace) : StartVerdict

    /** A copy of a start already taken, or an older copy of a race already known. */
    data object Ignore : StartVerdict
}

/**
 * Every race this device has started or been told of, and the one it is running (#220).
 *
 * Pure bookkeeping, with no idea what an engine is: the caller says whether a race is running, and
 * this answers which start wins. Process-lived in production — it is what remembers that a start the
 * peer sends again is one already taken — and so it forgets on a process death. A race restored
 * after one is **unannounced**: it has no key here, and yields to any start the peer sends, because
 * a device that cannot say when its race was tapped cannot claim it was tapped last.
 */
class PairRaceBook(
    private val newRaceId: () -> Long = { Random.nextLong() },
) {

    private var lastStamp = Long.MIN_VALUE
    private val known = LinkedHashMap<Long, PairRace>()

    /** The race this device last started or joined, while it has not been told the race is over. */
    var current: PairRace? = null
        private set

    /**
     * A race this device just started, with its gun on this clock. [consoleNowMs] is this instant on
     * the console's clock (see [RaceKey]), or null when the link has no measure of it — a watch with
     * no rounds — and the start is then ordered just after the last one this device saw.
     */
    fun startedHere(sequenceId: String, gunMs: Long, consoleNowMs: Long?): PairRace {
        val race = PairRace(RaceKey(nextStamp(consoleNowMs), newRaceId()), sequenceId, JoinGun(gunMs, 0L), startedHere = true)
        remember(race)
        current = race
        return race
    }

    /**
     * [race] again, ordered after everything seen: the same race id and gun under a new stamp, so it
     * outranks the start that beat it. How the officer's choice on the phone overturns the rule.
     */
    fun reasserted(race: PairRace, consoleNowMs: Long?): PairRace =
        race.copy(key = RaceKey(nextStamp(consoleNowMs), race.key.raceId)).also { remember(it) }

    /**
     * What to do with [start], given whether a race is [running] here.
     *
     * - **Nothing running: join it.** Whatever its order — a device with no race has nothing to
     *   defend, and a start ordered behind one this device ran and finished is still the race the
     *   other device is on.
     * - **A race running: the later start wins.** Join if the peer's outranks this device's, and
     *   otherwise keep this one and send it again.
     *
     * A race id already known keeps the gun it has here: exact for a race started on this device,
     * and its first translation for one joined before, rather than a second translation of this
     * device's own gun back across the link.
     */
    fun decide(start: PeerStart, running: Boolean): StartVerdict {
        val prior = known[start.key.raceId]
        if (prior != null && prior.key >= start.key) return StartVerdict.Ignore
        val race = PairRace(start.key, start.sequenceId, prior?.gun ?: start.gun, startedHere = prior?.startedHere == true)
        remember(race)
        val mine = current
        return when {
            !running -> StartVerdict.Join(race, displaces = false, replaced = null)
            mine == null || race.key > mine.key -> StartVerdict.Join(race, displaces = true, replaced = mine)
            else -> StartVerdict.Keep(mine, beaten = race)
        }
    }

    /** This device is now running [race]. */
    fun joined(race: PairRace) {
        current = race
    }

    /** This device is running a race it cannot name (restored after a process death), or none. */
    fun unannounced() {
        current = null
    }

    private fun nextStamp(consoleNowMs: Long?): Long =
        maxOf(consoleNowMs ?: Long.MIN_VALUE, lastStamp + 1L).also { lastStamp = it }

    private fun remember(race: PairRace) {
        known.remove(race.key.raceId)
        known[race.key.raceId] = race
        while (known.size > MAX_KNOWN) known.remove(known.keys.first())
        lastStamp = maxOf(lastStamp, race.key.stamp)
    }

    private companion object {
        /** A start day is a few dozen races; this is weeks of them, and each is four numbers. */
        const val MAX_KNOWN = 64
    }
}

/** What [PairStarts] needs from the link: `PairLink` in production and in the JVM tests alike. */
interface PairAnnouncer {
    /** Send [race] to the nearby peer, or nothing when there is none. */
    fun announce(race: PairRace)

    /** Keep measuring until [untilMs] on this clock, whatever is on screen. */
    fun holdUntil(untilMs: Long)

    /** Stop the hold [holdUntil] set. */
    fun endHold()

    /**
     * The peer's clock minus this one's, as the link last measured it, or null with no rounds. How
     * the watch places its tap on the console's clock ([RaceKey]); the link's own bound on it is
     * tens of milliseconds, far inside the fraction of a second in which two taps can cross.
     */
    fun peerOffsetMs(): Long?
}

/** What [PairStarts] needs from the app that runs the race. */
interface PairRaceHost {

    /** Whether a race is running here: counting down, or counting up past the gun. */
    fun raceRunning(): Boolean

    /**
     * Run [join] now. False when it could not be started at all — the platform refusing a foreground
     * start from the background, say — so nothing records a race that is not running.
     */
    fun join(join: PairJoin): Boolean
}

/** A race to join: which, what it runs, and how late a cue may be and still sound ([JOIN_LATE_CUE_GRACE_MS]). */
data class PairJoin(val race: PairRace, val sequence: RaceSequence, val lateCueGraceMs: Long)

/**
 * A start from the peer that met a race running here, when the two were tapped on different devices
 * — the moment the owner asked to be told about on the phone, with a choice of either (#220's pickup).
 * [followed] is the race both devices are now running; [other] is the one the rule set aside.
 */
data class PairContest(val followed: PairRace, val other: PairRace) {

    /** The start tapped on this device ([here] true) or on the other — whichever of the two it is. */
    fun startedOn(here: Boolean): PairRace = if (followed.startedHere == here) followed else other
}

/**
 * Start on either device (#220): what each device does when it starts a race, and when the other
 * device's start reaches it. One per process in production, driven on one thread.
 *
 * **Each device runs its own full engine.** A start carries a gun and a sequence and nothing is
 * ticked remotely, so the joining device runs the shared engine locally from the moment it joins, and
 * a later link loss changes nothing about its count by construction.
 *
 * **A device with no peer is the device it always was.** [startedHere] is told about a race the app
 * has already started, after its first cue has sounded, and all it does is send a message the link
 * drops when there is nobody to send it to.
 *
 * @param console whether this device is the console: the phone. Its clock is the one every start is
 *                ordered on ([RaceKey]), and it is the device that puts a [PairContest] in front of
 *                the officer — the owner wanted to be told on the console.
 */
class PairStarts(
    private val book: PairRaceBook,
    private val link: PairAnnouncer,
    private val host: PairRaceHost,
    private val clock: MonotonicClock,
    private val console: Boolean,
    private val onContest: (PairContest?) -> Unit = {},
    private val log: (String) -> Unit = {},
) {

    /** The conflict waiting for the officer, or null. See [PairContest]. */
    var contest: PairContest? = null
        private set

    /** This device just started a race from the top: tell the peer, and measure until its gun. */
    fun startedHere(sequenceId: String, gunMs: Long) {
        val race = book.startedHere(sequenceId, gunMs, consoleNowMs())
        setContest(null)
        link.holdUntil(gunMs)
        link.announce(race)
    }

    /** Now, on the console's clock: this clock on the phone, the phone's through the link on the watch. */
    private fun consoleNowMs(): Long? {
        val now = clock.elapsedMs()
        return if (console) now else link.peerOffsetMs()?.let { now + it }
    }

    /** This device resumed a race after a process death. It has no key, so it is not announced. */
    fun restored() {
        book.unannounced()
        setContest(null)
    }

    /** The race here is over: stopped, ended, or run to its gun and torn down. */
    fun ended() {
        book.unannounced()
        link.endHold()
        setContest(null)
    }

    /** The peer's start arrived. */
    fun onPeerStart(start: PeerStart) {
        val sequence = BuiltInSequences.resolve(start.sequenceId)
        if (sequence == null) {
            // Two app versions that disagree about the sequence set: a race this one cannot run is
            // not joined, rather than joined as something else (the #88 lesson, over the link).
            log("peer start not joined: unknown sequence ${start.sequenceId}")
            return
        }
        when (val verdict = book.decide(start, host.raceRunning())) {
            StartVerdict.Ignore -> log("peer start already known: race=${start.key.raceId}")
            is StartVerdict.Keep -> {
                log("peer start beaten: keeping race=${verdict.mine.key.raceId}")
                link.announce(verdict.mine)
                offerChoice(followed = verdict.mine, other = verdict.beaten)
            }
            is StartVerdict.Join -> {
                val grace = if (verdict.displaces) 0L else JOIN_LATE_CUE_GRACE_MS
                if (join(verdict.race, sequence, grace)) {
                    verdict.replaced?.let { offerChoice(followed = verdict.race, other = it) } ?: setContest(null)
                }
            }
        }
    }

    /**
     * The officer chose [race] from the [contest]. The one already followed changes nothing; the
     * other is started again on both devices under a new key, so it outranks the start that beat it.
     */
    fun choose(race: PairRace) {
        val c = contest ?: return
        setContest(null)
        if (race.key == c.followed.key || race.key != c.other.key) return
        val sequence = BuiltInSequences.resolve(race.sequenceId) ?: return
        val again = book.reasserted(race, consoleNowMs())
        if (join(again, sequence, lateCueGraceMs = 0L)) link.announce(again)
    }

    private fun join(race: PairRace, sequence: RaceSequence, lateCueGraceMs: Long): Boolean {
        if (race.gun.gunMs <= clock.elapsedMs() && !sequence.countUpAfterFinish) {
            log("peer start not joined: its gun has passed")
            return false
        }
        if (!host.join(PairJoin(race, sequence, lateCueGraceMs))) {
            log("peer start not joined: the host refused")
            return false
        }
        book.joined(race)
        link.holdUntil(race.gun.gunMs)
        return true
    }

    private fun offerChoice(followed: PairRace, other: PairRace) {
        setContest(if (console && followed.startedHere != other.startedHere) PairContest(followed, other) else null)
    }

    private fun setContest(c: PairContest?) {
        if (c == contest) return
        contest = c
        onContest(c)
    }
}

/**
 * The line a device owes the officer after joining the other's race on a gun it cannot vouch for
 * (#220 AC 5), or null when the join was within D2's budget.
 *
 * Standing until the officer taps Sync, as the degraded-restore prompt it sits beside does: the race
 * is counting on a gun that may be out by more than the pair promises, and Sync against the next flag
 * is the one act that settles it. [peerNoun] is the other device: "Phone" on the watch, "Watch" on the
 * phone.
 */
fun pairJoinNotice(gun: JoinGun, peerNoun: String): String? {
    val bound = gun.errorBoundMs ?: return "$peerNoun start unmeasured — tap Sync to confirm"
    return if (bound <= PAIR_SKEW_BUDGET_MS) null else "$peerNoun start ±$bound ms — tap Sync to confirm"
}

/**
 * What the phone says about a [PairContest]: who else started, how far apart, and which start both
 * devices are following. [peerNoun] is capitalised, as it opens the line.
 *
 * The gap is between the two guns on this device's clock, which for one sequence is the gap between
 * the taps. Two different sequences have guns that say nothing about each other, so the line names the
 * other device's sequence instead.
 */
fun pairContestLine(contest: PairContest, peerNoun: String, selfNoun: String): String {
    val peerRace = if (contest.followed.startedHere) contest.other else contest.followed
    val hereRace = if (contest.followed.startedHere) contest.followed else contest.other
    val following = if (contest.followed.startedHere) selfNoun else peerNoun.lowercase(Locale.ROOT)
    val what = if (peerRace.sequenceId == hereRace.sequenceId) {
        val gapMs = peerRace.gun.gunMs - hereRace.gun.gunMs
        val tenths = (abs(gapMs) + 50L) / 100L
        if (tenths == 0L) {
            "at the same moment"
        } else {
            String.format(Locale.ROOT, "%d.%d s %s", tenths / 10L, tenths % 10L, if (gapMs > 0L) "later" else "earlier")
        }
    } else {
        BuiltInSequences.resolve(peerRace.sequenceId)?.name ?: peerRace.sequenceId
    }
    return "$peerNoun started too, $what — both following the $following"
}
