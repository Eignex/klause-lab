package com.eignex.lab

import java.io.File
import java.nio.file.Path

/**
 * Keeps the lab on the newest klause-lab commit. Between jobs, at most every [intervalMs], it fetches [source], the
 * checkout the services were installed from, and when its upstream is ahead runs `deploy/update.sh` there. That
 * pulls, builds a new release, checks the host and restarts both services onto it, this process included, so a
 * return from [maybeUpdate] after an update started means the update failed and the running release stays.
 */
class SelfUpdater(
    private val source: Path?,
    private val intervalMs: Long,
    private val log: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val exec: (dir: File, cmd: List<String>, log: File?) -> Pair<Int, String> = ::execute,
) {
    private var lastCheck: Long? = null

    fun maybeUpdate() {
        val dir = source?.toFile() ?: return
        val now = clock()
        if (lastCheck?.let { now - it < intervalMs } == true) return
        lastCheck = now
        if (exec(dir, listOf("git", "fetch", "-q", "origin"), null).first != 0) return println("self-update: fetch failed")
        val head = exec(dir, listOf("git", "rev-parse", "HEAD"), null).second.trim()
        val upstream = exec(dir, listOf("git", "rev-parse", "@{u}"), null).second.trim()
        if (head.isEmpty() || upstream.isEmpty() || head == upstream) return
        println("self-update: ${head.take(9)} -> ${upstream.take(9)}")
        val exit = exec(dir, listOf("bash", "deploy/update.sh"), log).first
        println("self-update failed (exit $exit), staying on the running release; see ${log.name}")
    }
}

private fun execute(dir: File, cmd: List<String>, log: File?): Pair<Int, String> {
    val builder = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
    if (log != null) builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
    val process = builder.start()
    val out = if (log == null) process.inputStream.bufferedReader().readText() else ""
    return process.waitFor() to out
}
