package dev.mtmux.core

/** Replies to DA1/DA2 and dynamic color queries belong to the attached terminal client, never pane paste. */
object TerminalInput {
    private val deviceAttributes = Regex("\u001b\\[[?>][0-9]+(?:;[0-9]+)*c")
    private val colorReply = Regex("\u001b\\](?:10|11|12);rgb:[0-9a-fA-F]{1,4}/[0-9a-fA-F]{1,4}/[0-9a-fA-F]{1,4}(?:\u0007|\u001b\\\\)")
    fun isClientReply(data: String): Boolean = isDeviceAttributesReply(data) || (data.length <= 128 && colorReply.matches(data))
    fun isDeviceAttributesReply(data: String): Boolean = data.length <= 128 && deviceAttributes.matches(data)
}
