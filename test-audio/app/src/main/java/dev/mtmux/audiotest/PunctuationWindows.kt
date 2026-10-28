package dev.mtmux.audiotest

/** Bound attention memory while keeping entire short utterances in a single context. */
internal object PunctuationWindows {
    fun starts(size: Int, window: Int): List<Int> {
        require(size >= 0 && window in setOf(128, 512))
        if (size <= window) return listOf(0)
        val stride = window * 3 / 4
        return ((0 until size - window step stride).toList() + (size - window)).distinct()
    }
}
