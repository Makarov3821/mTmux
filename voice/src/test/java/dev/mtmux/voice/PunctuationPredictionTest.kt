package dev.mtmux.voice

import java.nio.FloatBuffer
import kotlin.math.ln
import org.junit.Assert.*
import org.junit.Test

class PunctuationPredictionTest {
    private val labels = listOf("_", "，", "。", "？", "！")
    private fun choose(vararg probabilities: Double, terminal: Boolean = false, conservative: Boolean = true): String {
        val scores = FloatBuffer.wrap(probabilities.map { ln(it).toFloat() }.toFloatArray())
        return PunctuationPrediction.choose(scores, 0, labels, conservative, terminal)
    }

    @Test fun rejectsTheNearTieThatBrokeModifyThenComplete() {
        assertEquals("_", choose(.48, .501, .015, .003, .001))
    }

    @Test fun rejectsWeakCommaAfterIndicates() {
        assertEquals("_", choose(.218, .659, .10, .020, .003))
    }

    @Test fun preservesStrongBoundaryEvenWhenCommaAndPeriodCompete() {
        assertEquals("，", choose(.002, .576, .419, .002, .001))
        assertEquals("，", choose(.004, .980, .012, .003, .001))
    }

    @Test fun rejectsPrematureQuestionsButKeepsTerminalAndVeryConfidentQuestions() {
        assertEquals("_", choose(.065, .008, .004, .922, .001))
        assertEquals("？", choose(.065, .008, .004, .922, .001, terminal = true))
        assertEquals("？", choose(.001, .001, .001, .996, .001))
    }

    @Test fun leavesCtWinnerAndExistingTerminalBehaviorUnchanged() {
        assertEquals("，", choose(.48, .501, .015, .003, .001, conservative = false))
        assertEquals("。", choose(.20, .05, .70, .04, .01, terminal = true))
        assertEquals("_", choose(.95, .02, .01, .01, .01))
    }

    @Test fun usesTheCorrectTensorOffsetAndStableSoftmaxForLargeScores() {
        val scores = FloatBuffer.wrap(floatArrayOf(-8f, 9f, 0f, 0f, 0f, 1000f, 1010f, 1001f, 1000f, 1000f))
        assertEquals("，", PunctuationPrediction.choose(scores, 5, labels, true, false))
    }

    @Test fun ignoresInvalidInteriorScores() {
        assertEquals("_", PunctuationPrediction.choose(FloatBuffer.wrap(floatArrayOf(Float.NaN, 2f, 1f, 0f, 0f)), 0, labels, true, false))
    }
}
