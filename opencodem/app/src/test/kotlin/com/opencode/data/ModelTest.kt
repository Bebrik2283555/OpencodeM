package com.opencode.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test
    fun parsesApiShape() {
        val o = JSONObject(
            """{"id":"m","providerID":"p","name":"N","variants":[{"id":"high"}],
               "capabilities":{"input":["text","image"]},"limit":{"context":200000},
               "cost":[{"input":1.5,"output":2.5}],"time":{"released":123},"enabled":false}"""
        )
        val m = Model.from(o)
        assertEquals("p/m", m.ref)
        assertEquals("N", m.name)
        assertEquals(listOf("high"), m.variants)
        assertTrue(m.images)
        assertEquals(200_000L, m.context)
        assertEquals(1.5, m.costIn!!, 0.0)
        assertEquals(2.5, m.costOut!!, 0.0)
        assertEquals(123L, m.released)
        assertFalse(m.enabled)
    }

    @Test
    fun missingNameFallsBackToId() {
        val m = Model.from(JSONObject("""{"id":"m","providerID":"p"}"""))
        assertEquals("m", m.name)
        assertEquals(0L, m.context)
    }
}
