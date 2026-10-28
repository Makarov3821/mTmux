package dev.mtmux.audiotest

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Tensor predictions were generated with the pinned real CT / SRF weights, not invented labels. */
class PunctuationFixtureTest {
    private data class Output(val name: String, val kind: String, val raw: String, val text: String)
    private fun results(): List<Output> = requireNotNull(javaClass.getResourceAsStream("/focused-punctuation.tsv"))
        .bufferedReader(Charsets.UTF_8).use { input -> input.lineSequence().filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t'); require(fields.size == 5)
            fun decode(value: String) = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
            val raw = decode(fields[2]); val tokens = PunctuationText.tokenize(raw, characterLevel = fields[1].startsWith("srf"))
            assertEquals("model input: ${fields[0]} / ${fields[1]}", decode(fields[3]).split('\u0000'), tokens.map { it.text })
            Output(fields[0], fields[1], raw, PunctuationText.apply(raw, tokens, fields[4].split(',')))
        }.toList() }
    @Test fun realPredictionsOnlyInsertMarksAndKeepInstructionsIntact() {
        val outputs = results(); assertEquals(27, outputs.size)
        val marks = setOf('，', '。', '？', '、', '！')
        for (output in outputs) {
            assertEquals("word preservation: ${output.name} / ${output.kind}", output.raw.filterNot { it in marks }, output.text.filterNot { it in marks })
            println("PUNC_OUTPUT\t${output.name}\t${output.kind}\t${Base64.getEncoder().encodeToString(output.text.toByteArray(Charsets.UTF_8))}")
        }
    }
    @Test fun technicalLiteralsAndExistingSentenceBoundariesSurviveActualModelPredictions() {
        for (output in results()) when (output.name) {
            "technical" -> for (literal in listOf("Jetpack", "Compose", "SSH", "connection", "gradlew", "README.md", "/tmp/demo.kt"))
                assertTrue("${output.kind}: $literal", output.text.contains(literal))
            "original-marks" -> { assertTrue(output.text.contains("不变，\n")); assertTrue(output.text.endsWith("测试。")) }
        }
    }
}
