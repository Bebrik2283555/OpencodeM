package com.opencode.engine

import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Last [max] bytes of a file, so a big log never has to be read whole. */
fun tailOf(file: File, max: Int): String = runCatching {
    RandomAccessFile(file, "r").use { f ->
        val size = minOf(f.length(), max.toLong()).toInt()
        f.seek(f.length() - size)
        val bytes = ByteArray(size)
        f.readFully(bytes)
        bytes.toString(Charsets.UTF_8)
    }
}.getOrDefault("")

/** File capture avoids hanging on stdout held open by descendants after the parent exits. */
suspend fun processOutput(builder: ProcessBuilder, log: File, timeoutMs: Long): String = withContext(Dispatchers.IO) {
    val process = builder.redirectErrorStream(true).redirectOutput(log).start()
    try {
        val finished = runInterruptible { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
        val text = RandomAccessFile(log, "r").use { file ->
            val size = minOf(file.length(), 20_000L).toInt()
            file.seek(file.length() - size)
            val bytes = ByteArray(size)
            file.readFully(bytes)
            bytes.toString(Charsets.UTF_8).trim()
        }
        if (!finished) error(tr("Команда не уложилась в ${timeoutMs / 1000} секунд", "Command timed out after ${timeoutMs / 1000} seconds") + "\n$text")
        text + if (process.exitValue() != 0) "\nExit code: ${process.exitValue()}" else ""
    } finally {
        process.destroyForcibly()
        process.waitFor(1000, TimeUnit.MILLISECONDS)
        process.inputStream.close()
        process.errorStream.close()
        process.outputStream.close()
        // Windows may release redirected file handles just after process exit.
        withContext(NonCancellable) {
            repeat(20) {
                if (!log.exists() || log.delete()) return@withContext
                delay(50)
            }
        }
    }
}
