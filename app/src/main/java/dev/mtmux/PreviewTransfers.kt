package dev.mtmux

import dev.mtmux.core.FileProblem
import dev.mtmux.core.RemoteFileException

/** Monotonic viewport plans reject late image requests before they can reach SFTP. */
internal class PreviewTransfers {
    private var sequence=0L
    private var allowed=0L
    private var closed=false
    private val requests=mutableMapOf<Long,PreviewCancellation>()
    fun update(nextSequence:Long,id:Long) {
        val cancelled=synchronized(this) {
            if(closed || nextSequence<=sequence) return
            sequence=nextSequence;allowed=id
            requests.filterKeys {it!=id}.values.toList().also {requests.keys.removeAll {it!=id}}
        }
        cancelled.forEach {it.cancel()}
    }
    @Synchronized fun begin(id:Long):PreviewCancellation {
        if(closed || id<=0 || id!=allowed) throw RemoteFileException(FileProblem.CANCELLED)
        return PreviewCancellation().also {requests.put(id,it)?.cancel()}
    }
    @Synchronized fun finish(id:Long,cancellation:PreviewCancellation) {if(requests[id]===cancellation) requests.remove(id)}
    fun close() {
        val cancelled=synchronized(this) {closed=true;allowed=0;requests.values.toList().also {requests.clear()}}
        cancelled.forEach {it.cancel()}
    }
}
