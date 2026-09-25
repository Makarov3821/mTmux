package dev.mtmux.core

/** User-facing text is fixed here; never expose library exceptions, keys or passwords. */
class ConnectionFailure(val location: String, val reason: String, cause: Throwable) :
    IllegalStateException("$location：$reason", cause)

fun connectionFailureReason(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    val messages = causes.joinToString(" ") { it.message.orEmpty() }.lowercase(java.util.Locale.ROOT)
    return when {
        causes.any { it is java.net.UnknownHostException } -> "地址无法解析，请检查域名和 DNS"
        causes.any { it is java.net.SocketTimeoutException } || "timeout" in messages || "timed out" in messages -> "连接超时，请检查网络、VPN、防火墙及 SSH 端口"
        "auth fail" in messages || "auth cancel" in messages -> "认证失败，请检查用户名、密码或私钥，以及服务器允许的认证方式"
        "invalid privatekey" in messages || "private key" in messages || "passphrase" in messages -> "私钥无法读取或解密，请检查密钥格式和私钥口令"
        "connection refused" in messages -> "SSH 端口拒绝连接，请检查端口及 SSH 服务是否启动"
        causes.any { it is java.net.NoRouteToHostException } || "unreachable" in messages -> "网络不可达，请检查网络、VPN 和服务器地址"
        "portforwarding" in messages || "forwarding" in messages || "administratively prohibited" in messages -> "SSH 转发失败，请检查跳板的转发权限及到下一台服务器的网络"
        "algorithm negotiation fail" in messages -> "SSH 算法不兼容，请检查服务器支持的算法"
        causes.any { it is java.net.ConnectException } -> "无法建立连接，请检查服务器地址、SSH 端口及网络"
        else -> "SSH 连接失败，请检查网络、认证及服务器配置；经跳板连接时也请检查转发权限"
    }
}

fun connectionErrorText(error: Throwable): String = when (error) {
    is ConnectionFailure -> error.message!!
    is IllegalStateException, is IllegalArgumentException -> error.message ?: "连接验证失败，请重新连接"
    else -> connectionFailureReason(error)
}
