package com.eignex.lab

import kotlin.test.Test
import kotlin.test.assertEquals

class DiskGuardTest {
    @Test
    fun `a disk below the floor holds the queue until space returns`() {
        val readings = ArrayDeque(listOf(5L, 9L, 10L, 20L))
        val guard = DiskGuard(minFreeBytes = 10L) { readings.removeFirst() }

        val allowed = List(4) { guard.allowsWork() }

        assertEquals(listOf(false, false, true, true), allowed)
    }
}
