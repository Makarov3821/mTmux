package dev.mtmux.core

object PrivateKeyText {
    fun decode(text: String): ByteArray {
        require(text.length <= 65536) { "私钥须小于 64 KiB" }
        val value = text.trim().removePrefix("\uFEFF").replace("\r\n","\n").replace('\r','\n')
        val label = Regex("^-----BEGIN ((?:OPENSSH |RSA |EC |DSA |ENCRYPTED )?PRIVATE KEY)-----\\n").find(value)?.groupValues?.get(1)
        require(label != null && value.endsWith("-----END $label-----")) { "请粘贴完整私钥（含 BEGIN / END 行），不能使用公钥" }
        val bytes = (value+"\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65536) { "私钥须小于 64 KiB" }
        // Only check the envelope here. Encrypted keys are decoded by SSH using the passphrase.
        return bytes
    }
}
