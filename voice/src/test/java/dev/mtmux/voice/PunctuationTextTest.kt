package dev.mtmux.voice

import org.junit.Assert.*
import org.junit.Test

class PunctuationTextTest {
    private fun apply(text: String, mark: String = "，") = PunctuationText.apply(text,
        PunctuationText.tokenize(text), PunctuationText.tokenize(text).map { mark })

    @Test fun addsPunctuationWithoutChangingOriginalWordsOrWhitespace() {
        val text = "先检查 SSH\t连接 再修改代码"
        val tokens = PunctuationText.tokenize(text)
        val result = PunctuationText.apply(text, tokens, tokens.map { if (it.text == "接") "，" else "_" })
        assertEquals("先检查 SSH\t连接， 再修改代码。", result)
    }

    @Test fun protectsPathsUrlsFlagsIdentifiersAndQuotedCode() {
        val text = "检查 /home/项目/main.kt 和 https://example.com/a?q=1 使用 --dry-run foo_bar README.md `git status` \"不要修改这里\""
        val result = apply(text)
        for (literal in listOf("/home/项目/main.kt", "https://example.com/a?q=1", "--dry-run", "foo_bar", "README.md", "`git status`", "\"不要修改这里\""))
            assertTrue(literal, result.contains(literal))
    }

    @Test fun keepsExistingPunctuationAndLineBreaks() {
        val text = "先检查，\n再修改。"
        val tokens = PunctuationText.tokenize(text)
        val result = PunctuationText.apply(text, tokens, tokens.map { "_" })
        assertEquals(text, result)
        assertEquals("先检查\n再修改。", PunctuationText.apply("先检查\n再修改",
            PunctuationText.tokenize("先检查\n再修改"), List(6) { "_" }))
    }

    @Test fun keepsSupplementaryCharactersAndEmojiIntact() {
        val text = "检查𠮷野家的连接🙂然后继续"
        assertTrue(PunctuationText.tokenize(text).any { it.text == "𠮷" })
        assertEquals(text, apply(text).replace("，", "").replace("。", ""))
    }

    @Test fun doesNotInventUnknownPunctuationOrAppendAfterLiteral() {
        assertEquals("检查 README.md", apply("检查 README.md", "<unk>"))
        assertEquals("你好吗？", PunctuationText.apply("你好吗", PunctuationText.tokenize("你好吗"), listOf("_", "_", "？")))
    }

    @Test fun skipsEnglishOnlyEmptyAndUnboundedInput() {
        assertFalse(PunctuationText.shouldRestore("git status --short"))
        assertFalse(PunctuationText.shouldRestore(""))
        assertFalse(PunctuationText.shouldRestore("中".repeat(4097)))
        assertTrue(PunctuationText.shouldRestore("检查 SSH connection"))
    }

    @Test fun characterModelNeverSplitsEnglishWordsOrDuplicatesExistingMarks() {
        val text = "检查 SSH connection，保持 README.md 和 /home/项目 完整。"
        val tokens = PunctuationText.tokenize(text, characterLevel = true)
        val result = PunctuationText.apply(text, tokens, List(tokens.size) { "，" })
        assertTrue(result.contains("SSH"))
        assertTrue(result.contains("connection，"))
        assertFalse(result.contains("connection，，"))
        assertTrue(result.contains("README.md"))
        assertTrue(result.contains("/home/项目"))
        assertTrue(result.endsWith("整。"))
    }

    @Test fun overlappingWindowsCoverTheEntireTranscriptWithoutTruncatingTheTail() {
        for (size in listOf(1, 128, 129, 257, 1024)) {
            val coverage = BooleanArray(size)
            for (start in PunctuationRestorer.windowStarts(size)) {
                val end = minOf(start + 128, size)
                assertTrue(end - start in 1..128)
                for (i in start until end) coverage[i] = true
            }
            assertTrue("uncovered token at size $size", coverage.all { it })
        }
    }

    @Test fun cancellationCanHappenBeforeAnyNativeModelIsLoaded() {
        val token = PunctuationCancellation()
        token.cancel()
        assertThrows(java.util.concurrent.CancellationException::class.java) { token.check() }
    }
}
