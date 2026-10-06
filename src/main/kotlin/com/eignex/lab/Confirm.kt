package com.eignex.lab

import kotlinx.serialization.Serializable

/** One sweep run against the run before it of the same experiment, problem by problem. */
@Serializable
data class Flips(
    /** Problems the earlier run solved and this one did not. */
    val lost: List<Problem>,
    /** Problems this run solved and the earlier one did not. */
    val gained: List<Problem>,
) {
    /** Two-sided sign test on the flips: how likely so lopsided a split is if each flip goes either way at random. */
    val pValue: Double get() = Confirm.signTest(lost.size, gained.size)
}

/**
 * Confirming a sweep's flips. A problem near its budget can flip between two runs on machine noise alone, so a
 * scheduled run that flips problems against the run before it is followed by a confirmation job: just those problems,
 * on both commits, alternating case by case so both see the same machine, [REPEATS] times each. A flip counts as a
 * regression or an improvement only when the repeats agree with it.
 */
object Confirm {
    /** Appended to a schedule's name for its confirmation jobs, which keeps them out of the schedule's trend. */
    const val SUFFIX = "~confirm"
    const val REPEATS = 3
    /** The most flips one confirmation reruns; more than this is a change no rerun is needed to see. */
    const val MAX_PROBLEMS = 40
    const val BEFORE = "before"
    const val AFTER = "after"

    /** Each problem's solved share over its runs in [cases], by its suite and name. */
    fun solved(cases: List<CaseResult>): Map<Pair<String, String>, Double> =
        cases.mapNotNull { case -> Outcome.of(case.record)?.let { case.problem to it } }
            .groupBy({ it.first.suite to it.first.problem }, { if (it.second.rank > 0) 1.0 else 0.0 })
            .mapValues { (_, runs) -> runs.average() }

    /** What [after] solved differently from [before], over the problems both ran; a problem's runs count by majority. */
    fun flips(before: List<CaseResult>, after: List<CaseResult>): Flips {
        val was = solved(before)
        val now = solved(after)
        val problems = (before + after).associateBy { it.problem.suite to it.problem.problem }
        val both = was.keys.intersect(now.keys).sortedWith(compareBy({ it.first }, { it.second }))
        return Flips(
            lost = both.filter { was.getValue(it) >= HALF && now.getValue(it) < HALF }.map { problems.getValue(it).problem },
            gained = both.filter { was.getValue(it) < HALF && now.getValue(it) >= HALF }.map { problems.getValue(it).problem },
        )
    }

    /** The confirmation of [flips] in a run of [spec] at [after], against the earlier run at [before]. */
    fun spec(series: String, spec: ExperimentSpec, before: String, after: String, flips: Flips): ExperimentSpec {
        val config = spec.configs.firstOrNull().orEmpty() - "ref" - "label"
        return ExperimentSpec(
            name = "$series$SUFFIX@${after.take(SHA_LENGTH)}",
            problems = emptyList(),
            problemList = (flips.lost + flips.gained).take(MAX_PROBLEMS),
            base = spec.base - "ref",
            configs = listOf(config + mapOf("label" to BEFORE, "ref" to before), config + mapOf("label" to AFTER, "ref" to after)),
            seeds = spec.seeds,
            repeats = REPEATS,
            parallel = spec.parallel,
            priority = spec.priority,
            confirm = true,
        )
    }

    /** What a finished confirmation found: the flips its repeats agree with, each way. */
    fun confirmed(cases: List<CaseResult>): Flips = flips(cases.filter { it.arm == BEFORE }, cases.filter { it.arm == AFTER })

    fun signTest(a: Int, b: Int): Double {
        val n = a + b
        if (n == 0) return 1.0
        var tail = 0.0
        var term = Math.pow(0.5, n.toDouble())
        for (k in 0..minOf(a, b)) {
            tail += term
            term = term * (n - k) / (k + 1)
        }
        return minOf(1.0, 2 * tail)
    }

    private const val HALF = 0.5
    private const val SHA_LENGTH = 9
}
