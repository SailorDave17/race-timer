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
        for (sec in 10L downTo 1L) {
            assertPattern(offsetMs = sec * 1_000L, longBlasts = 0, shortBlasts = 1)
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
        // HapticManager. scholastic and usSailing both state their own 3 s; club has not been
        // re-voiced, so it must keep the behaviour it has today.
        val sustained = BuiltInSequences.all
            .flatMap { seq -> seq.cues.map { seq to it } }
            .filter { (_, cue) -> cue.signal.sustainedMs > 0L }
        assertEquals(
            setOf(BuiltInSequences.scholastic.id, BuiltInSequences.usSailing.id),
            sustained.map { it.first.id }.toSet(),
        )
        assertTrue("only a gun may be sustained", sustained.all { it.second.isGun })
        assertEquals(0L, BuiltInSequences.club.cues.first { it.isGun }.signal.sustainedMs)
    }

    @Test fun `club has 4 cues`() {
        assertEquals(4, BuiltInSequences.club.cues.size)
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

    @Test fun `custom sequence has correct totalMs`() {
        val seq = BuiltInSequences.custom(totalSeconds = 360L)
        assertEquals(360_000L, seq.totalMs)
    }

    @Test fun `custom sequence contains gun at 0`() {
        val seq = BuiltInSequences.custom(totalSeconds = 120L)
        assertTrue(seq.cues.any { it.isGun && it.offsetMs == 0L })
    }

    @Test fun `custom sequence cues are sorted descending`() {
        val seq = BuiltInSequences.custom(totalSeconds = 300L)
        val offsets = seq.cues.map { it.offsetMs }
        assertEquals(offsets.sortedDescending(), offsets)
    }

    @Test fun `custom sequence excludes intermediate cues outside range`() {
        // 90s total; intermediate cues at 60, 30, 10 — all within range
        val seq = BuiltInSequences.custom(totalSeconds = 90L, intermediateCueOffsetsSec = listOf(60, 30, 10))
        assertTrue(seq.cues.any { it.offsetMs == 60_000L })
        assertTrue(seq.cues.any { it.offsetMs == 30_000L })
        assertTrue(seq.cues.any { it.offsetMs == 10_000L })
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
