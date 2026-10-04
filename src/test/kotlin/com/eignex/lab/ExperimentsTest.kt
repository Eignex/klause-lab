package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
