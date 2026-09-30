package com.eignex.lab

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreTest {
    private fun store() = Store(Files.createTempDirectory("lab").resolve("lab.db"))

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
}
