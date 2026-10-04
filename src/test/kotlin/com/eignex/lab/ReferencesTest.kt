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
}
