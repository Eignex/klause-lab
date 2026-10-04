package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The parts of a bench `SolveRecord` a comparison reads. */
data class Outcome(
    val optimize: Boolean,
    val maximize: Boolean,
    /** true: a solution; false: proved infeasible; null: undecided within the budget. */
    val feasible: Boolean?,
    val objective: Double?,
    val proven: Boolean,
    val timeToBestMs: Long?,
    val budgetMs: Long,
    val error: Boolean,
    /** Why klause declined the model, when it did: the run decided nothing, and nothing went wrong. */
    val unsupported: String? = null,
    /** Why the problem never reached the solver: it did not compile or parse. */
    val loadError: String? = null,
    /** The solver's own `solveTime` statistic, in ms. */
    val solveTimeMs: Long? = null,
) {
    /** Solved beats unsolved, and proven (an optimum, or infeasibility) beats merely solved. */
    val rank: Int get() = when {
        error || feasible == null -> 0
        proven || feasible == false || !optimize -> 2
        else -> 1
    }

    /**
     * The time a comparison charges a decided run: to its best solution, or, for a proof without one (an infeasibility
     * proof has no solution), the solve time the solver reported. Only an undecided run, or a decided one that
     * reported no time at all, is charged the whole budget, so a fast refutation never reads as a timeout.
     */
    val timeMs: Long get() = if (rank > 0) timeToBestMs ?: solveTimeMs ?: budgetMs else budgetMs

    companion object {
        fun of(record: JsonElement?): Outcome? {
            val fields = record as? JsonObject ?: return null
            fun field(name: String) = fields[name]?.takeUnless { it is JsonObject }?.jsonPrimitive
            return Outcome(
                optimize = field("kind")?.content == "optimize",
                maximize = field("maximize")?.booleanOrNull ?: false,
                feasible = field("feasible")?.booleanOrNull,
                objective = field("objective")?.doubleOrNull,
                proven = field("proven")?.booleanOrNull ?: false,
                timeToBestMs = field("timeToBestMs")?.longOrNull,
                budgetMs = field("budgetMs")?.longOrNull ?: 0,
                error = field("command")?.content == "ERROR",
                unsupported = stat(fields, "unsupported"),
                loadError = stat(fields, "loadError"),
                solveTimeMs = stat(fields, "solveTime")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                    ?.let { (it * MS_PER_S).toLong() },
            )
        }

        private const val MS_PER_S = 1000.0

        private fun stat(fields: JsonObject, name: String): String? =
            ((fields["stats"] as? JsonObject)?.get(name) as? JsonPrimitive)?.content
    }
}

/** One arm's totals over its cases with a record. */
data class ArmSummary(
    val label: String,
    val cases: Int,
    val solved: Int,
    /** Proven optima and proven infeasibility; a satisfied problem counts as solved only. */
    val proven: Int,
    /** Solver crashes and problems that failed to load. */
    val errors: Int,
    /** Problems klause declined. */
    val unsupported: Int,
    /** Pairwise Borda points against every other arm, over the (problem, seed) pairs both ran. */
    val score: Double,
    /** Against the first arm: pairs this arm did better on, worse on, and drew. */
    val wins: Int,
    val losses: Int,
    val ties: Int,
)

/** Arms that contradict each other on one problem, which at least one of them got wrong. */
@Serializable
data class Disagreement(val problem: Problem, val reason: String)

data class Comparison(val arms: List<ArmSummary>, val disagreements: List<Disagreement>)

object Compare {
    /**
     * Points [a] earns against [b] on one problem, by the MiniZinc Challenge rule `output/compare.sh` uses: the
     * better outcome takes 1, where better is solved over unsolved, then proven over unproven, then the better
     * objective; equal outcomes split the point by time, the faster taking the larger share; two unsolved score 0.
     * Times closer than [TIE_MS] or [TIE_SHARE] of the slower are a tie at half each: a run to run difference that
     * small is the machine's noise, and the time split alone would call it a win.
     */
    fun points(a: Outcome, b: Outcome): Double {
        if (a.rank != b.rank) return if (a.rank > b.rank) 1.0 else 0.0
        if (a.rank == 0) return 0.0
        val (oa, ob) = a.objective to b.objective
        if (a.optimize && oa != null && ob != null && oa != ob) return if ((oa > ob) == a.maximize) 1.0 else 0.0
        if (abs(a.timeMs - b.timeMs) <= maxOf(TIE_MS, (TIE_SHARE * maxOf(a.timeMs, b.timeMs)).toLong())) return 0.5
        return b.timeMs.toDouble() / (a.timeMs + b.timeMs)
    }

    /** Times this close (ms) are a tie, however short the runs. */
    const val TIE_MS = 250L

    /** Times within this share of the slower are a tie, however long the runs. */
    const val TIE_SHARE = 0.1

    /** Totals, pairwise scores and disagreements over [cases] of the arms named in [labels], in that order. */
    fun compare(labels: List<String>, cases: List<CaseResult>): Comparison {
        val outcomes = cases.mapNotNull { case -> Outcome.of(case.record)?.let { case to it } }
        // A (problem, seed) pair is one contest; seeds pair up across arms, so each arm meets the others on equal terms.
        val contests = outcomes.groupBy { (case, _) -> Triple(case.problem, case.seed, case.repeat) }
            .mapValues { (_, entries) -> entries.associate { (case, outcome) -> case.arm to outcome } }
        val arms = labels.map { label ->
            val own = outcomes.filter { it.first.arm == label }.map { it.second }
            var score = 0.0
            var (wins, losses, ties) = Triple(0, 0, 0)
            for (contest in contests.values) {
                val mine = contest[label] ?: continue
                for ((other, theirs) in contest) if (other != label) score += points(mine, theirs)
                val baseline = contest[labels.first()]
                if (label != labels.first() && baseline != null) {
                    val (gained, conceded) = points(mine, baseline) to points(baseline, mine)
                    when {
                        gained > conceded -> wins++
                        gained < conceded -> losses++
                        else -> ties++
                    }
                }
            }
            ArmSummary(
                label, own.size, own.count { it.rank > 0 }, own.count { !it.error && (it.proven || it.feasible == false) },
                own.count { it.error || it.loadError != null }, own.count { it.unsupported != null },
                score, wins, losses, ties,
            )
        }
        return Comparison(arms, disagreements(outcomes.map { (case, outcome) -> Triple(case.problem, case.arm, outcome) }))
    }

    /**
     * Problems whose outcomes cannot all be right: one arm proves infeasibility while another finds a solution, two
     * arms prove different optima, or an arm reports a solution better than another's proven optimum.
     */
    fun disagreements(outcomes: List<Triple<Problem, String, Outcome>>): List<Disagreement> =
        outcomes.groupBy { it.first }.mapNotNull { (problem, entries) ->
            val infeasible = entries.filter { it.third.feasible == false && !it.third.error }.map { it.second }.distinct()
            val feasible = entries.filter { it.third.feasible == true }.map { it.second }.distinct()
            if (infeasible.isNotEmpty() && feasible.isNotEmpty()) {
                return@mapNotNull Disagreement(problem, "infeasible per ${infeasible.joinToString()}, solved by ${feasible.joinToString()}")
            }
            val optima = entries.filter { it.third.optimize && it.third.proven && it.third.objective != null }
            if (optima.map { it.third.objective }.distinct().size > 1) {
                return@mapNotNull Disagreement(problem, "different proven optima: " + optima.distinctBy { it.second to it.third.objective }
                    .joinToString { "${it.second} ${it.third.objective}" })
            }
            val optimum = optima.firstOrNull() ?: return@mapNotNull null
            val beyond = entries.filter { (_, _, o) ->
                o.objective != null && o.objective != optimum.third.objective &&
                    (o.objective > optimum.third.objective!!) == optimum.third.maximize
            }
            if (beyond.isEmpty()) return@mapNotNull null
            Disagreement(problem, "${beyond.joinToString { "${it.second} ${it.third.objective}" }} beyond ${optimum.second}'s proven ${optimum.third.objective}")
        }
}
