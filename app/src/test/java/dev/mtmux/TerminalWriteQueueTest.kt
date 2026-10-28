package dev.mtmux

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class TerminalWriteQueueTest {
    private class ManualExecutor : Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { jobs.addLast(command) }
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }
    }
    @Test fun queuedTypingUsesOneWriteWithoutAnArtificialTimer() {
        val executor=ManualExecutor();val sent=mutableListOf<String>()
        val queue=TerminalWriteQueue<String>(executor) {_,bytes,_ -> sent.add(bytes.toString(Charsets.UTF_8))}
        listOf("你","好","\u007f","。","\r").forEach {queue.offer("pane-a",it.toByteArray())}
        assertEquals(1,executor.jobs.size)
        executor.drain();assertEquals(listOf("你好\u007f。\r"),sent)
        queue.offer("pane-a","next".toByteArray());assertEquals(1,executor.jobs.size)
        executor.drain();assertEquals("next",sent.last())
    }
    @Test fun targetChangesAndAcknowledgedPasteAreOrderingBoundaries() {
        val executor=ManualExecutor();val sent=mutableListOf<String>();var done=0
        val queue=TerminalWriteQueue<String>(executor) {target,bytes,callback ->
            sent.add("$target:${bytes.toString(Charsets.UTF_8)}");callback?.invoke()
        }
        queue.offer("a","one".toByteArray());queue.offer("b","two".toByteArray())
        queue.offer("b","paste".toByteArray()) {done++};queue.offer("b","after".toByteArray())
        executor.drain()
        assertEquals(listOf("a:one","b:two","b:paste","b:after"),sent);assertEquals(1,done)
    }
    @Test fun disconnectDropsQueuedInputButAllowsTheNextConnectionToDrain() {
        val executor=ManualExecutor();val sent=mutableListOf<String>()
        val queue=TerminalWriteQueue<String>(executor) {target,_,_ -> sent.add(target)}
        queue.offer("old",byteArrayOf(1));queue.clear();queue.offer("new",byteArrayOf(2))
        executor.drain();assertEquals(listOf("new"),sent)
    }
    @Test fun stuckConnectionCannotAccumulateUnboundedInput() {
        val executor=ManualExecutor()
        val queue=TerminalWriteQueue<String>(executor) {_,_,_ ->}
        queue.offer("a",ByteArray(65536))
        assertThrows(RejectedExecutionException::class.java) {queue.offer("a",byteArrayOf(1))}
        queue.clear();queue.offer("b",byteArrayOf(2));executor.drain()
    }
    @Test fun lateFailureFromOldConnectionCannotClearNewConnectionInput() {
        val executor=ManualExecutor();val sent=mutableListOf<String>()
        val queue=TerminalWriteQueue<String>(executor) {target,_,_ -> sent.add(target)}
        queue.offer("old",byteArrayOf(1));queue.offer("new",byteArrayOf(2))
        queue.clearMatching {it=="old"}
        executor.drain();assertEquals(listOf("new"),sent)
    }
}
