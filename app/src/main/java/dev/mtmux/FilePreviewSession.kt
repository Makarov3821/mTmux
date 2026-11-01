package dev.mtmux

import android.content.Context
import android.net.Uri
import dev.mtmux.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class PreviewRequest(val target: String, val token: String, val connection: SshClient,
    val binding: PaneBinding?, val tmux: String, val server: String, val host: String? = null)
internal data class PreviewDocument(val path: String, val root: String, val file: File,
    val kind: RemoteFilePaths.Kind, val text: String?, val url: String)

/** Private bounded cache for one visible preview. All transfers are serialized by RemoteFiles. */
internal class FilePreviewSession(context: Context, private val request: PreviewRequest) : AutoCloseable {
    val id=UUID.randomUUID().toString()
    val host=request.host
    private val trace=id.hashCode()
    private val access=request.connection.remoteFiles()
    private val directory=File(context.cacheDir,"preview/$id").apply { active.add(absolutePath);mkdirs() }
    private val closed=AtomicBoolean()
    private var connected=false
    private var home=""
    private val cached=linkedMapOf<String,File>()
    private var total=0L
    private var resources=0
    private val accounting=Any()
    init {DebugLog.event(DebugLog.Event.PREVIEW_OPEN,trace)}
    @Synchronized fun base(): String {
        connect()
        return request.binding?.let {request.connection.paneDirectory(it,request.tmux)} ?: home
    }
    private fun connect() {
        checkOpen()
        if(!connected) {
            val start=android.os.SystemClock.elapsedRealtime()
            access.connect();home=access.home();connected=true
            DebugLog.event(DebugLog.Event.PREVIEW_CHANNEL,trace,(android.os.SystemClock.elapsedRealtime()-start).toInt())
        }
    }
    private fun checkOpen() { if(closed.get()) throw RemoteFileException(FileProblem.UNAVAILABLE) }
    @Synchronized fun open(target: String, base: String): PreviewDocument {
        connect()
        val path=access.canonical(RemoteFilePaths.resolve(RemoteFilePaths.withoutLine(target),base,home))
        DebugLog.event(DebugLog.Event.PREVIEW_RESOLVED,trace)
        val kind=RemoteFilePaths.kind(path)
        if(kind==RemoteFilePaths.Kind.CSS) throw RemoteFileException(FileProblem.UNSUPPORTED)
        val root=access.canonical(base).takeIf {RemoteFilePaths.inside(path,it)} ?: path.substringBeforeLast('/').ifEmpty {"/"}
        val file=fetch(path,kind)
        val text=if(kind!=RemoteFilePaths.Kind.IMAGE) decode(file) else null
        val prefix="/$id/${UUID.randomUUID()}/remote"
        val url=Uri.Builder().scheme("https").authority(HOST).encodedPath(prefix).apply {path.split('/').filter {it.isNotEmpty()}.forEach {appendPath(it)}}.build().toString()
        return PreviewDocument(path,root,file,kind,text,url)
    }
    @Synchronized fun resource(document: PreviewDocument, path: String): Pair<File,RemoteFilePaths.Kind> {
        connect()
        val normalized=RemoteFilePaths.resolve(path,document.path.substringBeforeLast('/'),home)
        val canonical=access.canonical(normalized)
        if(!RemoteFilePaths.inside(canonical,document.root)) throw RemoteFileException(FileProblem.OUTSIDE_ROOT)
        val kind=RemoteFilePaths.kind(canonical)
        if(kind!=RemoteFilePaths.Kind.IMAGE && kind!=RemoteFilePaths.Kind.CSS) throw RemoteFileException(FileProblem.UNSUPPORTED)
        return fetch(canonical,kind) to kind
    }
    private fun fetch(path: String, kind: RemoteFilePaths.Kind): File {
        checkOpen()
        cached[path]?.takeIf {it.isFile}?.let {return it}
        if(resources>=32) throw RemoteFileException(FileProblem.TOO_LARGE)
        val maximum=when(kind) {
            RemoteFilePaths.Kind.IMAGE -> 20L*1024*1024
            RemoteFilePaths.Kind.CSS -> 512L*1024
            else -> 2L*1024*1024
        }.coerceAtMost(50L*1024*1024-total)
        if(maximum<=0) throw RemoteFileException(FileProblem.TOO_LARGE)
        if(globalBytes.addAndGet(maximum)>100L*1024*1024) {
            globalBytes.addAndGet(-maximum);throw RemoteFileException(FileProblem.TOO_LARGE)
        }
        val file=File(directory,UUID.randomUUID().toString())
        try {
            val size=access.download(path,file,maximum) {DebugLog.event(DebugLog.Event.PREVIEW_FIRST_BYTE,trace)}
            synchronized(accounting) {
                checkOpen();total+=size;resources++;cached[path]=file
                globalBytes.addAndGet(size-maximum)
            }
            DebugLog.event(DebugLog.Event.PREVIEW_READ,trace,size.toInt())
            return file
        } catch(e:Exception) {globalBytes.addAndGet(-maximum);file.delete();throw e}
    }
    override fun close() {
        if(closed.compareAndSet(false,true)) {try {access.close()} finally {
            synchronized(accounting) {globalBytes.addAndGet(-total);total=0}
            directory.deleteRecursively();active.remove(directory.absolutePath);DebugLog.event(DebugLog.Event.PREVIEW_CLOSED,trace)
        }}
    }
    companion object {
        const val HOST="preview.mtmux.invalid"
        private val active=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val globalBytes=java.util.concurrent.atomic.AtomicLong()
        fun cleanup(context:Context) {
            File(context.cacheDir,"preview").listFiles()?.filter {it.absolutePath !in active}?.forEach {it.deleteRecursively()}
        }
        fun decode(file:File):String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(file.readBytes())).toString().removePrefix("\uFEFF")
        } catch(e:Exception) {throw RemoteFileException(FileProblem.ENCODING,e)}
    }
}
