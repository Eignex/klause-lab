package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfirmTest {
    private fun case(problem: String, arm: String, solved: Boolean, repeat: Int = 0) = CaseResult(
        0, Status.DONE, Problem("s", problem), arm,
        record = Json.parseToJsonElement(
            if (solved) """{"kind":"satisfy","feasible":true,"timeToBestMs":5,"budgetMs":1000}""" else """{"kind":"satisfy","budgetMs":1000}""",
        ),
        repeat = repeat,
    )

    @Test
    fun `flips are the problems both runs ran that one solved and the other did not`() {
        val before = listOf(case("a", "x", true), case("b", "x", false), case("c", "x", true), case("only-before", "x", true))
        val after = listOf(case("a", "x", false), case("b", "x", true), case("c", "x", true))

        val flips = Confirm.flips(before, after)

        assertEquals(listOf(listOf("a"), listOf("b")), listOf(flips.lost.map { it.problem }, flips.gained.map { it.problem }))
    }

    @Test
    fun `a confirmation counts a flip only when the repeats agree with it`() {
        val held = (0..2).flatMap { listOf(case("held", Confirm.BEFORE, true, it), case("held", Confirm.AFTER, false, it)) }
        val noise = (0..2).flatMap { listOf(case("noise", Confirm.BEFORE, true, it), case("noise", Confirm.AFTER, it != 0, it)) }

        val confirmed = Confirm.confirmed(held + noise)

        assertEquals(listOf("held"), confirmed.lost.map { it.problem })
        assertTrue(confirmed.gained.isEmpty())
    }

    @Test
    fun `the sign test is two-sided and certain of nothing for an even split`() {
        assertEquals(1.0, Confirm.signTest(3, 3))
        assertEquals(2.0 / 32768, Confirm.signTest(15, 0), 1e-12)
    }

    @Test
    fun `a confirmation runs the flips on both commits, three times each`() {
        val sweep = ExperimentSpec("status-sweep@bbb", listOf(mapOf("set" to "sweep")), base = mapOf("timeout" to "10000", "ref" to "bbb"), priority = -1)
        val flips = Flips(lost = listOf(Problem("s", "a")), gained = listOf(Problem("s", "b")))

        val spec = Confirm.spec("status-sweep", sweep, "aaa111111111", "bbb222222222", flips, job = 7)
        val arms = Experiments.arms(spec)

        assertEquals("status-sweep~confirm@bbb222222-7", spec.name)
        assertEquals(listOf("before" to "aaa111111111", "after" to "bbb222222222"), arms.map { it.label to it.ref })
        assertEquals(listOf(3, 2, -1), listOf(spec.repeats, spec.problemList.size, spec.priority))
        assertEquals(10_000L, arms.first().timeoutMs)
        Experiments.validate(spec, maxParallel = 6)
    }
}
