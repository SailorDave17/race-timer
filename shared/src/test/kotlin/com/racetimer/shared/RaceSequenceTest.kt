package com.racetimer.shared

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [BuiltInSequences] to verify sequence definitions are correct.
 */
class RaceSequenceTest {

    @Test fun `usSailing has 30 cues`() {
        assertEquals(30, BuiltInSequences.usSailing.cues.size)
    }

    @Test fun `usSailing totalMs is 5 minutes`() {
        assertEquals(5 * 60_000L, BuiltInSequences.usSailing.totalMs)
    }

    @Test fun `usSailing last cue is gun at 0ms`() {
        val gun = BuiltInSequences.usSailing.cues.last()
        assertEquals(0L, gun.offsetMs)
        assertTrue(gun.isGun)
    }

    @Test fun `usSailing is voiced for the wrist, not the horn`() {
        // Every cue is short blasts: a sailor cannot count one 500 ms buzz against another without
        // looking at the watch, which is the whole thing this sequence is trying to avoid.
        val long = BuiltInSequences.usSailing.cues.filter { it.signal.longBlasts > 0 }
        assertEquals("no usSailing cue may use a long blast", emptyList<SequenceCue>(), long)
    }

    @Test fun `usSailing ticks once at each plain minute`() {
        assertPattern(BuiltInSequences.usSailing, 5 * 60_000L, longBlasts = 0, shortBlasts = 1)
        assertPattern(BuiltInSequences.usSailing, 3 * 60_000L, longBlasts = 0, shortBlasts = 1)
        assertPattern(BuiltInSequences.usSailing, 2 * 60_000L, longBlasts = 0, shortBlasts = 1)
    }

    @Test fun `usSailing doubles the two procedural signals`() {
        // Prep up and prep down are the marks that matter; they must stand out from the plain
        // minute ticks either side of them.
        assertPattern(BuiltInSequences.usSailing, 4 * 60_000L, longBlasts = 0, shortBlasts = 2)
        assertPattern(BuiltInSequences.usSailing, 1 * 60_000L, longBlasts = 0, shortBlasts = 2)
    }

    @Test fun `usSailing runs five sync ticks into the 4 and 1 minute signals`() {
        for (base in listOf(4 * 60_000L, 1 * 60_000L)) {
            for (sec in 5L downTo 1L) {
                val offset = base + sec * 1_000L
                val cue = BuiltInSequences.usSailing.cues.firstOrNull { it.offsetMs == offset }
                assertNotNull("expected a sync tick at $offset ms", cue)
                assertEquals("sync tick at $offset ms", CueVoice.SYNC, cue!!.signal.voice)
                assertEquals(1, cue.signal.shortBlasts)
                assertEquals(0, cue.signal.longBlasts)
            }
        }
    }

    @Test fun `sync is used for exactly the ten usSailing countdown ticks and nothing else`() {
        // A sync tick is not a signal. If one ever appears outside these ten offsets, a sailor is
        // being told a mark is coming when none is.
        val expected = listOf(4 * 60_000L, 1 * 60_000L)
            .flatMap { base -> (5L downTo 1L).map { base + it * 1_000L } }
            .sortedDescending()
        val actual = BuiltInSequences.all
            .flatMap { seq -> seq.cues.map { seq to it } }
            .filter { (_, cue) -> cue.signal.voice == CueVoice.SYNC }
        assertEquals(
            "every sync cue must belong to usSailing",
            setOf(BuiltInSequences.usSailing.id),
            actual.map { it.first.id }.toSet(),
        )
        assertEquals(expected, actual.map { it.second.offsetMs }.sortedDescending())
    }

    @Test fun `usSailing gun is a sustained 3 second signal`() {
        val gun = BuiltInSequences.usSailing.cues.first { it.isGun }
        assertEquals(3_000L, gun.signal.sustainedMs)
        assertEquals(0, gun.signal.longBlasts)
        assertEquals(0, gun.signal.shortBlasts)
    }

    @Test fun `usSailing and scholastic share one final minute`() {
        // The last minute must not mean two different things depending on which sequence is loaded.
        fun tailOf(seq: RaceSequence) = seq.cues.filter { it.offsetMs in 1L..50_000L }
        assertEquals(tailOf(BuiltInSequences.scholastic), tailOf(BuiltInSequences.usSailing))
        // And it is the ICSA call, not a flat one: 3 short at 0:30, 2 short at 0:20.
        assertPattern(BuiltInSequences.usSailing, 30_000L, longBlasts = 0, shortBlasts = 3)
        assertPattern(BuiltInSequences.usSailing, 20_000L, longBlasts = 0, shortBlasts = 2)
    }

    @Test fun `usSailing cues are sorted descending and unique`() {
        val offsets = BuiltInSequences.usSailing.cues.map { it.offsetMs }
        assertEquals(offsets.sortedDescending(), offsets)
        assertEquals("no offset may fire twice", offsets.size, offsets.distinct().size)
    }

    @Test fun `scholastic has 19 cues`() {
        assertEquals(19, BuiltInSequences.scholastic.cues.size)
    }

    @Test fun `scholastic totalMs is 3 minutes`() {
        assertEquals(3 * 60_000L, BuiltInSequences.scholastic.totalMs)
    }

    @Test fun `scholastic 30s cue has 3 short blasts`() {
        val cue = BuiltInSequences.scholastic.cues.first { it.offsetMs == 30_000L }
        assertEquals(3, cue.signal.shortBlasts)
        assertEquals(0, cue.signal.longBlasts)
    }

    @Test fun `scholastic 3min cue has 3 long blasts`() {
        val cue = BuiltInSequences.scholastic.cues.first { it.offsetMs == 3 * 60_000L }
        assertEquals(3, cue.signal.longBlasts)
        assertEquals(0, cue.signal.shortBlasts)
    }

    @Test fun `scholastic minute signals keep their blast patterns`() {
        // The ICSA blast structure the race committee actually sounds. Filling in the tail must
        // not re-voice any of it.
        assertPattern(offsetMs = 3 * 60_000L, longBlasts = 3, shortBlasts = 0)
        assertPattern(offsetMs = 2 * 60_000L, longBlasts = 2, shortBlasts = 0)
        assertPattern(offsetMs = 90_000L, longBlasts = 1, shortBlasts = 3)
        assertPattern(offsetMs = 1 * 60_000L, longBlasts = 1, shortBlasts = 0)
    }

    @Test fun `scholastic marks 50s and 40s with 1 short each`() {
        assertPattern(offsetMs = 50_000L, longBlasts = 0, shortBlasts = 1)
        assertPattern(offsetMs = 40_000L, longBlasts = 0, shortBlasts = 1)
    }

    @Test fun `scholastic 20s cue has 2 short blasts`() {
        assertPattern(offsetMs = 20_000L, longBlasts = 0, shortBlasts = 2)
    }

    @Test fun `scholastic ticks every second through the last ten`() {
        // Two phases, not one: single from 0:10 to 0:06, doubled from 0:05 down. The single ticks are
        // load-bearing — they are the contrast that makes the double read as a phase change rather
        // than as noise, so a change that doubled all ten would defeat the point and must fail here.
        for (sec in 10L downTo 6L) {
            assertPattern(offsetMs = sec * 1_000L, longBlasts = 0, shortBlasts = 1)
        }
        for (sec in 5L downTo 1L) {
            assertPattern(offsetMs = sec * 1_000L, longBlasts = 0, shortBlasts = 2)
        }
    }

    @Test fun `scholastic gun is a sustained 3 second signal`() {
        val gun = BuiltInSequences.scholastic.cues.first { it.isGun }
        assertEquals(3_000L, gun.signal.sustainedMs)
        // Sustained replaces the blast counts rather than following them.
        assertEquals(0, gun.signal.longBlasts)
        assertEquals(0, gun.signal.shortBlasts)
    }

    @Test fun `scholastic cues are sorted descending by offset`() {
        val offsets = BuiltInSequences.scholastic.cues.map { it.offsetMs }
        assertEquals(offsets.sortedDescending(), offsets)
    }

    @Test fun `scholastic cue offsets are unique`() {
        val offsets = BuiltInSequences.scholastic.cues.map { it.offsetMs }
        assertEquals("no offset may fire twice", offsets.size, offsets.distinct().size)
    }

    @Test fun `no cue in any sequence outruns the gap to the next one`() {
        // Blast lengths mirror CueTiming in the wear module, which HapticManager and ToneManager
        // both play a cue on. A cue that runs past its successor would overlap it on both channels.
        // Now covers every built-in, not just scholastic: usSailing's sync ticks fire at 1-second
        // spacing, the tightest gap anywhere, and 4:01 must not bleed into the signal it announces.
        val longMs = 500L + 250L
        val shortMs = 150L + 150L
        val syncMs = 60L + 60L
        for (seq in BuiltInSequences.all) {
            for ((cue, next) in seq.cues.zipWithNext()) {
                val sync = cue.signal.voice == CueVoice.SYNC
                val patternMs = cue.signal.longBlasts * (if (sync) syncMs else longMs) +
                    cue.signal.shortBlasts * (if (sync) syncMs else shortMs)
                val gapMs = cue.offsetMs - next.offsetMs
                assertTrue(
                    "${seq.id} cue at ${cue.offsetMs} ms runs $patternMs ms into a $gapMs ms gap",
                    patternMs <= gapMs,
                )
            }
        }
    }

    @Test fun `club's gun is the only one still on the legacy triple-buzz`() {
        // A gun carrying no sustainedMs is what falls through to the triple-buzz branch in
        // HapticManager. scholastic, usSailing and scholasticRaceManager all state their own 3 s
        // (the last of these by sharing scholastic's sustainedGun cue); club has taken on the final
        // five but nothing else, so its gun must keep the behaviour it has today.
        val sustained = BuiltInSequences.all
            .flatMap { seq -> seq.cues.map { seq to it } }
            .filter { (_, cue) -> cue.signal.sustainedMs > 0L }
        assertEquals(
            setOf(
                BuiltInSequences.scholastic.id,
                BuiltInSequences.usSailing.id,
                BuiltInSequences.scholasticRaceManager.id,
            ),
            sustained.map { it.first.id }.toSet(),
        )
        assertTrue("only a gun may be sustained", sustained.all { it.second.isGun })
        assertEquals(0L, BuiltInSequences.club.cues.first { it.isGun }.signal.sustainedMs)
    }

    // --- Scholastic Race Manager ------------------------------------------------

    @Test fun `scholasticRaceManager shares scholastic's head through 1 minute`() {
        // The 3:00-to-1:00 opening a race committee sails must not quietly drift from a sailor's —
        // this asserts identity of the shared cues, not just equal values, so a future edit to one
        // cannot forget the other.
        val headOffsets = setOf(3 * 60_000L, 2 * 60_000L, 90_000L, 1 * 60_000L)
        val scholasticHead = BuiltInSequences.scholastic.cues.filter { it.offsetMs in headOffsets }
        val raceManagerHead = BuiltInSequences.scholasticRaceManager.cues.filter { it.offsetMs in headOffsets }
        assertEquals(4, scholasticHead.size)
        for ((a, b) in scholasticHead.zip(raceManagerHead)) {
            assertSame(a, b)
        }
    }

    @Test fun `scholasticRaceManager's tail differs from scholastic's below the minute`() {
        // The race-manager cadence below 1:00 is deliberately its own — see raceManagerTail's doc in
        // RaceSequence.kt for why. This is the guard that a future edit to finalMinuteTail (shared by
        // scholastic and usSailing) doesn't silently start applying here too.
        fun tailOf(seq: RaceSequence) = seq.cues.filter { it.offsetMs in 1L..59_000L }
        assertNotEquals(tailOf(BuiltInSequences.scholastic), tailOf(BuiltInSequences.scholasticRaceManager))
    }

    @Test fun `scholasticRaceManager tail matches the requested race-manager cadence`() {
        assertPattern(BuiltInSequences.scholasticRaceManager, 30_000L, longBlasts = 0, shortBlasts = 3)
        assertPattern(BuiltInSequences.scholasticRaceManager, 20_000L, longBlasts = 0, shortBlasts = 2)
        assertPattern(BuiltInSequences.scholasticRaceManager, 10_000L, longBlasts = 0, shortBlasts = 1)
        for (sec in 5L downTo 1L) {
            assertPattern(BuiltInSequences.scholasticRaceManager, sec * 1_000L, longBlasts = 0, shortBlasts = 1)
        }
    }

    @Test fun `scholasticRaceManager has no ticks between 0-09 and 0-06, unlike scholastic`() {
        // Deliberate gap, not an oversight: the requested cadence jumps straight from 0:10 to 0:05.
        val gap = BuiltInSequences.scholasticRaceManager.cues.filter { it.offsetMs in 6_000L..9_000L }
        assertEquals(emptyList<SequenceCue>(), gap)
    }

    @Test fun `scholasticRaceManager has no 0-50 or 0-40 warning ticks, unlike scholastic`() {
        val warnings = BuiltInSequences.scholasticRaceManager.cues.filter { it.offsetMs in setOf(50_000L, 40_000L) }
        assertEquals(emptyList<SequenceCue>(), warnings)
    }

    @Test fun `scholasticRaceManager has 13 cues`() {
        // 4 head cues (3:00, 2:00, 1:30, 1:00) + 3 (0:30, 0:20, 0:10) + 5 (0:05..0:01) + the gun.
        assertEquals(13, BuiltInSequences.scholasticRaceManager.cues.size)
    }

    @Test fun `scholasticRaceManager cues are sorted descending and unique`() {
        val offsets = BuiltInSequences.scholasticRaceManager.cues.map { it.offsetMs }
        assertEquals(offsets.sortedDescending(), offsets)
        assertEquals("no offset may fire twice", offsets.size, offsets.distinct().size)
    }

    @Test fun `scholasticRaceManager totalMs is still 3 minutes`() {
        // The tail changed, not the head — the pre-start UI still shows the same 3:00 duration.
        assertEquals(3 * 60_000L, BuiltInSequences.scholasticRaceManager.totalMs)
    }

    @Test fun `scholasticRaceManager is the only sequence that counts up after the gun`() {
        // Guards against a future sequence quietly opting in (or scholasticRaceManager quietly
        // losing the flag) the same way `every sequence doubles the last five seconds` guards the
        // final-five cadence above.
        val countUp = BuiltInSequences.all.filter { it.countUpAfterFinish }
        assertEquals(listOf(BuiltInSequences.scholasticRaceManager), countUp)
    }

    @Test fun `built-in sequences other than scholasticRaceManager do not count up`() {
        for (seq in BuiltInSequences.all) {
            if (seq.id == BuiltInSequences.scholasticRaceManager.id) continue
            assertFalse("${seq.id} should not set countUpAfterFinish", seq.countUpAfterFinish)
        }
    }

    @Test fun `club has 9 cues`() {
        // 3 minute signals + the five final-five ticks + the gun.
        assertEquals(9, BuiltInSequences.club.cues.size)
    }

    @Test fun `club takes the final five and nothing else below the minute`() {
        // Club has no final-minute cadence and this story did not give it one. Anything appearing
        // between 0:59 and 0:06 would be a re-voicing of club that nobody asked for.
        val belowTheMinute = BuiltInSequences.club.cues.filter { it.offsetMs in 6_000L..59_000L }
        assertEquals(emptyList<SequenceCue>(), belowTheMinute)
        assertPattern(BuiltInSequences.club, 1 * 60_000L, longBlasts = 1, shortBlasts = 0)
    }

    @Test fun `club totalMs is 3 minutes`() {
        assertEquals(3 * 60_000L, BuiltInSequences.club.totalMs)
    }

    @Test fun `all built-in sequences have exactly one gun cue`() {
        for (seq in BuiltInSequences.all) {
            val guns = seq.cues.count { it.isGun }
            assertEquals("${seq.name} should have 1 gun cue", 1, guns)
        }
    }

    @Test fun `all built-in gun cues are at offsetMs = 0`() {
        for (seq in BuiltInSequences.all) {
            val gun = seq.cues.first { it.isGun }
            assertEquals("${seq.name} gun must be at 0 ms", 0L, gun.offsetMs)
        }
    }

    @Test fun `every sequence doubles the last five seconds, except scholasticRaceManager`() {
        // Iterated over `all` plus a custom rather than asserted per sequence: the point of this
        // cadence is that it is universal, so a sequence added later must not be able to opt out
        // quietly. If a new sequence lands without the final five, this is what should catch it.
        //
        // scholasticRaceManager is a deliberate, requested exception — see raceManagerTail's doc in
        // RaceSequence.kt and `scholasticRaceManager tail matches the requested race-manager cadence`
        // below, which pins down what it uses instead (single ticks, not doubled).
        val sequences = (BuiltInSequences.all - BuiltInSequences.scholasticRaceManager) +
            BuiltInSequences.custom(totalMinutes = 3)
        for (seq in sequences) {
            for (sec in 5L downTo 1L) {
                val offset = sec * 1_000L
                val cue = seq.cues.firstOrNull { it.offsetMs == offset }
                assertNotNull("${seq.id} has no cue at $offset ms", cue)
                assertEquals("${seq.id} at $offset ms", 2, cue!!.signal.shortBlasts)
                assertEquals("${seq.id} at $offset ms", 0, cue.signal.longBlasts)
                assertEquals("${seq.id} at $offset ms", 0L, cue.signal.sustainedMs)
                // A blast, not a sync tick: this is the race's own countdown, not a drift correction.
                assertEquals("${seq.id} at $offset ms", CueVoice.BLAST, cue.signal.voice)
            }
        }
    }

    // --- Custom sequence ------------------------------------------------------

    @Test fun `custom sequence has correct totalMs`() {
        val seq = BuiltInSequences.custom(totalMinutes = 6)
        assertEquals(6 * 60_000L, seq.totalMs)
    }

    @Test fun `custom sequence contains gun at 0`() {
        val seq = BuiltInSequences.custom(totalMinutes = 2)
        assertTrue(seq.cues.any { it.isGun && it.offsetMs == 0L })
    }

    @Test fun `custom sequence cues are sorted descending`() {
        val seq = BuiltInSequences.custom(totalMinutes = 5)
        val offsets = seq.cues.map { it.offsetMs }
        assertEquals(offsets.sortedDescending(), offsets)
    }

    @Test fun `custom sounds one long blast on every whole minute down to 1 00`() {
        val seq = BuiltInSequences.custom(totalMinutes = 8)
        for (minute in 8 downTo 1) {
            assertPattern(seq, minute * 60_000L, longBlasts = 1, shortBlasts = 0)
        }
    }

    @Test fun `custom has no cue above its own duration`() {
        val seq = BuiltInSequences.custom(totalMinutes = 4)
        assertEquals(4 * 60_000L, seq.cues.maxOf { it.offsetMs })
    }

    @Test fun `custom below the minute is the scholastic tail, cue for cue`() {
        // The whole point of the re-spec: below 1:00 a Custom race must be indistinguishable from
        // Scholastic, so an unfamiliar duration still ends in a cadence the sailor already races to.
        val custom = BuiltInSequences.custom(totalMinutes = 7).cues.filter { it.offsetMs < 60_000L }
        val scholastic = BuiltInSequences.scholastic.cues.filter { it.offsetMs < 60_000L }
        assertEquals(scholastic, custom)
    }

    @Test fun `custom ends in the sustained gun, not a blast count`() {
        val gun = BuiltInSequences.custom(totalMinutes = 3).cues.last()
        assertTrue("last cue must be the gun", gun.isGun)
        assertEquals(0L, gun.offsetMs)
        assertEquals(3_000L, gun.signal.sustainedMs)
        assertEquals(0, gun.signal.longBlasts)
        assertEquals(0, gun.signal.shortBlasts)
    }

    @Test fun `a one-minute custom race is one long blast plus the scholastic tail`() {
        // The documented minimum, spelled out in full rather than by property: 1:00 is the one
        // duration where the minute cues and the tail meet with nothing in between, so it is the
        // case most likely to lose or double a cue if the composition changes.
        val seq = BuiltInSequences.custom(totalMinutes = 1)
        assertEquals(60_000L, seq.totalMs)

        val expected = listOf(
            SequenceCue(60_000L, SignalPattern(longBlasts = 1, label = "1 long — 1:00")),
        ) + BuiltInSequences.scholastic.cues.filter { it.offsetMs < 60_000L }
        assertEquals(expected, seq.cues)
    }

    @Test fun `custom clamps to the one-minute minimum rather than throwing`() {
        // Only reachable from a malformed persisted id — the picker enforces the minimum. A watch
        // about to time a race is the wrong place to raise.
        for (minutes in listOf(0, -1, Int.MIN_VALUE)) {
            val seq = BuiltInSequences.custom(totalMinutes = minutes)
            assertEquals("custom($minutes)", 60_000L, seq.totalMs)
            assertEquals("custom($minutes)", BuiltInSequences.customId(1), seq.id)
        }
    }

    // --- Resolving a sequence by id (the restore path) ------------------------

    @Test fun `resolve returns every built-in by its own id`() {
        for (seq in BuiltInSequences.all) {
            assertSame(seq.id, seq, BuiltInSequences.resolve(seq.id))
        }
    }

    @Test fun `resolve rebuilds a custom sequence from its id alone`() {
        // The restore path in full: the id is the only thing persistence keeps about the chosen
        // sequence, so if this does not reproduce the race, a killed process cannot come back as
        // itself. Before this existed the lookup searched `all`, missed, and silently substituted
        // US Sailing — wrong duration, wrong cues, no error.
        for (minutes in listOf(1, 2, 8, 45, 120)) {
            val original = BuiltInSequences.custom(totalMinutes = minutes)
            val restored = BuiltInSequences.resolve(original.id)
            assertEquals("custom $minutes restored", original, restored)
        }
    }

    @Test fun `resolve returns null rather than substituting for an id nothing answers to`() {
        for (id in listOf("", "custom_", "custom_m", "custom_0m", "custom_-3m", "custom_8", "custom_8s",
                          "custom_eightm", "us_sailing", "scholastic_2")) {
            assertNull("'$id' must not resolve", BuiltInSequences.resolve(id))
        }
    }

    @Test fun `custom name and id both carry the chosen duration`() {
        val seq = BuiltInSequences.custom(totalMinutes = 12)
        assertEquals("Custom 12:00", seq.name)
        assertEquals(12, BuiltInSequences.customMinutes(seq.id))
    }

    /** Assert the scholastic cue at [offsetMs] is exactly the given blast pattern. */
    private fun assertPattern(offsetMs: Long, longBlasts: Int, shortBlasts: Int) =
        assertPattern(BuiltInSequences.scholastic, offsetMs, longBlasts, shortBlasts)

    /** Assert [sequence]'s cue at [offsetMs] is exactly the given blast pattern. */
    private fun assertPattern(
        sequence: RaceSequence,
        offsetMs: Long,
        longBlasts: Int,
        shortBlasts: Int,
    ) {
        val cue = sequence.cues.firstOrNull { it.offsetMs == offsetMs }
        assertNotNull("no ${sequence.id} cue at $offsetMs ms", cue)
        assertEquals("long blasts at $offsetMs ms", longBlasts, cue!!.signal.longBlasts)
        assertEquals("short blasts at $offsetMs ms", shortBlasts, cue.signal.shortBlasts)
    }
}
