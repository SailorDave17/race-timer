package com.racetimer.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The companion's pins as built (#294 criterion 4). Whether the platform honours them is the device
 * test's to show (`harness/companion-link`); this holds what they are.
 */
class CompanionPinsTest {

    private val field = CompanionPins.FIELD_KEY

    @Test
    fun `every build pins the field key, in the companion's package`() {
        val pins = CompanionPins.forBuild()
        assertEquals(CompanionContract.PACKAGE, pins.packageName)
        assertArrayEquals(CompanionPins.of(listOf(field)).sha256.single(), pins.sha256.first())
    }

    @Test
    fun `a digest pasted from apksigner or keytool is read as written`() {
        val colons = field.uppercase().chunked(2).joinToString(":")
        val pins = CompanionPins.of(listOf(" $colons ", "", field))
        assertEquals("the same key twice is one pin", 1, pins.sha256.size)
        assertEquals(0x85.toByte(), pins.sha256.single()[0])
        assertEquals(0x06.toByte(), pins.sha256.single()[31])
    }

    @Test
    fun `anything that is not a SHA-256 digest is refused, not pinned`() {
        assertThrows(IllegalArgumentException::class.java) { CompanionPins.of(listOf(field.drop(2))) }
        assertThrows(IllegalArgumentException::class.java) { CompanionPins.of(listOf(field.dropLast(1) + "g")) }
    }
}
