package dev.mtmux.core

import java.util.Base64

/** Lengths are UTF-8 byte counts (tmux's n modifier), so names may contain any delimiters. */
internal object TmuxFields {
    const val BATCH_SIZE = 8
    // Eight framed metadata + screen records must fit exec's 512 KiB output bound.
    const val MAX_BYTES = 16 * 1024

    fun format(values: List<String>) = values.joinToString("") { "#{n:$it}:$it" }

    fun command(pane: Pane, values: List<String>, path: String): String {
        // Reuse the ID/path validation; never put names or pane text into shell code.
        Tmux.field(pane, "session_name", path)
        val target = Tmux.quote("${pane.session}:${pane.window}.${pane.id}")
        return "${Tmux.quote(path)} -u display-message -p -t $target ${Tmux.quote(format(values))}"
    }

    fun decode(encoded: String, count: Int): List<String> {
        val bytes = Base64.getDecoder().decode(encoded)
        requireValid(bytes.isNotEmpty() && bytes.size <= MAX_BYTES, ErrorCode.TMUX_METADATA_INVALID)
        return records(bytes, count, 1).single()
    }

    fun records(bytes: ByteArray, count: Int, limit: Int = 256): List<List<String>> {
        var offset = 0
        val records = mutableListOf<List<String>>()
        while (offset < bytes.size) {
            requireValid(records.size < limit, ErrorCode.TOO_MANY_PANES)
            records += List(count) {
                val start = offset
                while (offset < bytes.size && bytes[offset] in 48..57) offset++
                requireValid(offset > start && offset - start <= 5 && offset < bytes.size && bytes[offset] == 58.toByte(), ErrorCode.TMUX_METADATA_INVALID)
                val size = String(bytes, start, offset - start, Charsets.US_ASCII).toInt()
                offset++
                requireValid(size <= MAX_BYTES && size <= bytes.size - offset, ErrorCode.TMUX_METADATA_INVALID)
                String(bytes, offset, size, Charsets.UTF_8).also { offset += size }
            }
            requireValid(offset < bytes.size && bytes[offset] == 10.toByte(), ErrorCode.TMUX_METADATA_INVALID)
            offset++
        }
        return records
    }

    fun metadataCommand(panes: List<Pane>, path: String): String {
        require(panes.size in 1..BATCH_SIZE)
        return buildString {
            append("command -v base64 >/dev/null || exit 127; printf 'MTMUX_FIELDS_1\\n';\n")
            panes.forEach { pane ->
                append(encodedCommand(pane, listOf("#{session_name}", "#{window_index}", "#{window_name}"), path))
            }
            append("printf 'MTMUX_END\\n'")
        }
    }

    fun encodedCommand(pane: Pane, values: List<String>, path: String, required: Boolean = true): String =
        // A sentinel protects all trailing newlines from shell command substitution.
        "fields=\$(" + command(pane, values, path) + " 2>/dev/null && printf .) || " +
            (if (required) "exit 1" else "fields=") + "; fields=\${fields%.};\n" +
            "printf '%s' \"\$fields\" | head -c $MAX_BYTES | base64 | tr -d '\\r\\n'; printf '\\n';\n"

    fun parseMetadata(output: String, panes: List<Pane>): List<Pane> {
        val lines = output.removeSuffix("\n").split('\n')
        requireValid(lines.size == panes.size + 2 && lines.first() == "MTMUX_FIELDS_1" && lines.last() == "MTMUX_END", ErrorCode.TMUX_METADATA_INVALID)
        return panes.mapIndexed { index, pane ->
            val fields = decode(lines[index + 1], 3)
            pane.copy(sessionName = fields[0].take(512), windowIndex = fields[1], windowName = fields[2].take(512))
        }
    }
}
