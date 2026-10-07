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

        assertEquals(listOf("c7i.2xlarge", "5", "4", "klause-lab"), listOf(aws.instanceType, "${aws.maxInstances}", "${aws.cores}", aws.profile))
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
}
