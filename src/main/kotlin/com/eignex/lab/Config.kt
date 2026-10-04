package com.eignex.lab

import java.nio.file.Path
import kotlin.io.path.Path

/** Server settings, all from the environment so that launchd plists carry them. */
data class Config(
    /** Queue database, per-job outputs and worktrees live here. */
    val dataDir: Path = Path(env("LAB_DATA", "${System.getProperty("user.home")}/klause-lab-data")),
    /** The klause repository to fetch refs from. */
    val repoUrl: String = env("LAB_REPO", "https://github.com/Eignex/klause.git"),
    /** Benchmark instances, rsynced from the dev PC; the bench's own default cache location. */
    val corpusDir: Path = Path(env("LAB_CORPUS", "${System.getProperty("user.home")}/.cache/klause-bench/corpus")),
    val port: Int = env("LAB_PORT", "8420").toInt(),
    /** JDK the klause build and CLI run on. */
    val javaHome: String = env("LAB_JAVA_HOME", System.getenv("JAVA_HOME") ?: ""),
    /** JVM flags for every klause-cli solve a command starts. */
    val solveJavaOpts: String = env("LAB_SOLVE_JAVA_OPTS", "-Xmx4g -XX:ActiveProcessorCount=1 -XX:+UseSerialGC"),
    val gradleWorkers: Int = env("LAB_GRADLE_WORKERS", "2").toInt(),
    val setupTimeoutSec: Long = env("LAB_SETUP_TIMEOUT_SEC", "3600").toLong(),
    /** The most commands one job may run at once, whatever it asks for. Each solve holds its own heap. */
    val maxParallel: Int = env("LAB_MAX_PARALLEL", "4").toInt(),
    /** The klause-lab checkout the services were installed from; the runner updates from it between jobs. */
    val sourceDir: Path? = System.getenv("LAB_SOURCE")?.takeIf { it.isNotBlank() }?.let { Path(it) },
    /** Seconds between update checks; `0` turns self-update off. */
    val updateCheckSec: Long = env("LAB_UPDATE_CHECK_SEC", "300").toLong(),
    /** Free space the data directory's disk must have before the runner starts a job. */
    val minFreeBytes: Long = env("LAB_MIN_FREE_GB", "10").toLong() * BYTES_PER_GB,
    /** The longest an experiment may take, every case using its whole budget, before it needs `"confirm": true`. */
    val maxExperimentHours: Long = env("LAB_MAX_EXPERIMENT_HOURS", "24").toLong(),
    /** Wait for `docker info` to succeed before taking jobs: set where reference solvers run in containers. */
    val requireDocker: Boolean = env("LAB_REQUIRE_DOCKER", "false").toBoolean(),
    /** Worktree-relative directories every job shares, separated by `:`. */
    val sharedPaths: List<String> = env("LAB_SHARED_PATHS", "klause-bench/build/bench-cache")
        .split(':').map { it.trim() }.filter { it.isNotEmpty() },
) {
    init {
        require(sharedPaths.none { it.startsWith("/") || ".." in it.split('/') }) {
            "LAB_SHARED_PATHS entries must stay inside the worktree: $sharedPaths"
        }
    }

    val sharedDir: Path get() = dataDir.resolve("shared")
    val database: Path get() = dataDir.resolve("lab.db")
    val mirror: Path get() = dataDir.resolve("repo.git")
    fun jobDir(id: Long): Path = dataDir.resolve("jobs").resolve(id.toString())
    fun worktree(id: Long): Path = dataDir.resolve("work").resolve(id.toString())
}

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
