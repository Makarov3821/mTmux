package dev.mtmux

import android.content.Context
import android.net.Uri
import dev.mtmux.core.*
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class PreviewRequest(val target:String,val token:String,val connection:SshClient,
    val binding:PaneBinding?,val tmux:String,val server:String,val host:String?=null)
internal data class PreviewDocument(val path:String,val root:String,val file:File,
    val kind:RemoteFilePaths.Kind,val text:String?,val url:String)
internal data class PreviewResource(val stream:InputStream,val kind:RemoteFilePaths.Kind,val size:Long,val mime:String?=null)

/** Cancels only the resource's SFTP channel, never the shared SSH/terminal. */
internal class PreviewCancellation {
    private val cancelled=AtomicBoolean()
    private val channel=AtomicReference<RemoteFiles?>()
    fun attach(value:RemoteFiles) {channel.set(value);if(cancelled.get()) value.close()}
    fun detach() {channel.set(null)}
    fun cancel() {cancelled.set(true);channel.getAndSet(null)?.close()}
    fun check() {if(cancelled.get()) throw RemoteFileException(FileProblem.CANCELLED)}
    val isCancelled get()=cancelled.get()
}

/** A bounded LRU disk cache for this preview, shared across its document/link history. */
internal class FilePreviewSession(context:Context,private val request:PreviewRequest,
    val limits:PreviewLimits=savedPreviewLimits(context)) : AutoCloseable {
    val id=UUID.randomUUID().toString()
    val host=request.host
    private val trace=id.hashCode()
    private val primary=request.connection.remoteFiles()
    private val resourceChannel=AtomicReference<RemoteFiles?>()
    private val directory=File(context.cacheDir,"preview/$id").apply {active.add(absolutePath);mkdirs()}
    private val closed=AtomicBoolean()
    private val transfers=Any()
    private val aliases=mutableMapOf<String,String>()
    private val cache=PreviewDiskCache(limits.cacheBytes) {path ->aliases.entries.removeAll {it.value==path}}
    private var connected=false
    private var home=""
    init {DebugLog.event(DebugLog.Event.PREVIEW_OPEN,trace)}
    fun base():String=synchronized(transfers) {
        connect()
        request.binding?.let {request.connection.paneDirectory(it,request.tmux)} ?: home
    }
    private fun connect() {
        checkOpen()
        if(!connected) {
            val start=android.os.SystemClock.elapsedRealtime()
            primary.connect();home=primary.home();connected=true
            DebugLog.event(DebugLog.Event.PREVIEW_CHANNEL,trace,(android.os.SystemClock.elapsedRealtime()-start).toInt())
        }
    }
    private fun checkOpen() {if(closed.get()) throw RemoteFileException(FileProblem.UNAVAILABLE)}
    fun resolve(target:String,base:String):String=synchronized(transfers) {
        connect();RemoteFilePaths.resolve(RemoteFilePaths.withoutLine(target),base,home)
    }
    fun open(target:String,base:String):PreviewDocument=synchronized(transfers) {
        connect();cache.pin(null)
        val normalized=RemoteFilePaths.resolve(RemoteFilePaths.withoutLine(target),base,home)
        val path=aliases[normalized]?.takeIf {cache.get(it)!=null} ?: primary.canonical(normalized).also {aliases[normalized]=it}
        DebugLog.event(DebugLog.Event.PREVIEW_RESOLVED,trace)
        val kind=RemoteFilePaths.kind(path)
        if(kind==RemoteFilePaths.Kind.CSS) throw RemoteFileException(FileProblem.UNSUPPORTED)
        val root=primary.canonical(base).takeIf {RemoteFilePaths.inside(path,it)} ?: path.substringBeforeLast('/').ifEmpty {"/"}
        val file=fetch(path,primary)
        val text=if(kind in setOf(RemoteFilePaths.Kind.MARKDOWN,RemoteFilePaths.Kind.HTML)) decode(file) else null
        if(kind in setOf(RemoteFilePaths.Kind.IMAGE,RemoteFilePaths.Kind.SVG)) cache.pin(path)
        val prefix="/$id/${UUID.randomUUID()}/remote"
        val url=Uri.Builder().scheme("https").authority(HOST).encodedPath(prefix).apply {
            path.split('/').filter {it.isNotEmpty()}.forEach {appendPath(it)}
        }.build().toString()
        PreviewDocument(path,root,file,kind,text,url)
    }
    fun resource(document:PreviewDocument,path:String,cancellation:PreviewCancellation=PreviewCancellation(),image:Boolean?=null):PreviewResource=synchronized(transfers) {
        checkOpen();cancellation.check()
        val normalized=RemoteFilePaths.resolve(path,document.path.substringBeforeLast('/'),home)
        val known=aliases[normalized]
        if(known!=null && RemoteFilePaths.inside(known,document.root)) {
            cache.get(known)?.let {file ->
                val kind=resourceKind(known,image)
                return@synchronized resourceResponse(file,kind,image)
            }
        }
        var access=resourceChannel.get()
        try {
            if(access==null) {
                access=request.connection.remoteFiles();resourceChannel.set(access)
                cancellation.attach(access);access.connect()
            } else cancellation.attach(access)
            cancellation.check();checkOpen()
            val canonical=access.canonical(normalized)
            if(!RemoteFilePaths.inside(canonical,document.root)) throw RemoteFileException(FileProblem.OUTSIDE_ROOT)
            val kind=resourceKind(canonical,image)
            aliases[normalized]=canonical
            val file=fetch(canonical,access)
            cancellation.check()
            // Open before releasing the transfer lock: LRU eviction may unlink this file later.
            resourceResponse(file,kind,image)
        } catch(error:Exception) {
            access?.let {resourceChannel.compareAndSet(it,null);it.close()}
            if(cancellation.isCancelled) throw RemoteFileException(FileProblem.CANCELLED)
            throw error
        } finally {cancellation.detach()}
    }
    private fun resourceResponse(file:File,kind:RemoteFilePaths.Kind,image:Boolean?)=
        if(image==true && kind==RemoteFilePaths.Kind.IMAGE) PreviewThumbnails.open(file)
        else PreviewResource(file.inputStream(),kind,file.length(),if(kind==RemoteFilePaths.Kind.SVG) "image/svg+xml" else null)
    private fun resourceKind(path:String,image:Boolean?)=RemoteFilePaths.kind(path).also {
        if(it !in setOf(RemoteFilePaths.Kind.IMAGE,RemoteFilePaths.Kind.SVG,RemoteFilePaths.Kind.CSS))
            throw RemoteFileException(FileProblem.UNSUPPORTED)
        if(image!=null && image!=(it!=RemoteFilePaths.Kind.CSS)) throw RemoteFileException(FileProblem.UNSUPPORTED)
    }
    private fun fetch(path:String,access:RemoteFiles):File {
        checkOpen();cache.get(path)?.let {return it}
        val file=File(directory,UUID.randomUUID().toString())
        try {
            val size=access.download(path,file,limits.fileBytes,onSize={cache.reserve(it)}) {
                DebugLog.event(DebugLog.Event.PREVIEW_FIRST_BYTE,trace)
            }
            checkOpen();cache.put(path,file)
            DebugLog.event(DebugLog.Event.PREVIEW_READ,trace,size.toInt())
            return file
        } catch(error:Exception) {file.delete();throw error}
    }
    override fun close() {
        if(closed.compareAndSet(false,true)) {
            primary.close();resourceChannel.getAndSet(null)?.close()
            synchronized(transfers) {
                cache.clear();directory.deleteRecursively();active.remove(directory.absolutePath)
                DebugLog.event(DebugLog.Event.PREVIEW_CLOSED,trace)
            }
        }
    }
    companion object {
        const val HOST="preview.mtmux.invalid"
        private val active=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun cleanup(context:Context) {
            File(context.cacheDir,"preview").listFiles()?.filter {it.absolutePath !in active}?.forEach {it.deleteRecursively()}
        }
        fun decode(file:File):String=try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(file.readBytes())).toString().removePrefix("\uFEFF")
        } catch(error:Exception) {throw RemoteFileException(FileProblem.ENCODING,error)}
    }
}
