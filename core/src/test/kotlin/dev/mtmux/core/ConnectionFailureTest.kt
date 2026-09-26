package dev.mtmux.core

import org.junit.Test
import kotlin.test.*
import java.net.*

class ConnectionFailureTest {
    @Test fun `wrapped library errors map to fixed categories without exposing their contents`() {
        val secret="SECRET_PRIVATE_PATH_PASSWORD"
        val cases=listOf(
            UnknownHostException(secret) to FailureReason.UNKNOWN_HOST,
            SocketTimeoutException(secret) to FailureReason.TIMEOUT,
            ConnectException("Connection refused $secret") to FailureReason.REFUSED,
            NoRouteToHostException(secret) to FailureReason.UNREACHABLE,
            Exception("Auth fail for methods publickey $secret") to FailureReason.AUTH,
            Exception("invalid privatekey $secret") to FailureReason.PRIVATE_KEY,
            Exception("PortForwardingL failed $secret") to FailureReason.FORWARDING,
            Exception("Algorithm negotiation fail $secret") to FailureReason.ALGORITHM,
            ConnectException(secret) to FailureReason.CONNECT,
            Exception(secret) to FailureReason.OTHER
        )
        cases.forEach { (cause,expected) ->
            val error=ConnectionFailure(2,connectionFailureReason(Exception("wrapper",cause)),cause)
            assertEquals(expected,error.reason)
            assertEquals(2,error.hop)
            assertFalse(error.message!!.contains(secret))
        }
    }

    @Test fun `coded errors carry stable machine names, not display text`() {
        val error=assertFailsWith<InvalidInput> { Login("bad host",22,"user","") }
        assertEquals(ErrorCode.INVALID_LOGIN,error.code)
        assertEquals("INVALID_LOGIN",error.message)
        assertEquals(ErrorCode.PRIVATE_KEY_INVALID,assertFailsWith<InvalidInput> { PrivateKeyText.decode("ssh-ed25519 AAAA") }.code)
        assertEquals(ErrorCode.TMUX_PATH_INVALID,assertFailsWith<InvalidInput> { Tmux.version("tmux; id") }.code)
    }
}
