package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/** Experiment results out of the lab as CSV, and the records of jobs from before experiments into it. */
object Results {
    /** Every case with a record as one CSV row: the case, its problem and arm, and the record's verdict and times. */
    fun casesCsv(cases: List<CaseResult>): String = buildString {
        appendLine(
            "index,status,arm,seed,suite,problem,collection,family,format,kind,maximize,feasible,objective,proven," +
                "timeToBestMs,timeToFirstFeasibleMs,budgetMs,error",
        )
        for (case in cases) {
            val record = case.record as? JsonObject
            fun field(name: String) = (record?.get(name) as? JsonPrimitive)?.content?.takeUnless { it == "null" }.orEmpty()
            val row = listOf(
                case.index.toString(), case.status.name, case.arm, case.seed?.toString().orEmpty(), case.problem.suite,
                case.problem.problem, case.problem.collection, case.problem.family, case.problem.format, field("kind"),
                field("maximize"), field("feasible"), field("objective"), field("proven"), field("timeToBestMs"),
                field("timeToFirstFeasibleMs"), field("budgetMs"), (field("command") == "ERROR").toString(),
            )
            appendLine(row.joinToString(",") { cell(it) })
        }
    }

    /**
     * One arm's results as the bench writes `output/<config>.csv`, so `bench credit` and the analysis scripts read
     * them: keyed by (collection, problem), one row per problem, from the cases of [seed] (null: the cases run
     * without one). Feature columns stay blank, as the bench leaves them for a problem without a reference row.
     */
    fun benchCsv(cases: List<CaseResult>, arm: String, seed: Long?): String = buildString {
        appendLine("suite,problem,maximize,objective,feasible,proven,elapsedMs,budgetMs,format,structure,numGlobal,numLinear,boolHeavy,logic")
        val rows = cases.filter { it.arm == arm && it.seed == seed }
            .mapNotNull { case -> Outcome.of(case.record)?.let { case.problem to it } }
            .sortedWith(compareBy({ it.first.collection }, { it.first.problem }))
        for ((problem, outcome) in rows) {
            val row = listOf(
                cell(problem.collection), cell(problem.problem), outcome.maximize.toString(),
                outcome.objective?.toString().orEmpty(), outcome.feasible?.toString().orEmpty(), outcome.proven.toString(),
                outcome.timeMs.toString(), outcome.budgetMs.toString(), "", "", "", "", "", "",
            )
            appendLine(row.joinToString(","))
        }
    }

    /**
     * Make a finished experiment of the bench records [job] collected: each directory directly under its collected
     * `klause-bench/output/` is an arm, each record there a case. Only records the job's own commit wrote are taken,
     * since that directory also carries results committed to the repository. Records carry no suite, so the
     * imported problems are named by problem alone.
     */
    fun import(config: Config, store: Store, job: Job): Long {
        require(job.status !in ACTIVE) { "job ${job.id} has not ended" }
        require(job.experiment == null) { "job ${job.id} is already an experiment" }
        val sha = requireNotNull(job.sha) { "job ${job.id} never checked a commit out" }
        val output = config.jobDir(job.id).resolve(COLLECTED_OUTPUT).toFile()
        val arms = output.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { dir ->
            val records = dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().sortedBy { it.name }
                .mapNotNull { file -> record(file)?.takeIf { it.string("gitSha") == sha }?.let { file to it } }
            records.takeIf { it.isNotEmpty() }?.let { dir.name to it }
        }
        require(arms.isNotEmpty()) { "job ${job.id} collected no bench records of its commit $sha" }
        val problems = arms.flatMap { (_, records) -> records.map { it.second.string("problem").orEmpty() } }.distinct()
            .map { Problem(suite = "", problem = it) }
        val index = problems.withIndex().associate { (i, p) -> p.problem to i }
        val cases = arms.withIndex().flatMap { (armIndex, arm) ->
            arm.second.map { (file, record) ->
                Triple(Case(index.getValue(record.string("problem").orEmpty()), armIndex, null), record.toString(), file)
            }
        }
        val spec = ExperimentSpec("${job.name} (imported)", listOf(mapOf("imported-from" to job.id.toString())))
        return store.importExperiment(
            spec.name, job.ref, sha, spec,
            arms.map { (label, _) -> PlannedArm(Arm(label, mapOf("ref" to sha)), sha) },
            problems,
            cases.map { it.first to it.second },
            cases.map { "imported from job ${job.id}: ${it.third.relativeTo(output)}" },
        )
    }

    private fun record(file: File): JsonObject? = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }
        .getOrNull()?.takeIf { it["problem"] != null && it["budgetMs"]?.jsonPrimitive?.longOrNull != null }

    private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.content

    private fun cell(text: String) =
        if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text

    private const val COLLECTED_OUTPUT = "collected/klause-bench/output"
}
