package com.racetimer.pairskew.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordTest {

    private val round = Record("ROUND", linkedMapOf("side" to "phone", "at" to "123", "t1" to "-5", "near" to "-"))

    @Test
    fun `a formatted record parses back to itself`() {
        assertEquals(round, Record.parse(round.format()))
    }

    @Test
    fun `a record behind a logcat prefix parses`() {
        val line = "09-25 16:40:01.123  1234  5678 I PairSkew: ${round.format()}"
        assertEquals(round, Record.parse(line))
    }

    @Test
    fun `a line that breaks the format is refused whole, never half-read`() {
        assertNull(Record.parse("v1 ROUND side=phone at"))
        assertNull(Record.parse("v1 ROUND side=phone side=wear"))
        assertNull(Record.parse("v1 ROUND side=phone at=1=2"))
        assertNull(Record.parse("v1 round side=phone"))
        assertNull(Record.parse("v1 ROUND side=phone  at=1"))
    }

    @Test
    fun `the version token must start a word`() {
        assertNull(Record.parse("xv1 ROUND side=phone"))
        assertNull(Record.parse("nothing to see here"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `format refuses a value the parser would split`() {
        Record("ERROR", mapOf("reason" to "two words")).format()
    }

    @Test
    fun `safe turns free text into one value`() {
        assertEquals("a_b_c", Record.safe("a b=c"))
        assertEquals("-", Record.safe(null))
        assertEquals("-", Record.safe(""))
        assertEquals(Record("ERROR", mapOf("reason" to "a_b_c")), Record.parse(Record("ERROR", mapOf("reason" to Record.safe("a b=c"))).format()))
    }
}
