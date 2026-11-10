package com.opencode.data

import org.json.JSONArray
import org.json.JSONObject

fun multiple(field: JSONObject) = field.str("type") in setOf("multiselect", "array")

fun formDefaults(fields: List<JSONObject>, draft: JSONObject?): Map<String, Any> = buildMap {
    for (field in fields) {
        val key = field.getString("key")
        val value = draft?.opt(key) ?: field.opt("default") ?: continue
        if (value != JSONObject.NULL) put(key, if (value is JSONArray) value.strings() else value)
    }
}

fun fieldVisible(field: JSONObject, values: Map<String, Any>): Boolean = !field.optBoolean("hidden") && fieldActive(field, values)

private fun fieldActive(field: JSONObject, values: Map<String, Any>): Boolean =
    field.optJSONArray("when").objects().all { condition ->
        val actual = values[condition.getString("key")] ?: return@all false
        val expected = condition.get("value")
        val equal = when {
            actual is List<*> -> expected in actual
            expected is Number -> actual.toString().toDoubleOrNull() == expected.toDouble()
            else -> actual == expected
        }
        if (condition.str("op") == "neq") !equal else equal
    }

/** Keep JSON types intact: OpenCode rejects a string in a multiselect, numeric or boolean field. */
fun formAnswer(fields: List<JSONObject>, values: Map<String, Any>): JSONObject = JSONObject().apply {
    val active = mutableMapOf<String, Any>()
    for (field in fields) {
        if (!fieldActive(field, active)) continue
        val key = field.getString("key")
        val value = values[key] ?: continue
        val encoded = when {
            multiple(field) -> JSONArray(value as? List<*> ?: listOf(value.toString()))
            field.str("type") == "integer" -> value.toString().toLongOrNull() ?: error("${field.str("title") ?: key}: expected integer")
            field.str("type") == "number" -> value.toString().toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: error("${field.str("title") ?: key}: expected number")
            else -> value
        }
        put(key, encoded)
        active[key] = if (encoded is JSONArray) encoded.strings() else encoded
    }
}
