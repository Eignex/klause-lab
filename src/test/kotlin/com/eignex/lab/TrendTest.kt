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

        val run = Trend.runs(store, "sweep").single()

        assertEquals(listOf(1, 1), listOf(run.disagreements, run.disagreementsBySuite["a"]))
        assertEquals(null, run.disagreementsBySuite["b"])
    }

    @Test
    fun `each run counts what it lost and gained against the run before`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        run(store, "sweep@aaa", listOf(true, true, false))
        run(store, "sweep@bbb", listOf(false, true, true))

        val runs = Trend.runs(store, "sweep")

        assertEquals(listOf(null to null, 1 to 1), runs.map { it.lost to it.gained })
    }

    @Test
    fun `a finished run's point is reused until the references change and the point has aged`() {
        var now = 0L
        val cache = TrendCache(maxReferenceLagMs = 1000) { now }
        val run = TrendRun(job = 1, sha = "a", spec = "s", at = 0, finished = true, cases = 1, problems = 1,
            solved = Estimate(1.0, 1.0, 1.0), proven = 0.0, unsupported = 0, errors = 0,
            par2 = Estimate(1.0, 1.0, 1.0), suites = emptyMap())
        cache.put(1, stamp = 5, run)

        now = 500
        val fresh = listOf(cache.get(1, stamp = 5), cache.get(1, stamp = 6))
        now = 2000
        val aged = listOf(cache.get(1, stamp = 5), cache.get(1, stamp = 6))

        assertEquals(listOf(run, run), fresh)
        assertEquals(listOf(run, null), aged)
    }
}
