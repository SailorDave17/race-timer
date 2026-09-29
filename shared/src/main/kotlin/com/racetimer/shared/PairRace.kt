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

/**
 * A race this device knows: its order, what it runs, its gun here, and whether it was tapped here.
 *
 * [gunKey] orders the race's **gun** rather than the race (#221): its start's [key] until a Sync
 * moves it, then that Sync's key. Two Syncs of one race taken on the two devices at nearly the same
 * moment are ordered by it exactly as two starts are by [key] — the later tap wins on both — so the
 * pair settles on one gun rather than each device taking the other's.
 */
data class PairRace(
    val key: RaceKey,
    val sequenceId: String,
    val gun: JoinGun,
    val startedHere: Boolean,
    val gunKey: RaceKey = key,
)

/** A start the nearby peer announced, its gun already moved onto this device's clock. */
data class PeerStart(val key: RaceKey, val sequenceId: String, val gun: JoinGun)

/**
 * A Sync the nearby peer took of race [raceId] (#221), its gun already moved onto this clock.
 *
 * [setRemainingMs] is the countdown the Sync set **as the peer read it when sending** — its gun
 * less its sending instant, both on its own clock: the whole minute its snap produced, less the few
 * milliseconds between the snap and the send. It is what the officer is told the peer synced to, and
 * it is taken from the sender because the receiver's own reading lands a message's trip later —
 * *measured* on the owner's pair at about 0.6 s, which read "Watch synced → 0:59" for a Sync to 1:00.
 */
data class PeerSync(val key: RaceKey, val raceId: Long, val gun: JoinGun, val setRemainingMs: Long)

/** The nearby peer ended race [raceId] [elapsedMs] past its gun (#221). */
data class PeerEnd(val raceId: Long, val elapsedMs: Long)

/** What a device does with the peer's Sync, decided by [PairRaceBook.decideSync]. */
sealed interface SyncVerdict {

    /** Move this device's gun: [race] is the race with the peer's gun and key. */
    data class Move(val race: PairRace) : SyncVerdict

    /** The peer's Sync is older than the one this device holds: send [mine] again so it converges. */
    data class Resend(val mine: PairRace) : SyncVerdict

    /** A copy of the Sync already taken. */
    data object Ignore : SyncVerdict

    /** A Sync for a race this device is not running (#221 AC 3): never applied to another race. */
    data object Stale : SyncVerdict
}

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

    /**
     * The race this device last started or joined, or null when it is running one it cannot name.
     *
     * **Kept after the race is over** (#221). A control that arrives for it later — the peer's Sync
     * after a Stop here, or an End Race crossing this device's own — is then recognised as a control
     * for *this device's* race, over or not, and never taken for another's: the rule that decides
     * whether it applies is whether the race is still running, which the caller is asked, not
     * whether this remembers it. Replaced by the next start or join, and cleared by a restore.
     */
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
        RaceKey(nextStamp(consoleNowMs), race.key.raceId).let { key -> race.copy(key = key, gunKey = key) }.also { remember(it) }

    /**
     * A key for something done here now — a Sync or a setup pick (#221) — ordered after everything
     * this device has seen, on the console's clock where it has a measure of it. The same stamps as
     * a start's, so a pick made after seeing a start outranks it, and the other way round.
     */
    fun nextKey(consoleNowMs: Long?): RaceKey = RaceKey(nextStamp(consoleNowMs), newRaceId())

    /** Order what this device does next after [key], which it has just been told of. */
    fun saw(key: RaceKey) {
        lastStamp = maxOf(lastStamp, key.stamp)
    }

    /**
     * The race this device is running had its gun moved by a Sync taken here, to [gunMs] on this
     * clock (#221). Null when it is running a race it cannot name, which is then not announced.
     */
    fun syncedHere(gunMs: Long, consoleNowMs: Long?): PairRace? {
        val race = current ?: return null
        return race.copy(gun = JoinGun(gunMs, 0L), gunKey = nextKey(consoleNowMs)).also {
            remember(it)
            current = it
        }
    }

    /**
     * What to do with the peer's [sync] (#221).
     *
     * **Stale** unless it names the race this device last started or joined: a control is never
     * applied to a different race (#221 AC 3), whatever it says. For this device's race, **the later
     * Sync wins**, by [PairRace.gunKey] — the same rule as starts, so two Syncs that cross settle on
     * one gun on both devices. The peer's older one is answered by sending this device's own again,
     * and a copy of the one already held changes nothing.
     *
     * Whether the race is still *running* is not decided here: the caller asks the engine, and a
     * [SyncVerdict.Move] it cannot apply is stale after all.
     */
    fun decideSync(sync: PeerSync): SyncVerdict {
        val race = current
        if (race == null || race.key.raceId != sync.raceId) return SyncVerdict.Stale
        saw(sync.key)
        return when {
            sync.key > race.gunKey -> SyncVerdict.Move(race.copy(gun = sync.gun, gunKey = sync.key))
            sync.key < race.gunKey -> SyncVerdict.Resend(race)
            else -> SyncVerdict.Ignore
        }
    }

    /** This device moved its gun as [race] says: the peer's Sync, applied. */
    fun moved(race: PairRace) {
        remember(race)
        current = race
    }

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
        // A known race keeps its gun, and with it the key that gun was set under — a Sync's, if one
        // moved it since (#221) — unless the start now outranks that too.
        val race = PairRace(
            start.key,
            start.sequenceId,
            prior?.gun ?: start.gun,
            startedHere = prior?.startedHere == true,
            gunKey = prior?.let { maxOf(it.gunKey, start.key) } ?: start.key,
        )
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
        lastStamp = maxOf(lastStamp, race.key.stamp, race.gunKey.stamp)
    }

    private companion object {
        /** A start day is a few dozen races; this is weeks of them, and each is four numbers. */
        const val MAX_KNOWN = 64
    }
}

/**
 * What [PairStarts] and [PairSetup] need from the link: `PairLink` in production and in the JVM tests
 * alike. Every send goes to the nearby peer or nowhere.
 */
interface PairAnnouncer {
    /** Send [race] to the nearby peer, or nothing when there is none. */
    fun announce(race: PairRace)

    /** Send [race]'s gun, moved by a Sync, under its [PairRace.gunKey] (#221). */
    fun announceSync(race: PairRace)

    /** Send that race [raceId] was ended [elapsedMs] past its gun (#221). */
    fun announceEnd(raceId: Long, elapsedMs: Long)

    /** Send this device's pre-start setup under [key] (#221). */
    fun announceSetup(key: RaceKey, choice: SetupChoice)

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

    /**
     * Move the running countdown's gun to [gun], the peer's Sync on this clock (#221), with
     * everything a Sync here does around it: the cues re-aimed, the wake lock re-sized (#126), the
     * race persisted. False when there is no countdown to move, which makes the Sync stale.
     */
    fun moveGun(gun: JoinGun): Boolean

    /**
     * End the race here [elapsedMs] past its gun, the peer's End Race (#221), winding down what a
     * local End Race winds down. Returns the elapsed time now frozen — the peer's, or this device's
     * own earlier end — or null when there is no race-manager race here to end.
     */
    fun endRaceAt(elapsedMs: Long): Long?
}

/** Why a control from the peer was not applied here: which control it was (#221 AC 3). */
enum class PairControl { SYNC, END_RACE }

/** Something the peer did to this device that the officer is owed a word about (#221). */
sealed interface PairEvent {

    /**
     * The peer's Sync moved this device's gun. [setRemainingMs] is the countdown the peer's Sync set,
     * as the peer read it when sending ([PeerSync.setRemainingMs]) — not this device's reading when
     * it landed, which is a message's trip later.
     */
    data class GunMoved(val setRemainingMs: Long) : PairEvent

    /**
     * A [control] arrived for a race this device is not running — over here, or never run here —
     * and was discarded rather than applied to whatever is running (#221 AC 3).
     */
    data class StaleControl(val control: PairControl) : PairEvent
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
 * drops when there is nobody to send it to. The same is true of [syncedHere] and [endedHere] (#221).
 *
 * **The race's controls mirror too (#221).** A Sync taken on either device moves both guns: it
 * travels as the gun it produced, translated as a start's is, and two Syncs that cross settle on the
 * later ([PairRaceBook.decideSync]). An End Race taken on either device ends the race on both at the
 * one elapsed time, the earlier of two that cross. A control names its race, and one for a race this
 * device is not running is dropped and said ([PairEvent.StaleControl]) — never applied to another.
 *
 * @param console whether this device is the console: the phone. Its clock is the one every start is
 *                ordered on ([RaceKey]), and it is the device that puts a [PairContest] in front of
 *                the officer — the owner wanted to be told on the console.
 * @param setup   the pre-start setup this device mirrors (#221), told when a race starts or is
 *                joined, since the race it runs is then what both screens are set to, and when the
 *                race is over, since that is when the two devices' setups are compared again.
 * @param onEvent what the peer did here that the officer is owed a word about (#221).
 */
class PairStarts(
    private val book: PairRaceBook,
    private val link: PairAnnouncer,
    private val host: PairRaceHost,
    private val clock: MonotonicClock,
    private val console: Boolean,
    private val onContest: (PairContest?) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val setup: PairSetup? = null,
    private val onEvent: (PairEvent) -> Unit = {},
) {

    /** The conflict waiting for the officer, or null. See [PairContest]. */
    var contest: PairContest? = null
        private set

    /** This device just started a race from the top: tell the peer, and measure until its gun. */
    fun startedHere(sequenceId: String, gunMs: Long) {
        val race = book.startedHere(sequenceId, gunMs, consoleNowMs(clock, console, link))
        setContest(null)
        link.holdUntil(gunMs)
        link.announce(race)
        setup?.raced(race)
    }

    /** This device resumed a race after a process death. It has no key, so it is not announced. */
    fun restored() {
        book.unannounced()
        setContest(null)
    }

    /**
     * The race here is over: stopped, ended, or run to its gun and torn down.
     *
     * The book still remembers it (see [PairRaceBook.current]), so a control that arrives for it
     * later is recognised as this device's and dropped as stale, rather than mistaken for another's.
     */
    fun ended() {
        link.endHold()
        setContest(null)
        setup?.ended()
    }

    /**
     * This device's Sync moved its gun to [gunMs] on this clock (#221): tell the peer, so both count
     * to the one gun, and measure until it. A Sync ends the choice between two starts on the phone —
     * the officer has just set the gun against the flag, which is the question the choice was asking.
     * A race restored after a process death has no key and is not announced.
     */
    fun syncedHere(gunMs: Long) {
        val race = book.syncedHere(gunMs, consoleNowMs(clock, console, link)) ?: return
        setContest(null)
        link.holdUntil(gunMs)
        link.announceSync(race)
    }

    /** This device's End Race froze its race [elapsedMs] past the gun (#221): tell the peer. */
    fun endedHere(elapsedMs: Long) {
        val race = book.current ?: return
        link.endHold()
        link.announceEnd(race.key.raceId, elapsedMs)
    }

    /**
     * The peer's Sync arrived (#221). Applied only to the race it names and only while that race is
     * counting down here; anything else is stale and said, never applied to whatever is running.
     */
    fun onPeerSync(sync: PeerSync) {
        when (val verdict = book.decideSync(sync)) {
            SyncVerdict.Stale -> stale(PairControl.SYNC, "not the race here, race=${sync.raceId}")
            SyncVerdict.Ignore -> log("peer sync already taken: race=${sync.raceId}")
            is SyncVerdict.Resend -> if (host.raceRunning()) {
                log("peer sync older than the one here: sending it again, race=${sync.raceId}")
                link.announceSync(verdict.mine)
            } else {
                stale(PairControl.SYNC, "race over here, race=${sync.raceId}")
            }
            is SyncVerdict.Move -> if (host.moveGun(verdict.race.gun)) {
                book.moved(verdict.race)
                setContest(null)
                link.holdUntil(verdict.race.gun.gunMs)
                onEvent(PairEvent.GunMoved(sync.setRemainingMs))
            } else {
                stale(PairControl.SYNC, "no countdown here, race=${sync.raceId}")
            }
        }
    }

    /**
     * The peer's End Race arrived (#221). Ends the race it names, at the peer's elapsed time — or
     * keeps this device's own earlier end, and sends that back so the peer takes it too. Anything
     * else is stale and said.
     */
    fun onPeerEnd(end: PeerEnd) {
        val race = book.current
        if (race == null || race.key.raceId != end.raceId) return stale(PairControl.END_RACE, "not the race here, race=${end.raceId}")
        val held = host.endRaceAt(end.elapsedMs) ?: return stale(PairControl.END_RACE, "no race counting up here, race=${end.raceId}")
        link.endHold()
        if (held < end.elapsedMs) {
            log("peer end later than the one here: sending it again, race=${end.raceId}")
            link.announceEnd(race.key.raceId, held)
        }
    }

    private fun stale(control: PairControl, why: String) {
        log("peer ${control.name.lowercase()} dropped: $why")
        onEvent(PairEvent.StaleControl(control))
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
        val again = book.reasserted(race, consoleNowMs(clock, console, link))
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
        setup?.raced(race)
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
fun pairJoinNotice(gun: JoinGun, peerNoun: String, placedBy: PairControlledGun = PairControlledGun.START): String? {
    val what = placedBy.word
    val bound = gun.errorBoundMs ?: return "$peerNoun $what unmeasured — tap Sync to confirm"
    return if (bound <= PAIR_SKEW_BUDGET_MS) null else "$peerNoun $what ±$bound ms — tap Sync to confirm"
}

/**
 * What placed a gun that came across the link, for [pairJoinNotice]'s words: the peer's start (#220),
 * or the peer's Sync, which moves the gun through the same translation and so can land out of budget
 * the same way (#221).
 */
enum class PairControlledGun(val word: String) { START("start"), SYNC("sync") }

/**
 * Now, on the console's clock: this clock on the phone, the phone's through the link on the watch —
 * or null on a watch the link has no measure of. What every key a device makes is stamped with
 * ([RaceKey]): a start's, a Sync's and a setup pick's alike (#221).
 */
internal fun consoleNowMs(clock: MonotonicClock, console: Boolean, link: PairAnnouncer): Long? {
    val now = clock.elapsedMs()
    return if (console) now else link.peerOffsetMs()?.let { now + it }
}

/**
 * The line the officer is owed when the peer's control named a race this device is not running
 * (#221 AC 3): what the other device did, and that it was not done here. A Tier 1 banner on the watch
 * and a transient line on the phone — it is news about the pair, with nothing to do on this screen.
 */
fun pairStaleControlLine(control: PairControl, peerNoun: String): String = when (control) {
    PairControl.SYNC -> "$peerNoun synced a race not running here"
    PairControl.END_RACE -> "$peerNoun ended a race not running here"
}

/**
 * The line the peer's Sync leaves where a Sync here leaves "Synced → 4:00" (#221): the same news,
 * naming the device that took it.
 *
 * [setRemainingMs] is the countdown the peer's Sync set, read on the peer's clock as it sent
 * ([PeerSync.setRemainingMs]): the whole minute its snap produced, less the milliseconds between
 * the snap and the send. It is shown to the **nearest** second rather than rounded up as a running
 * countdown is, so 4:59.994 reads as the 5:00 the peer set.
 *
 * *This read this device's own countdown when the move landed until #221's hardware run*, rounded
 * the same way on the premise that a trip plus the translation's error stays under half a second.
 * On the owner's pair a Sync took about 0.6 s to cross and the phone read "Watch synced → 0:59" for
 * a Sync to 1:00. The seam tests had a 60 ms trip, where the premise always held.
 */
fun pairGunMovedLine(setRemainingMs: Long, peerNoun: String): String =
    "$peerNoun synced → ${formatCountdown((setRemainingMs + 500L).floorDiv(1_000L) * 1_000L)}"

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
