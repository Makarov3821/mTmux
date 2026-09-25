package dev.mtmux.core

import org.junit.Test
import kotlin.test.*
import java.net.*

class ConnectionFailureTest {
    @Test fun `wrapped library errors produce actionable text without exposing their contents`() {
        val secret="SECRET_PRIVATE_PATH_PASSWORD"
        val cases=listOf(
            UnknownHostException(secret) to "地址无法解析",
            SocketTimeoutException(secret) to "连接超时",
            ConnectException("Connection refused $secret") to "端口拒绝连接",
            NoRouteToHostException(secret) to "网络不可达",
            Exception("Auth fail for methods publickey $secret") to "认证失败",
            Exception("invalid privatekey $secret") to "私钥无法读取",
            Exception("PortForwardingL failed $secret") to "转发失败",
            Exception("Algorithm negotiation fail $secret") to "算法不兼容",
            Exception(secret) to "SSH 连接失败"
        )
        cases.forEach { (cause,expected) ->
            val error=ConnectionFailure("跳板 2",connectionFailureReason(Exception("wrapper",cause)),cause)
            assertTrue(connectionErrorText(error).contains(expected))
            assertTrue(connectionErrorText(error).startsWith("跳板 2："))
            assertFalse(connectionErrorText(error).contains(secret))
        }
    }
}
