package dev.mtmux.core

import com.jcraft.jsch.ChannelSftp
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class FileProblem { INVALID_PATH, UNSUPPORTED, NOT_FILE, TOO_LARGE, OUTSIDE_ROOT, UNAVAILABLE, TIMEOUT, ENCODING, NOT_FOUND, PERMISSION }
class RemoteFileException(val problem: FileProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/** One owned, serialized SFTP channel. Cancelling this handle never cancels its SSH session. */
class RemoteFiles internal constructor(private val channel: ChannelSftp, private val released: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val releasedOnce = AtomicBoolean()
    @Volatile private var running = false
    private val lock = Any()
    private val lifecycle = Any()
    private val progress = AtomicLong()
    @Volatile private var expired = false
    private fun <T> operation(block: () -> T): T = synchronized(lock) {
        synchronized(lifecycle) {
            if (closed.get()) throw RemoteFileException(if (expired) FileProblem.TIMEOUT else FileProblem.UNAVAILABLE)
            running=true
        }
        val start = System.nanoTime()
        progress.set(start)
        val alarm = timer.scheduleAtFixedRate({
            val now = System.nanoTime()
            if (now - start > TimeUnit.SECONDS.toNanos(30) || now - progress.get() > TimeUnit.SECONDS.toNanos(10)) {
                expired = true; close()
            }
        }, 1, 1, TimeUnit.SECONDS)
        try { block() }
        catch (e: RemoteFileException) { throw e }
        catch (e: Exception) {
            if(e.cause is RemoteFileException) throw e.cause as RemoteFileException
            throw RemoteFileException(when {
            expired -> FileProblem.TIMEOUT
            e is com.jcraft.jsch.SftpException && e.id==ChannelSftp.SSH_FX_NO_SUCH_FILE -> FileProblem.NOT_FOUND
            e is com.jcraft.jsch.SftpException && e.id==ChannelSftp.SSH_FX_PERMISSION_DENIED -> FileProblem.PERMISSION
            else -> FileProblem.UNAVAILABLE
        }, e) }
        finally {alarm.cancel(false);val shouldRelease=synchronized(lifecycle) {running=false;closed.get()};if(shouldRelease) release()}
    }
    fun connect() = operation { channel.connect(10_000) }
    fun home(): String = operation { channel.home }
    fun canonical(path: String): String = operation { channel.realpath(RemoteFilePaths.valid(path)) }
    fun download(path: String, destination: File, limit: Long, onFirstByte: () -> Unit = {}): Long = operation {
        val literal=RemoteFilePaths.valid(path).replace("\\","\\\\").replace("*","\\*").replace("?","\\?").replace("[","\\[")
        val attributes = channel.stat(literal)
        if (!attributes.isReg) throw RemoteFileException(FileProblem.NOT_FILE)
        if (attributes.size > limit) throw RemoteFileException(FileProblem.TOO_LARGE)
        var count = 0L
        try {
            destination.outputStream().use { output ->
                // Use the sink API: closing a get() InputStream after channel cancellation
                // can send a late SFTP CLOSE on a dead channel and tear down the shared SSH.
                channel.get(literal,object: java.io.OutputStream() {
                    override fun write(value:Int) {write(byteArrayOf(value.toByte()),0,1)}
                    override fun write(buffer:ByteArray,offset:Int,n:Int) {
                    if (closed.get()) throw RemoteFileException(if (expired) FileProblem.TIMEOUT else FileProblem.UNAVAILABLE)
                    if(count==0L && n>0) onFirstByte()
                    if (closed.get()) throw RemoteFileException(if (expired) FileProblem.TIMEOUT else FileProblem.UNAVAILABLE)
                    count += n
                    if (count > limit) throw RemoteFileException(FileProblem.TOO_LARGE)
                    output.write(buffer, offset, n)
                    progress.set(System.nanoTime())
                    }
                })
            }
            count
        } catch (e: Exception) { destination.delete(); close(); throw e }
    }
    override fun close() {
        val active=synchronized(lifecycle) {if(!closed.compareAndSet(false,true)) return;running}
            // Let an active sink observe cancellation and unwind before detaching the channel.
            // A bounded fallback still interrupts a stalled subsystem/read without closing SSH.
            if(active) timer.schedule({release()},250,TimeUnit.MILLISECONDS) else release()
    }
    private fun release() {if(releasedOnce.compareAndSet(false,true)) try {channel.disconnect()} finally {released()}}
    companion object {
        private val timer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "preview-timeout").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true)
        }
    }
}

/** Linux remote paths; never interpreted as shell commands or local filesystem paths. */
object RemoteFilePaths {
    enum class Kind { MARKDOWN, HTML, IMAGE, SVG, CSS }
    fun valid(path: String): String {
        if (path.isBlank() || path.length > 8192 || path.any { it.isISOControl() }) throw RemoteFileException(FileProblem.INVALID_PATH)
        return path
    }
    fun kind(path: String): Kind = when (path.substringAfterLast('.').lowercase(java.util.Locale.ROOT)) {
        "md", "markdown" -> Kind.MARKDOWN
        "html", "htm" -> Kind.HTML
        "png", "jpg", "jpeg", "webp" -> Kind.IMAGE
        "svg" -> Kind.SVG
        "css" -> Kind.CSS
        else -> throw RemoteFileException(FileProblem.UNSUPPORTED)
    }
    fun resolve(path: String, base: String, home: String): String {
        valid(path)
        val expanded = when {
            path == "~" -> home
            path.startsWith("~/") -> home + path.drop(1)
            path.startsWith('/') -> path
            else -> "$base/$path"
        }
        if (!expanded.startsWith('/')) throw RemoteFileException(FileProblem.INVALID_PATH)
        val parts = mutableListOf<String>()
        expanded.split('/').forEach { when(it) { "", "." -> Unit; ".." -> if(parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts.add(it) } }
        return "/" + parts.joinToString("/")
    }
    fun inside(path: String, root: String) = path == root || path.startsWith(root.trimEnd('/') + "/")
    fun withoutLine(path: String) = path.replace(Regex("(?i)(\\.(?:md|markdown|html?|png|jpe?g|webp|svg)):\\d+(?::\\d+)?$"), "$1")
}
