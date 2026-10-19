package dev.mtmux

import dev.mtmux.core.TaskState
import org.junit.Assert.*
import org.junit.Test

class ServerSnapshotTest {
    private val first = RecentTask("p", "host", 22, "u", "tmux", "pin", "1:2:\$0:@0:%0:3", "session", "window")
    private val second = first.copy(identity = "1:2:\$0:@1:%1:4")
    private val old = ServerSnapshot(listOf(first, second), 1_000,
        mapOf(first.identity!! to TaskState.RUNNING, second.identity!! to TaskState.COMPLETED))

    @Test fun listingAndOtherBatchesKeepColorsWithoutRenewingTheirAge() {
        val listed = mergeTaskSnapshot(old, listOf(first.copy(windowName = "renamed"), second), emptyMap(), 2_000)
        assertEquals(TaskState.RUNNING, listed.taskState(first, 2_000))
        assertEquals(1_000L, listed.stateTimes[first.identity])
        val batch = mergeTaskSnapshot(listed, listed.tasks, mapOf(second.identity!! to TaskState.ATTENTION), 3_000)
        assertEquals(TaskState.RUNNING, batch.taskState(first, 3_000))
        assertEquals(TaskState.ATTENTION, batch.taskState(second, 3_000))
        assertEquals(TaskState.UNKNOWN, batch.taskState(first, 1_801_000))
        assertEquals(TaskState.ATTENTION, batch.taskState(second, 1_801_000))
    }
    @Test fun replacementIdentityTrustOrTargetNeverInheritsColor() {
        for (replacement in listOf(first.copy(identity = "5:6:\$0:@0:%0:7"), first.copy(hostKey = "new-pin"),
            first.copy(host = "new-host"), first.copy(route = "changed-route"))) {
            assertEquals(TaskState.UNKNOWN, mergeTaskSnapshot(old, listOf(replacement), emptyMap(), 2_000).taskState(replacement, 2_000))
        }
        assertTrue(mergeTaskSnapshot(old, emptyList(), emptyMap(), 2_000).states.isEmpty())
    }
    @Test fun unreadableUnknownReplacesOldColorAndExpiredColorsStayUnknown() {
        val unknown = mergeTaskSnapshot(old, old.tasks, mapOf(first.identity!! to TaskState.UNKNOWN), 2_000)
        assertEquals(TaskState.UNKNOWN, unknown.taskState(first, 2_000))
        val expired = mergeTaskSnapshot(old, old.tasks, emptyMap(), 1_801_000)
        assertEquals(TaskState.UNKNOWN, expired.taskState(first, 1_801_000))
        assertEquals(1_000L, expired.stateTimes[first.identity])
        assertEquals(TaskState.UNKNOWN, old.taskState(first, 999))
    }
}
