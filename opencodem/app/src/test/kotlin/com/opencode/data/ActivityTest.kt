package com.opencode.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTest {
    @Test
    fun newerStreamEventBlocksStaleReconcile() {
        assertFalse(canReconcileActivity(false, 1, 2))
        assertTrue(canReconcileActivity(false, 2, 2))
        assertFalse(canReconcileActivity(true, 2, 2))
    }

    @Test
    fun staleMeansIdleForTwentySeconds() {
        assertFalse(staleActivity(1_000, 999, false))
        assertTrue(staleActivity(21_000, 1_000, false))
        assertFalse(staleActivity(21_000, 1_000, true))
        assertTrue(staleActivity(21_000, null, false))
    }
}
