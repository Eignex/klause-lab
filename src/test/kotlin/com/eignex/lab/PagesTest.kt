package com.eignex.lab

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagesTest {
    private val host = HostReport("Test/Arm64", 4, "java", "engine", true, emptyList(), vendor = "Accelerate")

    @Test
    fun `a commit links to github only for a github repository`() {
        assertEquals(
            "https://github.com/Eignex/klause/commit/abc",
            commitUrl("https://github.com/Eignex/klause.git", "abc"),
        )
        assertEquals("https://github.com/Eignex/klause/commit/abc", commitUrl("git@github.com:Eignex/klause.git", "abc"))
        assertEquals(null, commitUrl("/srv/git/klause.git", "abc"))
    }

    @Test
    fun `a ref the mirror lacks reads as an unknown ref`() {
        val error = "failed: git rev-parse --verify 'fix/x^{commit}': fatal: Needed a single revision"

        assertEquals("unknown ref: fix/x", shortError(error))
    }

    @Test
    fun `a browser gets pages and an api client gets json`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.database)
        val done = store.create("sweep", "main", listOf("true" to 10L, "false" to 10L))
        store.next()
        store.setup(done, "0123456789abcdef")
        store.commandStarted(done, 0)
        store.commandFinished(done, 0, 0)
        store.commandStarted(done, 1)
        store.commandFinished(done, 1, 1)
        store.finish(done, Status.DONE)
        val queued = store.create("waiting", "main", listOf("true" to 10L))
        testApplication {
            application { api(config, store, host) }

            val index = client.get("/") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
            val job = client.get("/jobs/$done") { header(HttpHeaders.Accept, "text/html") }
            val failed = client.get("/jobs/$done?failed") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
            val json = client.get("/jobs/$done")

            assertContains(index, "#1 in queue")
            assertContains(index, "/jobs/$done?failed\">1 failed</a>")
            assertContains(index, "https://github.com/Eignex/klause/commit/0123456789abcdef")
            assertContains(job.bodyAsText(), "<code>false</code>")
            assertFalse(failed.contains("<code>true</code>"))
            assertTrue(json.contentType()?.match(ContentType.Application.Json) == true)
            assertContains(index, "/jobs/$queued")
        }
    }
}
