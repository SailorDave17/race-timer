package com.racetimer.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The companion link's outbox file (#294): what survives a process, and what bounds it. */
@RunWith(RobolectricTestRunner::class)
class CompanionOutboxTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val file: File get() = File(dir.root, CompanionOutbox.FILE_NAME)

    @Test
    fun `what is queued and the running sequence survive a new process, in order`() {
        CompanionOutbox(file).apply {
            beginSequence("s1")
            add("""{"id":"a"}""")
            add("""{"id":"b"}""")
            add("""{"id":"c"}""")
            remove("""{"id":"b"}""")
        }

        val reopened = CompanionOutbox(file)
        assertEquals(listOf("""{"id":"a"}""", """{"id":"c"}"""), reopened.pending)
        assertEquals("s1", reopened.sequence)
        assertEquals("""{"id":"a"}""", reopened.oldest())
    }

    @Test
    fun `past its bound the oldest event goes`() {
        val outbox = CompanionOutbox(file)
        repeat(CompanionOutbox.MAX_PENDING + 2) { outbox.add("""{"id":"$it"}""") }

        val reopened = CompanionOutbox(file)
        assertEquals(CompanionOutbox.MAX_PENDING, reopened.pending.size)
        assertEquals("""{"id":"2"}""", reopened.oldest())
    }

    @Test
    fun `clear deletes the file, and an empty outbox writes none`() {
        val outbox = CompanionOutbox(file)
        assertFalse("opening an outbox writes nothing", file.exists())
        outbox.add("""{"id":"a"}""")
        assertTrue(file.exists())

        outbox.clear()
        assertFalse(file.exists())
        assertTrue(CompanionOutbox(file).isEmpty)
        assertNull(CompanionOutbox(file).sequence)
    }

    @Test
    fun `an unreadable file starts an empty outbox rather than a crash`() {
        file.writeText("not json")
        val outbox = CompanionOutbox(file)
        assertTrue(outbox.isEmpty)
        outbox.add("""{"id":"a"}""")
        assertEquals(1, CompanionOutbox(file).pending.size)
    }
}
