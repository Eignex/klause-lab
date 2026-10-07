package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A reference solver's verdict on one problem, as `klause-bench/reference/<solver>.csv` records it. */
@Serializable
data class Reference(
    val solver: String,
    val maximize: Boolean,
    val objective: Double?,
    /** true: a solution; false: proved infeasible; null: undecided within its budget. */
    val feasible: Boolean?,
    val proven: Boolean,
    val elapsedMs: Long,
    val budgetMs: Long,
)

/** One collection's rows from one reference solver. */
@Serializable
data class ReferenceCoverage(
    val collection: String,
    val solver: String,
    val rows: Int,
    val decided: Int,
    val proven: Int,
    val infeasible: Int,
    val updatedAt: Long,
)

/** One lab case of a problem, as its inspection page lists it. */
@Serializable
data class ProblemRun(
    val job: Long,
    val jobName: String,
    val createdAt: Long,
    val arm: String,
    val sha: String,
    val case: Int,
    val seed: Long? = null,
    val repeat: Int = 0,
    val status: Status,
    val record: JsonElement? = null,
)

/** A problem as its inspection page shows it: every reference row and every lab run. */
@Serializable
data class ProblemReport(val collection: String, val problem: String, val references: List<Reference>, val runs: List<ProblemRun>)

/** What a reference verdict can be filtered to, with the condition on a `reference_rows` row that selects it. */
enum class ReferenceVerdict(val label: String, internal val sql: String) {
    OPTIMUM("proven optimum", "proven = 1 AND feasible = 1 AND objective IS NOT NULL"),
    UNPROVEN("solved, not proven", "feasible = 1 AND proven = 0"),
    SATISFIED("satisfied", "feasible = 1 AND objective IS NULL"),
    INFEASIBLE("proven infeasible", "feasible = 0"),
    UNKNOWN("unknown", "feasible IS NULL"),
}

/** Which reference rows to show: every set field must hold; [text] matches the problem or the collection. */
data class ReferenceFilter(
    val text: String? = null,
    val solver: String? = null,
    val collection: String? = null,
    val verdict: ReferenceVerdict? = null,
) {
    val isEmpty: Boolean get() = text == null && solver == null && collection == null && verdict == null
}

/** One arm against the reference, over the problems both have a verdict on. */
@Serializable
data class ReferenceSummary(
    val arm: String,
    /** Problems the arm ran that the reference covers. */
    val covered: Int,
    val bothSolved: Int,
    val armOnly: Int,
    val referenceOnly: Int,
    /** Problems with a proven reference optimum the arm reached. */
    val optimaMatched: Int,
    val provenOptima: Int,
    /** Problems where the arm's objective beats the reference's unproven best. */
    val better: Int,
    /** Mean relative gap to the reference objective where the arm is worse, over problems both found solutions to. */
    val meanGap: Double?,
    /** Problems where the arm's best objective is worse than the reference's. */
    val worse: Int = 0,
)

/** A problem an arm solved to a worse objective than the reference did. */
@Serializable
data class Shortfall(
    val problem: Problem,
    val arm: String,
    val objective: Double,
    val reference: Double,
    val referenceProven: Boolean,
    /** Relative distance to the reference objective. */
    val gap: Double,
)

@Serializable
data class ReferenceComparison(
    val summaries: List<ReferenceSummary>,
    val disagreements: List<Disagreement>,
    /** Every worse incumbent, the largest gap first. */
    val shortfalls: List<Shortfall> = emptyList(),
)

/** The `backend=` that runs each problem's reference solver, whose cases become reference results. */
const val REFERENCE_BACKEND = "reference"

/** How reference results merge and how an experiment measures up to them. */
object References {
    /**
     * A reference arm's case record as a reference row, timed as `bench reference` times its rows: a proof by its solve
     * time, an unproven solution by its time to the first one, anything else by the whole budget. Null for a record
     * of a run that never solved: an error, or a problem that did not load.
     */
    fun of(record: JsonElement): Reference? {
        val fields = record as? JsonObject ?: return null
        fun field(name: String) = (fields[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }
        val command = field("command")?.content
        if (command == "ERROR" || command == "LOAD") return null
        val solver = field("solver")?.content ?: return null
        val budgetMs = field("budgetMs")?.longOrNull ?: return null
        val feasible = field("feasible")?.booleanOrNull
        val proven = field("proven")?.booleanOrNull ?: false
        val solveMs = ((fields["stats"] as? JsonObject)?.get("solveTime") as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val elapsedMs = when {
            proven -> solveMs ?: budgetMs
            feasible == true -> field("timeToFirstFeasibleMs")?.longOrNull ?: solveMs ?: budgetMs
            else -> budgetMs
        }
        return Reference(
            solver = solver,
            maximize = field("maximize")?.booleanOrNull ?: false,
            objective = field("objective")?.doubleOrNull,
            feasible = feasible,
            proven = proven,
            elapsedMs = elapsedMs,
            budgetMs = budgetMs,
        )
    }

    /** Whether [a] is the better verdict on a problem than [b]: decided over undecided, proven over unproven,
     *  then the better objective, then the bigger budget, which makes an equal verdict the more telling one. */
    internal fun stronger(a: Reference, b: Reference): Boolean {
        if ((a.feasible != null) != (b.feasible != null)) return a.feasible != null
        if (a.proven != b.proven) return a.proven
        val (x, y) = a.objective to b.objective
        if (x != y) return x != null && (y == null || if (a.maximize) x > y else x < y)
        return a.budgetMs > b.budgetMs
    }


    /**
     * Each arm's cases against [references]. An arm's runs of a problem (seeds, repeats) count by their best: solved
     * if any run solved, its best objective. Speed is left out on purpose: the reference ran under its own budget.
     */
    fun compare(labels: List<String>, cases: List<CaseResult>, references: Map<Pair<String, String>, Reference>): ReferenceComparison {
        val disagreements = ArrayList<Disagreement>()
        val shortfalls = ArrayList<Shortfall>()
        val summaries = labels.map { label ->
            val byProblem = cases.filter { it.arm == label }
                .mapNotNull { case -> Outcome.of(case.record)?.let { case.problem to it } }
                .groupBy({ it.first }, { it.second })
            var covered = 0
            var bothSolved = 0
            var armOnly = 0
            var referenceOnly = 0
            var optimaMatched = 0
            var provenOptima = 0
            var better = 0
            val gaps = ArrayList<Double>()
            for ((problem, runs) in byProblem) {
                val reference = references[problem.collection to problem.problem] ?: continue
                covered++
                val solved = runs.any { it.rank > 0 }
                val refSolved = reference.feasible != null
                when {
                    solved && refSolved -> bothSolved++
                    solved -> armOnly++
                    refSolved -> referenceOnly++
                }
                val maximize = reference.maximize
                val objectives = runs.mapNotNull { it.objective }
                val best = if (maximize) objectives.maxOrNull() else objectives.minOrNull()
                val refObjective = reference.objective
                if (reference.proven && refObjective != null) {
                    provenOptima++
                    if (best == refObjective) optimaMatched++
                }
                if (best != null && refObjective != null) {
                    val beats = if (maximize) best > refObjective else best < refObjective
                    if (beats && !reference.proven) better++
                    if (!beats && best != refObjective) {
                        val gap = abs(best - refObjective) / maxOf(abs(refObjective), 1.0)
                        gaps += gap
                        shortfalls += Shortfall(problem, label, best, refObjective, reference.proven, gap)
                    }
                    if (beats && reference.proven) {
                        disagreements += Disagreement(problem, "$label $best beyond ${reference.solver}'s proven optimum $refObjective")
                    }
                }
                if (runs.any { it.feasible == false && !it.error } && reference.feasible == true) {
                    disagreements += Disagreement(problem, "$label proves infeasible, ${reference.solver} solved it")
                }
                if (runs.any { it.feasible == true } && reference.feasible == false && reference.proven) {
                    disagreements += Disagreement(problem, "$label solved it, ${reference.solver} proved it infeasible")
                }
                val armOptimum = runs.firstOrNull { it.proven && it.feasible == true && it.objective != null }?.objective
                if (armOptimum != null && reference.proven && refObjective != null && armOptimum != refObjective) {
                    disagreements += Disagreement(problem, "$label proves optimum $armOptimum, ${reference.solver} proves $refObjective")
                }
            }
            ReferenceSummary(label, covered, bothSolved, armOnly, referenceOnly, optimaMatched, provenOptima, better,
                gaps.takeIf { it.isNotEmpty() }?.average() ?: if (bothSolved > 0) 0.0 else null, worse = gaps.size)
        }
        return ReferenceComparison(summaries, disagreements.distinctBy { it.problem to it.reason }, shortfalls.sortedByDescending { it.gap })
    }

    /**
     * How the verdicts of several reference solvers on one problem contradict each other: one proving infeasible what
     * another solved, two proven optima that differ, or a solution beyond another solver's proven optimum. Any of
     * them means one of the solvers is wrong, so neither verdict is to be trusted until it is settled.
     */
    fun conflicts(rows: List<Reference>): List<String> = buildList {
        for (a in rows) {
            for (b in rows.filter { it.solver != a.solver }) {
                if (a.feasible == false && a.proven && b.feasible == true) add("${a.solver} proves infeasible, ${b.solver} found a solution")
                val (x, y) = a.objective to b.objective
                if (a.solver < b.solver && a.proven && b.proven && x != null && y != null && x != y) {
                    add("${a.solver} proves optimum ${fmt(x)}, ${b.solver} proves ${fmt(y)}")
                }
                if (a.proven && x != null && !b.proven && y != null && (if (a.maximize) y > x else y < x)) {
                    add("${b.solver}'s solution ${fmt(y)} beats ${a.solver}'s proven optimum ${fmt(x)}")
                }
            }
        }
    }.distinct()

    private fun fmt(x: Double) = if (x == Math.rint(x) && abs(x) < 1e15) x.toLong().toString() else x.toString()

    private fun abs(x: Double) = kotlin.math.abs(x)
}
