package com.opencode.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

class Draft(raw: JSONObject = JSONObject()) {
    var text by mutableStateOf(raw.str("text").orEmpty())
    val files = mutableStateListOf<String>().apply { addAll(raw.optJSONArray("files").strings()) }
    val skills = mutableStateListOf<String>().apply { addAll(raw.optJSONArray("skills").strings()) }
    val agents = mutableStateListOf<String>().apply { addAll(raw.optJSONArray("agents").strings()) }
    var folder by mutableStateOf(raw.str("folder").orEmpty())
    fun json() = jsonOf("text" to text, "files" to JSONArray(files), "skills" to JSONArray(skills), "agents" to JSONArray(agents), "folder" to folder)
}
