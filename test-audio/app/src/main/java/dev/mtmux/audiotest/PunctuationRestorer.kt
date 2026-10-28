package dev.mtmux.audiotest

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import java.nio.IntBuffer
import java.nio.LongBuffer
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Each call owns its native run handle; cancelling an old call cannot stop a new one. */
class PunctuationCancellation {
    private val cancelled = AtomicBoolean(false)
    private var active: OrtSession.RunOptions? = null
    @Synchronized fun cancel() { cancelled.set(true); active?.let { runCatching { it.setTerminate(true) } } }
    internal fun check() { if (cancelled.get()) throw CancellationException("VOICE_CANCELLED") }
    @Synchronized internal fun bind(options: OrtSession.RunOptions) { check(); active = options }
    @Synchronized internal fun unbind(options: OrtSession.RunOptions) { if (active === options) active = null }
}

data class PunctuationResult(val text: String, val elapsedMs: Long, val processed: Boolean, val failed: Boolean, val error: String? = null)

/** No model or inference thread is created until restore(). Every call closes its ONNX session. */
class PunctuationRestorer(private val root: java.io.File, private val catalog: Catalog, private val kind: String,
    private val contextWindow: Int = 128) {
    init {
        require(kind in setOf("ct", "srf"))
        require(contextWindow in setOf(128, 512) && (kind != "srf" || contextWindow == 128))
    }

    fun restore(text: String, cancellation: PunctuationCancellation = PunctuationCancellation()): PunctuationResult {
        cancellation.check()
        if (!PunctuationText.shouldRestore(text)) return PunctuationResult(text, 0, false, false)
        val started = SystemClock.elapsedRealtime()
        try {
            val file = catalog.group(kind).files.single().file(root)
            cancellation.check()
            val environment = OrtEnvironment.getEnvironment()
            val srf = kind == "srf"
            val tokens = PunctuationText.tokenize(text, characterLevel = srf)
            val predictions = MutableList(tokens.size) { "_" }
            val margins = IntArray(tokens.size) { -1 }
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                options.setCPUArenaAllocator(false)
                options.setMemoryPatternOptimization(false)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.addConfigEntry("session.inter_op.allow_spinning", "0")
                environment.createSession(file.absolutePath, options).use { session ->
                    cancellation.check()
                    val metadata = session.metadata.customMetadata
                    val vocabulary = if (srf) {
                        val json = JSONObject(catalog.group("srf-vocab").files.single().file(root).readText())
                        json.keys().asSequence().associateWith { json.getInt(it) }
                    } else requireNotNull(metadata["tokens"]).split('|').withIndex().associate { it.value to it.index }
                    val labels = if (srf) listOf("_", "，", "。", "？", "！") else requireNotNull(metadata["punctuations"]).split('|')
                    val unknown = requireNotNull(vocabulary[if(srf) "<UNK>" else metadata["unk_symbol"]])
                    require(vocabulary.size == if(srf) 8000 else metadata["vocab_size"]?.toInt())
                    require(labels.containsAll(listOf("_", "，", "。", "？")))
                    val ids = tokens.map { vocabulary[if(srf) it.text else it.text.lowercase(Locale.ROOT)] ?: unknown }
                    // Overlap retains context; each token keeps the prediction furthest from a window edge.
                    for (start in PunctuationWindows.starts(tokens.size, contextWindow)) {
                        cancellation.check()
                        val length = minOf(contextWindow, tokens.size - start)
                        val inputShape = longArrayOf(1, length.toLong())
                        val inputTensor = if (srf) OnnxTensor.createTensor(environment,
                            LongBuffer.wrap(ids.subList(start, start + length).map { it.toLong() }.toLongArray()), inputShape)
                            else OnnxTensor.createTensor(environment, IntBuffer.wrap(ids.subList(start, start + length).toIntArray()), inputShape)
                        inputTensor.use { input ->
                            val auxiliary = if (srf) OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(length) {1L}), inputShape)
                                else OnnxTensor.createTensor(environment, IntBuffer.wrap(intArrayOf(length)), longArrayOf(1))
                            auxiliary.use { inputLength ->
                                OrtSession.RunOptions().use { run ->
                                    cancellation.bind(run)
                                    try {
                                        val inputs = if (srf) mapOf("input_ids" to input, "attention_mask" to inputLength)
                                            else mapOf("inputs" to input, "text_lengths" to inputLength)
                                        session.run(inputs, run).use { result ->
                                            val tensor = result[0] as OnnxTensor
                                            require(tensor.info.shape.contentEquals(longArrayOf(1, length.toLong(), labels.size.toLong())))
                                            val scores = tensor.floatBuffer
                                            for (j in 0 until length) {
                                                val margin = minOf(j, length - 1 - j)
                                                if (margin <= margins[start + j]) continue
                                                var best = 0
                                                for (k in 1 until labels.size) if (scores[j * labels.size + k] > scores[j * labels.size + best]) best = k
                                                predictions[start + j] = labels[best]
                                                margins[start + j] = margin
                                            }
                                        }
                                    } finally { cancellation.unbind(run) }
                                }
                            }
                        }
                    }
                }
            }
            cancellation.check()
            return PunctuationResult(PunctuationText.apply(text, tokens, predictions), SystemClock.elapsedRealtime() - started, true, false)
        } catch (error: Exception) {
            cancellation.check()
            if (error is CancellationException) throw error
            // A punctuation failure must not discard otherwise valid recognition text.
            return PunctuationResult(text, SystemClock.elapsedRealtime() - started, false, true, "${error.javaClass.simpleName}: ${error.message}")
        } catch (error: LinkageError) {
            cancellation.check()
            return PunctuationResult(text, SystemClock.elapsedRealtime() - started, false, true, "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    companion object {
        internal fun windowStarts(size: Int): List<Int> = PunctuationWindows.starts(size, 128)
    }
}
