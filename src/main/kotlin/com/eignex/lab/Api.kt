package com.eignex.lab

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.accept
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

@Serializable
data class ParallelSpec(val parallel: Int)

@Serializable
data class PrioritySpec(val priority: Int)

/** An experiment rerun on every new commit of [ref]: each run is [experiment] with every arm at that commit. */
@Serializable
data class ScheduleSpec(
    val name: String,
    val ref: String,
    val experiment: ExperimentSpec,
    val intervalSec: Long = 3600,
)

@Serializable
data class Created(val id: Long)

@Serializable
data class ReferenceHit(val collection: String, val problem: String, val reference: Reference)

@Serializable
data class FileEntry(val path: String, val bytes: Long)

@Serializable
data class Health(val ok: Boolean, val queued: Int, val running: Int, val freeBytes: Long, val host: HostReport)

fun serveApi(config: Config, store: Store, host: HostReport) {
    embeddedServer(Netty, port = config.port) { api(config, store, host) }.start(wait = true)
}

fun Application.api(config: Config, store: Store, host: HostReport) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e -> call.respondText(e.message ?: "bad request", status = HttpStatusCode.BadRequest) }
    }
    routing {
        get("/") {
            val before = call.parameters["before"]?.toLong()
            val name = call.parameters["name"]?.takeIf { it.isNotBlank() }
            val history = store.jobs(PAGE_SIZE, before, name)
            val page = indexPage(config, host, store.active(), store.schedules(), history, PAGE_SIZE, before, name)
            call.respondText(page, ContentType.Text.Html)
        }
        get("/health") {
            val jobs = store.jobs()
            val queued = jobs.count { it.status == Status.QUEUED }
            val running = jobs.count { it.status == Status.RUNNING }
            call.respond(Health(true, queued, running, freeBytes(config.dataDir), host))
        }
        get("/jobs") {
            call.respond(store.jobs(call.parameters["limit"]?.toInt() ?: 200, call.parameters["before"]?.toLong(), call.parameters["name"]))
        }
        // An experiment is queued as a job with no commands; the runner plans its cases when it first sets it up.
        post("/experiments") {
            val spec = call.receive<ExperimentSpec>()
            Experiments.validate(spec, config.maxParallel, config.cores)
            val arms = Experiments.arms(spec)
            for (ref in arms.map { it.ref }.distinct()) requireRef(ref, config)
            val id = store.create(spec.name, arms.first().ref, emptyList(), spec.parallel ?: config.maxParallel, spec.priority, spec)
            call.respond(HttpStatusCode.Created, Created(id))
        }
        get("/experiments/{id}/arms") { call.respond(store.arms(call.parameters["id"]!!.toLong())) }
        get("/trend") {
            val schedules = store.schedules().map { it.name }
            val name = call.parameters["name"]?.takeIf { it.isNotBlank() } ?: schedules.firstOrNull()
            val runs = name?.let { Trend.runs(store, it) }.orEmpty()
            if (call.wantsHtml()) {
                call.respondText(trendPage(name, runs, config.repoUrl, schedules), ContentType.Text.Html)
            } else {
                call.respond(runs)
            }
        }
        get("/references") {
            fun param(name: String) = call.parameters[name]?.takeIf { it.isNotBlank() }
            val filter = ReferenceFilter(
                text = param("q"),
                solver = param("solver"),
                collection = param("collection"),
                verdict = param("verdict")?.let { v -> ReferenceVerdict.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } },
            )
            val (found, total) = if (filter.isEmpty) emptyList<Pair<Pair<String, String>, Reference>>() to 0 else store.searchReferences(filter, SEARCH_LIMIT)
            if (call.wantsHtml()) {
                val page = referencesPage(store.referenceCoverage(), filter, found, total)
                call.respondText(page, ContentType.Text.Html)
            } else {
                call.respond(found.map { (key, r) -> ReferenceHit(key.first, key.second, r) })
            }
        }
        get("/problem") {
            val collection = requireNotNull(call.parameters["collection"]) { "collection is required" }
            val problem = requireNotNull(call.parameters["problem"]) { "problem is required" }
            val report = ProblemReport(collection, problem, store.referenceRows(collection, problem), store.problemRuns(collection, problem))
            if (call.wantsHtml()) call.respondText(problemPage(report, config.repoUrl), ContentType.Text.Html) else call.respond(report)
        }
        get("/experiments/{id}/reference") {
            val id = call.parameters["id"]!!.toLong()
            val cases = store.cases(id)
            val references = store.references(cases.map { it.problem.collection to it.problem.problem })
            call.respond(References.compare(store.arms(id).map { it.arm.label }, cases, references))
        }
        get("/compare") {
            val ids = requireNotNull(call.parameters["jobs"]) { "jobs=<id>,<id>… is required" }.split(',').map { it.trim().toLong() }
            require(ids.size in 2..MAX_COMPARED) { "compare 2 to $MAX_COMPARED experiments" }
            val jobs = ids.map { id -> requireNotNull(store.job(id)?.takeIf { it.experiment != null }) { "job $id is not an experiment" } }
            // Arms are named by their job, so the same arm of two runs stays two columns.
            val arms = jobs.flatMap { job -> store.arms(job.id).map { it.copy(arm = it.arm.copy(label = "${job.id} ${it.arm.label}")) } }
            val cases = jobs.flatMap { job -> store.cases(job.id).map { it.copy(arm = "${job.id} ${it.arm}") } }
            val references = store.references(cases.map { it.problem.collection to it.problem.problem })
            call.respondText(comparePage(config, jobs, arms, cases, references), ContentType.Text.Html)
        }
        get("/experiments/{id}/cases") { call.respond(store.cases(call.parameters["id"]!!.toLong())) }
        get("/experiments/{id}/stats") {
            val id = call.parameters["id"]!!.toLong()
            call.respond(Stats.of(store.arms(id).map { it.arm.label }, store.cases(id)))
        }
        get("/experiments/{id}/cases.csv") {
            call.respondText(Results.casesCsv(store.cases(call.parameters["id"]!!.toLong())), ContentType.Text.CSV)
        }
        get("/experiments/{id}/bench.csv") {
            val arm = requireNotNull(call.parameters["arm"]) { "arm is required" }
            val seed = call.parameters["seed"]?.toLong()
            call.respondText(Results.benchCsv(store.cases(call.parameters["id"]!!.toLong()), arm, seed), ContentType.Text.CSV)
        }
        get("/jobs/{id}") {
            val job = store.job(call.parameters["id"]!!.toLong())
            when {
                job == null -> call.respond(HttpStatusCode.NotFound, "no such job")
                call.wantsHtml() -> {
                    val experiment = job.experiment != null
                    val cases = if (experiment) store.cases(job.id) else emptyList()
                    val page = jobPage(
                        config, job, "failed" in call.parameters,
                        arms = if (experiment) store.arms(job.id) else emptyList(),
                        cases = cases,
                        references = store.references(cases.map { it.problem.collection to it.problem.problem }),
                        showCommands = !experiment || "commands" in call.parameters || "failed" in call.parameters,
                        previous = if (experiment && '@' in job.name) previousRun(store, job) else null,
                    )
                    call.respondText(page, ContentType.Text.Html)
                }
                else -> call.respond(job)
            }
        }
        // A long poll: the answer comes when the job ends, or after timeoutSec with the job as it stands, so a
        // client learns of the end without polling over the network.
        get("/jobs/{id}/wait") {
            val id = call.parameters["id"]!!.toLong()
            val timeoutSec = (call.parameters["timeoutSec"]?.toLong() ?: DEFAULT_WAIT_SEC).coerceIn(1, MAX_WAIT_SEC)
            val deadline = now() + timeoutSec * 1000
            while (true) {
                val job = store.job(id)
                if (job == null) {
                    call.respond(HttpStatusCode.NotFound, "no such job")
                    break
                }
                if (job.status in ENDED || now() >= deadline) {
                    call.respond(job)
                    break
                }
                delay(WAIT_POLL_MS)
            }
        }
        post("/jobs/{id}/cancel") {
            when (store.requestCancel(call.parameters["id"]!!.toLong())) {
                CancelOutcome.CANCELLED -> call.respond(HttpStatusCode.OK, "cancelled before it started")
                CancelOutcome.REQUESTED -> call.respond(HttpStatusCode.Accepted, "cancel requested")
                CancelOutcome.FINISHED -> call.respond(HttpStatusCode.Conflict, "job already finished")
                CancelOutcome.MISSING -> call.respond(HttpStatusCode.NotFound, "no such job")
            }
        }
        // Its directory goes too, and any worktree a failed setup left; the mirror forgets those at its next prune.
        post("/jobs/{id}/delete") {
            val id = call.parameters["id"]!!.toLong()
            if (!store.deleteJob(id)) {
                call.respond(HttpStatusCode.Conflict, "no such job, or it has not ended")
            } else {
                val work = config.worktree(id).toFile()
                (work.parentFile.listFiles { f -> f.name == "$id" || f.name.startsWith("$id@") }.orEmpty().toList() + config.jobDir(id).toFile())
                    .forEach { deleteTree(it.toPath()) }
                call.respond(HttpStatusCode.OK, "deleted")
            }
        }
        post("/jobs/{id}/parallel") {
            val spec = call.receive<ParallelSpec>()
            requireParallel(spec.parallel, config)
            val found = store.setParallel(call.parameters["id"]!!.toLong(), spec.parallel)
            call.respond(if (found) HttpStatusCode.OK else HttpStatusCode.NotFound, if (found) "parallel ${spec.parallel}" else "no such job")
        }
        post("/jobs/{id}/priority") {
            val spec = call.receive<PrioritySpec>()
            val found = store.setPriority(call.parameters["id"]!!.toLong(), spec.priority)
            call.respond(if (found) HttpStatusCode.OK else HttpStatusCode.NotFound, if (found) "priority ${spec.priority}" else "no such job")
        }
        // A running job stops starting commands and goes back to the queue once the running ones end.
        post("/jobs/{id}/pause") {
            val paused = store.setPaused(call.parameters["id"]!!.toLong(), true)
            call.respond(if (paused) HttpStatusCode.OK else HttpStatusCode.Conflict, if (paused) "paused" else "no unfinished job")
        }
        post("/jobs/{id}/resume") {
            val resumed = store.setPaused(call.parameters["id"]!!.toLong(), false)
            call.respond(if (resumed) HttpStatusCode.OK else HttpStatusCode.Conflict, if (resumed) "resumed" else "no unfinished job")
        }
        post("/schedules") {
            val spec = call.receive<ScheduleSpec>()
            require(spec.name.isNotBlank() && spec.ref.isNotBlank() && !spec.ref.startsWith("-")) { "name and ref are required" }
            require(spec.intervalSec >= MIN_SCHEDULE_SEC) { "intervalSec must be at least $MIN_SCHEDULE_SEC" }
            Experiments.validate(spec.experiment, config.maxParallel, config.cores)
            require(Experiments.arms(spec.experiment).none { "ref" in it.values }) {
                "a scheduled experiment runs every arm at the schedule's ref; drop ref from its configs"
            }
            requireRef(spec.ref, config)
            val id = store.createSchedule(spec.name, spec.ref, spec.experiment, spec.intervalSec)
            call.respond(HttpStatusCode.Created, Created(id))
        }
        get("/schedules") { call.respond(store.schedules()) }
        // The runner's schedule thread finds it due within seconds; a push hook or relay can call this instead of waiting.
        post("/schedules/{id}/check") {
            val found = store.checkNow(call.parameters["id"]!!.toLong())
            call.respond(if (found) HttpStatusCode.Accepted else HttpStatusCode.NotFound, if (found) "checking" else "no such schedule")
        }
        post("/schedules/{id}/delete") {
            val deleted = store.deleteSchedule(call.parameters["id"]!!.toLong())
            call.respond(if (deleted) HttpStatusCode.OK else HttpStatusCode.NotFound, if (deleted) "deleted" else "no such schedule")
        }
        get("/jobs/{id}/files") {
            val id = call.parameters["id"]!!.toLong()
            val root = config.jobDir(id).toFile()
            val files = root.walkTopDown().filter { it.isFile }.map { FileEntry(it.relativeTo(root).path, it.length()) }.sortedBy { it.path }.toList()
            if (call.wantsHtml()) call.respondText(filesPage(id, files), ContentType.Text.Html) else call.respond(files)
        }
        get("/jobs/{id}/files/{path...}") {
            val root = config.jobDir(call.parameters["id"]!!.toLong()).toFile().canonicalFile
            val file = File(root, call.parameters.getAll("path")!!.joinToString("/")).canonicalFile
            require(file.toPath().startsWith(root.toPath())) { "path escapes the job directory" }
            val tail = call.parameters["tail"]?.toLong()
            when {
                !file.isFile -> call.respond(HttpStatusCode.NotFound, "no such file")
                tail != null -> call.respondText(tail(file, tail), ContentType.Text.Plain)
                // Logs and command output open in the browser instead of downloading.
                file.extension in TEXT_FILES -> call.respond(LocalFileContent(file, ContentType.Text.Plain.withCharset(Charsets.UTF_8)))
                else -> call.respondFile(file)
            }
        }
    }
}

private val ENDED = setOf(Status.DONE, Status.FAILED, Status.CANCELLED)
private const val DEFAULT_WAIT_SEC = 600L
private const val MAX_WAIT_SEC = 3600L
private const val WAIT_POLL_MS = 2000L
private const val MIN_SCHEDULE_SEC = 60L
private const val PAGE_SIZE = 200
private const val MAX_COMPARED = 6

/** The latest finished experiment queued by the same schedule before [job], whose name is `<schedule>@<sha>`. */
private fun previousRun(store: Store, job: Job): Long? =
    store.jobs(limit = PAGE_SIZE, before = job.id, name = job.name.substringBefore('@'))
        .firstOrNull { it.experiment != null && it.status == Status.DONE && '@' in it.name }?.id
private const val LS_REMOTE_SEC = 15L
private val TEXT_FILES = setOf("", "out", "err", "log", "txt", "sha", "pid")

/** A browser asks for a page; `?json` or any API client gets the data. */
private fun ApplicationCall.wantsHtml() = "json" !in parameters && request.accept()?.contains("text/html") == true

/**
 * Refuse a branch or tag name origin does not have, so a typo fails at submit and not later in the queue. A commit
 * hash or a ref expression cannot be checked without fetching, and an origin that does not answer proves nothing:
 * those pass, and the runner reports them when it resolves the ref.
 */
private suspend fun requireRef(ref: String, config: Config) {
    if (!Regex("[A-Za-z0-9._/-]+").matches(ref) || Regex("[0-9a-f]{7,40}").matches(ref)) return
    val known = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("git", "ls-remote", config.repoUrl, "refs/heads/$ref", "refs/tags/$ref")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }
            .start()
        // Waited on before reading: the answer is a line or two, well inside the pipe buffer, and a hung remote
        // must not hold the request past the timeout.
        if (!process.waitFor(LS_REMOTE_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            true
        } else {
            process.exitValue() != 0 || process.inputStream.bufferedReader().readText().isNotBlank()
        }
    }
    require(known) { "unknown ref: $ref (no such branch or tag on origin; is it pushed?)" }
}

private fun requireParallel(parallel: Int, config: Config) =
    require(parallel in 1..config.maxParallel) { "parallel must be between 1 and ${config.maxParallel}" }

/** The last [bytes] of a file that may still be growing. */
private fun tail(file: File, bytes: Long): String = RandomAccessFile(file, "r").use { raf ->
    val start = maxOf(0, raf.length() - bytes)
    raf.seek(start)
    val buffer = ByteArray((raf.length() - start).toInt())
    raf.readFully(buffer)
    String(buffer)
}
