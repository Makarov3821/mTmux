package dev.mtmux.core

object PrivateKeyText {
    fun decode(text: String): ByteArray {
        requireValid(text.length <= 65536, ErrorCode.PRIVATE_KEY_TOO_LARGE)
        val value = text.trim().removePrefix("\uFEFF").replace("\r\n","\n").replace('\r','\n')
        val label = Regex("^-----BEGIN ((?:OPENSSH |RSA |EC |DSA |ENCRYPTED )?PRIVATE KEY)-----\\n").find(value)?.groupValues?.get(1)
        requireValid(label != null && value.endsWith("-----END $label-----"), ErrorCode.PRIVATE_KEY_INVALID)
        val bytes = (value+"\n").toByteArray(Charsets.UTF_8)
        requireValid(bytes.size <= 65536, ErrorCode.PRIVATE_KEY_TOO_LARGE)
        // Only check the envelope here. Encrypted keys are decoded by SSH using the passphrase.
        return bytes
    }
}
