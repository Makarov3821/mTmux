package dev.mtmux

import dev.mtmux.core.FileProblem
import dev.mtmux.core.RemoteFileException
import java.io.File

/** Accessed under the session's transfer lock; open streams remain readable after eviction. */
internal class PreviewDiskCache(private val maximum:Long,private val onEvicted:(String)->Unit={}) {
    private data class Entry(val file:File,val size:Long)
    private val files=LinkedHashMap<String,Entry>(16,.75f,true)
    private var pinned:String?=null
    var bytes=0L;private set
    fun get(key:String):File? {
        val entry=files[key] ?: return null
        if(entry.file.isFile) return entry.file
        files.remove(key);bytes-=entry.size;onEvicted(key);return null
    }
    fun pin(key:String?) {pinned=key}
    fun reserve(size:Long) {
        if(size>maximum) throw RemoteFileException(FileProblem.TOO_LARGE,actualBytes=size,limitBytes=maximum)
        val entries=files.entries.iterator()
        while(bytes+size>maximum && entries.hasNext()) {
            val entry=entries.next()
            if(entry.key==pinned) continue
            bytes-=entry.value.size;entry.value.file.delete();onEvicted(entry.key);entries.remove()
        }
        check(bytes+size<=maximum) {"Pinned preview exceeds cache budget"}
    }
    fun put(key:String,file:File) {
        files.remove(key)?.let {bytes-=it.size;if(it.file!=file) it.file.delete()}
        reserve(file.length());files[key]=Entry(file,file.length());bytes+=file.length()
    }
    fun clear() {files.values.forEach {it.file.delete()};files.clear();bytes=0;pinned=null}
}
