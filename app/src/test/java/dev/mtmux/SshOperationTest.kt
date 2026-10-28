package dev.mtmux

import dev.mtmux.core.HostKeyChallenge
import org.junit.Assert.*
import org.junit.Test

class SshOperationTest {
    @Test fun cancellationAlsoBlocksCommitBeforeComposeHasDisposedTheJob() {
        val operation = SshOperation(); operation.cancel()
        assertThrows(java.util.concurrent.CancellationException::class.java) { operation.ensureNotCancelled() }
    }
    @Test fun leavingTheEditorUnblocksAnOutstandingFingerprintDecision() {
        val operation = SshOperation(); val prompt = HostApproval(HostKeyChallenge("fixture", "key", "SHA256:fixture", false))
        assertTrue(operation.offer(prompt)); operation.cancel()
        assertTrue(prompt.answer.isDone); assertFalse(prompt.answer.get()); assertTrue(operation.cancelled)
    }
    @Test fun aLateSshPromptCannotKeepAnOldEditorAlive() {
        val operation = SshOperation(); operation.cancel()
        val prompt = HostApproval(HostKeyChallenge("fixture", "key", "SHA256:fixture", false))
        assertFalse(operation.offer(prompt)); assertFalse(prompt.answer.get())
    }
}
