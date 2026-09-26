package com.racetimer.wear

import com.racetimer.android.WearablePairLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The watch advertises the Data Layer capability the pair link looks for (#219) — the phone's test
 * of the same name, for the other end. A capability either device fails to advertise is a link that
 * never forms, and nothing errors.
 */
@RunWith(RobolectricTestRunner::class)
class PairCapabilityDeclarationTest {

    @Test
    fun `the app advertises the capability the pair link queries`() {
        val app = RuntimeEnvironment.getApplication()
        // Positive control: the merged manifest was read, so a missing array below is a real absence.
        assertEquals("io.github.sailordave17.racetimer", app.packageName)

        val id = app.resources.getIdentifier("android_wear_capabilities", "array", app.packageName)
        assertNotEquals("android_wear_capabilities resolves in the app package", 0, id)
        val advertised = app.resources.getStringArray(id).toList()
        assertTrue("advertised $advertised", WearablePairLink.CAPABILITY in advertised)
    }
}
