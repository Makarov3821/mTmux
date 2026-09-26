package dev.mtmux.core

/** Fixed categories only; never expose library exceptions, keys or passwords. */
enum class FailureReason { UNKNOWN_HOST, TIMEOUT, AUTH, PRIVATE_KEY, REFUSED, UNREACHABLE, FORWARDING, ALGORITHM, CONNECT, OTHER }

/** [hop] is 0 for the target server, otherwise the 1-based jump index. */
class ConnectionFailure(val hop: Int, val reason: FailureReason, cause: Throwable) :
    IllegalStateException("hop=$hop reason=$reason", cause)

fun connectionFailureReason(error: Throwable): FailureReason {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    val messages = causes.joinToString(" ") { it.message.orEmpty() }.lowercase(java.util.Locale.ROOT)
    return when {
        causes.any { it is java.net.UnknownHostException } -> FailureReason.UNKNOWN_HOST
        causes.any { it is java.net.SocketTimeoutException } || "timeout" in messages || "timed out" in messages -> FailureReason.TIMEOUT
        "auth fail" in messages || "auth cancel" in messages -> FailureReason.AUTH
        "invalid privatekey" in messages || "private key" in messages || "passphrase" in messages -> FailureReason.PRIVATE_KEY
        "connection refused" in messages -> FailureReason.REFUSED
        causes.any { it is java.net.NoRouteToHostException } || "unreachable" in messages -> FailureReason.UNREACHABLE
        "portforwarding" in messages || "forwarding" in messages || "administratively prohibited" in messages -> FailureReason.FORWARDING
        "algorithm negotiation fail" in messages -> FailureReason.ALGORITHM
        causes.any { it is java.net.ConnectException } -> FailureReason.CONNECT
        else -> FailureReason.OTHER
    }
}
