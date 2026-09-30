package com.eignex.lab

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Works the queue one job at a time. Each job checks its ref out into a fresh worktree, builds klause-cli, and
 * runs its commands in order. Every step's outcome is committed to the store before the next starts, so a crash
 * or reboot resumes the job at its first unfinished command; a command cut off mid-run is rerun from scratch.
 */
class Runner(private val config: Config, private val store: Store) {
    @Volatile
    private var current: Process? = null

    fun loop() {
        config.dataDir.createDirectories()
        while (true) {
            val job = store.next()
            if (job == null) {
                Thread.sleep(IDLE_POLL_MS)
                continue
            }
            runCatching { work(job) }.onFailure { e ->
                log(job.id, "job failed: ${e.message}")
                store.cancelRemaining(job.id)
                store.finish(job.id, Status.FAILED, e.message ?: e.toString())
            }
        }
    }

    private fun work(claimed: Job) {
        val dir = config.jobDir(claimed.id).createDirectories()
        claimed.commands.filter { it.status == Status.RUNNING }.forEach { killOrphan(dir, it.index) }
        store.requeueInterrupted(claimed.id)
        var job = checkNotNull(store.job(claimed.id))
        if (!job.setupDone || !config.worktree(job.id).exists()) {
            val sha = setup(job, dir)
            store.setup(job.id, sha)
            job = checkNotNull(store.job(job.id))
        }
        for (command in job.commands) {
            if (command.status != Status.QUEUED) continue
            if (store.cancelRequested(job.id)) return cancel(job.id)
            store.commandStarted(job.id, command.index)
            val exit = run(job, command, dir)
            if (exit == CANCELLED_EXIT) return cancel(job.id)
            store.commandFinished(job.id, command.index, exit)
        }
        store.finish(job.id, if (store.job(job.id)!!.commands.all { it.status == Status.DONE }) Status.DONE else Status.FAILED)
        removeWorktree(job.id)
        log(job.id, "job finished")
    }

    /** Fetch the ref into the mirror, check it out detached into the job's worktree, and build klause-cli there. */
    private fun setup(job: Job, dir: Path): String {
        val log = dir.resolve("setup.log").toFile()
        log.writeText("")
        if (!config.mirror.exists()) {
            sh(log, config.dataDir.toFile(), "git clone --mirror ${quote(config.repoUrl)} ${quote(config.mirror.toString())}")
        }
        sh(log, config.mirror.toFile(), "git fetch --prune origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'")
        val sha = capture(config.mirror.toFile(), "git rev-parse --verify ${quote(job.ref + "^{commit}")}")
        removeWorktree(job.id)
        config.worktree(job.id).parent.createDirectories()
        sh(log, config.mirror.toFile(), "git worktree add --detach ${quote(config.worktree(job.id).toString())} $sha")
        sh(log, config.worktree(job.id).toFile(),
            "./gradlew :klause-cli:installJvmDist --max-workers=${config.gradleWorkers} -q")
        dir.resolve("sha").writeAtomically(sha)
        return sha
    }

    /**
     * Run one command in the job's worktree. Output streams straight to disk; the exit code is written atomically
     * once the process ends, so a file named `<n>.exit` always holds a complete result. The whole process tree is
     * killed on timeout or cancellation.
     */
    private fun run(job: Job, command: Command, dir: Path): Int {
        val work = config.worktree(job.id).toFile()
        val builder = ProcessBuilder("bash", "-c", command.cmd)
            .directory(work)
            .redirectOutput(dir.resolve("${command.index}.out").toFile())
            .redirectError(dir.resolve("${command.index}.err").toFile())
        builder.environment().apply {
            put("KLAUSE_WORKTREE", work.absolutePath)
            put("KLAUSE_CLI", "${work.absolutePath}/klause-cli/build/install/klause-cli-jvm/bin/klause-cli")
            put("JOB_DIR", dir.toString())
            put("KLAUSE_CORPUS", config.corpusDir.toString())
            put("KLAUSE_CLI_OPTS", config.solveJavaOpts)
            // Host BLAS libraries otherwise size their own thread pools to the machine.
            put("OPENBLAS_NUM_THREADS", "1")
            put("VECLIB_MAXIMUM_THREADS", "1")
            put("MKL_NUM_THREADS", "1")
            put("OMP_NUM_THREADS", "1")
            if (config.javaHome.isNotBlank()) put("JAVA_HOME", config.javaHome)
        }
        log(job.id, "command ${command.index}: ${command.cmd}")
        val process = builder.start()
        current = process
        val handle = process.toHandle()
        dir.resolve("${command.index}.pid").writeAtomically("${handle.pid()} ${handle.info().startInstant().map { it.toEpochMilli() }.orElse(0)}")
        try {
            return await(job, command, dir, process)
        } finally {
            current = null
        }
    }

    private fun await(job: Job, command: Command, dir: Path, process: Process): Int {
        val deadline = now() + command.timeoutSec * 1000
        while (!process.waitFor(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
            if (store.cancelRequested(job.id)) {
                killTree(process)
                return CANCELLED_EXIT
            }
            if (now() > deadline) {
                killTree(process)
                dir.resolve("${command.index}.exit").writeAtomically("timeout")
                return TIMEOUT_EXIT
            }
        }
        val exit = process.exitValue()
        dir.resolve("${command.index}.exit").writeAtomically(exit.toString())
        return exit
    }

    /**
     * A runner that died mid-command leaves that command's process tree running. Kill it before the command is
     * rerun, or the rerun competes with it for the core. The recorded start time guards against a reused pid.
     */
    private fun killOrphan(dir: Path, index: Int) {
        val file = dir.resolve("$index.pid")
        if (!file.exists()) return
        val (pid, started) = file.toFile().readText().trim().split(" ").map { it.toLong() }
        ProcessHandle.of(pid).filter { handle ->
            handle.info().startInstant().map { it.toEpochMilli() == started }.orElse(false)
        }.ifPresent { handle ->
            println("killing orphaned process tree $pid of command $index")
            handle.descendants().forEach { it.destroyForcibly() }
            handle.destroyForcibly()
            handle.onExit().get(KILL_WAIT_SEC, TimeUnit.SECONDS)
        }
    }

    /** Kill the running command's tree when the service manager stops the runner. */
    fun installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread { current?.let(::killTree) })
    }

    private fun cancel(jobId: Long) {
        store.cancelRemaining(jobId)
        store.finish(jobId, Status.CANCELLED)
        removeWorktree(jobId)
        log(jobId, "job cancelled")
    }

    private fun removeWorktree(jobId: Long) {
        val path = config.worktree(jobId)
        if (config.mirror.exists()) {
            runCatching { capture(config.mirror.toFile(), "git worktree remove --force ${quote(path.toString())}") }
            runCatching { capture(config.mirror.toFile(), "git worktree prune") }
        }
        if (path.exists()) path.toFile().deleteRecursively()
    }

    private fun sh(log: File, dir: File, cmd: String) {
        log.appendText("$ $cmd\n")
        val builder = ProcessBuilder("bash", "-c", cmd).directory(dir)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        if (config.javaHome.isNotBlank()) builder.environment()["JAVA_HOME"] = config.javaHome
        val process = builder.start()
        if (!process.waitFor(config.setupTimeoutSec, TimeUnit.SECONDS)) {
            killTree(process)
            error("setup step timed out: $cmd")
        }
        check(process.exitValue() == 0) { "setup step failed (${process.exitValue()}): $cmd — see setup.log" }
    }

    private fun capture(dir: File, cmd: String): String {
        val process = ProcessBuilder("bash", "-c", cmd).directory(dir).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "failed: $cmd: $out" }
        return out
    }

    private fun killTree(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(KILL_WAIT_SEC, TimeUnit.SECONDS)
    }

    private fun log(jobId: Long, message: String) {
        println("[job $jobId] $message")
        runCatching { config.jobDir(jobId).createDirectories().resolve("job.log").toFile().appendText("${now()} $message\n") }
    }

    private companion object {
        const val IDLE_POLL_MS = 2000L
        const val CANCEL_POLL_MS = 1000L
        const val KILL_WAIT_SEC = 10L
        const val CANCELLED_EXIT = -1000
        const val TIMEOUT_EXIT = -1001
    }
}

internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** Write via a temp file and an atomic rename, so a reader never sees a half-written file. */
internal fun Path.writeAtomically(text: String) {
    val temp = resolveSibling("$fileName.tmp")
    temp.writeText(text)
    Files.move(temp, this, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
