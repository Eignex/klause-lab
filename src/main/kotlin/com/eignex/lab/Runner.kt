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
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json

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

    private val lenient = Json { ignoreUnknownKeys = true }

    /** Held over every git operation on the shared mirror, which the schedule thread and job setup both touch. */
    private val mirrorLock = Any()

    fun loop() {
        config.dataDir.createDirectories()
        if (config.requireDocker) awaitDocker()
        val disk = DiskGuard(config.minFreeBytes) { freeBytes(config.dataDir) }
        val updater = SelfUpdater(
            config.sourceDir.takeIf { config.updateCheckSec > 0 },
            config.updateCheckSec * 1000,
            config.dataDir.resolve("logs").resolve("update.log").toFile(),
        )
        // On its own thread, so a schedule queues its run on time while a long job holds the runner; the queue's
        // priorities then decide when it runs.
        thread(isDaemon = true, name = "schedules") {
            while (true) {
                runCatching { enqueueScheduled() }.onFailure { println("schedules: ${it.message}") }
                Thread.sleep(SCHEDULE_POLL_MS)
            }
        }
        while (true) {
            // Only between jobs: an update restarts the runner, which would cut a command off mid-run.
            updater.maybeUpdate()
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
                runCatching { removeWorktree(job.id) }
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
            val sha = setupExperiment(job, dir)
            store.setup(job.id, sha)
            job = checkNotNull(store.job(job.id))
        }
        when (dispatch(job, dir)) {
            Dispatched.CANCELLED -> return cancel(job.id)
            // Its worktree and finished commands stay, so the job resumes at its next command when picked again.
            Dispatched.YIELDED -> {
                store.requeue(job.id)
                log(job.id, "job paused between commands")
                return
            }
            Dispatched.FINISHED -> Unit
        }
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
     * until the count is below it. Before each command it checks whether the job was paused or a higher-priority job
     * waits; then it starts no more and returns once the running ones end, so a job yields only between commands.
     */
    private fun dispatch(job: Job, dir: Path): Dispatched {
        val pending = ArrayDeque(job.commands.filter { it.status == Status.QUEUED })
        val active = LinkedHashMap<Int, Future<Int>>()
        val pool = Executors.newCachedThreadPool()
        var cancelled = false
        var yielding = false
        try {
            while (pending.isNotEmpty() || active.isNotEmpty()) {
                // Shutdown kills the commands and leaves them RUNNING to rerun; nothing here may record them.
                while (stopping) Thread.sleep(CANCEL_POLL_MS)
                for (index in active.filterValues { it.isDone }.keys) {
                    val exit = active.remove(index)!!.get()
                    if (exit == CANCELLED_EXIT) {
                        cancelled = true
                    } else {
                        store.commandFinished(job.id, index, exit)
                        keepRecord(job.id, index, dir)
                    }
                }
                // Each running command sees the request and kills its own tree; the job ends once they have.
                if (!cancelled && store.cancelRequested(job.id)) cancelled = true
                if (cancelled) {
                    if (active.isEmpty()) return Dispatched.CANCELLED
                } else if (yielding || (pending.isNotEmpty() && store.shouldYield(job.id))) {
                    yielding = true
                    if (active.isEmpty()) return Dispatched.YIELDED
                } else {
                    // Within the job's case limit and the lab's core budget, in order: a case too big for the cores
                    // left waits for room rather than letting smaller later ones jump it. One case always may run.
                    val limit = store.parallel(job.id).coerceIn(1, config.maxParallel)
                    while (pending.isNotEmpty() && active.size < limit &&
                        (active.isEmpty() || held(active.keys, job) + pending.first().cores <= config.cores)
                    ) {
                        val command = pending.removeFirst()
                        store.commandStarted(job.id, command.index)
                        active[command.index] = pool.submit<Int> { run(job, command, dir) }
                    }
                }
                Thread.sleep(DISPATCH_POLL_MS)
            }
            return Dispatched.FINISHED
        } catch (e: ExecutionException) {
            // One command's failure to run fails the job, which must not leave its siblings running past it.
            running.forEach(::killTree)
            throw e.cause ?: e
        } finally {
            pool.shutdown()
            pool.awaitTermination(KILL_WAIT_SEC, TimeUnit.SECONDS)
        }
    }

    /** Cores the running cases at [indices] of [job] hold. */
    private fun held(indices: Collection<Int>, job: Job): Int = job.commands.filter { it.index in indices }.sumOf { it.cores }

    /**
     * Queue a run of each due schedule whose ref has moved since its last run, pinned to the commit it resolves to, so
     * the job records exactly what it ran. A schedule whose ref has not moved queues nothing, and one whose previous run
     * has not ended waits for it. A branch or tag is first asked of origin directly, one small request, so a check that
     * finds it unmoved fetches nothing and checks can come every minute or two; the mirror is fetched only when it
     * moved, or for a ref origin does not advertise, such as a commit.
     */
    private fun enqueueScheduled() {
        val due = store.schedules().filter { s -> s.checkedAt?.let { now() - it >= s.intervalSec * 1000 } ?: true }
        for (schedule in due) {
            store.scheduleChecked(schedule.id, now())
            if (schedule.lastJob?.let(store::job)?.status in setOf(Status.QUEUED, Status.RUNNING)) continue
            val advertised = advertised(schedule.ref)
            if (advertised != null && advertised == schedule.lastSha) continue
            val sha = runCatching {
                synchronized(mirrorLock) {
                    if (!config.mirror.exists()) {
                        capture(config.dataDir.toFile(), "git clone --mirror ${quote(config.repoUrl)} ${quote(config.mirror.toString())}")
                    }
                    capture(config.mirror.toFile(), "git fetch --prune origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'")
                    capture(config.mirror.toFile(), "git rev-parse --verify ${quote(schedule.ref + "^{commit}")}")
                }
            }.getOrNull() ?: continue
            if (sha == schedule.lastSha) continue
            val name = "${schedule.name}@${sha.take(9)}"
            val spec = schedule.experiment.copy(name = name, base = schedule.experiment.base + ("ref" to sha))
            val id = store.create(name, sha, emptyList(), spec.parallel ?: config.maxParallel, spec.priority, spec)
            store.scheduleRan(schedule.id, sha, id)
            println("schedule ${schedule.name}: queued job $id for ${sha.take(9)}")
        }
    }

    /** The commit origin advertises for branch or tag [ref]; null when it names neither, or origin does not answer. */
    private fun advertised(ref: String): String? = runCatching {
        capture(
            config.dataDir.toFile(),
            "GIT_TERMINAL_PROMPT=0 git ls-remote ${quote(config.repoUrl)} ${quote("refs/heads/$ref")} ${quote("refs/tags/$ref")}",
        )
    }.getOrNull()?.lineSequence()?.map { it.substringBefore('\t').trim() }?.firstOrNull { SHA.matches(it) }

    /**
     * Set an experiment up: build every commit its arms name, each in its own worktree, and on the first setup plan
     * it. Planning selects the problems with the bench at the first arm's commit, refuses a plan whose estimate is
     * over [Config.maxExperimentHours] unless the spec confirms it, and writes a command per case. A setup after a
     * crash or a yield only rebuilds, against the commits the plan recorded.
     */
    private fun setupExperiment(job: Job, dir: Path): String {
        val spec = checkNotNull(job.experiment)
        val log = dir.resolve("setup.log").toFile()
        log.writeText("")
        val planned = store.arms(job.id)
        val arms = planned.map { it.arm }.ifEmpty { Experiments.arms(spec) }
        val shas = synchronized(mirrorLock) {
            fetchMirror(log)
            planned.associate { it.arm.ref to it.sha }.ifEmpty { arms.map { it.ref }.distinct().associateWith(::resolve) }
        }
        val primary = shas.getValue(arms.first().ref)
        removeWorktree(job.id)
        for (sha in shas.values.distinct()) {
            val path = experimentWorktree(job.id, sha, primary)
            synchronized(mirrorLock) {
                path.parent.createDirectories()
                sh(log, config.mirror.toFile(), "git worktree add --detach ${quote(path.toString())} $sha")
            }
            linkShared(path)
            sh(log, path.toFile(),
                "./gradlew :klause-cli:installJvmDist :klause-bench:installDist --max-workers=${config.gradleWorkers} -q")
        }
        if (job.commands.isEmpty()) plan(job, spec, arms, shas, primary, dir, log)
        dir.resolve("sha").writeAtomically(primary)
        return primary
    }

    private fun plan(job: Job, spec: ExperimentSpec, arms: List<Arm>, shas: Map<String, String>, primary: String, dir: Path, log: File) {
        val worktree = experimentWorktree(job.id, primary, primary)
        val opts = "-Dklause.bench.corpusCache=${config.corpusDir} -Dklause.workspace.root=$worktree"
        // Each selection is capped on its own; a problem two selections share is solved once.
        // Selections interleave, so a sweep cut short or paused part-way has covered every selection, not the first few.
        val problems = interleave(spec.problems.withIndex().map { (index, problemSelection) ->
            val selection = dir.resolve("selection-$index.jsonl")
            val mode = Experiments.referenceFilter(spec, problemSelection)
            // A filtered, capped selection asks the bench for the problems without its caps and caps what the filter
            // keeps, so the problems the reference never decided are replaced rather than leaving the selection short.
            val refill = Experiments.refills(mode, problemSelection)
            val asked = if (refill) Experiments.uncapped(problemSelection) else problemSelection
            sh(log, worktree.resolve("klause-bench").toFile(),
                "JAVA_OPTS=${quote(opts)} KLAUSE_BENCH_CORPUS_MAX_GB=off ./build/install/klause-bench/bin/klause-bench " +
                    "select ${Experiments.selectArgs(asked)} > ${quote(selection.toString())}")
            val selected = selection.toFile().readLines().filter { it.startsWith("{") }.map { lenient.decodeFromString<Problem>(it) }
            val references = store.references(selected.map { it.collection to it.problem })
            val kept = selected.filter { mode.keeps(references[it.collection to it.problem]) }
            if (kept.size < selected.size) {
                log(job.id, "selection $index: ${selected.size - kept.size} of ${selected.size} problems left out by reference=${mode.name.lowercase()}")
            }
            if (refill) Experiments.cap(kept, problemSelection["per-family"]?.toIntOrNull(), problemSelection["max"]?.toIntOrNull()) else kept
        }).distinctBy { it.suite to it.problem }
        require(problems.isNotEmpty()) { "the selection matched no problems the reference filter keeps" }
        val cases = Experiments.cases(problems.size, arms.size, spec.seeds, spec.repeats)
        val parallel = spec.parallel ?: config.maxParallel
        val hours = Experiments.estimateHours(cases, arms, parallel, config.cores)
        require(spec.confirm || hours <= config.maxExperimentHours) {
            "${cases.size} cases could take %.1f h at ×$parallel, over the ${config.maxExperimentHours} h limit; ".format(hours) +
                "resubmit with \"confirm\": true"
        }
        val commands = cases.mapIndexed { index, case ->
            val arm = arms[case.arm]
            val path = experimentWorktree(job.id, shas.getValue(arm.ref), primary)
            Experiments.command(path.toString(), problems[case.problem], arm, case.seed, index, config.corpusDir.toString()) to
                Experiments.caseTimeoutSec(arm) to arm.cores
        }.map { (command, cores) -> CaseCommand(command.first, command.second, cores) }
        store.planCommands(job.id, arms.map { PlannedArm(it, shas.getValue(it.ref)) }, problems, cases, commands)
        log(job.id, "planned ${problems.size} problems × ${arms.size} arms = ${cases.size} cases, up to %.1f h".format(hours))
    }

    /** The worktree an experiment builds [sha] in: the job's own for its first arm's commit, a sibling for others. */
    private fun experimentWorktree(jobId: Long, sha: String, primary: String): Path =
        if (sha == primary) config.worktree(jobId) else config.worktree(jobId).resolveSibling("$jobId@${sha.take(SHA_DIR_LENGTH)}")

    /** Keep the record a finished case wrote; a case that wrote none (it could not run) keeps none. */
    private fun keepRecord(jobId: Long, index: Int, dir: Path) {
        val record = dir.resolve(CASES).resolve(index.toString()).toFile().listFiles { f -> f.extension == "json" }?.singleOrNull()
        runCatching {
            val text = record?.readText() ?: return@runCatching
            store.caseRecord(jobId, index, text)
            promote(jobId, index, text)
        }.onFailure { log(jobId, "case $index: record not kept: ${it.message}") }
    }

    /** A case of a reference arm adds its verdict to the reference results as it finishes, so a run cut short still
     *  contributes what it solved; a stored stronger verdict stays. */
    private fun promote(jobId: Long, index: Int, record: String) {
        val (problem, arm) = store.caseOf(jobId, index) ?: return
        if (arm.values["backend"] != REFERENCE_BACKEND || problem.collection.isEmpty()) return
        val reference = References.of(Json.parseToJsonElement(record)) ?: return
        store.putReferences(listOf((problem.collection to problem.problem) to reference), "lab:$jobId")
    }

    private fun fetchMirror(log: File) {
        if (!config.mirror.exists()) {
            sh(log, config.dataDir.toFile(), "git clone --mirror ${quote(config.repoUrl)} ${quote(config.mirror.toString())}")
        }
        sh(log, config.mirror.toFile(), "git fetch --prune origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'")
    }

    private fun resolve(ref: String): String =
        runCatching { capture(config.mirror.toFile(), "git rev-parse --verify ${quote(ref + "^{commit}")}") }
            .getOrElse { error("unknown ref: $ref (not on origin; is it pushed?)") }

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
     * Run one case's command. Output streams straight to disk; the exit code is written atomically
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
            put("JOB_DIR", dir.toString())
            // The solve sees the cores its case holds, so a portfolio sizes its threads to them and a single-engine
            // solve does not size its pools to the whole machine.
            put("KLAUSE_CLI_OPTS", "${config.solveJavaOpts} -XX:ActiveProcessorCount=${command.cores}")
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

    private fun cancel(jobId: Long) {
        store.cancelRemaining(jobId)
        store.finish(jobId, Status.CANCELLED)
        removeWorktree(jobId)
        log(jobId, "job cancelled")
    }

    /** Remove the job's worktree and, for an experiment, the ones it built other commits in. */
    private fun removeWorktree(jobId: Long) {
        val path = config.worktree(jobId)
        val siblings = path.parent.toFile().listFiles { f -> f.name.startsWith("$jobId@") }.orEmpty()
        for (sibling in siblings) removeTree(sibling.toPath())
        removeTree(path)
    }

    private fun removeTree(path: Path) {
        // Unlink the shared directories first, so that nothing below can reach the data they point at.
        for (relative in config.sharedPaths) {
            val link = path.resolve(relative)
            if (link.isSymbolicLink()) Files.delete(link)
        }
        synchronized(mirrorLock) {
            if (config.mirror.exists()) {
                runCatching { capture(config.mirror.toFile(), "git worktree remove --force ${quote(path.toString())}") }
                runCatching { capture(config.mirror.toFile(), "git worktree prune") }
            }
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
        const val CASES = "cases"
        const val SHA_DIR_LENGTH = 12
        const val DOCKER_POLL_MS = 5000L
        /** How often the schedule thread looks for a due schedule: the most a forced check waits. */
        const val SCHEDULE_POLL_MS = 10_000L
        val SHA = Regex("[0-9a-f]{40}")
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

private enum class Dispatched { FINISHED, CANCELLED, YIELDED }
