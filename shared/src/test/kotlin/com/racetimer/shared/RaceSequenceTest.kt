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

    @Test fun `scholastic has 13 cues`() {
        assertEquals(13, BuiltInSequences.scholastic.cues.size)
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

    @Test fun `scholastic cues are sorted descending by offset`() {
        val offsets = BuiltInSequences.scholastic.cues.map { it.offsetMs }
        assertEquals(offsets.sortedDescending(), offsets)
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
}
