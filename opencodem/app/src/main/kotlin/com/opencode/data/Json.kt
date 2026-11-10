package com.opencode.data

import org.json.JSONArray
import org.json.JSONObject

/** optString gives "null" for a JSON null and "" for nothing; this gives null for both. */
fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

fun jsonOf(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
    for ((k, v) in pairs) if (v != null) put(k, v)
}
