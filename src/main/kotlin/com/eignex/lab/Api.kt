package com.eignex.lab

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class CommandSpec(val cmd: String, val timeoutSec: Long? = null)

@Serializable
data class JobSpec(
    val name: String,
    val ref: String,
    val commands: List<CommandSpec>,
    val parallel: Int = 1,
    val priority: Int = 0,
)

@Serializable
data class ParallelSpec(val parallel: Int)

@Serializable
data class PrioritySpec(val priority: Int)

@Serializable
data class Created(val id: Long)

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
        get("/") { call.respondText(page(store.jobs()), ContentType.Text.Html) }
        get("/health") {
            val jobs = store.jobs()
            val queued = jobs.count { it.status == Status.QUEUED }
            val running = jobs.count { it.status == Status.RUNNING }
            call.respond(Health(true, queued, running, freeBytes(config.dataDir), host))
        }
        get("/jobs") { call.respond(store.jobs(call.parameters["limit"]?.toInt() ?: 200)) }
        post("/jobs") {
            val spec = call.receive<JobSpec>()
            require(spec.name.isNotBlank()) { "name is required" }
            require(spec.ref.isNotBlank() && !spec.ref.startsWith("-")) { "ref is required" }
            require(spec.commands.isNotEmpty()) { "at least one command is required" }
            requireParallel(spec.parallel, config)
            val id = store.create(
                spec.name,
                spec.ref,
                spec.commands.map { it.cmd to (it.timeoutSec ?: config.defaultTimeoutSec) },
                spec.parallel,
                spec.priority,
            )
            call.respond(HttpStatusCode.Created, Created(id))
        }
        get("/jobs/{id}") {
            val job = store.job(call.parameters["id"]!!.toLong())
            if (job == null) call.respond(HttpStatusCode.NotFound, "no such job") else call.respond(job)
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
        get("/jobs/{id}/files") {
            val root = config.jobDir(call.parameters["id"]!!.toLong()).toFile()
            val files = root.walkTopDown().filter { it.isFile }.map { FileEntry(it.relativeTo(root).path, it.length()) }.sortedBy { it.path }.toList()
            call.respond(files)
        }
        get("/jobs/{id}/files/{path...}") {
            val root = config.jobDir(call.parameters["id"]!!.toLong()).toFile().canonicalFile
            val file = File(root, call.parameters.getAll("path")!!.joinToString("/")).canonicalFile
            require(file.toPath().startsWith(root.toPath())) { "path escapes the job directory" }
            val tail = call.parameters["tail"]?.toLong()
            when {
                !file.isFile -> call.respond(HttpStatusCode.NotFound, "no such file")
                tail != null -> call.respondText(tail(file, tail), ContentType.Text.Plain)
                else -> call.respondFile(file)
            }
        }
    }
}

private val ENDED = setOf(Status.DONE, Status.FAILED, Status.CANCELLED)
private const val DEFAULT_WAIT_SEC = 600L
private const val MAX_WAIT_SEC = 3600L
private const val WAIT_POLL_MS = 2000L

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

private val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

private fun time(millis: Long?) = millis?.let { clock.format(Instant.ofEpochMilli(it)) } ?: ""

private fun duration(from: Long?, to: Long?): String {
    if (from == null) return ""
    val seconds = ((to ?: now()) - from) / 1000
    return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun page(jobs: List<Job>): String = buildString {
    append(
        """<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="refresh" content="10">
        <meta name="viewport" content="width=device-width, initial-scale=1"><title>klause lab</title><style>
        body{font:14px system-ui,sans-serif;margin:16px;background:#fff;color:#111}
        table{border-collapse:collapse;width:100%}td,th{padding:4px 8px;border-bottom:1px solid #ddd;text-align:left;vertical-align:top}
        code{font-size:12px;word-break:break-all}.RUNNING{color:#0550ae}.DONE{color:#1a7f37}.FAILED{color:#cf222e}.CANCELLED{color:#888}
        @media (prefers-color-scheme: dark){body{background:#111;color:#ddd}td,th{border-color:#333}.RUNNING{color:#6cb6ff}}
        </style></head><body><h1>klause lab</h1><table><tr><th>id</th><th>name</th><th>ref</th><th>status</th>
        <th>progress</th><th>current</th><th>created</th><th>elapsed</th></tr>""",
    )
    for (job in jobs) {
        val current = job.commands.firstOrNull { it.status == Status.RUNNING }
        append("<tr><td><a href=\"/jobs/${job.id}\">${job.id}</a></td><td>${esc(job.name)}</td>")
        append("<td><code>${esc(job.ref)}${job.sha?.let { " " + it.take(9) } ?: ""}</code></td>")
        val flags = listOfNotNull("paused".takeIf { job.paused }, "priority ${job.priority}".takeIf { job.priority != 0 })
        val note = (flags + listOfNotNull(job.error)).joinToString("<br>") { "<small>${esc(it)}</small>" }
        append("<td class=\"${job.status}\">${job.status}${if (note.isEmpty()) "" else "<br>$note"}</td>")
        append("<td>${job.done}/${job.commands.size}${if (job.parallel > 1) " ×${job.parallel}" else ""}${if (job.failed > 0) "<br><small>${job.failed} failed</small>" else ""}</td>")
        append("<td>")
        if (current != null) {
            append("<code>${esc(current.cmd.take(160))}</code><br>${duration(current.startedAt, null)} ")
            append("<a href=\"/jobs/${job.id}/files/${current.index}.out?tail=4000\">out</a> ")
            append("<a href=\"/jobs/${job.id}/files/${current.index}.err?tail=4000\">err</a>")
        }
        append("</td><td>${time(job.createdAt)}</td><td>${duration(job.startedAt, job.finishedAt)}</td>")
        append("<td><a href=\"/jobs/${job.id}/files\">files</a></td></tr>")
    }
    append("</table></body></html>")
}
