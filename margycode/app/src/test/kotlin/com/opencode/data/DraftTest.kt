package com.opencode.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DraftTest {
    @Test
    fun roundTripKeepsComposerState() {
        val d = Draft(JSONObject("""{"text":"hi","files":["a"],"skills":["s"],"agents":["g"],"folder":"/x"}"""))
        assertEquals("hi", d.text)
        assertEquals(listOf("a"), d.files.toList())
        assertEquals(listOf("s"), d.skills.toList())
        assertEquals(listOf("g"), d.agents.toList())
        assertEquals("/x", d.folder)

        val again = Draft(d.json())
        assertEquals(d.text, again.text)
        assertEquals(d.files.toList(), again.files.toList())
        assertEquals(d.skills.toList(), again.skills.toList())
        assertEquals(d.agents.toList(), again.agents.toList())
        assertEquals(d.folder, again.folder)
    }
}
