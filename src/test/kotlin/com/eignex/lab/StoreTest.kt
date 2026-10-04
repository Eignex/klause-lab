package com.eignex.lab

import java.nio.file.Files
import java.sql.DriverManager
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

    @Test
    fun `a running job's parallel limit can be changed`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L), parallel = 2)
        store.next()

        store.setParallel(id, 4)

        assertEquals(4, store.parallel(id))
    }

    @Test
    fun `a database from before the parallel column runs its jobs serially`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { old ->
            old.createStatement().use {
                it.execute(
                    """CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ref TEXT NOT NULL,
                        sha TEXT, status TEXT NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0,
                        setup_done INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, started_at INTEGER,
                        finished_at INTEGER, error TEXT)""",
                )
                it.execute("INSERT INTO jobs (name, ref, status, created_at) VALUES ('old', 'main', 'QUEUED', 0)")
            }
        }

        val job = Store(file).jobs().single()

        assertEquals(1, job.parallel)
    }

    @Test
    fun `the highest priority queued job is taken first`() {
        val store = store()
        store.create("low", "main", listOf("true" to 10L))
        val high = store.create("high", "main", listOf("true" to 10L), priority = 5)

        assertEquals(high, store.next()?.id)
    }

    @Test
    fun `a paused queued job is passed over until it resumes`() {
        val store = store()
        val held = store.create("held", "main", listOf("true" to 10L))
        store.setPaused(held, true)
        val nextWhilePaused = store.next()?.id
        store.setPaused(held, false)

        assertEquals(listOf(null, held), listOf(nextWhilePaused, store.next()?.id))
    }

    @Test
    fun `a running job yields to a higher priority job that arrives`() {
        val store = store()
        val running = store.create("running", "main", listOf("true" to 10L, "true" to 10L))
        store.next()
        val before = store.shouldYield(running)
        store.create("urgent", "main", listOf("true" to 10L), priority = 1)

        assertEquals(listOf(false, true), listOf(before, store.shouldYield(running)))
    }

    @Test
    fun `a yielded job keeps its finished commands and is taken again`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L, "true" to 10L))
        store.next()
        store.commandStarted(id, 0)
        store.commandFinished(id, 0, 0)
        store.requeue(id)

        val resumed = store.next()

        assertEquals(listOf(Status.DONE, Status.QUEUED), resumed?.commands?.map { it.status })
    }

    @Test
    fun `a schedule keeps its commands and remembers the commit it last queued`() {
        val store = store()
        val id = store.createSchedule("status", "main", listOf(CommandSpec("true", 5L)), 2, -1, 3600)
        store.scheduleRan(id, "abc123", 7L)

        val schedule = store.schedules().single()

        assertEquals(
            listOf(listOf(CommandSpec("true", 5L)), "abc123", 7L, -1),
            listOf(schedule.commands, schedule.lastSha, schedule.lastJob, schedule.priority),
        )
    }
}
