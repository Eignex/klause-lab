package com.eignex.lab

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferencesTest {
    private fun ref(feasible: Boolean?, objective: Double? = null, proven: Boolean = false) =
        Reference("cp-sat", maximize = false, objective = objective, feasible = feasible, proven = proven, elapsedMs = 10, budgetMs = 1000)

    @Test
    fun `a re-import keeps the stronger result already stored`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = "c" to "p"
        store.putReferences(listOf(key to ref(true, 5.0, proven = true)), "a")

        val changed = store.putReferences(listOf(key to ref(true, 9.0)), "b")

        assertEquals(0 to 5.0, changed to store.references(listOf(key)).getValue(key).objective)
    }

    @Test
    fun `an arm proving infeasible what the reference solved is a disagreement`() {
        val case = CaseResult(0, Status.DONE, Problem("s", "p", collection = "c"), "a", null,
            Json.parseToJsonElement("""{"kind":"satisfy","feasible":false,"proven":true,"budgetMs":1000}"""))

        val comparison = References.compare(listOf("a"), listOf(case), mapOf(("c" to "p") to ref(true)))

        assertEquals(listOf("a proves infeasible, cp-sat solved it"), comparison.disagreements.map { it.reason })
    }

    @Test
    fun `a worse incumbent is listed with its gap, the largest first`() {
        fun case(problem: String, objective: Int) = CaseResult(0, Status.DONE, Problem("s", problem, collection = "c"), "a", null,
            Json.parseToJsonElement("""{"kind":"optimize","maximize":false,"feasible":true,"objective":$objective,"timeToBestMs":5,"budgetMs":1000}"""))
        val proven = Reference("cp-sat", false, 10.0, true, true, 5, 1000)

        val comparison = References.compare(
            listOf("a"),
            listOf(case("close", 11), case("far", 20), case("equal", 10)),
            mapOf(("c" to "close") to proven, ("c" to "far") to proven, ("c" to "equal") to proven),
        )

        assertEquals(listOf("far" to 1.0, "close" to 0.1), comparison.shortfalls.map { it.problem.problem to it.gap })
        assertEquals(2, comparison.summaries.single().worse)
    }

    @Test
    fun `two reference solvers that contradict each other are a conflict, and agreeing ones are not`() {
        val unsat = Reference("clasp", false, null, false, true, 10, 60_000)
        val sat = Reference("kissat", false, null, true, true, 10, 60_000)
        val optimum = Reference("scip", false, 10.0, true, true, 10, 60_000)

        assertEquals(listOf("clasp proves infeasible, kissat found a solution"), References.conflicts(listOf(unsat, sat)))
        assertEquals(listOf("highs proves optimum 9, scip proves 10"), References.conflicts(listOf(optimum, optimum.copy(solver = "highs", objective = 9.0))))
        assertEquals(listOf("highs's solution 8 beats scip's proven optimum 10"),
            References.conflicts(listOf(optimum, optimum.copy(solver = "highs", objective = 8.0, proven = false))))
        assertEquals(emptyList(), References.conflicts(listOf(optimum, optimum.copy(solver = "highs"))))
    }

    @Test
    fun `optima within the solvers' tolerance agree, and only a real difference conflicts`() {
        val scip = Reference("scip", false, 1480.0, true, true, 10, 60_000)

        assertEquals(emptyList(), References.conflicts(listOf(scip, scip.copy(solver = "highs", objective = 1479.99999999))))
        assertEquals(emptyList(), References.conflicts(listOf(scip.copy(objective = -2451377.0), scip.copy(solver = "highs", objective = -2451279.0))))
        assertEquals(listOf("highs proves optimum 1470, scip proves 1480"),
            References.conflicts(listOf(scip, scip.copy(solver = "highs", objective = 1470.0))))
    }

    @Test
    fun `a filter keeps the rows of its solver and verdict, and counts them all`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        store.putReferences(
            listOf(
                ("c" to "a") to ref(true, 1.0, proven = true),
                ("c" to "b") to ref(false, proven = true),
                ("c" to "c") to ref(null),
                ("d" to "a") to ref(true, 2.0, proven = true).copy(solver = "z3"),
            ),
            "test",
        )

        val (rows, total) = store.searchReferences(ReferenceFilter(solver = "cp-sat", verdict = ReferenceVerdict.OPTIMUM), limit = 10)

        assertEquals(listOf("c" to "a") to 1, rows.map { it.first } to total)
    }

    @Test
    fun `a problem's runs come from every experiment that ran it`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val problems = listOf(Problem("s", "p", collection = "c"), Problem("s", "q", collection = "c"))
        val id = store.create("e", "main", emptyList(), experiment = ExperimentSpec("e", listOf(mapOf("suite" to "s"))))
        store.plan(id, listOf(PlannedArm(Arm("base", emptyMap()), "sha")), problems, Experiments.cases(2, 1, emptyList()), listOf("true" to 1L, "true" to 1L))

        val runs = store.problemRuns("c", "q")

        assertEquals(listOf(id to 1), runs.map { it.job to it.case })
    }

    @Test
    fun `a reference arm's record becomes a row timed by its solve time, and an error none`() {
        val proof = """{"solver":"clasp","kind":"satisfy","maximize":false,"feasible":true,"proven":true,"budgetMs":10000,
            "command":"docker run","stats":{"solveTime":"0.25"}}"""
        val error = """{"solver":"clasp","kind":"satisfy","budgetMs":10000,"command":"ERROR"}"""

        val rows = listOf(proof, error).map { References.of(Json.parseToJsonElement(it)) }

        assertEquals(listOf(Reference("clasp", false, null, true, true, 250, 10000), null), rows)
    }
}
