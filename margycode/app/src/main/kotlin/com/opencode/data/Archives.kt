package com.opencode.data

import com.opencode.tr
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

fun archiveTarget(root: File, entry: String): File {
    val name = entry.replace('\\', '/')
    require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name) && name.split('/').none { it == ".." }) { tr("Небезопасный путь в архиве", "Unsafe archive path") }
    val file = File(root, name).canonicalFile
    require(file.toPath().startsWith(root.canonicalFile.toPath()) && file != root.canonicalFile) { tr("Небезопасный путь в архиве", "Unsafe archive path") }
    return file
}

fun unpack(input: InputStream, root: File, maxBytes: Long = 256L * 1024 * 1024): List<File> {
    val files = mutableListOf<File>()
    val paths = mutableSetOf<String>()
    var total = 0L
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val file = archiveTarget(root, entry.name)
            require(paths.add(file.path) && paths.size <= 10000) { tr("Повторяющийся или слишком большой архив", "Duplicate or oversized archive") }
            if (entry.isDirectory) { file.mkdirs(); continue }
            file.parentFile!!.mkdirs()
            file.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = zip.read(buffer); if (count < 0) break
                    total += count; require(total <= maxBytes) { tr("Архив превышает лимит размера", "Archive exceeds size limit") }
                    out.write(buffer, 0, count)
                }
            }
            files += file
        }
    }
    return files
}
