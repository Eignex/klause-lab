package com.eignex.lab

import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit

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
)

@Serializable
data class ReferenceComparison(val summaries: List<ReferenceSummary>, val disagreements: List<Disagreement>)

/**
 * Reads the bench's reference tables out of the lab's mirror of the klause repository, for import into the lab's own
 * reference table. Each solver covers its own corpora, one CSV each.
 */
class References(private val mirror: File) {
    /** Every row of every table at [ref] with its solver, and the commit [ref] resolved to; null without that commit. */
    fun read(ref: String): Pair<String, List<Pair<Pair<String, String>, Reference>>>? {
        if (!mirror.isDirectory) return null
        val sha = git("rev-parse", "--verify", "-q", "$ref^{commit}")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val files = git("ls-tree", "--name-only", sha, "$DIR/").orEmpty().lines().filter { it.endsWith(".csv") }
        val rows = files.flatMap { file ->
            val solver = file.substringAfterLast('/').removeSuffix(".csv")
            git("show", "$sha:$file").orEmpty().lineSequence().drop(1).filter { it.isNotBlank() }.mapNotNull { parse(solver, it) }.toList()
        }
        return sha to rows
    }

    private fun git(vararg args: String): String? {
        val process = ProcessBuilder(listOf("git", "-C", mirror.path) + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(GIT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return out.takeIf { process.exitValue() == 0 }
    }

    companion object {
        private const val DIR = "klause-bench/reference"
        private const val GIT_TIMEOUT_SEC = 60L

        /** One `suite,problem,maximize,objective,feasible,proven,elapsedMs,budgetMs,…` row, keyed by (suite, problem). */
        internal fun parse(solver: String, line: String): Pair<Pair<String, String>, Reference>? {
            val cells = cells(line)
            if (cells.size < ORACLE_COLUMNS) return null
            val reference = Reference(
                solver = solver,
                maximize = cells[2] == "true",
                objective = cells[3].toDoubleOrNull(),
                feasible = cells[4].toBooleanStrictOrNull(),
                proven = cells[5] == "true",
                elapsedMs = cells[6].toLongOrNull() ?: return null,
                budgetMs = cells[7].toLongOrNull() ?: return null,
            )
            return (cells[0] to cells[1]) to reference
        }

        /** Whether [a] is the better verdict on a problem than [b]: decided over undecided, proven over unproven,
         *  then the better objective. */
        internal fun stronger(a: Reference, b: Reference): Boolean {
            if ((a.feasible != null) != (b.feasible != null)) return a.feasible != null
            if (a.proven != b.proven) return a.proven
            val (x, y) = a.objective to b.objective
            return x != null && (y == null || if (a.maximize) x > y else x < y)
        }

        /** Split a CSV line, honouring the bench's quoting of cells that hold a comma or a quote. */
        private fun cells(line: String): List<String> {
            val out = ArrayList<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { out += cell.toString(); cell.clear() }
                    else -> cell.append(c)
                }
                i++
            }
            out += cell.toString()
            return out
        }

        private const val ORACLE_COLUMNS = 8

        /**
         * Each arm's cases against [references]. An arm's runs of a problem (seeds, repeats) count by their best: solved
         * if any run solved, its best objective. Speed is left out on purpose: the reference ran under its own budget.
         */
        fun compare(labels: List<String>, cases: List<CaseResult>, references: Map<Pair<String, String>, Reference>): ReferenceComparison {
            val disagreements = ArrayList<Disagreement>()
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
                        if (!beats && best != refObjective) gaps += abs(best - refObjective) / maxOf(abs(refObjective), 1.0)
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
                    gaps.takeIf { it.isNotEmpty() }?.average() ?: if (bothSolved > 0) 0.0 else null)
            }
            return ReferenceComparison(summaries, disagreements.distinctBy { it.problem to it.reason })
        }

        private fun abs(x: Double) = kotlin.math.abs(x)
    }
}
