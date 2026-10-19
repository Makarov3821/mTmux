package dev.mtmux

import dev.mtmux.core.TaskState

data class ServerSnapshot(val tasks: List<RecentTask>, val updatedAt: Long,
    val states: Map<String, TaskState> = emptyMap(), val stateTimes: Map<String, Long> = emptyMap())

fun ServerSnapshot.taskState(task: RecentTask, now: Long = System.currentTimeMillis()): TaskState {
    val time = stateTimes[task.identity] ?: updatedAt
    return if (now < time || now - time >= 30 * 60 * 1000L) TaskState.UNKNOWN else states[task.identity] ?: TaskState.UNKNOWN
}

/** Listing does not refresh old state timestamps. Explicit unknown readings replace old colors. */
fun mergeTaskSnapshot(previous: ServerSnapshot?, tasks: List<RecentTask>, readings: Map<String, TaskState>,
    now: Long = System.currentTimeMillis()): ServerSnapshot {
    val oldTasks = previous?.tasks.orEmpty().associateBy { it.identity }
    val states = linkedMapOf<String, TaskState>()
    val times = linkedMapOf<String, Long>()
    tasks.forEach { task ->
        val id = task.identity ?: return@forEach
        val old = oldTasks[id]
        val same = old != null && old.copy(sessionName = task.sessionName, windowName = task.windowName, usedAt = task.usedAt) == task
        states[id] = readings[id] ?: if (same) previous!!.taskState(old!!, now) else TaskState.UNKNOWN
        times[id] = if (id in readings || !same) now else previous!!.stateTimes[id] ?: previous.updatedAt
    }
    return ServerSnapshot(tasks, if (readings.isEmpty()) previous?.updatedAt ?: now else now, states, times)
}
