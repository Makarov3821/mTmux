package dev.mtmux.core

import java.io.File

/** Two bounded UTF-8 files; callers only supply structured, non-content events. */
class DiagnosticJournal(private val directory: File, val limit: Int = 5 * 1024 * 1024) {
    private val current get() = File(directory, "current.log")
    private val previous get() = File(directory, "previous.log")
    @Synchronized fun append(line: String) {
        directory.mkdirs()
        val bytes = (line.take(2048).replace('\n', ' ').replace('\r', ' ') + "\n").toByteArray()
        require(bytes.size <= limit)
        if (current.length() + bytes.size > limit) {
            if (previous.exists()) check(previous.delete())
            if (current.exists()) check(current.renameTo(previous))
        }
        current.appendBytes(bytes)
    }
    @Synchronized fun snapshot(): String = listOf(previous, current).filter { it.exists() }
        .joinToString("") { file ->
            file.inputStream().use { input ->
                val buffer=ByteArray(minOf(file.length(),limit.toLong()).toInt())
                var size=0
                while(size<buffer.size) {
                    val n=input.read(buffer,size,buffer.size-size)
                    if(n<0) break
                    size+=n
                }
                String(buffer,0,size,Charsets.UTF_8)
            }
        }
    @Synchronized fun clear() {
        listOf(previous, current).forEach { if (it.exists()) check(it.delete()) }
    }
}
