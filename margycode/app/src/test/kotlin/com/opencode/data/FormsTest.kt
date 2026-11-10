package com.opencode.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormsTest {
    private fun field(key: String, type: String) = JSONObject().put("key", key).put("type", type).put("title", key)

    @Test
    fun answersKeepJsonTypes() {
        val fields = listOf(field("flag", "boolean"), field("count", "integer"), field("ratio", "number"), field("tags", "multiselect"))
        val answer = formAnswer(fields, mapOf("flag" to true, "count" to "3", "ratio" to 1.5, "tags" to listOf("a", "b")))
        assertEquals(true, answer.getBoolean("flag"))
        assertEquals(3L, answer.getLong("count"))
        assertEquals(1.5, answer.getDouble("ratio"), 0.0)
        assertEquals("a", answer.getJSONArray("tags").getString(0))
        assertEquals("b", answer.getJSONArray("tags").getString(1))
    }

    @Test
    fun wrongIntegerIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            formAnswer(listOf(field("count", "integer")), mapOf("count" to "x"))
        }
    }

    @Test
    fun visibilityRespectsWhenCondition() {
        val f = JSONObject().put("key", "b").put("type", "boolean")
            .put("when", JSONArray().put(JSONObject().put("key", "a").put("value", "yes")))
        assertTrue(fieldVisible(f, mapOf("a" to "yes")))
        assertFalse(fieldVisible(f, mapOf("a" to "no")))
    }

    @Test
    fun scalarBecomesMultiselectArray() {
        val answer = formAnswer(listOf(field("tags", "multiselect")), mapOf("tags" to "a"))
        assertEquals(1, answer.getJSONArray("tags").length())
        assertEquals("a", answer.getJSONArray("tags").getString(0))
    }

    @Test
    fun invalidNumberIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            formAnswer(listOf(field("ratio", "number")), mapOf("ratio" to "x"))
        }
    }

    @Test
    fun neqConditionHidesWhenEqual() {
        val f = JSONObject().put("key", "b").put("type", "boolean")
            .put("when", JSONArray().put(JSONObject().put("key", "a").put("value", "yes").put("op", "neq")))
        assertFalse(fieldVisible(f, mapOf("a" to "yes")))
        assertTrue(fieldVisible(f, mapOf("a" to "no")))
    }

    @Test
    fun hiddenFieldIsNotVisible() {
        val f = JSONObject().put("key", "b").put("type", "boolean").put("hidden", true)
        assertFalse(fieldVisible(f, emptyMap()))
    }
}
