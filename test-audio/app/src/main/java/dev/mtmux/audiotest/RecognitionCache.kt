package dev.mtmux.audiotest

/** Scoped to one recording comparison, used sequentially. Failures are never cached. */
class RecognitionCache<T> {
    data class Result<T>(val value: T, val origin: String, val reused: Boolean)
    private val values = mutableMapOf<String, Pair<String, T>>()

    fun getOrRun(key: String, origin: String, decode: () -> T): Result<T> {
        values[key]?.let { return Result(it.second, it.first, true) }
        val value = decode()
        values[key] = origin to value
        return Result(value, origin, false)
    }
}
