package com.opencode.engine

import com.opencode.tr
import org.json.JSONObject

fun localProgress(slot: JSONObject): String {
    val decoded = slot.optJSONArray("next_token")?.optJSONObject(0)?.optInt("n_decoded") ?: 0
    if (decoded > 0) return tr("Генерирует: $decoded токенов", "Generating: $decoded tokens")
    val done = slot.optInt("n_prompt_tokens_processed") + slot.optInt("n_prompt_tokens_cache")
    val total = slot.optInt("n_prompt_tokens_total")
    return if (total > 0) tr("Читает запрос: ${done.coerceAtMost(total)} из $total токенов", "Reading prompt: ${done.coerceAtMost(total)} of $total tokens")
    else tr("Читает запрос: обработано $done токенов", "Reading prompt: $done tokens processed")
}
