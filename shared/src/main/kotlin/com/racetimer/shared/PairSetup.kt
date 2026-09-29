package com.racetimer.shared

/**
 * What a pre-start screen is set to (#221, epic decision D8): the sequence the next Start runs,
 * without any lead-in, and the box alert the lead-in picker opens on.
 *
 * The sequence id carries a Custom race's length (`custom_8m`), so a Custom duration mirrors as a
 * selection does. A lead-in is not part of it: arming one *is* a start on both apps, and #220 mirrors
 * the start. What mirrors of the lead-in before a start is the alert its picker opens on — the
 * owner's reading of D8's "lead-in arming" at #221's pickup — so both pickers open on the same alert.
 */
data class SetupChoice(val sequenceId: String, val boxAlertSeconds: Int)

/** The nearby peer's pre-start setup, and the key that orders it against this device's (#221). */
data class PeerSetup(val key: RaceKey, val choice: SetupChoice)

/** What [PairSetup] needs from the app that shows the pre-start screen. */
interface PairSetupHost {

    /**
     * Whether this device is at its pre-start screen's state: no race running and none on screen —
     * no countdown, no count-up, no "GO!" still showing, no frozen summary waiting for Done. Only
     * then is a peer's setup taken, because only then does the screen show a setup rather than a race.
     */
    fun atPreStart(): Boolean

    /**
     * Put [choice] on the pre-start screen, and remember it as a pick is remembered. Never told to
     * the peer again: it came from there.
     */
    fun apply(choice: SetupChoice)
}

/**
 * Mirror the pre-start setup across the pair (#221, epic decision D8): a sequence chosen, a Custom
 * length dialled, or a lead-in alert armed on either device is what the other device's pre-start
 * screen shows before Start — so one device drives the other from the dock.
 *
 * **The later pick wins, ordered like starts** — the owner's rule at #221's pickup. Every pick is
 * keyed with the stamps a start takes ([PairRaceBook.nextKey]): the instant on the console's clock,
 * raised past everything this device has seen. Both devices compare the same two keys, so they
 * always reach the same answer, and the one holding the later pick sends it back so the pair
 * converges even when one of two crossing picks is lost.
 *
 * **A setup nobody has touched loses to any that has been.** What an app opens on comes from its own
 * memory and has no key in this process ([unkeyed]), exactly as a race restored after a process death
 * has none, and it yields to any pick. When neither device's setup has been touched, the phone's
 * wins: the tie is broken by which device the console is, so both reach it.
 *
 * **When the two are compared:** on every pick, which is sent as it is made; when the peer becomes
 * reachable directly ([onPeerNearby]); and when this device's race is over and its pre-start screen
 * is back ([ended]). A device with a race on screen takes no setup, and the comparison happens when
 * its race is over and it sends its own.
 *
 * **A race is a pick of its own sequence.** A race started here takes the start's key as the setup's,
 * since the start is the latest thing the officer did; a race joined here keeps this device's key,
 * so the device whose start it was wins the comparison after the race. Either way the setup follows
 * the race — its sequence without the lead-in, and a lead-in's alert — so after a race both screens
 * are set to what was just run. That is how a lead-in armed on one device reaches the other's picker.
 *
 * **A device with no peer is the device it always was.** Every send goes to the nearby peer or
 * nowhere, and nothing here changes what the app shows until a peer's setup arrives.
 *
 * One per process in production, driven on the thread [PairStarts] runs on.
 *
 * @param initial what the app opened on, from its own memory, or null when it has not said.
 */
class PairSetup(
    private val book: PairRaceBook,
    private val link: PairAnnouncer,
    private val host: PairSetupHost,
    private val clock: MonotonicClock,
    private val console: Boolean,
    initial: SetupChoice?,
    private val log: (String) -> Unit = {},
) {

    /** The setup this device holds, or null before the app has said what it opened on. */
    var choice: SetupChoice? = initial
        private set

    /** The key that orders [choice]: [unkeyed] until something is done here or taken from the peer. */
    var key: RaceKey = unkeyed()
        private set

    /** The officer changed the setup here: key it after everything seen, and tell the peer. */
    fun pickedHere(choice: SetupChoice) {
        this.choice = choice
        key = book.nextKey(consoleNowMs(clock, console, link))
        send()
    }

    /**
     * A race started or joined here is now what both screens are set to (see the class doc). Nothing
     * is sent: the peer is running the same race, and the setups are compared when it is over.
     */
    fun raced(race: PairRace) {
        val next = SetupChoice(
            sequenceId = leadInBaseId(race.sequenceId),
            boxAlertSeconds = boxAlertSeconds(race.sequenceId) ?: choice?.boxAlertSeconds ?: DEFAULT_BOX_ALERT_SECONDS,
        )
        if (race.startedHere) key = maxOf(key, race.key)
        if (next != choice) {
            choice = next
            host.apply(next)
        }
    }

    /** The peer has just become reachable directly: compare the two setups. */
    fun onPeerNearby() = send()

    /** This device's race is over. Back at its pre-start screen, it compares its setup with the peer's. */
    fun ended() {
        if (host.atPreStart()) send()
    }

    /** The peer's setup arrived. */
    fun onPeerSetup(setup: PeerSetup) {
        book.saw(setup.key)
        if (!host.atPreStart()) {
            // Compared again when this device's race is over and it sends its own.
            log("peer setup not taken: a race is on screen here")
            return
        }
        when {
            setup.key > key -> {
                if (BuiltInSequences.resolve(setup.choice.sequenceId) == null) {
                    // Two app versions that disagree about the sequence set: a setup this one cannot
                    // run is not shown, rather than shown as something else (the #88 lesson again).
                    log("peer setup not taken: unknown sequence ${setup.choice.sequenceId}")
                    return
                }
                key = setup.key
                choice = setup.choice
                log("peer setup taken: seq=${setup.choice.sequenceId} alert=${setup.choice.boxAlertSeconds}")
                host.apply(setup.choice)
            }
            // This device's is the later: send it back, so the peer converges on it.
            setup.key < key -> send()
            else -> log("peer setup already held")
        }
    }

    private fun send() {
        val c = choice ?: return
        link.announceSetup(key, c)
    }

    /**
     * The key of a setup nobody has touched. Below every key a device makes, since a made stamp is
     * raised past `Long.MIN_VALUE` ([PairRaceBook.nextKey]); the second half breaks the tie between
     * two untouched setups in the console's favour, the same way on both devices.
     */
    private fun unkeyed(): RaceKey = RaceKey(Long.MIN_VALUE, if (console) 1L else 0L)
}
