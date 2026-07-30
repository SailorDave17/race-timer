package com.racetimer.shared

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [BuiltInSequences] to verify sequence definitions are correct.
 */
class RaceSequenceTest {

    @Test fun `usSailing has 4 cues`() {
        assertEquals(4, BuiltInSequences.usSailing.cues.size)
    }

    @Test fun `usSailing totalMs is 5 minutes`() {
        assertEquals(5 * 60_000L, BuiltInSequences.usSailing.totalMs)
    }

    @Test fun `usSailing last cue is gun at 0ms`() {
        val gun = BuiltInSequences.usSailing.cues.last()
        assertEquals(0L, gun.offsetMs)
        assertTrue(gun.isGun)
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

    @Test fun `no scholastic cue outruns the gap to the next one`() {
        // Blast lengths mirror CueTiming in the wear module, which HapticManager and ToneManager
        // both play a cue on. A cue that runs past its successor would overlap it on both channels.
        val longMs = 500L + 250L
        val shortMs = 150L + 150L
        val cues = BuiltInSequences.scholastic.cues
        for ((cue, next) in cues.zipWithNext()) {
            val patternMs = cue.signal.longBlasts * longMs + cue.signal.shortBlasts * shortMs
            val gapMs = cue.offsetMs - next.offsetMs
            assertTrue(
                "cue at ${cue.offsetMs} ms runs $patternMs ms into a $gapMs ms gap",
                patternMs <= gapMs,
            )
        }
    }

    @Test fun `only the scholastic gun is sustained`() {
        // usSailing and club gun cues carry no sustainedMs, which is what keeps them on the
        // existing triple-buzz branch in HapticManager.
        val sustained = BuiltInSequences.all
            .flatMap { seq -> seq.cues.map { seq to it } }
            .filter { (_, cue) -> cue.signal.sustainedMs > 0L }
        assertEquals(1, sustained.size)
        val (sequence, cue) = sustained.single()
        assertEquals(BuiltInSequences.scholastic.id, sequence.id)
        assertTrue(cue.isGun)
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
    private fun assertPattern(offsetMs: Long, longBlasts: Int, shortBlasts: Int) {
        val cue = BuiltInSequences.scholastic.cues.firstOrNull { it.offsetMs == offsetMs }
        assertNotNull("no scholastic cue at $offsetMs ms", cue)
        assertEquals("long blasts at $offsetMs ms", longBlasts, cue!!.signal.longBlasts)
        assertEquals("short blasts at $offsetMs ms", shortBlasts, cue.signal.shortBlasts)
    }
}
