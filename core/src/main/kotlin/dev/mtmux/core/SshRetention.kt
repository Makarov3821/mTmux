package dev.mtmux.core

/** Idle authenticated chains only; an active terminal/refresh is not an idle connection. */
enum class SshRetention(val capacity: Int) {
    NONE(0), FEW(1), MEDIUM(2), MANY(3), VERY_MANY(5);

    companion object {
        fun fromCapacity(value: Int) = entries.firstOrNull { it.capacity == value } ?: MEDIUM
    }
}
