package dev.mtmux

import dev.mtmux.core.HostKeyChallenge
import dev.mtmux.core.SshClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

internal data class HostApproval(val challenge: HostKeyChallenge, val answer: CompletableFuture<Boolean> = CompletableFuture())

/** Bridges blocking SSH host-key verification to an explicit Compose decision. */
internal class SshOperation {
    val client = AtomicReference<SshClient?>()
    @Volatile var cancelled = false; private set
    fun ensureNotCancelled() { if (cancelled) throw CancellationException("operation cancelled") }
    private var approval: HostApproval? = null
    @Synchronized fun offer(request: HostApproval): Boolean {
        if (cancelled) { request.answer.complete(false); return false }
        approval = request
        return true
    }
    @Synchronized fun cancel() {
        cancelled = true
        approval?.answer?.complete(false)
        client.get()?.cancel()
    }
}
