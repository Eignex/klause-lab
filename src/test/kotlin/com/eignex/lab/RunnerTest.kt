package com.eignex.lab

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunnerTest {
    @Test
    fun `deleting a worktree removes a shared link without touching what it points at`() {
        val root = Files.createTempDirectory("lab")
        val shared = root.resolve("shared").createDirectories()
        shared.resolve("result.json").writeText("{}")
        val worktree = root.resolve("work").resolve("build").createDirectories().parent
        Files.createSymbolicLink(worktree.resolve("build").resolve("bench-cache"), shared)

        deleteTree(worktree)

        assertTrue(!worktree.exists() && shared.resolve("result.json").exists())
    }

    @Test
    fun `killing the group reaches a child whose parent shell already died`() {
        val process = ProcessBuilder(inSession("bash", "-c", "sleep 30 & echo \$!; exit 0"))
            .redirectErrorStream(true).start()
        val orphan = process.inputStream.bufferedReader().readLine().trim().toLong()
        process.waitFor(5, TimeUnit.SECONDS)

        killGroup(process.pid())

        val child = ProcessHandle.of(orphan)
        child.ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertFalse(child.map { it.isAlive }.orElse(false))
    }
}
