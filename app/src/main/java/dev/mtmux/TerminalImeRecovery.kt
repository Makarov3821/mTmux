package dev.mtmux

/** Keep a missed restart pending until the terminal owns both view and window focus. */
internal class TerminalImeRecovery {
    data class Request(val restart: Boolean, val show: Boolean)
    private var restart = true
    private var show = false
    val pending: Boolean get() = restart || show

    fun invalidate() { restart = true }
    fun connected() { restart = false }
    fun suspend() { restart = true; show = false }
    fun keyboard(hasActiveConnection: Boolean) {
        if (!hasActiveConnection) restart = true
        show = true
    }
    fun take(ready: Boolean): Request? {
        if (!ready || !pending) return null
        return Request(restart, show).also { restart = false; show = false }
    }
}
