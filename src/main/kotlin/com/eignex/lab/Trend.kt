package com.eignex.lab

import kotlinx.serialization.Serializable

/** One suite's share of solved problems in one run. */
@Serializable
data class SuiteShare(val problems: Int, val solved: Double)

/**
 * One run of a schedule, as a point in its trend: shares of the problems it ran with results so far, so a run still
 * going plots what it has. Shares are fractions of problems, a problem's seeds and repeats averaged, which keeps runs
 * over different selections comparable as rates.
 */
@Serializable
data class TrendRun(
    val job: Long,
    val sha: String,
    /** When the run finished, or was queued while it has not. */
    val at: Long,
    val finished: Boolean,
    val cases: Int,
    val problems: Int,
    val solved: Estimate,
    val proven: Double,
    val unsupported: Int,
    val errors: Int,
    /** Mean PAR-2 seconds. */
    val par2: Estimate,
    val suites: Map<String, SuiteShare>,
)

object Trend {
    /** Every run a schedule named [name] queued, oldest first; an experiment of several arms is read by its first. */
    fun runs(store: Store, name: String): List<TrendRun> =
        store.jobs(limit = MAX_RUNS, name = name)
            .filter { it.experiment != null && it.name.startsWith("$name@") && it.status != Status.CANCELLED }
            .sortedBy { it.id }
            .mapNotNull { job -> run(job, store.arms(job.id), store.cases(job.id)) }

    fun run(job: Job, arms: List<PlannedArm>, cases: List<CaseResult>): TrendRun? {
        val label = arms.firstOrNull()?.arm?.label ?: return null
        val own = cases.filter { it.arm == label }
        val stats = Stats.of(listOf(label), own).arms.single()
        if (stats.problems == 0) return null
        val summary = Compare.compare(listOf(label), own).arms.single()
        val n = stats.problems.toDouble()
        val suites = own.mapNotNull { case -> Outcome.of(case.record)?.let { case.problem to it } }
            .groupBy({ it.first }, { it.second })
            .entries.groupBy { it.key.suite.ifEmpty { "(none)" } }
            .mapValues { (_, problems) ->
                SuiteShare(problems.size, problems.map { (_, runs) -> runs.map { if (it.rank > 0) 1.0 else 0.0 }.average() }.average())
            }.toSortedMap()
        return TrendRun(
            job = job.id,
            sha = job.sha ?: job.ref,
            at = job.finishedAt ?: job.createdAt,
            finished = job.status !in ACTIVE,
            cases = own.size,
            problems = stats.problems,
            solved = Estimate(stats.solved.value / n, stats.solved.low / n, stats.solved.high / n),
            proven = summary.proven / n,
            unsupported = summary.unsupported,
            errors = summary.errors,
            par2 = stats.par2,
            suites = suites,
        )
    }

    private const val MAX_RUNS = 500
}
