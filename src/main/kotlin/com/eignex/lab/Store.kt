package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
    /** Set for an experiment, whose commands the runner writes from it when it plans the job. */
    val experiment: ExperimentSpec? = null,
    val commands: List<Command> = emptyList(),
) {
    val done: Int get() = commands.count { it.status == Status.DONE || it.status == Status.FAILED }
    val failed: Int get() = commands.count { it.status == Status.FAILED }
}

enum class CancelOutcome { CANCELLED, REQUESTED, FINISHED, MISSING }

/** An experiment arm as planned: its configuration and the commit its ref resolved to. */
@Serializable
data class PlannedArm(val arm: Arm, val sha: String)

/** One case of an experiment, with the record its `solve-one` wrote once it ran. */
@Serializable
data class CaseResult(
    val index: Int,
    val status: Status,
    val problem: Problem,
    val arm: String,
    val seed: Long? = null,
    val record: JsonElement? = null,
    /** Which of the identical runs of its (problem, arm, seed) this is, from 0. */
    val repeat: Int = 0,
)

/**
 * An experiment queued again whenever [ref] moves: the runner checks it at most every [intervalSec], and queues a run
 * with every arm at the commit the ref resolves to when that commit is not [lastSha] and the previous run has ended.
 * A schedule from before experiments has no [experiment] and queues nothing.
 */
@Serializable
data class Schedule(
    val id: Long,
    val name: String,
    val ref: String,
    val experiment: ExperimentSpec?,
    val parallel: Int,
    val priority: Int,
    val intervalSec: Long,
    val lastSha: String? = null,
    val lastJob: Long? = null,
    val checkedAt: Long? = null,
)

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
            it.execute(
                """CREATE TABLE IF NOT EXISTS schedules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ref TEXT NOT NULL, commands TEXT NOT NULL,
                    parallel INTEGER NOT NULL, priority INTEGER NOT NULL, interval_sec INTEGER NOT NULL,
                    last_sha TEXT, last_job INTEGER, checked_at INTEGER)""",
            )
            // A database created before the column existed gains it here; every older job ran serially.
            val columns = it.executeQuery("PRAGMA table_info(jobs)").use { rows ->
                generateSequence { if (rows.next()) rows.getString("name") else null }.toSet()
            }
            if ("parallel" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN parallel INTEGER NOT NULL DEFAULT 1")
            if ("priority" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
            if ("paused" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN paused INTEGER NOT NULL DEFAULT 0")
            if ("experiment" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN experiment TEXT")
            val scheduleColumns = it.executeQuery("PRAGMA table_info(schedules)").use { rows ->
                generateSequence { if (rows.next()) rows.getString("name") else null }.toSet()
            }
            if ("experiment" !in scheduleColumns) it.execute("ALTER TABLE schedules ADD COLUMN experiment TEXT")
            it.execute(
                """CREATE TABLE IF NOT EXISTS arms (
                    job_id INTEGER NOT NULL, idx INTEGER NOT NULL, label TEXT NOT NULL, arm TEXT NOT NULL,
                    sha TEXT NOT NULL, PRIMARY KEY (job_id, idx))""",
            )
            it.execute(
                """CREATE TABLE IF NOT EXISTS problems (
                    job_id INTEGER NOT NULL, idx INTEGER NOT NULL, problem TEXT NOT NULL, PRIMARY KEY (job_id, idx))""",
            )
            it.execute(
                """CREATE TABLE IF NOT EXISTS cases (
                    job_id INTEGER NOT NULL, idx INTEGER NOT NULL, problem_idx INTEGER NOT NULL,
                    arm_idx INTEGER NOT NULL, seed INTEGER, record TEXT, repeat INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (job_id, idx))""",
            )
            // A database from before repeats gains the column; every case it holds was its seed's only run.
            val caseColumns = it.executeQuery("PRAGMA table_info(cases)").use { rows ->
                generateSequence { if (rows.next()) rows.getString("name") else null }.toSet()
            }
            if ("repeat" !in caseColumns) it.execute("ALTER TABLE cases ADD COLUMN repeat INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Synchronized
    fun create(
        name: String,
        ref: String,
        commands: List<Pair<String, Long>>,
        parallel: Int = 1,
        priority: Int = 0,
        experiment: ExperimentSpec? = null,
    ): Long = transaction {
        val id = connection.prepareStatement(
            "INSERT INTO jobs (name, ref, status, created_at, parallel, priority, experiment) VALUES (?, ?, ?, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setString(1, name)
            it.setString(2, ref)
            it.setString(3, Status.QUEUED.name)
            it.setLong(4, now())
            it.setInt(5, parallel)
            it.setInt(6, priority)
            it.setString(7, experiment?.let { spec -> Json.encodeToString(spec) })
            it.executeUpdate()
            it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
        insertCommands(id, commands)
        id
    }

    private fun insertCommands(jobId: Long, commands: List<Pair<String, Long>>) = connection.prepareStatement(
        "INSERT INTO commands (job_id, idx, cmd, timeout_sec, status) VALUES (?, ?, ?, ?, ?)",
    ).use { statement ->
        commands.forEachIndexed { index, (cmd, timeout) ->
            statement.setLong(1, jobId)
            statement.setInt(2, index)
            statement.setString(3, cmd)
            statement.setLong(4, timeout)
            statement.setString(5, Status.QUEUED.name)
            statement.addBatch()
        }
        statement.executeBatch()
    }

    /** Record an experiment's plan in one transaction: its arms, its problems, and a command per case, the case at
     *  each command's index. A job with commands is planned. */
    @Synchronized
    fun plan(jobId: Long, arms: List<PlannedArm>, problems: List<Problem>, cases: List<Case>, commands: List<Pair<String, Long>>) {
        require(cases.size == commands.size) { "a command per case" }
        transaction { planRows(jobId, arms, problems, cases, commands) }
    }

    /**
     * Turn records an earlier job left into a finished experiment: a new job of [name] at [sha], whose cases are
     * [records], each already done with its record kept, so the experiment views compare them like any other.
     */
    @Synchronized
    fun importExperiment(
        name: String,
        ref: String,
        sha: String,
        spec: ExperimentSpec,
        arms: List<PlannedArm>,
        problems: List<Problem>,
        records: List<Pair<Case, String>>,
        sources: List<String>,
    ): Long = transaction {
        val id = connection.prepareStatement(
            "INSERT INTO jobs (name, ref, sha, status, setup_done, created_at, started_at, finished_at, experiment) " +
                "VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use {
            val at = now()
            it.setString(1, name)
            it.setString(2, ref)
            it.setString(3, sha)
            it.setString(4, Status.DONE.name)
            it.setLong(5, at)
            it.setLong(6, at)
            it.setLong(7, at)
            it.setString(8, Json.encodeToString(spec))
            it.executeUpdate()
            it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
        planRows(id, arms, problems, records.map { it.first }, sources.map { it to 0L })
        update("UPDATE commands SET status = ? WHERE job_id = ?", Status.DONE.name, id)
        records.forEachIndexed { index, (_, record) -> update("UPDATE cases SET record = ? WHERE job_id = ? AND idx = ?", record, id, index) }
        id
    }

    private fun planRows(jobId: Long, arms: List<PlannedArm>, problems: List<Problem>, cases: List<Case>, commands: List<Pair<String, Long>>) {
        arms.forEachIndexed { index, planned ->
            update(
                "INSERT INTO arms (job_id, idx, label, arm, sha) VALUES (?, ?, ?, ?, ?)",
                jobId, index, planned.arm.label, Json.encodeToString(planned.arm), planned.sha,
            )
        }
        connection.prepareStatement("INSERT INTO problems (job_id, idx, problem) VALUES (?, ?, ?)").use { statement ->
            problems.forEachIndexed { index, problem ->
                statement.setLong(1, jobId)
                statement.setInt(2, index)
                statement.setString(3, Json.encodeToString(problem))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement(
            "INSERT INTO cases (job_id, idx, problem_idx, arm_idx, seed, repeat) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            cases.forEachIndexed { index, case ->
                statement.setLong(1, jobId)
                statement.setInt(2, index)
                statement.setInt(3, case.problem)
                statement.setInt(4, case.arm)
                statement.setObject(5, case.seed)
                statement.setInt(6, case.repeat)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        insertCommands(jobId, commands)
    }

    @Synchronized
    fun arms(jobId: Long): List<PlannedArm> = connection.prepareStatement(
        "SELECT arm, sha FROM arms WHERE job_id = ? ORDER BY idx",
    ).use { statement ->
        statement.setLong(1, jobId)
        statement.executeQuery().use { rows ->
            generateSequence { if (rows.next()) PlannedArm(Json.decodeFromString(rows.getString(1)), rows.getString(2)) else null }
                .toList()
        }
    }

    /** Keep the record a case's `solve-one` wrote. */
    @Synchronized
    fun caseRecord(jobId: Long, index: Int, record: String) =
        update("UPDATE cases SET record = ? WHERE job_id = ? AND idx = ?", record, jobId, index)

    /** Every case of an experiment in command order, with its problem, arm, status and record. */
    @Synchronized
    fun cases(jobId: Long): List<CaseResult> = connection.prepareStatement(
        """SELECT c.idx, m.status, p.problem, a.label, c.seed, c.record, c.repeat FROM cases c
           JOIN commands m ON m.job_id = c.job_id AND m.idx = c.idx
           JOIN problems p ON p.job_id = c.job_id AND p.idx = c.problem_idx
           JOIN arms a ON a.job_id = c.job_id AND a.idx = c.arm_idx
           WHERE c.job_id = ? ORDER BY c.idx""",
    ).use { statement ->
        statement.setLong(1, jobId)
        statement.executeQuery().use { rows ->
            generateSequence {
                if (!rows.next()) return@generateSequence null
                CaseResult(
                    rows.getInt(1), Status.valueOf(rows.getString(2)), Json.decodeFromString(rows.getString(3)),
                    rows.getString(4), rows.longOrNull("seed"), rows.getString(6)?.let { Json.parseToJsonElement(it) },
                    rows.getInt(7),
                )
            }.toList()
        }
    }

    /** The newest jobs first, older than [before] when given. A [name] keeps that name's jobs and the ones its
     *  schedule queued as `name@sha`. */
    @Synchronized
    fun jobs(limit: Int = 200, before: Long? = null, name: String? = null): List<Job> = jobsWhere(
        "id < ? AND (? IS NULL OR name = ? OR name LIKE ? ESCAPE '\\') ORDER BY id DESC LIMIT ?",
        before ?: Long.MAX_VALUE, name, name, name?.let { likeEscape(it) + "@%" }, limit,
    )

    /** Every queued or running job, oldest first. */
    @Synchronized
    fun active(): List<Job> = jobsWhere("status IN (?, ?) ORDER BY id", Status.QUEUED.name, Status.RUNNING.name)

    private fun jobsWhere(where: String, vararg values: Any?): List<Job> =
        connection.prepareStatement("SELECT * FROM jobs WHERE $where").use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> generateSequence { if (rows.next()) job(rows) else null }.toList() }
        }.map { it.copy(commands = commands(it.id)) }

    private fun likeEscape(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

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
    fun createSchedule(name: String, ref: String, experiment: ExperimentSpec, intervalSec: Long): Long = connection.prepareStatement(
        "INSERT INTO schedules (name, ref, commands, parallel, priority, interval_sec, experiment) VALUES (?, ?, '[]', ?, ?, ?, ?)",
        java.sql.Statement.RETURN_GENERATED_KEYS,
    ).use {
        it.setString(1, name)
        it.setString(2, ref)
        it.setInt(3, experiment.parallel)
        it.setInt(4, experiment.priority)
        it.setLong(5, intervalSec)
        it.setString(6, Json.encodeToString(experiment))
        it.executeUpdate()
        it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
    }

    @Synchronized
    fun schedules(): List<Schedule> = connection.prepareStatement("SELECT * FROM schedules ORDER BY id").use { statement ->
        statement.executeQuery().use { rows ->
            generateSequence {
                if (!rows.next()) return@generateSequence null
                Schedule(
                    rows.getLong("id"), rows.getString("name"), rows.getString("ref"),
                    rows.getString("experiment")?.let { Json.decodeFromString<ExperimentSpec>(it) },
                    rows.getInt("parallel"), rows.getInt("priority"),
                    rows.getLong("interval_sec"), rows.getString("last_sha"), rows.longOrNull("last_job"),
                    rows.longOrNull("checked_at"),
                )
            }.toList()
        }
    }

    @Synchronized
    fun deleteSchedule(id: Long): Boolean = update("DELETE FROM schedules WHERE id = ?", id) == 1

    @Synchronized
    fun scheduleChecked(id: Long, at: Long) = update("UPDATE schedules SET checked_at = ? WHERE id = ?", at, id)

    @Synchronized
    fun scheduleRan(id: Long, sha: String, jobId: Long) =
        update("UPDATE schedules SET last_sha = ?, last_job = ? WHERE id = ?", sha, jobId, id)

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
        rows.getString("experiment")?.let { Json.decodeFromString<ExperimentSpec>(it) },
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
