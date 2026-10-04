package com.eignex.lab

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferencesTest {
    private fun ref(feasible: Boolean?, objective: Double? = null, proven: Boolean = false) =
        Reference("cp-sat", maximize = false, objective = objective, feasible = feasible, proven = proven, elapsedMs = 10, budgetMs = 1000)

    @Test
    fun `a row with a quoted problem name parses whole`() {
        val row = References.parse("z3", "smtlib-qf_lia,\"a,b/c\",false,7.0,true,true,12,10000,smtlib,arithmetic,0,1,false,QF_LIA")

        assertEquals("smtlib-qf_lia" to "a,b/c", row?.first)
        assertEquals(Reference("z3", false, 7.0, true, true, 12, 10000), row?.second)
    }

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
    fun `the tables are read from a commit of the mirror`() {
        val repo = Files.createTempDirectory("klause").toFile()
        fun git(vararg args: String) = ProcessBuilder(listOf("git", "-C", repo.path) + args).redirectErrorStream(true).start().waitFor()
        git("init", "-q", "-b", "main")
        File(repo, "klause-bench/reference").mkdirs()
        File(repo, "klause-bench/reference/clasp.csv").writeText(
            "suite,problem,maximize,objective,feasible,proven,elapsedMs,budgetMs\npb,x,false,3.0,true,true,5,30000\n",
        )
        git("add", ".")
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "tables")

        val (_, rows) = References(repo).read("main")!!

        assertEquals(listOf(("pb" to "x") to "clasp"), rows.map { it.first to it.second.solver })
    }
}
