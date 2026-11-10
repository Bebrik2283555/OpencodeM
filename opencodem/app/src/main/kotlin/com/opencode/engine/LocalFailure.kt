package com.opencode.engine

import com.opencode.tr

fun localFailure(log: String): String? {
    val text = log.lowercase()
    return when {
        "invalid ggml type" in text || "unknown tensor type" in text ->
            tr("Формат тензоров не поддерживается этим движком.", "This engine does not support the tensor format.")
        "failed to allocate" in text || "out of memory" in text || "cannot allocate memory" in text ->
            tr("Не хватает памяти. Уменьшите контекст или выберите модель поменьше.", "Not enough memory. Reduce the context or choose a smaller model.")
        "failed to load model" in text || "error loading model" in text ->
            tr("Не удалось загрузить модель. Подробности в llama.log ниже.", "Could not load the model. See llama.log below.")
        else -> null
    }
}
