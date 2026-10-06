package com.eignex.lab

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class TrendTest {
    private fun run(store: Store, name: String, solvedOf: List<Boolean>, finish: Boolean = true): Long {
        val problems = solvedOf.indices.map { Problem(if (it % 2 == 0) "a" else "b", "p$it") }
        val id = store.create(name, "sha", emptyList(), experiment = ExperimentSpec(name, listOf(mapOf("suite" to "a"))))
        store.next()
        store.plan(id, listOf(PlannedArm(Arm("base", emptyMap()), "sha")), problems, Experiments.cases(problems.size, 1, emptyList()), problems.map { "true" to 1L })
        solvedOf.forEachIndexed { i, solved ->
            store.caseRecord(id, i, if (solved) """{"kind":"satisfy","feasible":true,"timeToBestMs":5,"budgetMs":1000}""" else """{"kind":"satisfy","budgetMs":1000}""")
        }
        if (finish) store.finish(id, Status.DONE)
        return id
    }

    @Test
    fun `a schedule's runs come oldest first with their solved shares, other jobs left out`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val first = run(store, "sweep@aaa", listOf(true, false, false, false))
        run(store, "other@aaa", listOf(true))
        val second = run(store, "sweep@bbb", listOf(true, true, true, false), finish = false)

        val runs = Trend.runs(store, "sweep")

        assertEquals(listOf(first to 0.25, second to 0.75), runs.map { it.job to it.solved.value })
        assertEquals(listOf(true, false), runs.map { it.finished })
        assertEquals(mapOf("a" to 1.0, "b" to 0.5), runs[1].suites.mapValues { it.value.solved })
    }

    @Test
    fun `a run that contradicts the reference counts its disagreements`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        store.putReferences(listOf(("" to "p0") to Reference("clasp", false, null, false, true, 10, 1000)), "test")
        run(store, "sweep@aaa", listOf(true, false))

        assertEquals(listOf(1), Trend.runs(store, "sweep").map { it.disagreements })
    }

    @Test
    fun `each run counts what it lost and gained against the run before`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        run(store, "sweep@aaa", listOf(true, true, false))
        run(store, "sweep@bbb", listOf(false, true, true))

        val runs = Trend.runs(store, "sweep")

        assertEquals(listOf(null to null, 1 to 1), runs.map { it.lost to it.gained })
    }
}
