package com.opencode.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun newerVersionWins() {
        assertTrue(Updater.isNewerVersion("1.0.8", "1.0.7"))
        assertTrue(Updater.isNewerVersion("v2.0", "1.9.9"))
        assertTrue(Updater.isNewerVersion("1.0.10", "1.0.9"))
    }

    @Test
    fun sameOrOlderLoses() {
        assertFalse(Updater.isNewerVersion("1.0.7", "1.0.7"))
        assertFalse(Updater.isNewerVersion("1.0.7", "1.0.8"))
        assertFalse(Updater.isNewerVersion("1.0", "1.0.0"))
    }

    @Test
    fun signerMustBeInTrustedSet() {
        val key = byteArrayOf(1, 2, 3)
        val other = byteArrayOf(4, 5, 6)
        assertTrue(Updater.sameSigners(listOf(key), listOf(other, key)))
        assertFalse(Updater.sameSigners(listOf(other), listOf(key)))
        assertFalse(Updater.sameSigners(emptyList(), listOf(key)))
    }
}
