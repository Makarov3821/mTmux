package dev.mtmux

internal object AutomaticRefreshPolicy {
    const val INTERVAL_MS = 5 * 60 * 1000L

    fun intervalElapsed(lastAttempt: Long, now: Long): Boolean =
        lastAttempt <= 0 || now < lastAttempt || now - lastAttempt >= INTERVAL_MS

    // A terminal return bypasses only the interval; folded groups stay quiet.
    fun allowsRefresh(folderCollapsed: Boolean, intervalElapsed: Boolean, forceInterval: Boolean): Boolean =
        !folderCollapsed && (forceInterval || intervalElapsed)
}
