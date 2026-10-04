package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CompareTest {
    private fun outcome(feasible: Boolean?, objective: Double? = null, proven: Boolean = false, timeMs: Long = 100, maximize: Boolean = false) =
        Outcome(optimize = true, maximize = maximize, feasible, objective, proven, timeMs, budgetMs = 1000, error = false)

    private fun case(arm: String, problem: String, record: String, seed: Long? = null) =
        CaseResult(0, Status.DONE, Problem("s", problem), arm, seed, Json.parseToJsonElement(record))

    @Test
    fun `a solved outcome beats an unsolved one whatever the time`() {
        assertEquals(1.0, Compare.points(outcome(true, 5.0, timeMs = 900), outcome(null)))
    }

    @Test
    fun `a better objective wins in the direction of the objective`() {
        assertEquals(
            listOf(1.0, 0.0),
            listOf(false, true).map { maximize -> Compare.points(outcome(true, 3.0, maximize = maximize), outcome(true, 5.0, maximize = maximize)) },
        )
    }

    @Test
    fun `equal outcomes split the point by time`() {
        assertEquals(0.75, Compare.points(outcome(true, 5.0, timeMs = 1000), outcome(true, 5.0, timeMs = 3000)))
    }

    @Test
    fun `an arm proving infeasible against one that solves is a disagreement`() {
        val cases = listOf(
            case("a", "p", """{"kind":"optimize","feasible":false,"proven":true,"budgetMs":1000}"""),
            case("b", "p", """{"kind":"optimize","feasible":true,"objective":4,"budgetMs":1000}"""),
        )

        assertEquals(listOf("infeasible per a, solved by b"), Compare.compare(listOf("a", "b"), cases).disagreements.map { it.reason })
    }

    @Test
    fun `a solution better than a proven optimum is a disagreement`() {
        val cases = listOf(
            case("a", "p", """{"kind":"optimize","feasible":true,"objective":10,"proven":true,"budgetMs":1000}"""),
            case("b", "p", """{"kind":"optimize","feasible":true,"objective":8,"budgetMs":1000}"""),
        )

        assertEquals(1, Compare.compare(listOf("a", "b"), cases).disagreements.size)
    }

    @Test
    fun `arms are counted against the first arm per problem and seed`() {
        val solved = """{"kind":"optimize","feasible":true,"objective":1,"timeToBestMs":10,"budgetMs":1000}"""
        val unknown = """{"kind":"optimize","budgetMs":1000}"""
        val cases = listOf(
            case("a", "p", solved, 1), case("b", "p", unknown, 1),
            case("a", "p", unknown, 2), case("b", "p", solved, 2),
            case("a", "q", solved, 1), case("b", "q", solved, 1),
        )

        val b = Compare.compare(listOf("a", "b"), cases).arms[1]

        assertEquals(listOf(1, 1, 1), listOf(b.wins, b.losses, b.ties))
    }

    @Test
    fun `a declined model counts as unsupported and a broken one as an error`() {
        val cases = listOf(
            case("a", "p", """{"kind":"optimize","budgetMs":1000,"command":"LOAD","stats":{"unsupported":"unbounded float"}}"""),
            case("a", "q", """{"kind":"satisfy","budgetMs":1000,"command":"LOAD","stats":{"loadError":"include error"}}"""),
        )

        val a = Compare.compare(listOf("a"), cases).arms.single()

        assertEquals(listOf(1, 1, 0), listOf(a.unsupported, a.errors, a.solved))
    }

    @Test
    fun `a proof of infeasibility is timed by its solve time and not the budget`() {
        val record = """{"kind":"satisfy","feasible":false,"proven":true,"budgetMs":30000,"stats":{"solveTime":"0.09"}}"""

        assertEquals(90L, Outcome.of(Json.parseToJsonElement(record))?.timeMs)
    }

    @Test
    fun `times within the noise are a tie`() {
        assertEquals(
            listOf(0.5, 0.5, 0.5),
            listOf(360L to 350L, 1220L to 1100L, 30_000L to 27_500L).map { (a, b) ->
                Compare.points(outcome(true, 5.0, timeMs = a), outcome(true, 5.0, timeMs = b))
            },
        )
    }
}
