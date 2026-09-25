package com.racetimer.pairskew.analysis

import com.racetimer.pairskew.protocol.Record
import com.racetimer.pairskew.protocol.Round
import com.racetimer.pairskew.protocol.Side

/**
 * Everything a run logged, from any mix of the apps' own log files and logcat captures.
 *
 * The same line read from two sources is one record. The same round is one round whether it came
 * from the requester's `ROUND` line or the responder's `SAMPLE` copy. A round answered after its
 * timeout is kept, marked [late], and its `FAIL timeout` line is dropped, because the reply did come:
 * counting it as a failure too would count one round twice.
 */
class Capture private constructor(
    val records: List<Record>,
    val rounds: List<Round>,
    val late: Set<Triple<Side, Long, Long>>,
    val failures: List<Record>,
) {
    val hellos: List<Record> get() = kind("HELLO")
    val states: List<Record> get() = kind("STATE")
    val guns: List<Record> get() = kind("GUN")
    val links: List<Record> get() = kind("LINK")

    fun kind(kind: String): List<Record> = records.filter { it.kind == kind }

    companion object {
        fun of(all: List<Record>): Capture {
            val records = all.distinct()
            val rounds = LinkedHashMap<Triple<Side, Long, Long>, Round>()
            // (requester, run, id), since that is what a FAIL line names.
            val late = HashSet<Triple<Side, Long, Long>>()
            for (record in records) {
                if (record.kind != "ROUND" && record.kind != "SAMPLE") continue
                val round = Round.of(record)
                rounds.putIfAbsent(round.key, round)
                if (record.flag("late") == true) late += Triple(round.requester, round.run, round.id)
            }
            val failures = records.filter { it.kind == "FAIL" }.filterNot {
                it.text("reason") == "timeout" && Triple(Side.of(it.text("req")), it.long("run"), it.long("id")) in late
            }
            return Capture(records, rounds.values.sortedBy { it.phoneAtNs }, late, failures)
        }
    }
}
