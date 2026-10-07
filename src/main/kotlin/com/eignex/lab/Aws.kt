package com.eignex.lab

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Where the lab runs experiments on AWS, read from `$LAB_DATA/aws/aws.properties` (written by `deploy/aws-setup.sh`),
 * so no service needs reinstalling to change it. The CLI's credentials are a named [profile] in `~/.aws/credentials`.
 */
data class AwsConfig(
    val region: String,
    val profile: String,
    val instanceType: String,
    /** The most instances the lab runs at once, over every AWS job. */
    val maxInstances: Int,
    val keyName: String,
    /** The private key of [keyName], on the lab machine. */
    val keyFile: String,
    val securityGroup: String,
    val subnet: String?,
    /** Cores of one instance a job's cases may hold together: its physical cores, as the lab Mac's are its P-cores. */
    val cores: Int,
    /** An instance powers itself off after this long, whatever the lab does: a lost controller cannot leave one running. */
    val maxHours: Int,
    /** The MiniZinc bundle an instance installs, the lab Mac's version so models compile alike. */
    val minizinc: String,
    /** An image to launch instead of the current Ubuntu 24.04 one. */
    val ami: String?,
    /** An S3 bucket holding a copy of the corpus cache: an instance syncs it down while it builds and pushes back what
     *  it had to fetch, so each collection is fetched from its source once. Null: every instance fetches its own. */
    val corpusBucket: String? = null,
    /** The instance profile whose role may read and write [corpusBucket]. */
    val instanceProfile: String? = null,
) {
    companion object {
        fun load(file: Path): AwsConfig? {
            if (!file.exists()) return null
            val p = Properties().apply { file.toFile().inputStream().use(::load) }
            fun get(key: String, default: String? = null) = p.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default
            return AwsConfig(
                region = requireNotNull(get("region")) { "aws.properties needs region" },
                profile = get("profile", "klause-lab")!!,
                instanceType = get("instanceType", "c7i.2xlarge")!!,
                maxInstances = get("maxInstances", "5")!!.toInt(),
                keyName = requireNotNull(get("keyName")) { "aws.properties needs keyName" },
                keyFile = requireNotNull(get("keyFile")) { "aws.properties needs keyFile" },
                securityGroup = requireNotNull(get("securityGroup")) { "aws.properties needs securityGroup" },
                subnet = get("subnet"),
                cores = get("cores", "4")!!.toInt(),
                maxHours = get("maxHours", "24")!!.toInt(),
                minizinc = get("minizinc", "2.9.7")!!,
                ami = get("ami"),
                corpusBucket = get("corpusBucket"),
                instanceProfile = get("instanceProfile"),
            )
        }
    }
}

/** The AWS CLI, as the lab's IAM user, in the background so it stays off the solves' cores. */
class AwsCli(private val aws: AwsConfig) {
    fun call(vararg args: String): String {
        val cmd = Background.prefix + listOf("aws", "--region", aws.region, "--profile", aws.profile, "--output", "text") + args
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "aws ${args.take(2).joinToString(" ")} failed: ${out.take(ERROR_CHARS)}" }
        return out
    }

    fun image(): String = aws.ami ?: call("ssm", "get-parameter", "--name", UBUNTU_IMAGE, "--query", "Parameter.Value")

    /** Launch one instance for job [jobId], tagged as the lab's, terminating itself when it powers off. */
    fun launch(jobId: Long, name: String, userData: File): String = call(
        "ec2", "run-instances",
        "--image-id", image(),
        "--instance-type", aws.instanceType,
        "--key-name", aws.keyName,
        "--security-group-ids", aws.securityGroup,
        *(aws.subnet?.let { arrayOf("--subnet-id", it) } ?: emptyArray()),
        *(aws.instanceProfile?.let { arrayOf("--iam-instance-profile", "Name=$it") } ?: emptyArray()),
        "--instance-initiated-shutdown-behavior", "terminate",
        "--user-data", "file://${userData.absolutePath}",
        "--metadata-options", "HttpTokens=required",
        "--block-device-mappings", "DeviceName=/dev/sda1,Ebs={VolumeSize=$VOLUME_GB,VolumeType=gp3,DeleteOnTermination=true}",
        "--tag-specifications",
        "ResourceType=instance,Tags=[{Key=$TAG,Value=true},{Key=Name,Value=$name},{Key=$JOB_TAG,Value=$jobId}]",
        "ResourceType=volume,Tags=[{Key=$TAG,Value=true},{Key=$JOB_TAG,Value=$jobId}]",
        "--query", "Instances[0].InstanceId",
    )

    /** Admit SSH from the lab machine's current public address, which a home connection can change. */
    fun admitThisMachine() {
        val ip = ProcessBuilder("curl", "-fsS", "https://checkip.amazonaws.com").start().let { p ->
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        }
        if (ip.isEmpty()) return
        runCatching {
            call("ec2", "authorize-security-group-ingress", "--group-id", aws.securityGroup, "--protocol", "tcp", "--port", "22",
                "--cidr", "$ip/32")
        } // already admitted is an error from the CLI, and the only one expected here
    }

    fun awaitRunning(id: String) {
        call("ec2", "wait", "instance-running", "--instance-ids", id)
    }

    fun publicIp(id: String): String =
        call("ec2", "describe-instances", "--instance-ids", id, "--query", "Reservations[0].Instances[0].PublicIpAddress")

    fun terminate(ids: Collection<String>) {
        if (ids.isNotEmpty()) call("ec2", "terminate-instances", "--instance-ids", *ids.toTypedArray(), "--query", "TerminatingInstances[].InstanceId")
    }

    /** The lab's instances that are pending or running, each with the job it was launched for. */
    fun running(): List<Pair<String, Long?>> = call(
        "ec2", "describe-instances",
        "--filters", "Name=tag:$TAG,Values=true", "Name=instance-state-name,Values=pending,running",
        "--query", "Reservations[].Instances[].[InstanceId, Tags[?Key=='$JOB_TAG']|[0].Value]",
    ).lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val parts = line.split(Regex("\\s+"))
        parts[0] to parts.getOrNull(1)?.toLongOrNull()
    }

    private companion object {
        const val UBUNTU_IMAGE = "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id"
        const val TAG = "klause-lab"
        const val JOB_TAG = "klause-lab-job"
        const val VOLUME_GB = 60
        const val ERROR_CHARS = 2000
    }
}

/** An instance as the lab reaches it: SSH as `ubuntu`, with the lab's key, in the background. */
class Ssh(private val ip: String, private val aws: AwsConfig, private val knownHosts: Path) {
    private fun command(script: String): List<String> = Background.prefix + listOf(
        "ssh", "-i", aws.keyFile,
        "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", "UserKnownHostsFile=$knownHosts",
        "-o", "ConnectTimeout=15",
        "-o", "ServerAliveInterval=30",
        "-o", "ServerAliveCountMax=4",
        "ubuntu@$ip",
        "bash -c ${quote(script)}",
    )

    fun start(script: String): Process = ProcessBuilder(command(script)).redirectErrorStream(true).start()

    /** Run [script] to its end, at most [timeoutSec]; its exit (255 when SSH itself failed) and its output. */
    fun exec(script: String, timeoutSec: Long): Pair<Int, String> {
        val process = start(script)
        val output = StringBuilder()
        val reader = thread(isDaemon = true) { output.append(process.inputStream.bufferedReader().readText()) }
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(READ_WAIT_MS)
            return TIMED_OUT to output.toString()
        }
        reader.join(READ_WAIT_MS)
        return process.exitValue() to output.toString()
    }

    /** [exec] that fails unless [script] succeeds. */
    fun run(script: String, timeoutSec: Long): String {
        val (exit, out) = exec(script, timeoutSec)
        check(exit == 0) { "on $ip (exit $exit): ${out.takeLast(OUTPUT_CHARS)}" }
        return out
    }

    companion object {
        const val TIMED_OUT = -1
        private const val READ_WAIT_MS = 5_000L
        private const val OUTPUT_CHARS = 3000
    }
}

/**
 * An AWS instance as an [ExecutionHost]: worktrees under `~/work`, the corpus under `~/corpus`, cases run over SSH
 * under `timeout`, each record copied back into the job's local directory as the case ends.
 */
class AwsHost(
    val instance: String,
    val ssh: Ssh,
    override val cores: Int,
    private val solveJavaOpts: String,
    private val cancelled: () -> Boolean,
) : ExecutionHost {
    override val corpus = "$HOME/corpus"
    override val yieldsToUpdates = false
    override val yieldsToPriority = false

    override fun worktree(sha: String) = "$HOME/work/${sha.take(SHA_DIR)}"

    override fun select(worktree: String, args: String): List<String> {
        val opts = "-Dklause.bench.corpusCache=$corpus -Dklause.workspace.root=$worktree"
        val out = ssh.run(
            "$ENV; cd ${quote("$worktree/klause-bench")} && JAVA_OPTS=${quote(opts)} KLAUSE_BENCH_CORPUS_MAX_GB=off " +
                "./build/install/klause-bench/bin/klause-bench select $args 2>/dev/null",
            SELECT_TIMEOUT_SEC,
        )
        return out.lines()
    }

    /** Run [command], again from the start when SSH itself dropped before the case reported its exit. */
    override fun run(command: Command, dir: Path): Int {
        var attempt = 1
        while (true) {
            try {
                return runOnce(command, dir)
            } catch (e: SshDropped) {
                if (attempt >= SSH_ATTEMPTS || cancelled()) throw IllegalStateException(e.message, e)
                Thread.sleep(SSH_RETRY_MS)
                attempt++
            }
        }
    }

    private class SshDropped(message: String) : Exception(message)

    private fun runOnce(command: Command, dir: Path): Int {
        val i = command.index
        val script = """
            $ENV
            export JOB_DIR=$HOME/job KLAUSE_CLI_OPTS=${quote("$solveJavaOpts -XX:ActiveProcessorCount=${command.cores}")}
            export OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 OMP_NUM_THREADS=1
            mkdir -p ${'$'}JOB_DIR/cases
            timeout -k 30 ${command.timeoutSec} bash -c ${quote(command.cmd)} > ${'$'}JOB_DIR/$i.out 2> ${'$'}JOB_DIR/$i.err
            echo "exit=${'$'}?"
            echo "$RECORD"
            cat ${'$'}JOB_DIR/cases/$i/*.json 2>/dev/null || true
        """.trimIndent()
        val process = ssh.start(script)
        val output = StringBuilder()
        val reader = thread(isDaemon = true) { output.append(process.inputStream.bufferedReader().readText()) }
        val deadline = System.currentTimeMillis() + (command.timeoutSec + SSH_GRACE_SEC) * MS_PER_SEC
        while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
            if (cancelled() || System.currentTimeMillis() > deadline) {
                process.destroyForcibly()
                ssh.exec("pkill -f -- 'cases/$i([^0-9]|${'$'})' || true", KILL_TIMEOUT_SEC)
                return if (cancelled()) CANCELLED else TIMEOUT
            }
        }
        reader.join(READ_WAIT_MS)
        val text = output.toString()
        val exit = Regex("""(?m)^exit=(\d+)$""").find(text)?.groupValues?.get(1)?.toInt()
            ?: throw SshDropped("case $i on $instance: no exit reported (ssh exit ${process.exitValue()}): ${text.takeLast(ERROR_CHARS)}")
        val record = text.substringAfter("$RECORD\n", "").trim()
        if (record.isNotEmpty()) {
            dir.resolve("cases").resolve(i.toString()).createDirectories().resolve("record.json").toFile().writeText(record)
        }
        val result = if (exit == TIMEOUT_EXIT_CODE) TIMEOUT else exit
        dir.resolve("$i.exit").toFile().writeText(if (result == TIMEOUT) "timeout\n" else "$exit\n")
        return result
    }

    companion object {
        const val HOME = "/home/ubuntu"
        const val ENV = "export JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:/usr/local/bin:${'$'}PATH"
        private const val SHA_DIR = 12
        private const val RECORD = "----klause-lab-record----"
        private const val SELECT_TIMEOUT_SEC = 3600L
        private const val SSH_GRACE_SEC = 120L
        private const val KILL_TIMEOUT_SEC = 30L
        private const val POLL_MS = 1000L
        private const val READ_WAIT_MS = 5_000L
        private const val MS_PER_SEC = 1000L
        private const val ERROR_CHARS = 2000
        private const val TIMEOUT_EXIT_CODE = 124
        private const val SSH_ATTEMPTS = 3
        private const val SSH_RETRY_MS = 15_000L

        /** The runner's exits for a case cut off by a cancel or by its timeout. */
        const val CANCELLED = -1000
        const val TIMEOUT = -1001
    }
}

/**
 * Runs `"host": "aws"` experiments on EC2, on demand: no instance runs while none is queued. A job takes up to its
 * `machines` instances (all the free ones by default, at most [AwsConfig.maxInstances] over every job), each built
 * at the job's commits, plans on the first, and splits its problems across them, every arm of a problem on the same
 * instance, so each comparison is made on one machine. Records come back as cases end, so the job's page, its
 * comparisons and its CSVs read as a local job's. The instances are terminated when the job ends, whatever way it
 * ends; one the lab lost track of powers itself off after [AwsConfig.maxHours], and the worker terminates any lab
 * instance no running job owns when it starts.
 */
class AwsWorker(
    private val runner: Runner,
    private val store: Store,
    private val config: Config,
    private val aws: AwsConfig,
) {
    private val cli = AwsCli(aws)
    private val busy = AtomicInteger()

    /** Whether any AWS job is running: the runner holds off updating itself until none is, since a restart would end
     *  its thread and, with it, the job's instances. */
    val active: Boolean get() = busy.get() > 0
    private val knownHosts = config.dataDir.resolve("aws").resolve("known_hosts")

    fun loop() {
        knownHosts.parent.createDirectories()
        runCatching { recover() }.onFailure { println("aws: recovery failed: ${it.message}") }
        while (true) {
            runCatching {
                val free = aws.maxInstances - busy.get()
                if (free > 0) {
                    store.next(Experiments.AWS_HOST)?.let { job ->
                        val want = job.experiment?.machines ?: aws.maxInstances
                        val machines = want.coerceIn(1, free)
                        busy.addAndGet(machines)
                        thread(name = "aws-${job.id}") {
                            try {
                                work(job, machines)
                            } finally {
                                busy.addAndGet(-machines)
                            }
                        }
                    }
                }
            }.onFailure { println("aws: ${it.message}") }
            Thread.sleep(POLL_MS)
        }
    }

    /** After a restart: jobs left running go back in the queue, and instances no running job owns are terminated. */
    private fun recover() {
        for (job in store.active().filter { it.status == Status.RUNNING && it.experiment?.host == Experiments.AWS_HOST }) {
            store.requeueInterrupted(job.id)
            store.requeue(job.id)
        }
        val owned = store.active().filter { it.status == Status.RUNNING }.map { it.id }.toSet()
        val orphans = cli.running().filter { (_, job) -> job == null || job !in owned }.map { it.first }
        if (orphans.isNotEmpty()) {
            println("aws: terminating instances no running job owns: $orphans")
            cli.terminate(orphans)
        }
    }

    private fun work(claimed: Job, machines: Int) {
        val dir = config.jobDir(claimed.id).createDirectories()
        val log = dir.resolve("setup.log").toFile().apply { appendText("") }
        val instances = ArrayList<String>()
        try {
            val job = checkNotNull(store.job(claimed.id))
            val spec = checkNotNull(job.experiment)
            val (arms, shas) = runner.resolveArms(job, spec, log)
            val primary = shas.getValue(arms.first().ref)
            val hosts = launch(job, machines, instances)
            parallelOn(hosts) { host -> setup(job, host, shas.values.distinct(), log) }
            if (job.commands.isEmpty()) {
                runner.log(job.id, "${hosts.first().instance}: planning, which fetches the corpora the selections read")
                runner.plan(job, spec, arms, shas, primary, dir, hosts.first())
            }
            if (hosts.size > 1) runner.log(job.id, "fetching the corpora on the other instances")
            // Each instance fetches the corpora its cases read before they start, so cases that share a collection
            // never fetch it side by side.
            val selections = spec.problems.map { Experiments.selectArgs(it) }
            parallelOn(hosts.drop(1)) { host -> selections.forEach { host.select(host.worktree(primary), it) } }
            aws.corpusBucket?.let { bucket ->
                // Before any case runs, so the upload takes nothing from them.
                runner.log(job.id, "${hosts.first().instance}: pushing newly fetched corpora to s3://$bucket")
                runCatching { hosts.first().ssh.run(s3Sync("~/corpus", "s3://$bucket/corpus"), S3_PUSH_TIMEOUT_SEC) }
                    .onFailure { runner.log(job.id, "corpus push failed, the next job fetches upstream: ${it.message}") }
            }
            store.setup(job.id, primary)
            val shards = shards(job.id, hosts.size)
            runner.log(job.id, "running on ${hosts.size} instances: " + hosts.mapIndexed { i, h -> "${h.instance} (${shards[i].size} cases)" }.joinToString())
            val outcomes = parallelOn(hosts.indices.toList()) { i -> runner.dispatch(checkNotNull(store.job(job.id)), dir, hosts[i], shards[i]) }
            when {
                Dispatched.CANCELLED in outcomes -> runner.cancel(job.id)
                Dispatched.YIELDED in outcomes -> {
                    store.requeue(job.id)
                    runner.log(job.id, "job paused between commands; its instances are terminated and launched again on resume")
                }
                else -> {
                    store.finish(job.id, Status.DONE)
                    runner.log(job.id, "job finished")
                }
            }
        } catch (e: Exception) {
            if (store.cancelRequested(claimed.id)) {
                runner.cancel(claimed.id)
            } else {
                runner.log(claimed.id, "job failed: ${e.message}")
                store.cancelRemaining(claimed.id)
                store.finish(claimed.id, Status.FAILED, e.message ?: e.toString())
            }
        } finally {
            runCatching { cli.terminate(instances) }.onFailure { runner.log(claimed.id, "could not terminate $instances: ${it.message}") }
            Files.deleteIfExists(config.jobDir(claimed.id).resolve(INSTANCES_FILE))
            if (instances.isNotEmpty()) runner.log(claimed.id, "terminated $instances")
        }
    }

    /** Launch [machines] instances for [job], recording each in [instances] at once so that it is terminated whatever
     *  happens next. */
    private fun launch(job: Job, machines: Int, instances: MutableList<String>): List<AwsHost> {
        runCatching { cli.admitThisMachine() }
        val userData = Files.createTempFile("klause-lab-user-data", ".sh").toFile()
        try {
            userData.writeText(bootstrap())
            repeat(machines) { n ->
                val id = runner.withRetry(job.id, "launching an instance") { cli.launch(job.id, "klause-lab-${job.id}-$n", userData) }
                instances += id
            }
        } finally {
            userData.delete()
        }
        runner.log(job.id, "launched ${aws.instanceType} instances $instances")
        config.jobDir(job.id).resolve(INSTANCES_FILE).toFile().writeText(instances.joinToString("\n", postfix = "\n"))
        return instances.map { id ->
            runner.withRetry(job.id, "waiting for $id") { cli.awaitRunning(id) }
            val ip = runner.withRetry(job.id, "reading $id's address") { cli.publicIp(id).also { check(it != "None") { "no public address yet" } } }
            AwsHost(id, Ssh(ip, aws, knownHosts), aws.cores, config.solveJavaOpts) { store.cancelRequested(job.id) }
        }
    }

    /** Wait for [host]'s bootstrap, then clone the repository and build each of [shas] on it. */
    private fun setup(job: Job, host: AwsHost, shas: List<String>, log: File) {
        runner.withRetry(job.id, "waiting for ${host.instance} to boot", BOOT_WAIT) {
            host.ssh.run("test -f /var/lib/klause-ready", SSH_STEP_SEC)
        }
        runner.log(job.id, "${host.instance}: booted; building ${shas.joinToString { it.take(SHA_LOG) }}")
        val script = buildString {
            appendLine("set -euo pipefail; ${AwsHost.ENV}")
            appendLine("mkdir -p ~/work ~/job/cases ~/corpus")
            // The corpus comes down from the bucket while the build runs; a failed sync only means fetching upstream.
            aws.corpusBucket?.let { appendLine("(${s3Sync("s3://$it/corpus", "~/corpus")} || true) > ~/corpus-sync.log 2>&1 & sync=${'$'}!") }
            appendLine("[ -d ~/repo/.git ] || git clone -q --filter=blob:none --no-checkout ${quote(config.repoUrl)} ~/repo")
            appendLine("git -C ~/repo fetch -q origin ${shas.joinToString(" ")} || git -C ~/repo fetch -q origin")
            for (sha in shas) {
                val worktree = host.worktree(sha)
                appendLine("[ -d $worktree ] || git -C ~/repo worktree add -q --detach $worktree $sha")
                appendLine("(cd $worktree && ./gradlew :klause-cli:installJvmDist :klause-bench:installDist -q --max-workers=${aws.cores})")
            }
            if (aws.corpusBucket != null) appendLine("wait ${'$'}sync || true")
        }
        val out = runner.withRetry(job.id, "building on ${host.instance}", config.buildRetry) { host.ssh.run(script, BUILD_TIMEOUT_SEC) }
        log.appendText("[${host.instance}] $out\n")
        runner.log(job.id, "${host.instance}: built")
    }

    private fun shards(jobId: Long, n: Int): List<Set<Int>> = shards(store.cases(jobId), n)

    /** `aws s3 sync` with the instance role, leaving out the bench's staging copies of compressed instances. */
    private fun s3Sync(from: String, to: String) =
        "aws s3 sync $from $to --region ${aws.region} --only-show-errors --exclude '.plain/*' --exclude '*/.plain/*'"

    private fun <T, R> parallelOn(items: List<T>, block: (T) -> R): List<R> {
        if (items.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(items.size)
        try {
            return items.map { item -> pool.submit<R> { block(item) } }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** What a fresh instance runs at first boot: its power-off deadline, then a JDK, MiniZinc and the tools a build and
     *  the bench need, then the mark [setup] waits for. */
    private fun bootstrap(): String = """
        #!/bin/bash
        set -euxo pipefail
        shutdown -h +${aws.maxHours * MINUTES_PER_HOUR}
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -q
        apt-get install -yq git zstd unzip curl xz-utils
        mkdir -p /opt/jdk /opt/minizinc
        curl -fsSL https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse | tar -xz -C /opt/jdk --strip-components=1
        curl -fsSL https://github.com/MiniZinc/MiniZincIDE/releases/download/${aws.minizinc}/MiniZincIDE-${aws.minizinc}-bundle-linux-x86_64.tgz | tar -xz -C /opt/minizinc --strip-components=1
        ln -sf /opt/minizinc/bin/minizinc /usr/local/bin/minizinc
        curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscli.zip
        unzip -q /tmp/awscli.zip -d /tmp && /tmp/aws/install
        touch /var/lib/klause-ready
    """.trimIndent() + "\n"

    companion object {
        /** The job's running instances, one id a line, while it has them: what the pages show. */
        const val INSTANCES_FILE = "aws-instances"

        /** [cases] split by problem over [n] instances: a problem's cases, every arm, seed and repeat of it, all on
         *  one, and the problems dealt out in plan order so each instance gets a share of every selection. */
        internal fun shards(cases: List<CaseResult>, n: Int): List<Set<Int>> {
            val order = cases.map { it.problem.suite to it.problem.problem }.distinct().withIndex().associate { (i, key) -> key to i }
            return (0 until n).map { shard ->
                cases.filter { order.getValue(it.problem.suite to it.problem.problem) % n == shard }.map { it.index }.toSet()
            }
        }

        private const val POLL_MS = 10_000L
        private const val SSH_STEP_SEC = 60L
        private const val SHA_LOG = 9
        private const val S3_PUSH_TIMEOUT_SEC = 3600L
        private const val BUILD_TIMEOUT_SEC = 3600L
        private const val MINUTES_PER_HOUR = 60
        /** A fresh instance takes a few minutes to boot and install: tries every 20 s, up to about 15 minutes. */
        private val BOOT_WAIT = Backoff(attempts = 45, baseMs = 20_000, maxMs = 20_000)
    }
}
