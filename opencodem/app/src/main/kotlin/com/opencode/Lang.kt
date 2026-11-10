package com.opencode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** The app supports system language, Russian, or English override. */
object Lang {
    var mode: String by mutableStateOf("auto")

    val ru: Boolean
        get() = when (mode) {
            "ru" -> true
            "en" -> false
            else -> Locale.getDefault().language in setOf("ru", "uk", "be", "kk")
        }
}

fun tr(ru: String, en: String) = if (Lang.ru) ru else en
