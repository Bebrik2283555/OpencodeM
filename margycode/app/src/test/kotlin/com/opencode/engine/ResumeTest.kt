package com.opencode.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResumeTest {
    @Test
    fun freshDownloadStartsAtZero() {
        assertEquals(0L, resumeOffset(200, null, 50))
    }

    @Test
    fun matchingRangeResumesAtHave() {
        assertEquals(50L, resumeOffset(206, "bytes 50-99/100", 50))
    }

    @Test
    fun wrongRangeIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            resumeOffset(206, "bytes 10-99/100", 50)
        }
    }

    @Test
    fun otherStatusIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            resumeOffset(500, null, 0)
        }
    }
}
