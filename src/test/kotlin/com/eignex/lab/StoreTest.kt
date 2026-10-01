package com.eignex.lab

import java.nio.file.Files
import kotlin.test.Test
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoreTest {
    private fun store() = Store(Files.createTempDirectory("lab").resolve("lab.db"))

    @Test
    fun `a cancel from a second process lands while the other one keeps writing`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val runner = Store(file)
        val api = Store(file)
        val id = runner.create("a", "main", List(200) { "true" to 10L })
        runner.next()
        val writer = thread {
            repeat(200) { index ->
                runner.commandStarted(id, index)
                runner.commandFinished(id, index, 0)
            }
        }

        val cancelled = api.requestCancel(id)
        writer.join()

        assertTrue(cancelled == CancelOutcome.REQUESTED && runner.cancelRequested(id))
    }

    @Test
    fun `a job left running by a crashed runner is resumed before a queued one`() {
        val store = store()
        val crashed = store.create("a", "main", listOf("true" to 10L))
        store.create("b", "main", listOf("true" to 10L))
        store.next()

        assertEquals(crashed, store.next()?.id)
    }

    @Test
    fun `an interrupted command is queued again and finished ones keep their result`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L, "sleep 9" to 10L))
        store.next()
        store.commandStarted(id, 0)
        store.commandFinished(id, 0, 0)
        store.commandStarted(id, 1)

        store.requeueInterrupted(id)

        assertEquals(listOf(Status.DONE, Status.QUEUED), store.job(id)!!.commands.map { it.status })
    }

    @Test
    fun `cancelling a queued job cancels it without a runner`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L))

        store.requestCancel(id)

        assertEquals(Status.CANCELLED, store.job(id)!!.status)
    }

    @Test
    fun `cancelling a finished job reports it as finished`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L))
        store.next()
        store.finish(id, Status.DONE)

        assertEquals(CancelOutcome.FINISHED, store.requestCancel(id))
    }
}
