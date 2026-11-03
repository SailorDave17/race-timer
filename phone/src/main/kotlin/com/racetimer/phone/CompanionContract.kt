package com.racetimer.phone

import com.racetimer.shared.CueVoice
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.SequenceCue
import org.json.JSONObject

/**
 * race-timer's half of the companion link, contract version 1 (#294).
 *
 * The contract is pro-companion's ADR 004 ("The race-timer link"), and this file is a copy of the
 * parts race-timer sends: the transport's names and the event's fields. The companion is
 * `SailorDave17/pro-companion`, a race-committee logging app on the same phone. It logs the sequence
 * race-timer runs, so the PRO does not have to tap each signal in by hand. race-timer owns the
 * sequence and the companion only logs it (ADR 004, scope decision 2), so nothing in here changes
 * what race-timer does. It only says, after the fact, what it did.
 *
 * **What is sent, and what is not.** The contract names six kinds. race-timer fires three of them:
 * `sequence_start`, `signal` and `start`. It has no postponement, individual-recall or
 * general-recall control, so it never sends those kinds. The PRO taps them on the companion, which
 * already has those controls (#294's owner decision; adding them to race-timer is a story of its own).
 * And of the cues race-timer sounds, only the horn cues at or above one minute are sent as signals
 * (the owner's decision at #294's pickup). The sub-minute countdown calls would make the companion
 * buzz every second near the gun, on top of race-timer's own cues, and a PRO's sheet does not log them.
 *
 * **No fleet, class or race id** (#294 criterion 6). The companion attaches each sequence to the fleet
 * the PRO has armed on it (pro-companion groom decision G25). race-timer knows nothing about fleets.
 */
object CompanionContract {

    /** The contract version, `v` on every event. It changes only on an incompatible change. */
    const val VERSION = 1

    /** The companion's application id, and the package race-timer pins a certificate for. */
    const val PACKAGE = "com.procompanion.app"

    /** The companion's exported link service, bound by explicit component, never by intent filter. */
    const val SERVICE = "com.procompanion.app.LinkService"

    /** The interface token written ahead of every event. A raw transaction, so there is no AIDL stub. */
    const val DESCRIPTOR = "com.procompanion.link.ILink"

    /** The companion's core has committed the event, or already held its id. */
    const val ACCEPTED = "accepted"

    /** Nothing committed it: the companion's core is down or did not answer in time. Kept and resent. */
    const val NO_CORE = "no_core"

    /** The companion does not trust this caller: its pins are out of date. A resend cannot help. */
    const val REFUSED = "refused"

    /** The companion could not read the event: a race-timer bug. A resend cannot help. */
    const val MALFORMED = "malformed"
}

/** The contract's `kind`, for the three kinds race-timer fires. */
enum class CompanionKind(val wire: String) {
    SEQUENCE_START("sequence_start"),
    SIGNAL("signal"),
    START("start"),
}

/**
 * One event, as the companion receives it.
 *
 * [id] is minted once, when the event is first queued, and is the same on every resend. That is
 * what lets the companion store each event once however often it arrives (ADR 004 decision 4).
 * [atMs] is when race-timer fired the signal, on the wall clock, not when the event was sent.
 */
data class CompanionEvent(
    val id: String,
    val kind: CompanionKind,
    val atMs: Long,
    val sequence: String,
    val sequenceName: String? = null,
    val signal: String? = null,
    val secondsToStart: Int? = null,
) {
    /**
     * The event as the one JSON object the contract carries.
     *
     * Through `org.json` rather than by hand, so escaping is the platform's and a sequence name can
     * never produce text the companion answers `malformed`.
     */
    fun toJson(): String = JSONObject().apply {
        put("v", CompanionContract.VERSION)
        put("id", id)
        put("kind", kind.wire)
        put("at_ms", atMs)
        put("sequence", sequence)
        sequenceName?.let { put("sequence_name", it) }
        signal?.let { put("signal", it) }
        secondsToStart?.let { put("seconds_to_start", it) }
    }.toString()
}

/** What the companion is told about one cue race-timer fired. */
sealed class CueReport {
    /** A signal, named by the contract where it has a name, and its seconds to the start. */
    data class Signal(val name: String, val secondsToStart: Int) : CueReport()

    /** The gun. */
    object Start : CueReport()
}

/** The shortest cue offset the companion is told about: one minute (#294's pickup decision). */
const val COMPANION_SIGNAL_FLOOR_MS = 60_000L

/**
 * What the companion is told about [cue] in [sequence], or null for a cue it is not told about.
 *
 * Only a [CueVoice.BLAST] cue is a signal, because it is what the committee sounds. A
 * [CueVoice.SYNC] tick counts *into* a signal and a [CueVoice.PROMPT] tells the race manager to act,
 * and neither is something a fleet hears. A lead-in cue belongs to the run-up ahead of the
 * sequence, not to it. Below [COMPANION_SIGNAL_FLOOR_MS] only the gun is sent.
 *
 * The three signals the contract names are found by position, not by label, so a relabelled cue
 * cannot rename one:
 * - `warning` is the sequence's first signal, at [RaceSequence.sequenceMs];
 * - `one_minute` is the signal at 1:00, where that is not the warning;
 * - `preparatory` is the signal one minute after the warning, where that is above 1:00.
 *
 * That reads 5-4-1 and 3-2-1 alike (RRS 26 and Appendix S), and a Custom race's minute cues the same
 * way. Any other signal (US Sailing's 3:00 and 2:00, Scholastic's 1:30, a Custom race's middle
 * minutes) goes by race-timer's own name for it, the cue's label, which is what the contract allows.
 */
fun companionReportOf(sequence: RaceSequence, cue: SequenceCue): CueReport? {
    if (cue.isLeadIn || cue.signal.voice != CueVoice.BLAST) return null
    if (cue.isGun) return CueReport.Start
    if (cue.offsetMs < COMPANION_SIGNAL_FLOOR_MS) return null
    val name = when {
        cue.offsetMs == sequence.sequenceMs -> "warning"
        cue.offsetMs == COMPANION_SIGNAL_FLOOR_MS -> "one_minute"
        cue.offsetMs == sequence.sequenceMs - 60_000L -> "preparatory"
        cue.signal.label.isNotBlank() -> cue.signal.label
        else -> "signal"
    }
    return CueReport.Signal(name, (cue.offsetMs / 1_000L).toInt())
}
