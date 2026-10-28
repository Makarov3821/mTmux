package dev.mtmux.voice

import java.nio.FloatBuffer
import kotlin.math.exp

/** SRF scores are not calibrated accuracy estimates: use them only to reject uncertain insertions. */
internal object PunctuationPrediction {
    fun choose(scores: FloatBuffer, offset: Int, labels: List<String>, conservative: Boolean, terminal: Boolean): String {
        var best = 0
        for (k in 1 until labels.size) if (scores[offset + k] > scores[offset + best]) best = k
        val mark = labels[best]
        if (!conservative || terminal || mark == "_") return mark

        val maximum = scores[offset + best].toDouble()
        if (!maximum.isFinite()) return "_"
        var total = 0.0
        for (k in labels.indices) total += exp(scores[offset + k] - maximum)
        if (!total.isFinite() || total == 0.0) return "_"
        val noMark = exp(scores[offset] - maximum) / total
        // Prefer a missing comma to breaking a phrase when the no-punctuation label remains plausible.
        if (noMark > 0.10) return "_"
        // A premature sentence end carries more meaning than a comma; require a stronger winner.
        if ((mark == "。" || mark == "？" || mark == "！") && 1.0 / total < 0.97) return "_"
        return mark
    }
}
