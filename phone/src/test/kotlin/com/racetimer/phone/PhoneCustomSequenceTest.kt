package com.racetimer.phone

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.LaunchNotice
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * #209's glue: a Custom duration is remembered across a cold launch, and a Custom race survives a
 * kill on the sequence it was actually running.
 *
 * The *mechanism* is not tested here and deliberately so. `custom_8m` encoding a duration,
 * `resolve` rebuilding the race from it, and `launchPlan` ranking a saved race above a remembered
 * pick are all `:shared`, all covered by the JVM suite, and all reached by the watch on the same
 * code. What this file covers is the part only the phone has: which values reach SharedPreferences,
 * which survive [PhoneRacePersistence.clear], and what the service hands a launch.
 *
 * Robolectric within #160's scope — prefs, services, clocks; never audio.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneCustomSequenceTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun service(): PhoneTimerService =
        Robolectric.buildService(PhoneTimerService::class.java).create().get()

    private fun start(svc: PhoneTimerService) {
        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_START), 0, 1)
    }

    @Test
    fun `a custom pick comes back on the next cold launch, at its own duration`() {
        val first = service()
        first.savePickedSequence(BuiltInSequences.custom(8).id)

        // A whole new service, as a cold launch is: nothing carries over but the prefs file.
        val plan = service().launchPlan()

        assertEquals("custom_8m", plan.sequence?.id)
        assertEquals(8 * 60_000L, plan.sequence?.totalMs)
        // The stepper reopens where it was left, derived from that same id.
        assertEquals(8, plan.customMinutes)
        // A pick is not a race: there is nothing to resume and nothing to apologise for.
        assertNull(plan.resumable)
        assertNull(plan.notice)
    }

    @Test
    fun `a running custom race round-trips through the snapshot and rebuilds identically`() {
        val first = Robolectric.buildService(PhoneTimerService::class.java).create()
        val chosen = BuiltInSequences.custom(3)
        first.get().runner.select(chosen)
        first.get().savePickedSequence(chosen.id)
        start(first.get())
        assertEquals(TimerState.RUNNING, first.get().runner.engine.currentState)

        // Destruction is the closest a JVM gets to a force-stop: no cleanup path runs that would
        // clear the snapshot.
        first.destroy()

        val plan = service().launchPlan()
        val restored = plan.sequence
        assertNotNull("the saved custom race resolved to nothing", restored)
        assertEquals(chosen.id, restored?.id)
        // Identical, not merely same-length: the cue list is what a sailor hears, and a rebuild that
        // got the duration right and the cues wrong would pass a totalMs check.
        assertEquals(chosen.cues, restored?.cues)
        assertNotNull("a race that was running should be offered back", plan.resumable)
    }

    @Test
    fun `the pick outlives the race it was made for`() {
        val svc = service()
        val chosen = BuiltInSequences.custom(12)
        svc.runner.select(chosen)
        svc.savePickedSequence(chosen.id)
        start(svc)

        // Stop is the ordinary end of a race, and it clears the snapshot. #88's defect was this
        // path taking the pick with it, so the app remembered the sequence only while a race was
        // running and forgot it in every ordinary case.
        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_STOP), 0, 2)

        val plan = service().launchPlan()
        assertNull("the race should be gone", plan.resumable)
        assertEquals("the pick should not be", chosen.id, plan.sequence?.id)
        assertEquals(12, plan.customMinutes)
    }

    @Test
    fun `a pick that resolves to nothing is announced rather than absorbed`() {
        // Only reachable by a downgrade or a corrupted file, which is exactly when a silent fallback
        // to the default would be least welcome: the shared plan reports it and the caller decides.
        service().savePickedSequence("custom_not_a_number")

        val plan = service().launchPlan()

        assertNull(plan.sequence)
        assertEquals(LaunchNotice.PICKED_SEQUENCE_UNREADABLE, plan.notice)
    }

    @Test
    fun `nothing remembered is nothing to say`() {
        // The positive control for every assertion above: on a first-ever launch the same call
        // returns an empty plan, so a plan that came back populated came back from persistence and
        // not from a default the service supplies on its own.
        val plan = service().launchPlan()

        assertNull(plan.sequence)
        assertNull(plan.customMinutes)
        assertNull(plan.resumable)
        assertNull(plan.notice)
    }
}
