package com.opencode.engine

import com.opencode.tr

/** Never append a response that starts at a different byte than the existing partial file. */
fun resumeOffset(code: Int, range: String?, have: Long): Long = when (code) {
    200 -> 0
    206 -> {
        val start = range?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
        check(start == have) { "Server returned an unexpected download range" }
        have
    }
    else -> error(tr("Загрузка: HTTP $code", "Download: HTTP $code"))
}
