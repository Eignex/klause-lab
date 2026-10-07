package com.eignex.lab

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultsTest {
    @Test
    fun `exported rejected witnesses carry validation without solution credit`() {
        val record = Json.parseToJsonElement(
            """{"kind":"optimize","feasible":true,"objective":1.001466,"proven":true,"budgetMs":1000,
                "stats":{"sourceValidation":"invalid","floatApproximation":"true"}}""",
        )
        val cases = listOf(CaseResult(0, Status.DONE, Problem("s", "p", collection = "s"), "a", null, record))

        val lines = Results.casesCsv(cases).lines()
        val fields = lines[1].split(',')

        assertEquals(listOf("sourceValidation", "floatApproximation"), lines[0].split(',').takeLast(2))
        assertEquals(listOf("", "", "false"), fields.slice(12..14))
        assertEquals(listOf("invalid", "true"), fields.takeLast(2))
        assertEquals("s,p,false,,,false,1000,1000,,,,,,", Results.benchCsv(cases, "a", null).lines()[1])
    }

    @Test
    fun `an arm's bench table has a row per problem of the chosen seed`() {
        val record = """{"kind":"optimize","maximize":false,"feasible":true,"objective":7,"proven":true,"timeToBestMs":40,"budgetMs":1000}"""
        val cases = listOf(1L, 2L).map { seed ->
            CaseResult(0, Status.DONE, Problem("xcsp3-cop", "Rack-1", collection = "xcsp3-cop-22to25"), "a", seed, Json.parseToJsonElement(record))
        }

        val csv = Results.benchCsv(cases, "a", 2).lines()

        assertEquals(listOf("xcsp3-cop-22to25,Rack-1,false,7.0,true,true,40,1000,,,,,,", ""), csv.drop(1))
    }

}
