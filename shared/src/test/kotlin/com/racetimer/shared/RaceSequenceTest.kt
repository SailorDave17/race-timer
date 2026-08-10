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

    @Test fun `every sync run counts into a real cue, and runs the full five`() {
        // GENERALISED for #105, deliberately — this used to enumerate the ten offsets usSailing was
        // then the only owner of, and assert every SYNC cue in `all` sat on one of them.
        //
        // The rule that test was written to protect is in its own comment: "a sync tick is not a
        // signal — if one ever appears outside these ten offsets, a sailor is being told a mark is
        // coming when none is." That rule is *a sync run announces something*. Enumerating where sync
        // was allowed enforced it only for as long as there was one legitimate user, and made a
        // second one — us_sailing_race_manager, which runs three — indistinguishable from the bug.
        //
        // Stated as the property, it covers every sequence including ones not written yet, and it is
        // strictly stronger: the old version would have accepted a run of four ticks, or a run into
        // an offset that had since lost its signal. What it no longer pins is that usSailing in
        // particular has runs at 4:00 and 1:00 — that was never this test's job and is asserted
        // outright by `usSailing runs five sync ticks into the 4 and 1 minute signals`, with the
        // matching test for the race-manager variant below.
        val sequences = BuiltInSequences.all + BuiltInSequences.custom(totalMinutes = 3)
        for (seq in sequences) {
            val syncOffsets = seq.cues.filter { it.signal.voice == CueVoice.SYNC }.map { it.offsetMs }
            val signalOffsets = seq.cues
                .filterNot { it.signal.voice == CueVoice.SYNC }
                .map { it.offsetMs }
                .toSet()

            // Each tick sits 1-5 s above a cue that actually sounds.
            val announced = syncOffsets.map { tick ->
                val target = (1L..5L).map { tick - it * 1_000L }.firstOrNull { it in signalOffsets }
                assertNotNull("${seq.id}: sync tick at $tick ms announces no cue", target)
                target!!
            }

            // And no run is short: five ticks, one per second, or the cadence means something else.
            for (target in announced.toSet()) {
                for (sec in 5L downTo 1L) {
                    assertTrue(
                        "${seq.id}: run into $target ms is missing its $sec-second tick",
                        target + sec * 1_000L in syncOffsets,
                    )
                }
            }
        }
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

    // --- US Sailing Race Manager (#105) ---------------------------------------

    @Test fun `usSailingRaceManager sounds 1 long at each of the three signals`() {
        // The headline difference from the sailor sequence, which sounds 1 short at 5:00 and 2 short
        // at 4:00 and 1:00. A race manager is sounding the signals rather than counting them, so the
        // long matches their own horn — and the iStart manual's Rule 26 loud-horn schedule is 1 long
        // at exactly these three marks.
        for (offset in listOf(5 * 60_000L, 4 * 60_000L, 1 * 60_000L)) {
            assertPattern(BuiltInSequences.usSailingRaceManager, offset, longBlasts = 1, shortBlasts = 0)
        }
    }

    @Test fun `usSailingRaceManager is silent at 3 and 2 minutes`() {
        // Deliberate, and the same reasoning that strips 0:50 and 0:40 out of the Scholastic
        // race-manager tail: those are a sailor's cross-check marks, and the committee is the thing
        // being cross-checked against. The manual's table has no entries there either.
        val marks = BuiltInSequences.usSailingRaceManager.cues
            .filter { it.offsetMs == 3 * 60_000L || it.offsetMs == 2 * 60_000L }
        assertEquals(emptyList<SequenceCue>(), marks)
    }

    @Test fun `usSailingRaceManager runs five sync ticks into each of its three signals`() {
        // Three runs, not two: the sailor sequence has none into the gun, and this replaces its
        // finalMinuteTail with one. All three are the same device, so the race manager learns one
        // cadence — and it stays categorically distinct from the signals at the one mark that must
        // never be anticipated by mistake.
        for (base in listOf(4 * 60_000L, 1 * 60_000L, 0L)) {
            for (sec in 5L downTo 1L) {
                val offset = base + sec * 1_000L
                val cue = BuiltInSequences.usSailingRaceManager.cues.firstOrNull { it.offsetMs == offset }
                assertNotNull("expected a sync tick at $offset ms", cue)
                assertEquals("sync tick at $offset ms", CueVoice.SYNC, cue!!.signal.voice)
                assertEquals(1, cue.signal.shortBlasts)
                assertEquals(0, cue.signal.longBlasts)
            }
        }
    }

    @Test fun `usSailingRaceManager has no doubled final five, unlike a sailor's countdown`() {
        // What it has instead of finalMinuteTail. Stated as its own assertion rather than left to
        // the exclusion in `every sailor sequence doubles the last five seconds` — an exclusion says
        // only that the cadence is absent, and the point is that a *different* one is present.
        for (sec in 5L downTo 1L) {
            val cue = BuiltInSequences.usSailingRaceManager.cues.first { it.offsetMs == sec * 1_000L }
            assertEquals("at $sec s", CueVoice.SYNC, cue.signal.voice)
            assertEquals("at $sec s", 1, cue.signal.shortBlasts)
        }
    }

    @Test fun `usSailingRaceManager has nothing between 0-59 and 0-06`() {
        // No finalMinuteTail: the 0:50/0:40 warnings, the 3-short/2-short calls at 0:30/0:20 and the
        // single ticks from 0:10 to 0:06 are all a sailor's, and none of them are here.
        val belowTheMinute = BuiltInSequences.usSailingRaceManager.cues.filter { it.offsetMs in 6_000L..59_000L }
        assertEquals(emptyList<SequenceCue>(), belowTheMinute)
    }

    @Test fun `usSailingRaceManager has 19 cues`() {
        // 3 signals + 3 sync runs of 5 + the gun.
        assertEquals(19, BuiltInSequences.usSailingRaceManager.cues.size)
    }

    @Test fun `usSailingRaceManager totalMs is 5 minutes`() {
        assertEquals(5 * 60_000L, BuiltInSequences.usSailingRaceManager.totalMs)
        assertEquals(5 * 60_000L, BuiltInSequences.usSailingRaceManager.sequenceMs)
        assertEquals(0L, BuiltInSequences.usSailingRaceManager.leadInMs)
    }

    @Test fun `usSailingRaceManager cues are sorted descending and unique`() {
        val offsets = BuiltInSequences.usSailingRaceManager.cues.map { it.offsetMs }
        assertEquals(offsets.sortedDescending(), offsets)
        assertEquals("no offset may fire twice", offsets.size, offsets.distinct().size)
    }

    @Test fun `usSailingRaceManager ends in the shared sustained gun`() {
        val gun = BuiltInSequences.usSailingRaceManager.cues.last()
        assertTrue("last cue must be the gun", gun.isGun)
        assertEquals(0L, gun.offsetMs)
        assertEquals(3_000L, gun.signal.sustainedMs)
        // Identity, not equality: the same cue object usSailing and scholastic end on, so a change
        // to the gun cannot reach four sequences and miss the fifth.
        assertSame(BuiltInSequences.usSailing.cues.last(), gun)
    }

    @Test fun `usSailing is byte-for-byte unchanged by the race-manager variant`() {
        // AC 2, asserted rather than inspected. The two sequences share offsets and share nothing
        // else, so the risk this guards is a well-meant extraction of a "common head" — the mistake
        // #59 shipped in reverse, assuming two tails that looked alike were alike.
        val sailor = BuiltInSequences.usSailing
        assertEquals(30, sailor.cues.size)
        assertEquals(5 * 60_000L, sailor.totalMs)
        assertFalse("usSailing is a sailor's race", sailor.countUpAfterFinish)
        assertEquals("no usSailing cue may use a long blast", emptyList<SequenceCue>(),
            sailor.cues.filter { it.signal.longBlasts > 0 })
        // Its own voicing at the three marks the race-manager variant re-voices to 1 long.
        assertPattern(sailor, 5 * 60_000L, longBlasts = 0, shortBlasts = 1)
        assertPattern(sailor, 4 * 60_000L, longBlasts = 0, shortBlasts = 2)
        assertPattern(sailor, 1 * 60_000L, longBlasts = 0, shortBlasts = 2)
        // And the parts the race manager drops entirely: the plain minute ticks and the sailor tail.
        assertPattern(sailor, 3 * 60_000L, longBlasts = 0, shortBlasts = 1)
        assertPattern(sailor, 2 * 60_000L, longBlasts = 0, shortBlasts = 1)
        assertEquals(
            BuiltInSequences.scholastic.cues.filter { it.offsetMs < 60_000L },
            sailor.cues.filter { it.offsetMs < 60_000L },
        )
    }

    @Test fun `the two US Sailing variants share their offsets and nothing else`() {
        // The reason no head is extracted. Same marks, different voice at every one of them — an
        // extraction would have to be parameterised on each cue it contained, which is a copy with
        // extra steps and two places to edit.
        val sailor = BuiltInSequences.usSailing
        val manager = BuiltInSequences.usSailingRaceManager
        for (offset in listOf(5 * 60_000L, 4 * 60_000L, 1 * 60_000L)) {
            val a = sailor.cues.first { it.offsetMs == offset }
            val b = manager.cues.first { it.offsetMs == offset }
            assertNotEquals("cues at $offset ms must not be the same signal", a.signal, b.signal)
        }
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
        // Blast lengths come from CueTiming, which HapticManager and ToneManager both play a cue on.
        // A cue that runs past its successor would overlap it on both channels. This used to carry a
        // hand-copied mirror of those constants back when CueTiming lived in the wear module and was
        // out of reach here — so a retune there would have left this test asserting the old shape.
        // Now covers every built-in, not just scholastic: usSailing's sync ticks fire at 1-second
        // spacing, the tightest gap anywhere, and 4:01 must not bleed into the signal it announces.
        for (seq in BuiltInSequences.all) {
            for ((cue, next) in seq.cues.zipWithNext()) {
                val patternMs = CueTiming.durationMs(cue.signal, cue.isGun)
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
        // HapticManager. Every sequence but club states its own 3 s (the race-manager variants by
        // sharing the same sustainedGun cue); club has taken on the final five but nothing else, so
        // its gun must keep the behaviour it has today.
        //
        // #105 adds us_sailing_race_manager to the list rather than changing what is asserted: the
        // new sequence ends in the same shared sustainedGun, which is the point — "same as every
        // other sequence" is true of all four of these and remains untrue of club.
        val sustained = BuiltInSequences.all
            .flatMap { seq -> seq.cues.map { seq to it } }
            .filter { (_, cue) -> cue.signal.sustainedMs > 0L }
        assertEquals(
            setOf(
                BuiltInSequences.scholastic.id,
                BuiltInSequences.usSailing.id,
                BuiltInSequences.usSailingRaceManager.id,
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

    @Test fun `the race-manager modes are exactly the sequences that count up after the gun`() {
        // RENAMED AND WIDENED for #105: the old name — `scholasticRaceManager is the only sequence
        // that counts up after the gun` — became false the moment a second committee mode existed.
        //
        // The assertion itself is unchanged in kind, and deliberately so. It still pins the whole
        // set rather than checking membership, because its job is to catch a sequence *quietly*
        // opting in: countUpAfterFinish is not only the count-up flag, it is what `offersLeadIn`
        // reads to decide which sequences may be armed with a lead-in (#104), so a sailor sequence
        // acquiring it by accident both breaks the reset at the gun and hands sailors a control
        // built for a committee boat. Two things, one flag, one guard.
        val countUp = BuiltInSequences.all.filter { it.countUpAfterFinish }
        assertEquals(
            listOf(BuiltInSequences.usSailingRaceManager, BuiltInSequences.scholasticRaceManager),
            countUp,
        )
    }

    @Test fun `no sailor sequence counts up`() {
        // The same rule by exclusion, and kept as its own test rather than folded into the one above
        // because the two fail differently: that one fails when the *set* is wrong, in either
        // direction, while this one names the offending sailor sequence in its message. A committee
        // flag on a sailor sequence is the dangerous direction — it is the one that changes what
        // happens at the gun of a race someone is sailing.
        val raceManagers = setOf(
            BuiltInSequences.usSailingRaceManager.id,
            BuiltInSequences.scholasticRaceManager.id,
        )
        for (seq in BuiltInSequences.all) {
            if (seq.id in raceManagers) continue
            assertFalse("${seq.id} should not set countUpAfterFinish", seq.countUpAfterFinish)
        }
        assertFalse("custom is a sailor's race", BuiltInSequences.custom(5).countUpAfterFinish)
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

    @Test fun `every sailor sequence doubles the last five seconds, but no race-manager mode does`() {
        // Iterated over `all` plus a custom rather than asserted per sequence: the point of this
        // cadence is that it is universal, so a sequence added later must not be able to opt out
        // quietly. If a new sequence lands without the final five, this is what should catch it.
        //
        // TWO named exceptions now, both race-manager modes, and both for the same reason rather
        // than by coincidence — the doubled final five is a *sailor's* phase-change signal, marking
        // the seconds they live through with their head up and their hands full. A race manager is
        // sounding that countdown, not racing to it.
        //
        //  - scholasticRaceManager uses single ticks (raceManagerTail's doc in RaceSequence.kt, and
        //    `scholasticRaceManager tail matches the requested race-manager cadence` below).
        //  - usSailingRaceManager (#105) uses a CueVoice.SYNC run into the gun instead, so its last
        //    five seconds are not blasts at all — pinned by `usSailingRaceManager runs five sync
        //    ticks into each of its three signals`.
        //
        // Adding an exception here is therefore not a weakening: each exclusion has a test naming
        // what that sequence does instead, so nothing has become unasserted by being excluded.
        val raceManagers = listOf(
            BuiltInSequences.usSailingRaceManager,
            BuiltInSequences.scholasticRaceManager,
        )
        val sequences = (BuiltInSequences.all - raceManagers.toSet()) +
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

    // --- Nothing fires after the gun (#126) -------------------------------------

    @Test fun `no built-in sequence has a cue after the gun`() {
        // Load-bearing for the doze answer in docs/timing-accuracy.md, and stated there as a fact
        // about the sequences rather than a hope. TimerService releases the wake lock at the gun for
        // a countUpAfterFinish sequence — deliberately, since a committee count-up is unbounded and
        // has nothing left to sound — and lets the watch suspend for the rest of the race.
        //
        // That is safe only while the cue queue is empty by then. A cue at a negative offset would
        // be a cue scheduled into a window where the CPU is allowed to sleep and the handler posts on
        // the uptime clock, so it could be deferred indefinitely with nothing reporting it. The
        // reasoning lives in a document; this is what stops the document quietly becoming wrong.
        for (sequence in BuiltInSequences.all) {
            val afterGun = sequence.cues.filter { it.offsetMs < 0L }
            assertTrue(
                "${sequence.id} has ${afterGun.size} cue(s) after the gun: " +
                    afterGun.map { it.signal.label },
                afterGun.isEmpty(),
            )
        }
    }

    @Test fun `every count-up sequence ends on its gun`() {
        // The sharper half of the rule above, aimed at exactly the sequences that release the wake
        // lock: it is not enough that no cue sits *after* the gun — the gun must be the last cue, so
        // the queue is provably drained at the moment COUNTING_UP begins.
        val countUp = BuiltInSequences.all.filter { it.countUpAfterFinish }
        assertTrue("no count-up sequences found; this test has stopped testing anything", countUp.isNotEmpty())
        for (sequence in countUp) {
            val last = sequence.cues.last()
            assertTrue("${sequence.id} does not end on its gun", last.isGun)
            assertEquals("${sequence.id} gun is not at offset 0", 0L, last.offsetMs)
        }
    }
}
