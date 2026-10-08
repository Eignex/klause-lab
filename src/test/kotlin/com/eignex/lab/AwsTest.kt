package com.eignex.lab

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AwsTest {
    @Test
    fun `aws settings load with their defaults, and there are none without the file`() {
        val dir = Files.createTempDirectory("aws")
        val file = dir.resolve("aws.properties")

        assertNull(AwsConfig.load(file))
        file.toFile().writeText("region=eu-north-1\nkeyName=klause-lab\nkeyFile=/k.pem\nsecurityGroup=sg-1\n")
        val aws = AwsConfig.load(file)!!

        assertEquals(listOf("c7i.2xlarge", "4", "4", "klause-lab"), listOf(aws.instanceType, "${aws.maxInstances}", "${aws.cores}", aws.profile))
    }

    @Test
    fun `a job is split by problem, every case of a problem on one instance`() {
        val cases = (0 until 12).map { i -> CaseResult(i, Status.QUEUED, Problem("s", "p${i / 2}"), if (i % 2 == 0) "a" else "b") }

        val shards = AwsWorker.shards(cases, 3)

        assertEquals(listOf(setOf(0, 1, 6, 7), setOf(2, 3, 8, 9), setOf(4, 5, 10, 11)), shards)
    }

    @Test
    fun `the lab runner and the AWS worker each take only their own host's jobs`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), description = "d")
        val onAws = store.create("aws", "main", emptyList(), experiment = spec.copy(host = Experiments.AWS_HOST), priority = 5)
        val onLab = store.create("lab", "main", emptyList(), experiment = spec)

        assertEquals(listOf(listOf(onLab), listOf(onAws)), listOf(store.queueOrder(), store.queueOrder(Experiments.AWS_HOST)))
        assertEquals(listOf(onLab, onAws), listOf(store.next()?.id, store.next(Experiments.AWS_HOST)?.id))
    }

    @Test
    fun `a queued job moves to AWS until it is planned`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val id = store.create("e", "main", emptyList(), experiment = ExperimentSpec("e", listOf(mapOf("suite" to "s")), description = "d"))

        val moved = store.setHost(id, Experiments.AWS_HOST, 2)
        val spec = store.job(id)?.experiment
        val planned = store.create("p", "main", listOf("true" to 10L), experiment = ExperimentSpec("p", listOf(mapOf("suite" to "s"))))

        assertEquals(listOf(true, Experiments.AWS_HOST, 2), listOf(moved, spec?.host, spec?.machines))
        assertEquals(false, store.setHost(planned, Experiments.AWS_HOST, null))
    }

    @Test
    fun `a job gets the smallest instance with the cores one of its cases needs, within the quota`() {
        val aws = AwsConfig("eu-north-1", "p", "c7i.2xlarge", 4, 32, "k", "/k.pem", "sg", null, 4, 24, "2.9.7", null)

        assertEquals(listOf("c7i.2xlarge", "c7i.4xlarge", "c7i.8xlarge"), aws.sizes.map { it.type })
        assertEquals(listOf("c7i.2xlarge", "c7i.2xlarge", "c7i.4xlarge", "c7i.8xlarge", null),
            listOf(1, 4, 5, 16, 17).map { aws.sizeFor(it)?.type })
    }
}
