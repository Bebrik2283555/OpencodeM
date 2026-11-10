package com.opencode.engine

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Keep
object NativePty {
    init { System.loadLibrary("margypty") }
    external fun open(shell: String, directory: String, env: Array<String>): IntArray
    external fun resize(fd: Int, cols: Int, rows: Int)
    external fun wait(pid: Int): Int
    external fun kill(pid: Int)
}

class TerminalSession(val title: String, shell: String, directory: String, env: Map<String, String>, private val scope: CoroutineScope) {
    val id = UUID.randomUUID().toString()
    var output by mutableStateOf("")
        private set
    val stream = MutableSharedFlow<String>(extraBufferCapacity = 128)
    var running by mutableStateOf(true)
        private set
    private val handle = NativePty.open(shell, directory, env.map { "${it.key}=${it.value}" }.toTypedArray())
    private val fd = ParcelFileDescriptor.adoptFd(handle[0])
    private val input = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).reader()
    private val writer = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.fileDescriptor))
    init {
        scope.launch(Dispatchers.IO) {
            try {
                val buffer = CharArray(4096)
                var lastFlush = System.currentTimeMillis()
                val pending = StringBuilder()
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    val chunk = String(buffer, 0, count)
                    pending.append(chunk)
                    stream.tryEmit(chunk)
                    val now = System.currentTimeMillis()
                    if (now - lastFlush >= 40 || !input.ready()) {
                        val flushed = pending.toString()
                        pending.setLength(0)
                        withContext(Dispatchers.Main) {
                            output = (output + flushed).takeLast(256000)
                        }
                        lastFlush = now
                    }
                }
                if (pending.isNotEmpty()) {
                    val flushed = pending.toString()
                    withContext(Dispatchers.Main) {
                        output = (output + flushed).takeLast(256000)
                    }
                }
            } catch (_: java.io.IOException) {
            } finally { input.close() }
        }
        scope.launch(Dispatchers.IO) {
            val exit = NativePty.wait(handle[1])
            withContext(Dispatchers.Main) {
                running = false
                val msg = "\r\n[exit $exit]\r\n"
                output += msg
                stream.tryEmit(msg)
            }
        }
    }
    fun write(text: String) { if (running && text.length <= 16384) scope.launch(Dispatchers.IO) { runCatching { writer.write(text.toByteArray()); writer.flush() } } }
    fun resize(cols: Int, rows: Int) { if (running) NativePty.resize(handle[0], cols, rows) }
    fun close() { if (running) NativePty.kill(handle[1]); runCatching { input.close() }; runCatching { writer.close() }; runCatching { fd.close() } }
}
