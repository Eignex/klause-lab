package com.eignex.lab

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultsTest {
    @Test
    fun `an arm's bench table has a row per problem of the chosen seed`() {
        val record = """{"kind":"optimize","maximize":false,"feasible":true,"objective":7,"proven":true,"timeToBestMs":40,"budgetMs":1000}"""
        val cases = listOf(1L, 2L).map { seed ->
            CaseResult(0, Status.DONE, Problem("xcsp3-cop", "Rack-1", collection = "xcsp3-cop-22to25"), "a", seed, Json.parseToJsonElement(record))
        }

        val csv = Results.benchCsv(cases, "a", 2).lines()

        assertEquals(listOf("xcsp3-cop-22to25,Rack-1,false,7.0,true,true,40,1000,,,,,,", ""), csv.drop(1))
    }

    @Test
    fun `an import takes the records the job's own commit wrote, one arm per config`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.database)
        val old = store.create("sweep", "main", listOf("true" to 10L))
        store.next()
        store.setup(old, "abc")
        store.finish(old, Status.DONE)
        val output = config.jobDir(old).resolve("collected/klause-bench/output").toFile()
        fun write(config: String, problem: String, sha: String) = output.resolve(config).apply { mkdirs() }.resolve("$problem.json")
            .writeText("""{"problem":"$problem","kind":"satisfy","feasible":true,"budgetMs":1000,"gitSha":"$sha"}""")
        write("klause-cp", "p", "abc")
        write("klause-ls", "p", "abc")
        write("klause-ls", "q", "older")

        val imported = Results.import(config, store, checkNotNull(store.job(old)))

        assertEquals(
            listOf("klause-cp" to "p", "klause-ls" to "p"),
            store.cases(imported).map { it.arm to it.problem.problem },
        )
    }
}
