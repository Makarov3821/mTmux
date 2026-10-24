package dev.mtmux.voice

/** Offsets always refer to the original text: reconstruction only inserts punctuation. */
internal object PunctuationText {
    data class Token(val text: String, val start: Int, val end: Int, val protected: Boolean)
    private val protectedSpans = Regex(
        "`[^`]*`|\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|" +
            "(?:https?://|www\\.|\\.\\.?/|~/|[A-Za-z]:[\\\\/]|/)[^\\s，。！？；、\"'`]+|" +
            "[A-Za-z0-9_./~:@%+\\\\=\\-]+"
    )
    private val marks = setOf("，", "。", "？", "、", "！")
    private const val EXISTING_MARKS = "，。！？、；：\"'“”‘’（）【】《》,!?.;:()[]"

    fun shouldRestore(text: String) = text.length in 2..4096 && text.codePoints().filter { isHan(it) }.limit(2).count() >= 2

    fun tokenize(text: String, characterLevel: Boolean = false): List<Token> {
        val protected = BooleanArray(text.length + 1)
        for (match in protectedSpans.findAll(text)) {
            val value = match.value
            if (value.startsWith('`') || value.startsWith('"') || value.startsWith('\'') ||
                value.any { it in "_./~:@%+\\=-" }) {
                for (i in match.range) protected[i] = true
            }
        }
        val result = ArrayList<Token>()
        var at = 0
        while (at < text.length) {
            val cp = text.codePointAt(at)
            if (Character.isWhitespace(cp)) { at += Character.charCount(cp); continue }
            if (characterLevel && cp <= Char.MAX_VALUE.code && cp.toChar() in EXISTING_MARKS) { at++; continue }
            val start = at
            at += Character.charCount(cp)
            if (!characterLevel && asciiWord(cp)) while (at < text.length && asciiWord(text.codePointAt(at))) at++
            result += Token(text.substring(start, at), start, at, (start until at).any { protected[it] })
        }
        return result
    }

    fun apply(text: String, tokens: List<Token>, predicted: List<String>): String {
        require(tokens.size == predicted.size)
        val result = StringBuilder(text.length + tokens.size)
        var copied = 0
        for ((index, token) in tokens.withIndex()) {
            result.append(text, copied, token.end)
            copied = token.end
            if (token.protected || !Character.isLetterOrDigit(token.text.codePointBefore(token.text.length))) continue
            val next = tokens.getOrNull(index + 1)
            // Existing punctuation, code, whitespace and line breaks remain byte-for-byte intact.
            if (next != null && !Character.isLetterOrDigit(next.text.codePointAt(0))) continue
            val gap = text.substring(token.end, next?.start ?: text.length)
            if (gap.any { !it.isWhitespace() || it == '\n' || it == '\r' }) continue
            if (next != null && next.start == token.end && asciiWord(token.text.last().code) && asciiWord(next.text.first().code)) continue
            val last = next == null
            val mark = predicted[index].takeIf { it in marks }
            val insertion = if (last && (mark == null || mark == "，" || mark == "、")) "。" else mark
            if (insertion != null) result.append(insertion)
        }
        result.append(text, copied, text.length)
        return result.toString()
    }

    private fun asciiWord(cp: Int) = cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code || cp == '\''.code
    private fun isHan(cp: Int) = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
}
