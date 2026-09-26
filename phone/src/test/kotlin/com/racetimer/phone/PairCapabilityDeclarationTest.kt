package com.racetimer.phone

import com.racetimer.android.WearablePairLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The phone advertises the Data Layer capability the pair link looks for (#219).
 *
 * The capability is declared once, in `:shared-android`'s resources, and named again in
 * `WearablePairLink.CAPABILITY`. If the two ever disagree, or the declaration fails to merge into
 * this app, nothing errors: each device simply never finds the other, and the only place that shows
 * is a paired phone and watch in hand. So this reads it the way the Data Layer does — by name, from
 * the app's own package, in the merged resources.
 */
@RunWith(RobolectricTestRunner::class)
class PairCapabilityDeclarationTest {

    @Test
    fun `the app advertises the capability the pair link queries`() {
        val app = RuntimeEnvironment.getApplication()
        // Positive control: the merged manifest was read, so a missing array below is a real absence
        // and not Robolectric falling back to an empty package.
        assertEquals("io.github.sailordave17.racetimer", app.packageName)

        val id = app.resources.getIdentifier("android_wear_capabilities", "array", app.packageName)
        assertNotEquals("android_wear_capabilities resolves in the app package", 0, id)
        val advertised = app.resources.getStringArray(id).toList()
        assertTrue("advertised $advertised", WearablePairLink.CAPABILITY in advertised)
    }
}
