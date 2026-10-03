package com.eignex.lab

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.writeText

/**
 * Works the queue one job at a time. Each job checks its ref out into a fresh worktree, builds klause-cli, and
 * runs its commands in order. Every step's outcome is committed to the store before the next starts, so a crash
 * or reboot resumes the job at its first unfinished command; a command cut off mid-run is rerun from scratch.
 *
 * Every process the runner starts leads its own session, so killing that process group reaches everything the
 * command spawned, including children whose parent shell has already died.
 */
class Runner(private val config: Config, private val store: Store) {
    /** Every process the runner has started and not yet seen exit, so cancel and shutdown can reach them all. */
    private val running: MutableSet<Process> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var stopping = false

    fun loop() {
        config.dataDir.createDirectories()
        if (config.requireDocker) awaitDocker()
        val disk = DiskGuard(config.minFreeBytes) { freeBytes(config.dataDir) }
        while (true) {
            if (!disk.allowsWork()) {
                Thread.sleep(IDLE_POLL_MS)
                continue
            }
            val job = store.next()
            if (job == null) {
                Thread.sleep(IDLE_POLL_MS)
                continue
            }
            runCatching { work(job) }.onFailure { e ->
                if (stopping) return
                log(job.id, "job failed: ${e.message}")
                store.cancelRemaining(job.id)
                store.finish(job.id, Status.FAILED, e.message ?: e.toString())
            }
        }
    }

    private fun work(claimed: Job) {
        val dir = config.jobDir(claimed.id).createDirectories()
        killOrphan(dir.resolve("setup.pid"))
        claimed.commands.filter { it.status == Status.RUNNING }.forEach { killOrphan(dir.resolve("${it.index}.pid")) }
        store.requeueInterrupted(claimed.id)
        var job = checkNotNull(store.job(claimed.id))
        if (!job.setupDone || !config.worktree(job.id).exists()) {
            val sha = setup(job, dir)
            store.setup(job.id, sha)
            job = checkNotNull(store.job(job.id))
        }
        if (!dispatch(job, dir)) return cancel(job.id)
        // Collected before the job reads as done, so a client that sees DONE finds the results in place.
        collect(job.id, dir)
        // A job that ran every command is done; which commands failed is on the commands. FAILED is kept for a job
        // that could not run, so a sweep with a few refused instances does not read as a broken job.
        store.finish(job.id, Status.DONE)
        removeWorktree(job.id)
        log(job.id, "job finished")
    }

    /**
     * Run [job]'s queued commands in index order, up to the job's `parallel` many at once, and record each result
     * as it ends. The limit is reread from the store on every pass, so it can be raised or lowered while the job
     * runs: raising it starts more commands at once, lowering it lets the running ones finish and starts no more
     * until the count is below it. False when the job was cancelled.
     */
    private fun dispatch(job: Job, dir: Path): Boolean {
        val pending = ArrayDeque(job.commands.filter { it.status == Status.QUEUED })
        val active = LinkedHashMap<Int, Future<Int>>()
        val pool = Executors.newCachedThreadPool()
        var cancelled = false
        try {
            while (pending.isNotEmpty() || active.isNotEmpty()) {
                // Shutdown kills the commands and leaves them RUNNING to rerun; nothing here may record them.
                while (stopping) Thread.sleep(CANCEL_POLL_MS)
                for (index in active.filterValues { it.isDone }.keys) {
                    val exit = active.remove(index)!!.get()
                    if (exit == CANCELLED_EXIT) cancelled = true else store.commandFinished(job.id, index, exit)
                }
                // Each running command sees the request and kills its own tree; the job ends once they have.
                if (!cancelled && store.cancelRequested(job.id)) cancelled = true
                if (cancelled) {
                    if (active.isEmpty()) return false
                } else {
                    val limit = store.parallel(job.id).coerceIn(1, config.maxParallel)
                    while (active.size < limit && pending.isNotEmpty()) {
                        val command = pending.removeFirst()
                        store.commandStarted(job.id, command.index)
                        active[command.index] = pool.submit<Int> { run(job, command, dir) }
                    }
                }
                Thread.sleep(DISPATCH_POLL_MS)
            }
            return true
        } catch (e: ExecutionException) {
            // One command's failure to run fails the job, which must not leave its siblings running past it.
            running.forEach(::killTree)
            throw e.cause ?: e
        } finally {
            pool.shutdown()
            pool.awaitTermination(KILL_WAIT_SEC, TimeUnit.SECONDS)
        }
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
        linkShared(config.worktree(job.id))
        sh(log, config.worktree(job.id).toFile(),
            "./gradlew :klause-cli:installJvmDist --max-workers=${config.gradleWorkers} -q")
        dir.resolve("sha").writeAtomically(sha)
        return sha
    }

    /**
     * Point each of [Config.sharedPaths] in the worktree at one directory every job uses. The bench result cache is
     * the reason: reference results are keyed by instance, solver and budget alone, so a later job replays them
     * instead of rerunning the reference, and klause results also key on the CLI binary each job builds afresh.
     */
    private fun linkShared(worktree: Path) {
        for (relative in config.sharedPaths) {
            val target = config.sharedDir.resolve(relative).createDirectories()
            val link = worktree.resolve(relative)
            link.parent.createDirectories()
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) deleteTree(link)
            Files.createSymbolicLink(link, target)
        }
    }

    /**
     * Run one command in the job's worktree. Output streams straight to disk; the exit code is written atomically
     * once the process ends, so a file named `<n>.exit` always holds a complete result. The whole process tree is
     * killed on timeout or cancellation.
     */
    private fun run(job: Job, command: Command, dir: Path): Int {
        val work = config.worktree(job.id).toFile()
        val builder = ProcessBuilder(inSession("bash", "-c", command.cmd))
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
        val process = start(builder, dir.resolve("${command.index}.pid"))
        try {
            return await(job, command, dir, process)
        } finally {
            running.remove(process)
        }
    }

    private fun await(job: Job, command: Command, dir: Path, process: Process): Int {
        val deadline = now() + command.timeoutSec * 1000
        while (!process.waitFor(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
            if (!stopping && store.cancelRequested(job.id)) {
                killTree(process)
                return CANCELLED_EXIT
            }
            if (now() > deadline) {
                killTree(process)
                dir.resolve("${command.index}.exit").writeAtomically("timeout\n")
                return TIMEOUT_EXIT
            }
        }
        // A command the shutdown killed has no result. Leaving it RUNNING in the store makes the next runner
        // rerun it, as it would after a crash; recording the kill would mark it failed and skip it.
        while (stopping) Thread.sleep(CANCEL_POLL_MS)
        val exit = process.exitValue()
        dir.resolve("${command.index}.exit").writeAtomically("$exit\n")
        return exit
    }

    /** Start a process that leads its own session, and record its pid and start time in [pidFile]. */
    private fun start(builder: ProcessBuilder, pidFile: Path): Process {
        val process = builder.start()
        running.add(process)
        val handle = process.toHandle()
        pidFile.writeAtomically("${handle.pid()} ${handle.info().startInstant().map { it.toEpochMilli() }.orElse(0)}\n")
        return process
    }

    /**
     * A runner that died mid-step leaves that step's process group running. Kill it before the step reruns, or the
     * rerun competes with it for the core. The group outlives its leader when the leader dies first, so a dead
     * leader still means the group is killed; a live process under that pid with another start time is a reused
     * pid, and its group is left alone.
     */
    private fun killOrphan(pidFile: Path) {
        if (!pidFile.exists()) return
        val (pid, started) = pidFile.toFile().readText().trim().split(" ").map { it.toLong() }
        val leader = ProcessHandle.of(pid)
        val reused = leader.map { handle ->
            handle.info().startInstant().map { it.toEpochMilli() != started }.orElse(true)
        }.orElse(false)
        if (reused) return
        println("killing orphaned process group $pid from ${pidFile.fileName}")
        killGroup(pid)
        leader.ifPresent { it.onExit().get(KILL_WAIT_SEC, TimeUnit.SECONDS) }
    }

    /** Wait until Docker answers before taking work, since reference commands need it and it starts after boot. */
    private fun awaitDocker() {
        var waited = 0L
        while (ProcessBuilder("docker", "info").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() != 0
        ) {
            if (waited % DOCKER_LOG_EVERY_MS == 0L) println("waiting for docker (${waited / 1000} s)")
            Thread.sleep(DOCKER_POLL_MS)
            waited += DOCKER_POLL_MS
        }
        println("docker is up")
    }

    /** Kill the running command's tree when the service manager stops the runner. */
    fun installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                stopping = true
                running.forEach(::killTree)
            },
        )
    }

    /**
     * Copy each of [Config.collectPaths] that the job's worktree holds into `collected/` under its job directory,
     * since the worktree is deleted next. That is where bench writes its per-problem results and its updated
     * reference tables, which a command would otherwise have to copy out itself.
     */
    private fun collect(jobId: Long, dir: Path) {
        val worktree = config.worktree(jobId)
        for (relative in config.collectPaths) {
            val source = worktree.resolve(relative)
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue
            copyTree(source, dir.resolve(COLLECTED).resolve(relative))
            log(jobId, "collected $relative")
        }
    }

    private fun cancel(jobId: Long) {
        collect(jobId, config.jobDir(jobId))
        store.cancelRemaining(jobId)
        store.finish(jobId, Status.CANCELLED)
        removeWorktree(jobId)
        log(jobId, "job cancelled")
    }

    private fun removeWorktree(jobId: Long) {
        val path = config.worktree(jobId)
        // Unlink the shared directories first, so that nothing below can reach the data they point at.
        for (relative in config.sharedPaths) {
            val link = path.resolve(relative)
            if (link.isSymbolicLink()) Files.delete(link)
        }
        if (config.mirror.exists()) {
            runCatching { capture(config.mirror.toFile(), "git worktree remove --force ${quote(path.toString())}") }
            runCatching { capture(config.mirror.toFile(), "git worktree prune") }
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) deleteTree(path)
    }

    private fun sh(log: File, dir: File, cmd: String) {
        log.appendText("$ $cmd\n")
        val builder = ProcessBuilder(inSession("bash", "-c", cmd)).directory(dir)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        if (config.javaHome.isNotBlank()) builder.environment()["JAVA_HOME"] = config.javaHome
        val process = start(builder, log.toPath().resolveSibling("setup.pid"))
        val finished = try {
            process.waitFor(config.setupTimeoutSec, TimeUnit.SECONDS)
        } finally {
            running.remove(process)
        }
        while (stopping) Thread.sleep(CANCEL_POLL_MS)
        if (!finished) {
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
        killGroup(process.pid())
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
        const val DISPATCH_POLL_MS = 100L
        const val COLLECTED = "collected"
        const val DOCKER_POLL_MS = 5000L
        const val DOCKER_LOG_EVERY_MS = 60_000L
    }
}

/**
 * [command] wrapped so that it leads a new session, and so its own process group. perl is the portable way to
 * get there: macOS ships no `setsid` command, and both macOS and every common Linux base image ship perl.
 */
internal fun inSession(vararg command: String): List<String> =
    listOf("perl", "-e", "use POSIX qw(setsid); setsid() or die \"setsid: \$!\"; exec @ARGV or die \"exec: \$!\"") +
        command

/** SIGKILL every process in the group that [leader] leads. */
internal fun killGroup(leader: Long) {
    ProcessBuilder("kill", "-KILL", "--", "-$leader").redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
}

/** Copy the tree at [source] to [target], replacing files already there. Links are not followed, nor copied. */
internal fun copyTree(source: Path, target: Path) {
    Files.walk(source).use { paths ->
        paths.filter { !it.isSymbolicLink() }.forEach { path ->
            val destination = target.resolve(source.relativize(path).toString())
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                destination.createDirectories()
            } else {
                destination.parent.createDirectories()
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

/** Delete [root] and everything under it without following symbolic links, which are removed as links. */
internal fun deleteTree(root: Path) {
    if (root.isSymbolicLink() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        Files.deleteIfExists(root)
        return
    }
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}

internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** Write via a temp file and an atomic rename, so a reader never sees a half-written file. */
internal fun Path.writeAtomically(text: String) {
    val temp = resolveSibling("$fileName.tmp")
    temp.writeText(text)
    Files.move(temp, this, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
