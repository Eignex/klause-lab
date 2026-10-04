package com.eignex.lab

import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The queue's front page: what runs and waits, the schedules, and the history, newest first. */
internal fun indexPage(
    config: Config,
    host: HostReport,
    active: List<Job>,
    schedules: List<Schedule>,
    history: List<Job>,
    limit: Int,
    before: Long?,
    name: String?,
): String = buildString {
    val front = before == null && name == null
    append(head("klause lab", live = before == null))
    append("<header><h1><a href=\"/\">klause lab</a></h1>")
    append("<p id=\"summary\" data-live class=\"muted\">${summary(config, host, active)}</p></header>")
    if (front) {
        append("<section><h2>Active</h2><div id=\"active\" data-live>${activeTable(config, active)}</div></section>")
        if (schedules.isNotEmpty()) {
            append("<section><h2>Schedules</h2><div id=\"schedules\" data-live>${schedulesTable(schedules)}</div></section>")
        }
    }
    append("<section><h2>")
    append(if (name == null) "History" else "Runs of ${esc(name)} <small><a href=\"/\">all jobs</a></small>")
    append("</h2><div class=\"tools\"><input id=\"q\" type=\"search\" placeholder=\"filter by name or ref\">")
    for (chip in listOf("all", "failed", "done", "cancelled", "active")) {
        if (chip == "active" && front) continue
        append("<button class=\"chip${if (chip == "all") " on" else ""}\" data-chip=\"$chip\">$chip</button>")
    }
    append("</div><div id=\"history\" data-live>")
    val shown = if (front) history.filter { it.status !in ACTIVE } else history
    append(jobTable(config, shown, emptyMap(), filterable = true))
    append("<p class=\"pager\">")
    if (before != null) append("<a href=\"/${query("name" to name)}\">newest</a> ")
    if (history.size == limit) append("<a href=\"/${query("name" to name, "before" to history.last().id.toString())}\">older</a>")
    append("</p></div></section>")
    append(SCRIPT)
    append("</body></html>")
}

/** One job: its settings, controls while it is unfinished, and every command with its output. */
internal fun jobPage(
    config: Config,
    job: Job,
    failedOnly: Boolean,
    arms: List<PlannedArm> = emptyList(),
    cases: List<CaseResult> = emptyList(),
    showCommands: Boolean = true,
): String = buildString {
    val dir = config.jobDir(job.id).toFile()
    val active = job.status in ACTIVE
    append(head("${job.id} ${job.name} · klause lab", live = active))
    append("<header><h1><a href=\"/\">klause lab</a> / ${job.id} ${esc(job.name)}</h1></header>")
    append("<div id=\"job\" data-live>")
    append("<dl class=\"meta\">")
    append("<dt>status</dt><dd>${statusCell(job, null)}</dd>")
    append("<dt>ref</dt><dd>${refText(config, job)}</dd>")
    append("<dt>progress</dt><dd>${progress(job)}</dd>")
    append("<dt>created</dt><dd>${ago(job.createdAt)}</dd>")
    if (job.startedAt != null) append("<dt>started</dt><dd>${ago(job.startedAt)}</dd>")
    if (job.finishedAt != null) append("<dt>finished</dt><dd>${ago(job.finishedAt)}</dd>")
    append("<dt>elapsed</dt><dd>${duration(job.startedAt, job.finishedAt)}</dd>")
    append("<dt>parallel</dt><dd>${job.parallel}</dd><dt>priority</dt><dd>${job.priority}</dd>")
    append("<dt>files</dt><dd><a href=\"/jobs/${job.id}/files\">all files</a>")
    for (log in listOf("setup.log", "job.log")) {
        if (File(dir, log).isFile) append(" · <a href=\"/jobs/${job.id}/files/$log\">$log</a>")
    }
    append(" · <a href=\"/jobs/${job.id}?json\">json</a></dd>")
    append("</dl>")
    job.error?.let { append("<pre class=\"error\">${esc(it)}</pre>") }
    if (active) {
        val id = job.id
        append("<div class=\"actions\">")
        append(
            if (job.paused) {
                "<button onclick=\"act('/jobs/$id/resume')\">resume</button>"
            } else {
                "<button onclick=\"act('/jobs/$id/pause')\">pause</button>"
            },
        )
        append("<label>priority <input id=\"priority\" type=\"number\" value=\"${job.priority}\"></label>")
        append("<button onclick=\"act('/jobs/$id/priority', {priority: +document.getElementById('priority').value})\">set</button>")
        append("<label>parallel <input id=\"parallel\" type=\"number\" min=\"1\" max=\"${config.maxParallel}\" value=\"${job.parallel}\"></label>")
        append("<button onclick=\"act('/jobs/$id/parallel', {parallel: +document.getElementById('parallel').value})\">set</button>")
        if (!job.cancelRequested) {
            append("<button class=\"danger\" onclick=\"act('/jobs/$id/cancel', null, 'Cancel job $id?')\">cancel</button>")
        }
        append("</div>")
    }
    if (job.experiment != null) append(experimentSection(config, job, arms, cases))
    if (!showCommands) {
        append("<p><a href=\"/jobs/${job.id}?commands\">every case's command</a></p></div>")
        append(SCRIPT)
        append("</body></html>")
        return@buildString
    }
    val commands = if (failedOnly) job.commands.filter { it.status == Status.FAILED } else job.commands
    append("<h2>Commands")
    if (failedOnly) {
        append(" <small>${commands.size} failed of ${job.commands.size} · <a href=\"/jobs/${job.id}\">show all</a></small>")
    } else if (job.failed > 0) {
        append(" <small><a href=\"/jobs/${job.id}?failed\">show the ${job.failed} failed</a></small>")
    }
    append("</h2><div class=\"scroll\"><table><tr><th>#</th><th>command</th><th>status</th><th>started</th>")
    append("<th>elapsed</th><th>output</th></tr>")
    for (command in commands) {
        val exit = command.exitCode?.takeIf { it != 0 }?.let { "<br><small>exit $it</small>" } ?: ""
        append("<tr><td>${command.index}</td><td><code>${esc(command.cmd)}</code></td>")
        append("<td class=\"${command.status}\">${command.status}$exit</td>")
        append("<td>${ago(command.startedAt)}</td><td>${duration(command.startedAt, command.finishedAt)}</td><td>")
        if (command.status != Status.QUEUED) {
            append(output(job.id, dir, "${command.index}.out", command.status == Status.RUNNING))
            append("<br>")
            append(output(job.id, dir, "${command.index}.err", command.status == Status.RUNNING))
        }
        append("</td></tr>")
    }
    append("</table></div></div>")
    append(SCRIPT)
    append("</body></html>")
}

/** An experiment's results: each arm's totals and score, the disagreements, and every problem across the arms. */
private fun experimentSection(config: Config, job: Job, arms: List<PlannedArm>, cases: List<CaseResult>): String = buildString {
    if (arms.isEmpty()) {
        append("<p class=\"muted\">Not planned yet: the runner selects the problems and writes the cases when it sets the job up.</p>")
        return@buildString
    }
    val labels = arms.map { it.arm.label }
    val comparison = Compare.compare(labels, cases)
    val seeds = cases.map { it.seed }.distinct().sortedBy { it ?: Long.MIN_VALUE }
    val total = cases.groupBy { it.arm }
    append("<h2>Arms <small>${cases.map { it.problem }.distinct().size} problems")
    if (seeds.size > 1) append(" × ${seeds.size} seeds")
    append(" · <a href=\"/experiments/${job.id}/cases.csv\">cases.csv</a></small></h2>")
    append("<div class=\"scroll\"><table><tr><th>arm</th><th>commit</th><th class=\"num\">done</th><th class=\"num\">solved</th>")
    append("<th class=\"num\">proven</th><th class=\"num\">errors</th><th class=\"num\">score</th><th>vs ${esc(labels.first())}</th>")
    append("<th>bench csv</th></tr>")
    for ((planned, summary) in arms.zip(comparison.arms)) {
        val commit = commitUrl(config.repoUrl, planned.sha)?.let { "<a href=\"$it\">${planned.sha.take(9)}</a>" } ?: planned.sha.take(9)
        val versus = if (summary.label == labels.first()) "<span class=\"muted\">baseline</span>" else
            "<span class=\"DONE\">${summary.wins} better</span> · <span class=\"FAILED\">${summary.losses} worse</span> · ${summary.ties} even"
        append("<tr><td><b>${esc(summary.label)}</b><br><small class=\"muted\">${esc(describe(planned.arm))}</small></td>")
        append("<td><code>$commit</code></td><td class=\"num\">${summary.cases}/${total[summary.label]?.size ?: 0}</td>")
        append("<td class=\"num\">${summary.solved}</td><td class=\"num\">${summary.proven}</td>")
        append("<td class=\"num\">${if (summary.errors > 0) "<span class=\"FAILED\">${summary.errors}</span>" else "0"}</td>")
        append("<td class=\"num\">${"%.1f".format(summary.score)}</td><td>$versus</td><td>")
        append(seeds.joinToString(" ") { seed ->
            val name = if (seed == null) "csv" else "seed $seed"
            "<a href=\"/experiments/${job.id}/bench.csv${query("arm" to summary.label, "seed" to seed?.toString())}\">$name</a>"
        })
        append("</td></tr>")
    }
    append("</table></div>")
    if (comparison.disagreements.isNotEmpty()) {
        append("<h2 class=\"FAILED\">Disagreements <small>${comparison.disagreements.size}</small></h2><ul class=\"error\">")
        for (d in comparison.disagreements) append("<li><code>${esc(name(d.problem))}</code>: ${esc(d.reason)}</li>")
        append("</ul>")
    }
    append(problemGrid(labels, cases))
}

/** One row per problem, one cell per arm with its outcome on each seed; the arm that scores best on a row is marked. */
private fun problemGrid(labels: List<String>, cases: List<CaseResult>): String = buildString {
    val byProblem = cases.groupBy { it.problem }
    append("<h2>Problems <small><label><input type=\"checkbox\" id=\"differ\"> only where arms differ</label></small></h2>")
    append("<div class=\"scroll\"><table class=\"grid\"><tr><th>problem</th>")
    for (label in labels) append("<th>${esc(label)}</th>")
    append("</tr>")
    for ((problem, ofProblem) in byProblem) {
        val outcomes = ofProblem.groupBy { it.arm }.mapValues { (_, cs) -> cs.sortedBy { it.seed ?: Long.MIN_VALUE } }
        val verdicts = labels.map { label -> outcomes[label].orEmpty().map { verdict(Outcome.of(it.record), it.status) } }
        val points = labels.associateWith { label ->
            ofProblem.filter { it.arm == label }.sumOf { mine ->
                val a = Outcome.of(mine.record) ?: return@sumOf 0.0
                ofProblem.filter { it.arm != label && it.seed == mine.seed }.sumOf { other -> Outcome.of(other.record)?.let { Compare.points(a, it) } ?: 0.0 }
            }
        }
        val best = points.values.maxOrNull()?.takeIf { top -> points.values.any { it < top } }
        append("<tr data-differ=\"${verdicts.distinct().size > 1}\"><td><code>${esc(name(problem))}</code></td>")
        for (label in labels) {
            val cls = if (best != null && points[label] == best) " class=\"best\"" else ""
            append("<td$cls>")
            append(outcomes[label].orEmpty().joinToString("<br>") { case ->
                val outcome = Outcome.of(case.record)
                val time = outcome?.takeIf { it.rank > 0 }?.let { " <small class=\"muted\">${"%.2f".format(it.timeMs / 1000.0)}s</small>" }.orEmpty()
                verdict(outcome, case.status) + time
            })
            append("</td>")
        }
        append("</tr>")
    }
    append("</table></div>")
}

/** One outcome in a word: the objective (starred when proven), sat, infeasible, unknown, or why there is none. */
private fun verdict(outcome: Outcome?, status: Status): String = when {
    outcome == null -> "<span class=\"${status.name}\">${status.name.lowercase()}</span>"
    outcome.error -> "<span class=\"FAILED\">error</span>"
    outcome.feasible == false -> "infeasible"
    outcome.feasible == null -> "<span class=\"muted\">unknown</span>"
    outcome.optimize && outcome.objective != null -> number(outcome.objective) + if (outcome.proven) "*" else ""
    else -> "sat"
}

private fun number(value: Double) = if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

private fun name(problem: Problem) = if (problem.suite.isEmpty()) problem.problem else "${problem.suite}/${problem.problem}"

private fun describe(arm: Arm) = arm.values.toSortedMap().entries.joinToString(" ") { (k, v) -> "$k=$v" }

internal fun filesPage(jobId: Long, files: List<FileEntry>): String = buildString {
    append(head("$jobId files · klause lab", live = false))
    append("<header><h1><a href=\"/\">klause lab</a> / <a href=\"/jobs/$jobId\">$jobId</a> / files</h1></header>")
    if (files.isEmpty()) append("<p class=\"muted\">No files yet.</p>")
    append("<div class=\"scroll\"><table><tr><th>path</th><th class=\"num\">size</th></tr>")
    for (file in files) {
        append("<tr><td><a href=\"/jobs/$jobId/files/${pathUrl(file.path)}\"><code>${esc(file.path)}</code></a></td>")
        append("<td class=\"num\">${size(file.bytes)}</td></tr>")
    }
    append("</table></div></body></html>")
}

internal val ACTIVE = setOf(Status.QUEUED, Status.RUNNING)

/** The GitHub page of [sha] when [repoUrl] is on GitHub. */
internal fun commitUrl(repoUrl: String, sha: String): String? =
    Regex("github\\.com[:/]([^/]+/[^/]+?)(\\.git)?/?$").find(repoUrl)
        ?.let { "https://github.com/${it.groupValues[1]}/commit/$sha" }

/** An error as one line: a ref the mirror lacks reads as such, anything else is cut to its first line. */
internal fun shortError(error: String): String =
    Regex("rev-parse --verify '(.+)\\^\\{commit\\}'").find(error)?.let { "unknown ref: ${it.groupValues[1]}" }
        ?: error.lineSequence().first().let { if (it.length > 120) it.take(120) + "…" else it }

private fun summary(config: Config, host: HostReport, active: List<Job>): String {
    val running = active.count { it.status == Status.RUNNING }
    val queued = active.count { it.status == Status.QUEUED }
    val free = freeBytes(config.dataDir)
    val disk = if (free < config.minFreeBytes) "<span class=\"FAILED\">${size(free)} free: queue held</span>" else "${size(free)} free"
    val accel = listOfNotNull(host.vendor, "simd".takeIf { host.simd }).joinToString(", ")
    return "$running running · $queued queued · $disk · ${esc(host.host)}, ${host.cores} cores" +
        if (accel.isEmpty()) "" else ", ${esc(accel)}"
}

private fun activeTable(config: Config, active: List<Job>): String {
    if (active.isEmpty()) return "<p class=\"muted\">Nothing running or queued.</p>"
    // The order the runner takes queued jobs in, as Store.next picks them.
    val positions = active
        .filter { it.status == Status.QUEUED && !it.paused && !it.cancelRequested }
        .sortedWith(compareByDescending<Job> { it.priority }.thenBy { it.id })
        .withIndex().associate { (index, job) -> job.id to index + 1 }
    val ordered = active.sortedWith(compareBy<Job> { it.status != Status.RUNNING }.thenBy { positions[it.id] ?: Int.MAX_VALUE })
    return jobTable(config, ordered, positions, filterable = false)
}

private fun jobTable(config: Config, jobs: List<Job>, positions: Map<Long, Int>, filterable: Boolean): String = buildString {
    if (jobs.isEmpty()) return "<p class=\"muted\">No jobs.</p>"
    append("<div class=\"scroll\"><table><tr><th>id</th><th>name</th><th>ref</th><th>status</th><th>progress</th>")
    append("<th>created</th><th>elapsed</th><th></th></tr>")
    for (job in jobs) {
        if (filterable) {
            val state = when {
                job.status == Status.FAILED || job.failed > 0 -> "failed"
                job.status in ACTIVE -> "active"
                else -> job.status.name.lowercase()
            }
            val text = esc(listOfNotNull(job.name, job.ref, job.sha).joinToString(" ").lowercase())
            append("<tr data-state=\"$state\" data-text=\"$text\">")
        } else {
            append("<tr>")
        }
        append("<td><a href=\"/jobs/${job.id}\">${job.id}</a></td>")
        val base = job.name.substringBefore('@')
        append("<td><a class=\"plain\" href=\"/${query("name" to base)}\" title=\"all runs of ${esc(base)}\">${esc(job.name)}</a></td>")
        append("<td>${refText(config, job)}</td><td>${statusCell(job, positions[job.id])}</td><td>${progress(job)}</td>")
        append("<td>${ago(job.createdAt)}</td><td>${duration(job.startedAt, job.finishedAt)}</td>")
        append("<td><a href=\"/jobs/${job.id}/files\">files</a></td></tr>")
        val running = job.commands.filter { it.status == Status.RUNNING }
        if (running.isNotEmpty() && !filterable) {
            append("<tr class=\"sub\"><td></td><td colspan=\"7\">")
            for (command in running) {
                append("<div><code>${esc(command.cmd.take(200))}</code> ${duration(command.startedAt, null)} ")
                append("<a href=\"/jobs/${job.id}/files/${command.index}.out?tail=$TAIL_BYTES\">out</a> ")
                append("<a href=\"/jobs/${job.id}/files/${command.index}.err?tail=$TAIL_BYTES\">err</a></div>")
            }
            append("</td></tr>")
        }
    }
    append("</table></div>")
}

private fun schedulesTable(schedules: List<Schedule>): String = buildString {
    append("<div class=\"scroll\"><table><tr><th>name</th><th>ref</th><th>every</th><th>commands</th>")
    append("<th>last run</th><th>checked</th><th>next check</th></tr>")
    for (schedule in schedules) {
        append("<tr><td><a class=\"plain\" href=\"/${query("name" to schedule.name)}\">${esc(schedule.name)}</a>")
        if (schedule.priority != 0) append("<br><small>priority ${schedule.priority}</small>")
        append("</td><td><code>${esc(schedule.ref)}</code></td><td>${span(schedule.intervalSec)}</td>")
        append("<td>${schedule.commands.size}${if (schedule.parallel > 1) " ×${schedule.parallel}" else ""}</td><td>")
        if (schedule.lastJob != null) {
            append("<a href=\"/jobs/${schedule.lastJob}\">${schedule.lastJob}</a> <code>${schedule.lastSha?.take(9) ?: ""}</code>")
        } else {
            append("<span class=\"muted\">never</span>")
        }
        append("</td><td>${ago(schedule.checkedAt)}</td><td>")
        val next = schedule.checkedAt?.let { it + schedule.intervalSec * 1000 - now() } ?: 0
        append(if (next <= 0) "due" else "in ${span(next / 1000)}")
        append("</td></tr>")
    }
    append("</table></div>")
}

private fun statusCell(job: Job, position: Int?): String {
    val partial = job.status == Status.DONE && job.failed > 0
    val label = if (partial) {
        "DONE · <a href=\"/jobs/${job.id}?failed\">${job.failed} failed</a>"
    } else {
        job.status.name
    }
    val notes = listOfNotNull(
        "cancelling".takeIf { job.cancelRequested && job.status in ACTIVE },
        "<span class=\"badge\">paused</span>".takeIf { job.paused && job.status in ACTIVE },
        "#$position in queue".takeIf { position != null },
        "priority ${job.priority}".takeIf { job.priority != 0 },
        job.error?.let { "<span title=\"${esc(it)}\">${esc(shortError(it))}</span>" },
    )
    val cls = if (partial) "PARTIAL" else job.status.name
    return "<span class=\"$cls\">$label</span>" + notes.joinToString("") { "<br><small>$it</small>" }
}

/** The ref and the commit it ran, without repeating a ref that is the commit itself. */
private fun refText(config: Config, job: Job): String {
    val isSha = Regex("[0-9a-f]{7,40}").matches(job.ref)
    val sha = job.sha ?: job.ref.takeIf { isSha }
    val commit = sha?.let { full ->
        val short = full.take(9)
        commitUrl(config.repoUrl, full)?.let { "<a href=\"$it\" title=\"$full\">$short</a>" } ?: short
    }
    return "<code>" + listOfNotNull(esc(job.ref).takeUnless { isSha }, commit).joinToString(" ") + "</code>"
}

private fun progress(job: Job): String {
    val total = job.commands.size.coerceAtLeast(1)
    val running = job.commands.count { it.status == Status.RUNNING }
    fun part(cls: String, count: Int) = if (count == 0) "" else "<i class=\"$cls\" style=\"width:${100.0 * count / total}%\"></i>"
    val bar = "<span class=\"bar\">${part("ok", job.done - job.failed)}${part("bad", job.failed)}${part("run", running)}</span>"
    val parallel = if (job.parallel > 1) " ×${job.parallel}" else ""
    return "$bar<small>${job.done}/${job.commands.size}$parallel</small>"
}

private fun output(jobId: Long, dir: File, name: String, running: Boolean): String {
    val file = File(dir, name)
    if (!file.isFile) return "<span class=\"muted\">${name.substringAfter('.')}</span>"
    val link = if (running) "$name?tail=$TAIL_BYTES" else name
    return "<a href=\"/jobs/$jobId/files/$link\">${name.substringAfter('.')}</a> <small class=\"muted\">${size(file.length())}</small>"
}

private val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/** How long ago, for the last day, then the date; the exact time is in the tooltip. */
private fun ago(millis: Long?): String {
    if (millis == null) return ""
    val seconds = (now() - millis) / 1000
    val text = if (seconds < 86_400) "${span(seconds)} ago" else clock.format(Instant.ofEpochMilli(millis))
    return "<span title=\"${stamp.format(Instant.ofEpochMilli(millis))}\">$text</span>"
}

private fun span(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86_400 -> "${seconds / 3600}h${if (seconds / 60 % 60 > 0) " ${seconds / 60 % 60}m" else ""}"
    else -> "${seconds / 86_400}d${if (seconds / 3600 % 24 > 0) " ${seconds / 3600 % 24}h" else ""}"
}

private fun duration(from: Long?, to: Long?): String {
    if (from == null) return ""
    val seconds = ((to ?: now()) - from) / 1000
    return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

private fun size(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
}

private fun query(vararg params: Pair<String, String?>): String = params
    .filter { it.second != null }
    .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
    .let { if (it.isEmpty()) "" else "?$it" }

private fun pathUrl(path: String) =
    path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }

private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private const val TAIL_BYTES = 65_536

private fun head(title: String, live: Boolean) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>${esc(title)}</title><style>$STYLE</style></head>
<body${if (live) " data-refresh" else ""}>"""

private const val STYLE = """
:root{--bg:#fff;--fg:#111;--muted:#666;--line:#ddd;--soft:#f3f3f3;--link:#0969da;
--run:#0550ae;--ok:#1a7f37;--warn:#9a6700;--bad:#cf222e;--off:#888}
@media (prefers-color-scheme: dark){:root{--bg:#111;--fg:#ddd;--muted:#999;--line:#333;--soft:#222;--link:#58a6ff;
--run:#6cb6ff;--ok:#3fb950;--warn:#d29922;--bad:#f85149;--off:#888}}
body{font:14px system-ui,sans-serif;margin:16px;background:var(--bg);color:var(--fg)}
a{color:var(--link)}a.plain{color:inherit;text-decoration:none}a.plain:hover{text-decoration:underline}
h1{margin:0 0 4px}h1 a{color:inherit;text-decoration:none}h2{font-size:16px;margin:24px 0 8px}h2 small{font-weight:normal}
.muted{color:var(--muted)}.scroll{overflow-x:auto}
table{border-collapse:collapse;width:100%}td,th{padding:4px 8px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
th.num,td.num{text-align:right}tr.sub td{border-top:0;padding-top:0;font-size:12px}tr.sub div{margin:2px 0}
code{font-size:12px;word-break:break-all}
.RUNNING{color:var(--run)}.DONE{color:var(--ok)}.PARTIAL{color:var(--warn)}.FAILED{color:var(--bad)}.CANCELLED,.QUEUED{color:var(--off)}
.PARTIAL a{color:inherit}
.badge{background:var(--warn);color:var(--bg);border-radius:3px;padding:0 4px}
.bar{display:flex;width:90px;height:6px;margin:6px 0 2px;background:var(--soft);border-radius:3px;overflow:hidden}
.bar i{display:block}.bar .ok{background:var(--ok)}.bar .bad{background:var(--bad)}.bar .run{background:var(--run)}
.tools{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px}.tools input{font:inherit;padding:3px 6px;min-width:200px}
button{font:inherit;padding:3px 10px;border:1px solid var(--line);border-radius:4px;background:var(--soft);color:var(--fg);cursor:pointer}
.chip.on{border-color:var(--link);color:var(--link)}button.danger{color:var(--bad);border-color:var(--bad)}
.actions{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:12px 0}.actions input{width:4em;font:inherit}
dl.meta{display:grid;grid-template-columns:max-content 1fr;gap:4px 16px;margin:12px 0}dl.meta dt{color:var(--muted)}dl.meta dd{margin:0}
table.grid td.best{background:color-mix(in srgb,var(--ok) 14%,transparent)}ul.error{color:var(--bad)}
pre.error{color:var(--bad);white-space:pre-wrap;word-break:break-all;background:var(--soft);padding:8px}
.pager{margin-top:12px}
"""

/** Filtering, actions and a refresh that swaps the live parts in place, so scrolling, a selection and a half-typed
 *  input survive it. A page stops refreshing once its fresh copy says nothing is live any more. */
private const val SCRIPT = """<script>
let chip = 'all';
function applyFilter() {
  const q = document.getElementById('q');
  const text = q ? q.value.trim().toLowerCase() : '';
  document.querySelectorAll('tr[data-state]').forEach(function (row) {
    row.hidden = !((!text || row.dataset.text.includes(text)) && (chip === 'all' || row.dataset.state === chip));
  });
}
document.getElementById('q')?.addEventListener('input', applyFilter);
function applyDiffer() {
  const only = document.getElementById('differ')?.checked;
  document.querySelectorAll('tr[data-differ]').forEach(function (row) { row.hidden = only && row.dataset.differ !== 'true'; });
}
document.addEventListener('change', function (e) { if (e.target.id === 'differ') applyDiffer(); });
document.querySelectorAll('[data-chip]').forEach(function (button) {
  button.addEventListener('click', function () {
    chip = button.dataset.chip;
    document.querySelectorAll('[data-chip]').forEach(function (b) { b.classList.toggle('on', b === button); });
    applyFilter();
  });
});
async function act(path, body, ask) {
  if (ask && !confirm(ask)) return;
  const response = await fetch(path, body
    ? {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)}
    : {method: 'POST'});
  if (!response.ok) alert(await response.text());
  location.reload();
}
let timer = null;
async function refresh() {
  if (document.hidden || String(getSelection()).length) return;
  if (document.activeElement && document.activeElement.closest('[data-live]')) return;
  try {
    const response = await fetch(location.href, {headers: {Accept: 'text/html'}});
    if (!response.ok) return;
    const fresh = new DOMParser().parseFromString(await response.text(), 'text/html');
    fresh.querySelectorAll('[data-live]').forEach(function (part) {
      const old = document.getElementById(part.id);
      if (old) old.innerHTML = part.innerHTML;
    });
    applyFilter();
    applyDiffer();
    if (!fresh.body.hasAttribute('data-refresh')) clearInterval(timer);
  } catch (e) {}
}
if (document.body.hasAttribute('data-refresh')) timer = setInterval(refresh, 10000);
</script>"""
