package com.eignex.lab

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import jdk.jfr.consumer.RecordingFile
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliMeasurementsTest {
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `a profiled JVM returns a readable recording and physical resource measurements`() {
        val dir = Files.createTempDirectory("measurement")
        val cli = dir.resolve(CliMeasurements.CLI)
        Files.createDirectories(cli.parent)
        val javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
        Files.writeString(cli, "#!/bin/bash\nexec ${quote(javaExecutable.toString())} \$KLAUSE_CLI_OPTS -version\n")
        cli.toFile().setExecutable(true)
        val setup = ProcessBuilder("bash", "-c", CliMeasurements.install(dir.toString())).start()
        assertEquals(0, setup.waitFor(), setup.errorStream.bufferedReader().readText())
        val profile = dir.resolve("profile")
        Files.createDirectories(profile)
        val builder = ProcessBuilder(cli.toString()).redirectErrorStream(true)
        builder.environment()["KLAUSE_LAB_PROFILE_DIR"] = profile.toString()
        builder.environment()["KLAUSE_CLI_OPTS"] = "-Xmx64m -XX:ActiveProcessorCount=1"

        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
        val events = RecordingFile.readAllEvents(profile.resolve("cli.jfr"))
        assertTrue(events.any { it.eventType.name == "jdk.JVMInformation" })
        val resources = Files.readString(profile.resolve("resources.txt"))
        assertTrue("exit=0" in resources)
        assertTrue(Regex("peakRssKiB=[1-9][0-9]*").containsMatchIn(resources))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `a version probe preserves solve artifacts and omits recording options`() {
        val dir = Files.createTempDirectory("measurement")
        val cli = dir.resolve(CliMeasurements.CLI)
        Files.createDirectories(cli.parent)
        Files.writeString(cli, "#!/bin/bash\nprintf '%s' \"\$KLAUSE_CLI_OPTS\"\n")
        cli.toFile().setExecutable(true)
        val setup = ProcessBuilder("bash", "-c", CliMeasurements.install(dir.toString())).start()
        assertEquals(0, setup.waitFor(), setup.errorStream.bufferedReader().readText())
        val profile = dir.resolve("profile")
        Files.createDirectories(profile)
        val recording = byteArrayOf(1, 2, 3)
        val resources = "peakRssKiB=123456\nexit=0\n"
        Files.write(profile.resolve("cli.jfr"), recording)
        Files.writeString(profile.resolve("resources.txt"), resources)
        val builder = ProcessBuilder(cli.toString(), "--version")
        builder.environment()["KLAUSE_LAB_PROFILE_DIR"] = profile.toString()
        builder.environment()["KLAUSE_CLI_OPTS"] = "-Xmx64m -XX:ActiveProcessorCount=1"

        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor())
        assertEquals("-Xmx64m -XX:ActiveProcessorCount=1", output)
        assertContentEquals(recording, Files.readAllBytes(profile.resolve("cli.jfr")))
        assertEquals(resources, Files.readString(profile.resolve("resources.txt")))
    }

    @Test
    fun `a complete binary transfer preserves every byte`() {
        val dir = Files.createTempDirectory("measurement")
        val source = dir.resolve("source")
        val target = dir.resolve("recording.jfr")
        val bytes = ByteArray(256) { it.toByte() }
        Files.write(source, bytes)

        receiveFile(ProcessBuilder("cat", source.toString()).start(), target, 5) { false }

        assertContentEquals(bytes, Files.readAllBytes(target))
        assertFalse(Files.exists(dir.resolve("recording.jfr.part")))
    }

    @Test
    fun `a failed transfer preserves a completed artifact`() {
        val dir = Files.createTempDirectory("measurement")
        val target = dir.resolve("recording.jfr")
        val bytes = byteArrayOf(4, 5, 6)
        Files.write(target, bytes)

        val failure = assertFailsWith<FileTransferFailure> {
            receiveFile(ProcessBuilder("sh", "-c", "printf partial; exit 255").start(), target, 5) { false }
        }

        assertEquals(255, failure.exit)
        assertContentEquals(bytes, Files.readAllBytes(target))
        assertFalse(Files.exists(dir.resolve("recording.jfr.part")))
    }

    @Test
    fun `cancelling a transfer stops its process without publishing a partial artifact`() {
        val dir = Files.createTempDirectory("measurement")
        val target = dir.resolve("recording.jfr")
        val process = ProcessBuilder("sleep", "30").start()

        assertFailsWith<IllegalStateException> { receiveFile(process, target, 5) { true } }

        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(dir.resolve("recording.jfr.part")))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `profiling preserves arguments stdout and the CLI exit status`() {
        val dir = Files.createTempDirectory("measurement with spaces")
        val cli = dir.resolve(CliMeasurements.CLI)
        Files.createDirectories(cli.parent)
        Files.writeString(cli, "#!/bin/bash\nprintf '<%s>\\n' \"\$@\"\nexit 7\n")
        cli.toFile().setExecutable(true)
        val setup = ProcessBuilder("bash", "-c", CliMeasurements.install(dir.toString())).start()
        assertEquals(0, setup.waitFor(), setup.errorStream.bufferedReader().readText())
        val profile = dir.resolve("profile")
        Files.createDirectories(profile)
        val builder = ProcessBuilder(cli.toString(), "two words", "dollar\$value", "")
        builder.environment()["KLAUSE_LAB_PROFILE_DIR"] = profile.toString()

        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(7, process.waitFor())
        assertEquals("<two words>\n<dollar\$value>\n<>\n", output)
        val resources = Files.readString(profile.resolve("resources.txt"))
        assertTrue("exit=7" in resources)
        assertTrue(Regex("peakRssKiB=[1-9][0-9]*").containsMatchIn(resources))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `forcibly stopping the timer also stops its CLI child`() {
        val dir = Files.createTempDirectory("measurement")
        val cli = dir.resolve(CliMeasurements.CLI)
        Files.createDirectories(cli.parent)
        Files.writeString(cli, "#!/bin/bash\necho \$\$\nexec sleep 30\n")
        cli.toFile().setExecutable(true)
        val setup = ProcessBuilder("bash", "-c", CliMeasurements.install(dir.toString())).start()
        assertEquals(0, setup.waitFor(), setup.errorStream.bufferedReader().readText())
        val profile = dir.resolve("profile")
        Files.createDirectories(profile)
        val builder = ProcessBuilder(cli.toString())
        builder.environment()["KLAUSE_LAB_PROFILE_DIR"] = profile.toString()
        val process = builder.start()
        var child: Long? = null
        try {
            child = process.inputStream.bufferedReader().readLine().toLong()
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            val status = java.nio.file.Path.of("/proc/$child/status")
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
            while (Files.exists(status) && !Regex("(?m)^State:\\s+Z").containsMatchIn(Files.readString(status)) &&
                System.nanoTime() < deadline) Thread.sleep(5)

            assertTrue(!Files.exists(status) || Regex("(?m)^State:\\s+Z").containsMatchIn(Files.readString(status)))
        } finally {
            process.destroyForcibly()
            child?.let { ProcessHandle.of(it).ifPresent { handle -> handle.destroyForcibly() } }
        }
    }
}
