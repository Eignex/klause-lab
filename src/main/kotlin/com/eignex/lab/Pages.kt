package com.eignex.lab

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    append("<header><h1>Queue</h1>")
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
    previous: Long? = null,
    references: Map<Pair<String, String>, Reference> = emptyMap(),
): String = buildString {
    val dir = config.jobDir(job.id).toFile()
    val active = job.status in ACTIVE
    append(head("${job.id} ${job.name} · klause lab", live = active))
    append("<header><h1>${job.id} ${esc(job.name)}</h1></header>")
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
    if (previous != null) {
        append("<p>Previous run of this schedule: <a href=\"/jobs/$previous\">$previous</a> · ")
        append("<a href=\"/compare?jobs=$previous,${job.id}\">compare with it</a> · ")
        append("<a href=\"/trend${query("name" to job.name.substringBefore('@'))}\">trend</a></p>")
    }
    if (job.experiment != null) append(experimentSection(config, job.id, arms, cases, references))
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
private fun experimentSection(
    config: Config,
    job: Long?,
    arms: List<PlannedArm>,
    cases: List<CaseResult>,
    references: Map<Pair<String, String>, Reference> = emptyMap(),
): String = buildString {
    if (arms.isEmpty()) {
        append("<p class=\"muted\">Not planned yet: the runner selects the problems and writes the cases when it sets the job up.</p>")
        return@buildString
    }
    val labels = arms.map { it.arm.label }
    val comparison = Compare.compare(labels, cases)
    val stats = Stats.of(labels, cases)
    val seeds = cases.map { it.seed }.distinct().sortedBy { it ?: Long.MIN_VALUE }
    val repeats = (cases.maxOfOrNull { it.repeat } ?: 0) + 1
    val total = cases.groupBy { it.arm }
    append("<h2>Arms <small>${cases.map { it.problem }.distinct().size} problems")
    if (seeds.size > 1) append(" × ${seeds.size} seeds")
    if (repeats > 1) append(" × $repeats repeats")
    if (job != null) append(" · <a href=\"/experiments/$job/cases.csv\">cases.csv</a>")
    append("</small></h2>")
    append("<div class=\"scroll\"><table><tr><th>arm</th><th>commit</th><th class=\"num\">done</th><th class=\"num\">solved</th>")
    append("<th class=\"num\">proven</th><th class=\"num\">unsupported</th><th class=\"num\">errors</th><th class=\"num\">PAR-2 s</th><th class=\"num\">score</th>")
    append("<th>vs ${esc(labels.first())}</th>")
    append(if (job != null) "<th>bench csv</th></tr>" else "</tr>")
    val paired = stats.paired.associateBy { it.label }
    for ((index, planned) in arms.withIndex()) {
        val summary = comparison.arms[index]
        val armStats = stats.arms[index]
        val commit = commitUrl(config.repoUrl, planned.sha)?.let { "<a href=\"$it\">${planned.sha.take(9)}</a>" } ?: planned.sha.take(9)
        val versus = paired[summary.label]?.let(::versus) ?: "<span class=\"muted\">baseline</span>"
        append("<tr><td><b>${esc(summary.label)}</b><br><small class=\"muted\">${esc(describe(planned.arm))}</small></td>")
        append("<td><code>$commit</code></td><td class=\"num\">${summary.cases}/${total[summary.label]?.size ?: 0}</td>")
        append("<td class=\"num\">${estimate(armStats.solved, "%.1f")}</td><td class=\"num\">${summary.proven}</td>")
        append("<td class=\"num\">${if (summary.unsupported > 0) "<span class=\"PARTIAL\">${summary.unsupported}</span>" else "0"}</td>")
        append("<td class=\"num\">${if (summary.errors > 0) "<span class=\"FAILED\">${summary.errors}</span>" else "0"}</td>")
        append("<td class=\"num\">${estimate(armStats.par2, "%.2f")}</td><td class=\"num\">${estimate(armStats.score, "%.1f")}</td>")
        append("<td>$versus</td>")
        if (job != null) {
            append("<td>")
            append(seeds.joinToString(" ") { seed ->
                val name = if (seed == null) "csv" else "seed $seed"
                "<a href=\"/experiments/$job/bench.csv${query("arm" to summary.label, "seed" to seed?.toString())}\">$name</a>"
            })
            append("</td>")
        }
        append("</tr>")
    }
    append("</table></div>")
    append("<p class=\"muted\"><small>Intervals are 95%, bootstrapped over problems, a problem's seeds and repeats averaged. ")
    append("PAR-2 charges an unsolved run twice its budget. The time ratio is the geometric mean of an arm's PAR-2 time over the ")
    append("first arm's, on the problems either solved; its p is a Wilcoxon signed-rank test. Better and worse count problems by ")
    append("the score, with a sign test. p below 0.05 is bold.</small></p>")
    if (comparison.disagreements.isNotEmpty()) {
        append("<h2 class=\"FAILED\">Disagreements <small>${comparison.disagreements.size}</small></h2><ul class=\"error\">")
        for (d in comparison.disagreements) append("<li><code>${esc(name(d.problem))}</code>: ${esc(d.reason)}</li>")
        append("</ul>")
    }
    if (stats.noise.isNotEmpty()) append(noiseTable(stats.noise))
    if (references.isNotEmpty()) append(referenceSection(labels, cases, references))
    append(problemGrid(labels, cases, references))
}

/** Each arm against the lab's reference results: who solved what, optima, gaps, and where they contradict. */
private fun referenceSection(labels: List<String>, cases: List<CaseResult>, references: Map<Pair<String, String>, Reference>): String =
    buildString {
        val comparison = References.compare(labels, cases, references)
        append("<h2>Against the reference <small>${references.size} of ${cases.map { it.problem }.distinct().size} problems have ")
        append("a reference verdict · <a href=\"/references\">reference results</a></small></h2>")
        append("<div class=\"scroll\"><table><tr><th>arm</th><th class=\"num\">covered</th><th class=\"num\">both solved</th>")
        append("<th class=\"num\">only the arm</th><th class=\"num\">only the reference</th><th class=\"num\">proven optima reached</th>")
        append("<th class=\"num\">beats its best</th><th class=\"num\">mean gap</th></tr>")
        for (r in comparison.summaries) {
            append("<tr><td><b>${esc(r.arm)}</b></td><td class=\"num\">${r.covered}</td><td class=\"num\">${r.bothSolved}</td>")
            append("<td class=\"num\">${if (r.armOnly > 0) "<span class=\"DONE\">${r.armOnly}</span>" else "0"}</td>")
            append("<td class=\"num\">${if (r.referenceOnly > 0) "<span class=\"FAILED\">${r.referenceOnly}</span>" else "0"}</td>")
            append("<td class=\"num\">${r.optimaMatched}/${r.provenOptima}</td><td class=\"num\">${r.better}</td>")
            append("<td class=\"num\">${r.meanGap?.let { "%.1f%%".format(it * 100) } ?: "–"}</td></tr>")
        }
        append("</table></div><p class=\"muted\"><small>The reference ran under its own budget, so this compares verdicts ")
        append("and objectives, not speed. Mean gap is the relative distance to the reference objective where the arm is worse.</small></p>")
        if (comparison.disagreements.isNotEmpty()) {
            append("<h2 class=\"FAILED\">Disagreements with the reference <small>${comparison.disagreements.size}</small></h2><ul class=\"error\">")
            for (d in comparison.disagreements) append("<li><code>${esc(name(d.problem))}</code>: ${esc(d.reason)}</li>")
            append("</ul>")
        }
    }

/** An arm against the baseline: the time ratio with its interval and test, then better and worse with theirs. */
private fun versus(p: Paired): String {
    val ratio = if (p.problems == 0) "<span class=\"muted\">no problem solved by either</span>" else
        "${"%.2f".format(p.timeRatio.value)}× <small class=\"muted\">${"%.2f".format(p.timeRatio.low)}–${"%.2f".format(p.timeRatio.high)}</small> " +
            "${pValue(p.wilcoxonP)} <small class=\"muted\">n=${p.problems}</small>"
    return "$ratio<br><span class=\"DONE\">${p.better} better</span> · <span class=\"FAILED\">${p.worse} worse</span> ${pValue(p.signP)}"
}

private fun estimate(e: Estimate, format: String) =
    "${format.format(e.value)} <small class=\"muted\">${format.format(e.low)}–${format.format(e.high)}</small>"

private fun pValue(p: Double): String {
    val text = if (p < 0.001) "p<0.001" else "p=${"%.3f".format(p)}"
    return if (p < SIGNIFICANT) "<b>$text</b>" else "<small>$text</small>"
}

/** Problems whose runs of one arm disagree, the most unsettled first. */
private fun noiseTable(noise: List<Noise>): String = buildString {
    append("<h2>Noisy problems <small>${noise.size}: runs that disagree on the verdict, or whose time to best spreads over ")
    append("25% of its mean</small></h2><div class=\"scroll\"><table><tr><th>problem</th><th>arm</th>")
    append("<th class=\"num\">runs</th><th class=\"num\">verdicts</th><th class=\"num\">time spread</th></tr>")
    for (n in noise.take(NOISE_ROWS)) {
        append("<tr><td><code>${esc(name(n.problem))}</code></td><td>${esc(n.arm)}</td><td class=\"num\">${n.runs}</td>")
        append("<td class=\"num\">${if (n.verdicts > 1) "<span class=\"FAILED\">${n.verdicts}</span>" else "1"}</td>")
        append("<td class=\"num\">${"%.0f".format(n.spread * 100)}%</td></tr>")
    }
    append("</table></div>")
    if (noise.size > NOISE_ROWS) append("<p class=\"muted\">${noise.size - NOISE_ROWS} more in cases.csv</p>")
}

private const val SIGNIFICANT = 0.05
private const val NOISE_ROWS = 50

/** One row per problem, one cell per arm with its outcome on each seed; the arm that scores best on a row is marked. */
private fun problemGrid(
    labels: List<String>,
    cases: List<CaseResult>,
    references: Map<Pair<String, String>, Reference> = emptyMap(),
): String = buildString {
    val byProblem = cases.groupBy { it.problem }
    append("<h2>Problems <small><label><input type=\"checkbox\" id=\"differ\"> only where arms differ</label></small></h2>")
    append("<div class=\"scroll\"><table class=\"grid\"><tr><th>problem</th>")
    for (label in labels) append("<th>${esc(label)}</th>")
    if (references.isNotEmpty()) append("<th class=\"ref\">reference</th>")
    append("</tr>")
    for ((problem, ofProblem) in byProblem) {
        val outcomes = ofProblem.groupBy { it.arm }.mapValues { (_, cs) -> cs.sortedBy { it.seed ?: Long.MIN_VALUE } }
        val verdicts = labels.map { label -> outcomes[label].orEmpty().map { verdict(Outcome.of(it.record), it.status) } }
        val points = labels.associateWith { label ->
            ofProblem.filter { it.arm == label }.sumOf { mine ->
                val a = Outcome.of(mine.record) ?: return@sumOf 0.0
                ofProblem.filter { it.arm != label && it.seed == mine.seed && it.repeat == mine.repeat }.sumOf { other -> Outcome.of(other.record)?.let { Compare.points(a, it) } ?: 0.0 }
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
        if (references.isNotEmpty()) append("<td class=\"ref\">${referenceVerdict(references[problem.collection to problem.problem])}</td>")
        append("</tr>")
    }
    append("</table></div>")
}

/** A reference verdict in a word, the solver beside it and its time against its own budget on hover. */
private fun referenceVerdict(r: Reference?): String {
    if (r == null) return "<span class=\"muted\">–</span>"
    val word = when {
        r.feasible == false -> "infeasible"
        r.feasible == null -> "unknown"
        r.objective != null -> number(r.objective) + if (r.proven) "*" else ""
        else -> "sat"
    }
    val title = "${r.solver}: ${"%.2f".format(r.elapsedMs / 1000.0)}s of a ${r.budgetMs / 1000}s budget"
    return "<span title=\"${esc(title)}\">$word</span> <small class=\"muted\">${esc(r.solver)}</small>"
}

/** One outcome in a word: the objective (starred when proven), sat, infeasible, unknown, unsupported, a load error, or
 *  the case's status when it left no record; a reason shows on hover. */
private fun verdict(outcome: Outcome?, status: Status): String = when {
    outcome == null -> "<span class=\"${status.name}\">${status.name.lowercase()}</span>"
    outcome.error -> "<span class=\"FAILED\">error</span>"
    outcome.unsupported != null -> "<span class=\"PARTIAL\" title=\"${esc(outcome.unsupported)}\">unsupported</span>"
    outcome.loadError != null -> "<span class=\"FAILED\" title=\"${esc(outcome.loadError)}\">load error</span>"
    outcome.feasible == false -> "infeasible"
    outcome.feasible == null -> "<span class=\"muted\">unknown</span>"
    outcome.optimize && outcome.objective != null -> number(outcome.objective) + if (outcome.proven) "*" else ""
    else -> "sat"
}

private fun number(value: Double) = if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

private fun name(problem: Problem) = if (problem.suite.isEmpty()) problem.problem else "${problem.suite}/${problem.problem}"

private fun describe(arm: Arm) = arm.values.toSortedMap().entries.joinToString(" ") { (k, v) -> "$k=$v" }

/**
 * Several experiments side by side, each arm named by its job: a scheduled run against the one before it, or any
 * experiments over the same problems. Problems pair up by suite and name, seeds by value.
 */
internal fun comparePage(
    config: Config,
    jobs: List<Job>,
    arms: List<PlannedArm>,
    cases: List<CaseResult>,
    references: Map<Pair<String, String>, Reference> = emptyMap(),
): String = buildString {
    append(head("compare ${jobs.joinToString(", ") { it.id.toString() }} · klause lab", live = false))
    append("<header><h1>Compare ")
    append(jobs.joinToString(" · ") { "<a href=\"/jobs/${it.id}\">${it.id} ${esc(it.name)}</a>" })
    append("</h1></header><div>")
    append(experimentSection(config, null, arms, cases, references))
    append("</div>")
    append(SCRIPT)
    append("</body></html>")
}

/**
 * How a schedule's runs moved over its commits: solved and proven shares with the solved interval, PAR-2 time, and
 * solved share per suite, against commit or date. The runs ride in the page as JSON; a small script draws the charts.
 */
internal fun trendPage(name: String?, runs: List<TrendRun>, repoUrl: String, schedules: List<String> = emptyList()): String = buildString {
    append(head("${name ?: "regression"} · klause lab", live = runs.any { !it.finished }, tab = Tab.REGRESSION))
    if (name == null) {
        append("<header><h1>Regression</h1></header><p class=\"muted\">No schedule yet: <code>lab schedule</code> sets one up, ")
        append("and its runs chart here, one point per commit.</p></body></html>")
        return@buildString
    }
    append("<header><h1>Regression: ${esc(name)}</h1>")
    if (schedules.size > 1) {
        append("<div class=\"tools\">")
        for (other in schedules) {
            append("<a class=\"chip${if (other == name) " on" else ""}\" href=\"/trend${query("name" to other)}\">${esc(other)}</a>")
        }
        append("</div>")
    }
    append("<p class=\"muted\">${runs.size} runs · <a href=\"/${query("name" to name)}\">its jobs</a></p></header>")
    if (runs.isEmpty()) {
        append("<p class=\"muted\">No run of this schedule has results yet.</p></body></html>")
        return@buildString
    }
    append("<div class=\"tools\" role=\"group\" aria-label=\"x-axis\"><span class=\"muted\">x-axis</span>")
    append("<button class=\"chip on\" data-x=\"commit\">commit</button><button class=\"chip\" data-x=\"date\">date</button></div>")
    append("<h2>Solved and proven <small>% of the problems each run ran; band: 95% interval of solved</small></h2>")
    append("<div class=\"legend\"><span><i style=\"background:var(--series-1)\"></i>solved</span>")
    append("<span><i style=\"background:var(--series-2)\"></i>proven</span>")
    append("<span><i class=\"hollow\"></i>run still going</span></div>")
    append("<div class=\"chart\" id=\"chart-solved\"></div>")
    append("<h2>PAR-2 time <small>mean seconds per problem, an unsolved one charged twice its budget; lower is better</small></h2>")
    append("<div class=\"chart\" id=\"chart-par2\"></div>")
    append("<h2>Solved by suite <small>% of each suite's problems</small></h2><div class=\"multiples\" id=\"suites\"></div>")
    append("<h2>Runs</h2><div class=\"scroll\"><table><tr><th>job</th><th>commit</th><th>when</th>")
    append("<th class=\"num\">problems</th><th class=\"num\">solved</th><th class=\"num\">proven</th>")
    append("<th class=\"num\">unsupported</th><th class=\"num\">errors</th><th class=\"num\">PAR-2 s</th></tr>")
    for (run in runs.asReversed()) {
        val commit = commitUrl(repoUrl, run.sha)?.let { "<a href=\"$it\">${run.sha.take(9)}</a>" } ?: run.sha.take(9)
        append("<tr><td><a href=\"/jobs/${run.job}\">${run.job}</a>${if (run.finished) "" else " <small class=\"muted\">running</small>"}</td>")
        append("<td><code>$commit</code></td><td>${ago(run.at)}</td><td class=\"num\">${run.problems}</td>")
        append("<td class=\"num\">${percent(run.solved.value)} <small class=\"muted\">${percent(run.solved.low)}–${percent(run.solved.high)}</small></td>")
        append("<td class=\"num\">${percent(run.proven)}</td><td class=\"num\">${run.unsupported}</td><td class=\"num\">${run.errors}</td>")
        append("<td class=\"num\">${"%.2f".format(run.par2.value)}</td></tr>")
    }
    append("</table></div><div id=\"tip\" class=\"tip\" hidden></div>")
    append("<script type=\"application/json\" id=\"trend-data\">")
    append(Json.encodeToString(runs).replace("</", "<\\/"))
    append("</script>")
    append(SCRIPT)
    append(TREND_SCRIPT)
    append("</body></html>")
}

private fun percent(share: Double) = "%.1f%%".format(share * 100)

/** Draws the trend charts from the embedded runs, and redraws them when the x-axis or the width changes. */
private const val TREND_SCRIPT = """<script>
(function () {
  const runs = JSON.parse(document.getElementById('trend-data').textContent);
  const tip = document.getElementById('tip');
  const NS = 'http://www.w3.org/2000/svg';
  let xMode = 'commit';
  try { xMode = localStorage.getItem('trend-x') || 'commit'; } catch (e) {}
  const suites = Array.from(new Set(runs.flatMap(function (r) { return Object.keys(r.suites); }))).sort();

  function el(name, attrs, parent) {
    const node = document.createElementNS(NS, name);
    for (const k in attrs) node.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(node);
    return node;
  }
  function pct(v) { return (v * 100).toFixed(1) + '%'; }
  function when(ms) {
    const d = new Date(ms);
    function two(n) { return String(n).padStart(2, '0'); }
    return two(d.getMonth() + 1) + '-' + two(d.getDate()) + ' ' + two(d.getHours()) + ':' + two(d.getMinutes());
  }
  function niceMax(v) {
    if (!(v > 0)) return 1;
    const p = Math.pow(10, Math.floor(Math.log10(v)));
    for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p;
    return 10 * p;
  }

  // series: [{name, color, value(run), band(run)?, label?}]; y: {max, ticks, fmt}
  function draw(host, series, y, opts) {
    host.innerHTML = '';
    const W = Math.max(host.clientWidth, 240), H = opts.height;
    const m = {l: 46, r: opts.endLabels ? 92 : 36, t: 10, b: 28};
    const pw = W - m.l - m.r, ph = H - m.t - m.b;
    const svg = el('svg', {width: W, height: H, viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': opts.title}, host);
    const times = runs.map(function (r) { return r.at; });
    const t0 = Math.min.apply(null, times), t1 = Math.max.apply(null, times);
    function x(i) {
      if (runs.length === 1) return m.l + pw / 2;
      if (xMode === 'date') return m.l + (t1 === t0 ? pw / 2 : (runs[i].at - t0) / (t1 - t0) * pw);
      return m.l + i / (runs.length - 1) * pw;
    }
    // Lines join runs left to right: by time on the date axis, where runs need not have ended in job order.
    const order = runs.map(function (r, i) { return i; });
    if (xMode === 'date') order.sort(function (a, b) { return runs[a].at - runs[b].at; });
    function yy(v) { return m.t + ph - Math.min(v, y.max) / y.max * ph; }
    for (const t of y.ticks) {
      el('line', {x1: m.l, x2: m.l + pw, y1: yy(t), y2: yy(t), class: 'grid'}, svg);
      el('text', {x: m.l - 6, y: yy(t) + 4, 'text-anchor': 'end', class: 'tick'}, svg).textContent = y.fmt(t);
    }
    if (xMode === 'date') {
      const n = Math.max(2, Math.min(5, runs.length, Math.floor(pw / 110)));
      for (let k = 0; k < n; k++) {
        const t = n === 1 ? t0 : t0 + (t1 - t0) * k / (n - 1);
        const px = t1 === t0 ? m.l + pw / 2 : m.l + (t - t0) / (t1 - t0) * pw;
        el('text', {x: px, y: H - 8, 'text-anchor': 'middle', class: 'tick'}, svg).textContent = when(t);
      }
    } else {
      const every = Math.max(1, Math.ceil(runs.length / Math.max(1, Math.floor(pw / 64))));
      runs.forEach(function (r, i) {
        if (i % every === 0 || i === runs.length - 1) {
          el('text', {x: x(i), y: H - 8, 'text-anchor': 'middle', class: 'tick mono'}, svg).textContent = r.sha.slice(0, 7);
        }
      });
    }
    for (const s of series) {
      if (s.band) {
        const pts = order.map(function (i) { const b = s.band(runs[i]); return b ? [x(i), yy(b[0]), yy(b[1])] : null; }).filter(Boolean);
        if (pts.length > 1) {
          const d = 'M' + pts.map(function (p) { return p[0] + ',' + p[2]; }).join('L') +
            'L' + pts.slice().reverse().map(function (p) { return p[0] + ',' + p[1]; }).join('L') + 'Z';
          el('path', {d: d, fill: s.color, 'fill-opacity': 0.1, stroke: 'none'}, svg);
        }
      }
      const pts = order.map(function (i) { const v = s.value(runs[i]); return v == null ? null : [x(i), yy(v), runs[i]]; }).filter(Boolean);
      if (pts.length > 1) {
        el('path', {d: 'M' + pts.map(function (p) { return p[0] + ',' + p[1]; }).join('L'), fill: 'none', stroke: s.color,
          'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round'}, svg);
      }
      for (const p of pts) {
        el('circle', {cx: p[0], cy: p[1], r: 4, fill: p[2].finished ? s.color : 'var(--bg)', stroke: p[2].finished ? 'var(--bg)' : s.color,
          'stroke-width': 2}, svg);
      }
      if (opts.endLabels && pts.length) {
        const last = pts[pts.length - 1];
        el('text', {x: last[0] + 10, y: last[1] + 4, class: 'endlabel'}, svg).textContent = s.name + ' ' + y.fmt(s.value(last[2]));
      }
    }
    const cross = el('line', {y1: m.t, y2: m.t + ph, class: 'cross', visibility: 'hidden'}, svg);
    const hit = el('rect', {x: m.l - 8, y: 0, width: pw + 16, height: H, fill: 'transparent'}, svg);
    function nearest(evt) {
      const box = svg.getBoundingClientRect();
      const px = evt.clientX - box.left;
      let best = 0;
      runs.forEach(function (r, i) { if (Math.abs(x(i) - px) < Math.abs(x(best) - px)) best = i; });
      return best;
    }
    hit.addEventListener('pointermove', function (evt) {
      const i = nearest(evt);
      cross.setAttribute('x1', x(i)); cross.setAttribute('x2', x(i)); cross.setAttribute('visibility', 'visible');
      show(runs[i], evt, opts.suite);
    });
    hit.addEventListener('pointerleave', function () { cross.setAttribute('visibility', 'hidden'); tip.hidden = true; });
    hit.addEventListener('click', function (evt) { location.href = '/jobs/' + runs[nearest(evt)].job; });
    hit.style.cursor = 'pointer';
  }

  function show(r, evt, suite) {
    const rows = [
      ['commit', r.sha.slice(0, 9)], ['when', when(r.at) + (r.finished ? '' : ' (running)')], ['job', '#' + r.job],
      ['solved', pct(r.solved.value) + ' (' + pct(r.solved.low) + '–' + pct(r.solved.high) + ')'],
      ['proven', pct(r.proven)], ['PAR-2', r.par2.value.toFixed(2) + ' s'],
      ['unsupported', r.unsupported], ['errors', r.errors], ['problems', r.problems],
    ];
    if (suite && r.suites[suite]) rows.unshift([suite, pct(r.suites[suite].solved) + ' of ' + r.suites[suite].problems]);
    tip.innerHTML = rows.map(function (kv) { return '<div><span>' + kv[0] + '</span><b>' + kv[1] + '</b></div>'; }).join('');
    tip.hidden = false;
    const w = tip.offsetWidth, h = tip.offsetHeight;
    let left = evt.pageX + 14, top = evt.pageY - h - 10;
    if (left + w > window.scrollX + document.documentElement.clientWidth - 8) left = evt.pageX - w - 14;
    if (top < window.scrollY + 8) top = evt.pageY + 16;
    tip.style.left = left + 'px'; tip.style.top = top + 'px';
  }

  function render() {
    const percentAxis = {max: 1, ticks: [0, 0.25, 0.5, 0.75, 1], fmt: function (v) { return Math.round(v * 100) + '%'; }};
    draw(document.getElementById('chart-solved'), [
      {name: 'solved', color: 'var(--series-1)', value: function (r) { return r.solved.value; },
        band: function (r) { return [r.solved.low, r.solved.high]; }},
      {name: 'proven', color: 'var(--series-2)', value: function (r) { return r.proven; }},
    ], percentAxis, {height: 260, endLabels: true, title: 'Solved and proven share per run'});
    const top = niceMax(Math.max.apply(null, runs.map(function (r) { return r.par2.high; })));
    draw(document.getElementById('chart-par2'), [
      {name: 'PAR-2', color: 'var(--series-1)', value: function (r) { return r.par2.value; },
        band: function (r) { return [r.par2.low, r.par2.high]; }},
    ], {max: top, ticks: [0, top / 4, top / 2, 3 * top / 4, top], fmt: function (v) { return (Math.round(v * 10) / 10) + 's'; }},
      {height: 220, endLabels: false, title: 'PAR-2 seconds per run'});
    const grid = document.getElementById('suites');
    grid.innerHTML = '';
    for (const s of suites) {
      const cell = document.createElement('div');
      cell.className = 'multiple';
      cell.innerHTML = '<div class="mtitle">' + s + '</div>';
      const plot = document.createElement('div');
      cell.appendChild(plot);
      grid.appendChild(cell);
      draw(plot, [{name: s, color: 'var(--series-1)', value: function (r) { return r.suites[s] ? r.suites[s].solved : null; }}],
        {max: 1, ticks: [0, 0.5, 1], fmt: function (v) { return Math.round(v * 100) + '%'; }},
        {height: 130, endLabels: false, title: s + ' solved share per run', suite: s});
    }
  }

  document.querySelectorAll('[data-x]').forEach(function (b) {
    b.classList.toggle('on', b.dataset.x === xMode);
    b.addEventListener('click', function () {
      xMode = b.dataset.x;
      try { localStorage.setItem('trend-x', xMode); } catch (e) {}
      document.querySelectorAll('[data-x]').forEach(function (o) { o.classList.toggle('on', o === b); });
      render();
    });
  });
  let pending = null;
  window.addEventListener('resize', function () { clearTimeout(pending); pending = setTimeout(render, 120); });
  render();
})();
</script>"""

/** The reference tab: what the lab's reference results cover, a search over them, and the import. */
internal fun referencesPage(
    coverage: List<ReferenceCoverage>,
    lastImport: Triple<String, Long, Int>?,
    search: String?,
    found: List<Pair<Pair<String, String>, Reference>>,
    repoUrl: String,
): String = buildString {
    append(head("reference · klause lab", live = false, tab = Tab.REFERENCE))
    append("<header><h1>Reference</h1><p class=\"muted\">${coverage.sumOf { it.rows }} results from ")
    append("${coverage.map { it.solver }.distinct().size} solvers over ${coverage.map { it.collection }.distinct().size} collections")
    if (lastImport != null) {
        val (sha, at, rows) = lastImport
        val commit = commitUrl(repoUrl, sha)?.let { "<a href=\"$it\">${sha.take(9)}</a>" } ?: sha.take(9)
        append(" · last import from <code>$commit</code> ${ago(at)}, $rows rows changed")
    }
    append("</p></header>")
    append("<div class=\"actions\"><button onclick=\"act('/references/import?ref=main', null, ")
    append("'Import the reference tables from klause main? Stronger results already here are kept.')\">import from main</button>")
    append("<form method=\"get\" action=\"/references\" class=\"tools\" style=\"margin:0\"><input name=\"q\" type=\"search\" ")
    append("placeholder=\"find a problem or collection\" value=\"${esc(search.orEmpty())}\"><button>find</button></form></div>")
    if (search != null) {
        append("<h2>Results for ${esc(search)} <small>${found.size}${if (found.size == SEARCH_LIMIT) "+" else ""}</small></h2>")
        append("<div class=\"scroll\"><table><tr><th>collection</th><th>problem</th><th>verdict</th><th class=\"num\">time</th>")
        append("<th class=\"num\">budget</th></tr>")
        for ((key, r) in found) {
            append("<tr><td>${esc(key.first)}</td><td><code>${esc(key.second)}</code></td><td>${referenceVerdict(r)}</td>")
            append("<td class=\"num\">${"%.2f".format(r.elapsedMs / 1000.0)}s</td><td class=\"num\">${r.budgetMs / 1000}s</td></tr>")
        }
        append("</table></div>")
    }
    append("<h2>Coverage</h2>")
    if (coverage.isEmpty()) append("<p class=\"muted\">No reference results yet: import them from klause main.</p>")
    append("<div class=\"scroll\"><table><tr><th>collection</th><th>solver</th><th class=\"num\">problems</th>")
    append("<th class=\"num\">decided</th><th class=\"num\">proven</th><th class=\"num\">infeasible</th><th>updated</th></tr>")
    for (c in coverage) {
        append("<tr><td>${esc(c.collection)}</td><td>${esc(c.solver)}</td><td class=\"num\">${c.rows}</td>")
        append("<td class=\"num\">${c.decided}</td><td class=\"num\">${c.proven}</td><td class=\"num\">${c.infeasible}</td>")
        append("<td>${ago(c.updatedAt)}</td></tr>")
    }
    append("</table></div>")
    append(SCRIPT)
    append("</body></html>")
}

internal const val SEARCH_LIMIT = 200

internal fun filesPage(jobId: Long, files: List<FileEntry>): String = buildString {
    append(head("$jobId files · klause lab", live = false))
    append("<header><h1><a href=\"/jobs/$jobId\">$jobId</a> / files</h1></header>")
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
    append("<div class=\"scroll\"><table><tr><th>name</th><th>ref</th><th>every</th><th>arms</th>")
    append("<th>last run</th><th>checked</th><th>next check</th></tr>")
    for (schedule in schedules) {
        append("<tr><td><a class=\"plain\" href=\"/${query("name" to schedule.name)}\">${esc(schedule.name)}</a>")
        append(" <small><a href=\"/trend${query("name" to schedule.name)}\">trend</a></small>")
        if (schedule.priority != 0) append("<br><small>priority ${schedule.priority}</small>")
        append("</td><td><code>${esc(schedule.ref)}</code></td><td>${span(schedule.intervalSec)}</td>")
        val arms = schedule.experiment?.let { Experiments.arms(it).size.toString() }
            ?: "<span class=\"muted\" title=\"from before experiments; queues nothing\">legacy</span>"
        append("<td>$arms${if (schedule.parallel > 1) " ×${schedule.parallel}" else ""}</td><td>")
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

/** The lab's three views, as the tab bar shows them. */
internal enum class Tab(val label: String, val href: String) {
    QUEUE("Queue", "/"), REGRESSION("Regression", "/trend"), REFERENCE("Reference", "/references")
}

private fun head(title: String, live: Boolean, tab: Tab = Tab.QUEUE) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>${esc(title)}</title><style>$STYLE</style></head>
<body${if (live) " data-refresh" else ""}><nav class="tabs"><a class="brand" href="/">klause lab</a>${tabs(tab)}</nav>"""

private fun tabs(active: Tab) = Tab.entries.joinToString("") {
    "<a href=\"${it.href}\"${if (it == active) " class=\"on\" aria-current=\"page\"" else ""}>${it.label}</a>"
}

private const val STYLE = """
:root{--bg:#fff;--fg:#111;--muted:#666;--line:#ddd;--soft:#f3f3f3;--link:#0969da;
--run:#0550ae;--ok:#1a7f37;--warn:#9a6700;--bad:#cf222e;--off:#888;--series-1:#2a78d6;--series-2:#eb6834}
@media (prefers-color-scheme: dark){:root{--bg:#111;--fg:#ddd;--muted:#999;--line:#333;--soft:#222;--link:#58a6ff;
--run:#6cb6ff;--ok:#3fb950;--warn:#d29922;--bad:#f85149;--off:#888;--series-1:#3987e5;--series-2:#d95926}}
body{font:14px system-ui,sans-serif;margin:0 auto;max-width:1440px;padding:24px 32px 48px;background:var(--bg);color:var(--fg)}
@media (max-width:700px){body{padding:16px}}
a{color:var(--link)}a.plain{color:inherit;text-decoration:none}a.plain:hover{text-decoration:underline}
h1{margin:0 0 4px}h1 a{color:inherit;text-decoration:none}h2{font-size:16px;margin:32px 0 10px}h2 small{font-weight:normal}
.muted{color:var(--muted)}.scroll{overflow-x:auto}
table{border-collapse:collapse;width:100%}td,th{padding:6px 12px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
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
table.grid{width:auto}table.grid td{min-width:110px}
table.grid .ref{border-left:1px solid var(--line);color:var(--muted)}
table.grid td.best{background:color-mix(in srgb,var(--ok) 14%,transparent)}ul.error{color:var(--bad)}
pre.error{color:var(--bad);white-space:pre-wrap;word-break:break-all;background:var(--soft);padding:8px}
.pager{margin-top:12px}
nav.tabs{display:flex;align-items:center;gap:4px;border-bottom:1px solid var(--line);margin:-8px 0 20px;flex-wrap:wrap}
nav.tabs a{padding:8px 14px;color:var(--muted);text-decoration:none;border-bottom:2px solid transparent;margin-bottom:-1px}
nav.tabs a:hover{color:var(--fg)}nav.tabs a.on{color:var(--fg);border-bottom-color:var(--link);font-weight:600}
nav.tabs a.brand{color:var(--fg);font-weight:700;padding-left:0;margin-right:12px}
a.chip{text-decoration:none;display:inline-block}
.chart svg,.multiple svg{display:block}.chart{width:100%}
.grid{stroke:var(--line);stroke-width:1}.tick{fill:var(--muted);font-size:11px}.tick.mono{font-family:ui-monospace,monospace}
.endlabel{fill:var(--fg);font-size:12px}.cross{stroke:var(--muted);stroke-width:1}
.legend{display:flex;gap:16px;font-size:12px;color:var(--muted);margin:-4px 0 6px}
.legend i{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:5px;vertical-align:-1px}
.legend i.hollow{border:2px solid var(--series-1);width:6px;height:6px}
.multiples{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:12px 20px}
.mtitle{font-size:12px;color:var(--muted);margin-bottom:2px}
.tip{position:absolute;z-index:5;background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:6px 8px;
font-size:12px;box-shadow:0 2px 8px rgba(0,0,0,.15);pointer-events:none;min-width:180px}
.tip div{display:flex;justify-content:space-between;gap:12px}.tip span{color:var(--muted)}
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
