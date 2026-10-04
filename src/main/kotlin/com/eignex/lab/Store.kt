package com.eignex.lab

import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Properties

@Serializable
enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

@Serializable
data class Command(
    val index: Int,
    val cmd: String,
    val timeoutSec: Long,
    val status: Status,
    val exitCode: Int? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

@Serializable
data class Job(
    val id: Long,
    val name: String,
    val ref: String,
    val sha: String? = null,
    val status: Status,
    val cancelRequested: Boolean,
    val setupDone: Boolean,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
    /** How many of the job's commands may run at once. The runner rereads it, so it can change mid-job. */
    val parallel: Int = 1,
    /** The runner takes the highest priority first, the oldest first within one. */
    val priority: Int = 0,
    /** A paused job is not started, and a running one stops starting commands and goes back to the queue. */
    val paused: Boolean = false,
    val commands: List<Command> = emptyList(),
) {
    val done: Int get() = commands.count { it.status == Status.DONE || it.status == Status.FAILED }
    val failed: Int get() = commands.count { it.status == Status.FAILED }
}

enum class CancelOutcome { CANCELLED, REQUESTED, FINISHED, MISSING }

/**
 * The job queue, persisted in SQLite so that neither a crashed runner nor a reboot loses queued or finished work.
 * Every state change is one transaction; WAL mode lets the API read while the runner writes.
 */
class Store(file: Path) {
    // IMMEDIATE takes the write lock at BEGIN. A deferred transaction that reads and then writes cannot wait for a
    // lock the other process holds, and fails with SQLITE_BUSY instead of honouring the busy timeout.
    private val connection: Connection = DriverManager.getConnection(
        "jdbc:sqlite:$file",
        Properties().apply { setProperty("transaction_mode", "IMMEDIATE") },
    ).apply {
        createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA synchronous=FULL")
            it.execute("PRAGMA busy_timeout=10000")
            it.execute(
                """CREATE TABLE IF NOT EXISTS jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ref TEXT NOT NULL, sha TEXT,
                    status TEXT NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0,
                    setup_done INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, started_at INTEGER,
                    finished_at INTEGER, error TEXT)""",
            )
            it.execute(
                """CREATE TABLE IF NOT EXISTS commands (
                    job_id INTEGER NOT NULL, idx INTEGER NOT NULL, cmd TEXT NOT NULL, timeout_sec INTEGER NOT NULL,
                    status TEXT NOT NULL, exit_code INTEGER, started_at INTEGER, finished_at INTEGER,
                    PRIMARY KEY (job_id, idx))""",
            )
            // A database created before the column existed gains it here; every older job ran serially.
            val columns = it.executeQuery("PRAGMA table_info(jobs)").use { rows ->
                generateSequence { if (rows.next()) rows.getString("name") else null }.toSet()
            }
            if ("parallel" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN parallel INTEGER NOT NULL DEFAULT 1")
            if ("priority" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
            if ("paused" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN paused INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Synchronized
    fun create(
        name: String,
        ref: String,
        commands: List<Pair<String, Long>>,
        parallel: Int = 1,
        priority: Int = 0,
    ): Long = transaction {
        val id = connection.prepareStatement(
            "INSERT INTO jobs (name, ref, status, created_at, parallel, priority) VALUES (?, ?, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setString(1, name)
            it.setString(2, ref)
            it.setString(3, Status.QUEUED.name)
            it.setLong(4, now())
            it.setInt(5, parallel)
            it.setInt(6, priority)
            it.executeUpdate()
            it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
        connection.prepareStatement(
            "INSERT INTO commands (job_id, idx, cmd, timeout_sec, status) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            commands.forEachIndexed { index, (cmd, timeout) ->
                statement.setLong(1, id)
                statement.setInt(2, index)
                statement.setString(3, cmd)
                statement.setLong(4, timeout)
                statement.setString(5, Status.QUEUED.name)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        id
    }

    @Synchronized
    fun jobs(limit: Int = 200): List<Job> = connection.prepareStatement(
        "SELECT * FROM jobs ORDER BY id DESC LIMIT ?",
    ).use { statement ->
        statement.setInt(1, limit)
        statement.executeQuery().use { rows -> generateSequence { if (rows.next()) job(rows) else null }.toList() }
    }.map { it.copy(commands = commands(it.id)) }

    @Synchronized
    fun job(id: Long): Job? = connection.prepareStatement("SELECT * FROM jobs WHERE id = ?").use { statement ->
        statement.setLong(1, id)
        statement.executeQuery().use { rows -> if (rows.next()) job(rows) else null }
    }?.let { it.copy(commands = commands(it.id)) }

    /** The job to work on: one left RUNNING by a crashed runner first, else the queued, unpaused job with the highest
     *  priority, oldest first, claimed. */
    @Synchronized
    fun next(): Job? = transaction {
        val running = connection.prepareStatement("SELECT id FROM jobs WHERE status = ? ORDER BY id LIMIT 1").use {
            it.setString(1, Status.RUNNING.name)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }
        val id = running ?: connection.prepareStatement(
            "SELECT id FROM jobs WHERE status = ? AND cancel_requested = 0 AND paused = 0 " +
                "ORDER BY priority DESC, id LIMIT 1",
        ).use {
            it.setString(1, Status.QUEUED.name)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }?.also { claimed ->
            connection.prepareStatement("UPDATE jobs SET status = ?, started_at = ? WHERE id = ?").use {
                it.setString(1, Status.RUNNING.name)
                it.setLong(2, now())
                it.setLong(3, claimed)
                it.executeUpdate()
            }
        }
        id
    }?.let(::job)

    /** A command a crashed runner left RUNNING is rerun from the start: its partial output is not a result. */
    @Synchronized
    fun requeueInterrupted(jobId: Long) = update(
        "UPDATE commands SET status = ?, started_at = NULL WHERE job_id = ? AND status = ?",
        Status.QUEUED.name, jobId, Status.RUNNING.name,
    )

    @Synchronized
    fun setup(jobId: Long, sha: String) = update("UPDATE jobs SET sha = ?, setup_done = 1 WHERE id = ?", sha, jobId)

    @Synchronized
    fun commandStarted(jobId: Long, index: Int) = update(
        "UPDATE commands SET status = ?, started_at = ?, exit_code = NULL WHERE job_id = ? AND idx = ?",
        Status.RUNNING.name, now(), jobId, index,
    )

    @Synchronized
    fun commandFinished(jobId: Long, index: Int, exitCode: Int) = update(
        "UPDATE commands SET status = ?, exit_code = ?, finished_at = ? WHERE job_id = ? AND idx = ?",
        if (exitCode == 0) Status.DONE.name else Status.FAILED.name, exitCode, now(), jobId, index,
    )

    @Synchronized
    fun finish(jobId: Long, status: Status, error: String? = null) = update(
        "UPDATE jobs SET status = ?, finished_at = ?, error = ? WHERE id = ?",
        status.name, now(), error, jobId,
    )

    @Synchronized
    fun requestCancel(jobId: Long): CancelOutcome = transaction {
        val job = job(jobId) ?: return@transaction CancelOutcome.MISSING
        when (job.status) {
            Status.QUEUED -> {
                update("UPDATE jobs SET status = ?, cancel_requested = 1, finished_at = ? WHERE id = ?",
                    Status.CANCELLED.name, now(), jobId)
                update("UPDATE commands SET status = ? WHERE job_id = ? AND status = ?",
                    Status.CANCELLED.name, jobId, Status.QUEUED.name)
                CancelOutcome.CANCELLED
            }
            Status.RUNNING -> {
                update("UPDATE jobs SET cancel_requested = 1 WHERE id = ?", jobId)
                CancelOutcome.REQUESTED
            }
            else -> CancelOutcome.FINISHED
        }
    }

    /** Set how many of [jobId]'s commands may run at once; false when there is no such job. */
    @Synchronized
    fun setPriority(jobId: Long, priority: Int): Boolean =
        update("UPDATE jobs SET priority = ? WHERE id = ?", priority, jobId) == 1

    /** Pause or resume a job that has not finished. False when there is no such job or it already finished. */
    @Synchronized
    fun setPaused(jobId: Long, paused: Boolean): Boolean = update(
        "UPDATE jobs SET paused = ? WHERE id = ? AND status IN (?, ?)",
        if (paused) 1 else 0, jobId, Status.QUEUED.name, Status.RUNNING.name,
    ) == 1

    /** Whether running [jobId] should stop starting commands: it was paused, or a job it must give way to waits. */
    @Synchronized
    fun shouldYield(jobId: Long): Boolean = connection.prepareStatement(
        "SELECT (SELECT paused FROM jobs WHERE id = ?) OR EXISTS (SELECT 1 FROM jobs WHERE status = ? " +
            "AND cancel_requested = 0 AND paused = 0 AND priority > (SELECT priority FROM jobs WHERE id = ?))",
    ).use {
        it.setLong(1, jobId)
        it.setString(2, Status.QUEUED.name)
        it.setLong(3, jobId)
        it.executeQuery().use { rows -> rows.next() && rows.getInt(1) == 1 }
    }

    /** Put a running job that yielded back in the queue; its finished commands and its worktree stay. */
    @Synchronized
    fun requeue(jobId: Long) = update(
        "UPDATE jobs SET status = ? WHERE id = ? AND status = ?", Status.QUEUED.name, jobId, Status.RUNNING.name,
    )

    @Synchronized
    fun setParallel(jobId: Long, parallel: Int): Boolean =
        update("UPDATE jobs SET parallel = ? WHERE id = ?", parallel, jobId) == 1

    @Synchronized
    fun parallel(jobId: Long): Int = connection.prepareStatement("SELECT parallel FROM jobs WHERE id = ?").use {
        it.setLong(1, jobId)
        it.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 1 }
    }

    @Synchronized
    fun cancelRequested(jobId: Long): Boolean = connection.prepareStatement(
        "SELECT cancel_requested FROM jobs WHERE id = ?",
    ).use {
        it.setLong(1, jobId)
        it.executeQuery().use { rows -> rows.next() && rows.getInt(1) == 1 }
    }

    @Synchronized
    fun cancelRemaining(jobId: Long) = update(
        "UPDATE commands SET status = ? WHERE job_id = ? AND status IN (?, ?)",
        Status.CANCELLED.name, jobId, Status.QUEUED.name, Status.RUNNING.name,
    )

    private fun commands(jobId: Long): List<Command> = connection.prepareStatement(
        "SELECT * FROM commands WHERE job_id = ? ORDER BY idx",
    ).use { statement ->
        statement.setLong(1, jobId)
        statement.executeQuery().use { rows ->
            generateSequence {
                if (!rows.next()) {
                    null
                } else {
                    Command(
                        rows.getInt("idx"), rows.getString("cmd"), rows.getLong("timeout_sec"),
                        Status.valueOf(rows.getString("status")), rows.intOrNull("exit_code"),
                        rows.longOrNull("started_at"), rows.longOrNull("finished_at"),
                    )
                }
            }.toList()
        }
    }

    private fun job(rows: ResultSet) = Job(
        rows.getLong("id"), rows.getString("name"), rows.getString("ref"), rows.getString("sha"),
        Status.valueOf(rows.getString("status")), rows.getInt("cancel_requested") == 1, rows.getInt("setup_done") == 1,
        rows.getLong("created_at"), rows.longOrNull("started_at"), rows.longOrNull("finished_at"),
        rows.getString("error"), rows.getInt("parallel"), rows.getInt("priority"), rows.getInt("paused") == 1,
    )

    private fun update(sql: String, vararg values: Any?) = connection.prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

    private fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
}

fun now(): Long = System.currentTimeMillis()
