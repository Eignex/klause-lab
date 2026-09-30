package com.eignex.lab

import java.nio.file.Path
import kotlin.io.path.Path

/** Server settings, all from the environment so that launchd plists carry them. */
data class Config(
    /** Queue database, per-job outputs and worktrees live here. */
    val dataDir: Path = Path(env("LAB_DATA", "${System.getProperty("user.home")}/klause-lab-data")),
    /** The klause repository to fetch refs from. */
    val repoUrl: String = env("LAB_REPO", "git@github.com:Eignex/klause.git"),
    /** Benchmark instances, rsynced from the dev PC; the bench's own default cache location. */
    val corpusDir: Path = Path(env("LAB_CORPUS", "${System.getProperty("user.home")}/.cache/klause-bench/corpus")),
    val port: Int = env("LAB_PORT", "8420").toInt(),
    /** JDK the klause build and CLI run on. */
    val javaHome: String = env("LAB_JAVA_HOME", System.getenv("JAVA_HOME") ?: ""),
    /** JVM flags for every klause-cli solve a command starts. */
    val solveJavaOpts: String = env("LAB_SOLVE_JAVA_OPTS", "-Xmx4g -XX:ActiveProcessorCount=1 -XX:+UseSerialGC"),
    val gradleWorkers: Int = env("LAB_GRADLE_WORKERS", "2").toInt(),
    val defaultTimeoutSec: Long = env("LAB_DEFAULT_TIMEOUT_SEC", "21600").toLong(),
    val setupTimeoutSec: Long = env("LAB_SETUP_TIMEOUT_SEC", "3600").toLong(),
) {
    val database: Path get() = dataDir.resolve("lab.db")
    val mirror: Path get() = dataDir.resolve("repo.git")
    fun jobDir(id: Long): Path = dataDir.resolve("jobs").resolve(id.toString())
    fun worktree(id: Long): Path = dataDir.resolve("work").resolve(id.toString())
}

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
