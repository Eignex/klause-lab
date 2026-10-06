package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExperimentsTest {
    private fun spec(
        configs: List<Map<String, String>> = listOf(emptyMap()),
        grid: Map<String, List<String>> = emptyMap(),
        base: Map<String, String> = emptyMap(),
    ) = ExperimentSpec("e", listOf(mapOf("suite" to "hakank")), base, configs, grid)

    @Test
    fun `a grid crosses every axis over the base`() {
        val arms = Experiments.arms(spec(base = mapOf("timeout" to "5000"), grid = mapOf("ref" to listOf("main", "fix"), "engine" to listOf("cp", "ls"))))

        assertEquals(
            listOf("ref=main engine=cp", "ref=main engine=ls", "ref=fix engine=cp", "ref=fix engine=ls"),
            arms.map { it.label },
        )
    }

    @Test
    fun `a config's own label names its arm and is not passed on`() {
        val arms = Experiments.arms(spec(configs = listOf(mapOf("label" to "a"), mapOf("label" to "b", "engine" to "ls"))))

        assertEquals(listOf("a" to emptyMap(), "b" to mapOf("engine" to "ls")), arms.map { it.label to it.values })
    }

    @Test
    fun `each problem runs every arm with the order rotated`() {
        val cases = Experiments.cases(problems = 3, arms = 2, seeds = emptyList())

        assertEquals(listOf(0 to 0, 0 to 1, 1 to 1, 1 to 0, 2 to 0, 2 to 1), cases.map { it.problem to it.arm })
    }

    @Test
    fun `every seed of an arm runs before the next arm`() {
        val cases = Experiments.cases(problems = 1, arms = 2, seeds = listOf(1, 2))

        assertEquals(listOf(0 to 1L, 0 to 2L, 1 to 1L, 1 to 2L), cases.map { it.arm to it.seed })
    }

    @Test
    fun `a spec with an unknown key is refused`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Experiments.validate(spec(configs = listOf(mapOf("cmd" to "rm"))), maxParallel = 4)
        }

        assertContains(error.message.orEmpty(), "unknown config key 'cmd'")
    }

    @Test
    fun `a value with whitespace is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Experiments.validate(spec(base = mapOf("engine" to "cp; rm -rf /")), maxParallel = 4)
        }
    }

    @Test
    fun `a case solves one problem with its arm's arguments and seed`() {
        val arm = Arm("x", mapOf("ref" to "main", "timeout" to "3000", "param.restarts" to "luby"))

        val command = Experiments.command("/w/7", Problem("xcsp3-cop", "Rack-1"), arm, 5, 12, "/corpus")

        assertContains(
            command,
            "solve-one 'suite=xcsp3-cop' 'problem=Rack-1' 'param=restarts=luby' 'timeout=3000' 'solver-seed=5' " +
                "out=\"\$JOB_DIR/cases/12\"",
        )
    }

    @Test
    fun `problems read as one selection or a list of them`() {
        val one = """{"name":"e","problems":{"suite":"a"}}"""
        val two = """{"name":"e","problems":[{"suite":"a"},{"suite":"b","max":"5"}]}"""

        val read = listOf(one, two).map { Json.decodeFromString<ExperimentSpec>(it).problems }

        assertEquals(listOf(listOf(mapOf("suite" to "a")), listOf(mapOf("suite" to "a"), mapOf("suite" to "b", "max" to "5"))), read)
    }

    @Test
    fun `repeats run back to back within each seed`() {
        val cases = Experiments.cases(problems = 1, arms = 1, seeds = listOf(7), repeats = 3)

        assertEquals(listOf(7L to 0, 7L to 1, 7L to 2), cases.map { it.seed to it.repeat })
    }

    @Test
    fun `selections interleave so every one is reached early`() {
        assertEquals(listOf("a1", "b1", "c1", "a2", "c2", "a3"), interleave(listOf(listOf("a1", "a2", "a3"), listOf("b1"), listOf("c1", "c2"))))
    }

    @Test
    fun `the default filter leaves out only what the reference ran and left undecided`() {
        val decided = Reference("scip", false, 3.0, true, true, 100, 10_000)
        val undecided = decided.copy(objective = null, feasible = null, proven = false)

        assertEquals(listOf(true, false, true), listOf(decided, undecided, null).map { ReferenceFilterMode.DECIDED.keeps(it, 60_000) })
    }

    @Test
    fun `an experiment that runs the reference solver is not filtered by it`() {
        val reference = spec(base = mapOf("backend" to "reference"))

        assertEquals(listOf(ReferenceFilterMode.DECIDED, ReferenceFilterMode.ANY),
            listOf(spec(), reference).map { Experiments.referenceFilter(it, mapOf("suite" to "s")) })
    }

    @Test
    fun `a filtered selection is capped per family and in all after filtering`() {
        val problems = listOf("a" to 1, "a" to 2, "a" to 3, "b" to 1, "b" to 2).map { (f, i) -> Problem("s", "$f$i", family = f) }

        assertEquals(listOf("a1", "b1", "a2"), Experiments.cap(problems, perFamily = 2, max = 3).map { it.problem })
    }

    @Test
    fun `a capped selection is asked of the bench without its caps`() {
        assertEquals(mapOf("suite" to "s", "per-family" to "1000000", "seed" to "1"),
            Experiments.uncapped(mapOf("suite" to "s", "per-family" to "1", "max" to "20", "seed" to "1")))
    }

    @Test
    fun `the missing filter keeps only problems without a reference verdict`() {
        val decided = Reference("scip", false, 3.0, true, true, 100, 10_000)

        assertEquals(listOf(false, true), listOf(decided, null).map { ReferenceFilterMode.MISSING.keeps(it, 60_000) })
    }

    @Test
    fun `the unsettled filter reruns what a smaller budget left unproven`() {
        val proven = Reference("scip", false, 3.0, true, true, 100, 10_000)
        val short = proven.copy(proven = false)
        val full = short.copy(budgetMs = 60_000)

        assertEquals(listOf(false, true, false, true), listOf(proven, short, full, null).map { ReferenceFilterMode.UNSETTLED.keeps(it, 60_000) })
    }

    @Test
    fun `the default filter leaves out what the reference took over five seconds to decide`() {
        val fast = Reference("scip", false, 3.0, true, true, 2_000, 60_000)
        val slow = fast.copy(elapsedMs = 6_000)

        assertEquals(listOf(true, false), listOf(fast, slow).map { ReferenceFilterMode.DECIDED.keeps(it, 60_000) })
        assertEquals(listOf(true, false), listOf(fast, slow).map { ReferenceFilterMode.PROVEN.keeps(it, 60_000) })
    }

    @Test
    fun `a selection with one problem per family and no per-family is flagged as capped by the suite`() {
        val one = listOf("a", "b", "c").map { Problem("s", "$it/1", family = it) }
        val two = one + Problem("s", "a/2", family = "a")

        assertNotNull(Experiments.defaultCapped(mapOf("suite" to "s"), one))
        assertNull(Experiments.defaultCapped(mapOf("suite" to "s", "per-family" to "1"), one))
        assertNull(Experiments.defaultCapped(mapOf("suite" to "s"), two))
    }

    @Test
    fun `a fingerprint ignores how a spec runs but not what it measures`() {
        val sweep = spec(base = mapOf("timeout" to "10000"))

        assertEquals(Experiments.fingerprint(sweep),
            Experiments.fingerprint(sweep.copy(name = "x@abc", base = sweep.base + ("ref" to "abc"), priority = -1, parallel = 3)))
        assertNotEquals(Experiments.fingerprint(sweep), Experiments.fingerprint(spec(base = mapOf("timeout" to "60000"))))
    }
}
