package com.opencode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchivesTest {
    private fun root() = Files.createTempDirectory("opencode-archive").toFile()

    @Test
    fun normalPathStaysUnderRoot() {
        val dir = root()
        assertEquals(File(dir, "a/b.txt").canonicalFile, archiveTarget(dir, "a/b.txt"))
    }

    @Test
    fun traversalIsRejected() {
        val dir = root()
        for (bad in listOf("../x", "a/../../b", "/abs", "C:/x")) {
            assertThrows(IllegalArgumentException::class.java) { archiveTarget(dir, bad) }
        }
    }

    @Test
    fun unpackRejectsZipSlipAndSizeLimit() {
        val dir = root()
        fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                for ((name, bytes) in entries) {
                    z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
                }
            }
            return out.toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) {
            unpack(ByteArrayInputStream(zip(listOf("../evil" to "x".toByteArray()))), dir)
        }
        assertThrows(IllegalArgumentException::class.java) {
            unpack(ByteArrayInputStream(zip(listOf("big.bin" to ByteArray(64)))), dir, maxBytes = 10)
        }
    }
}
