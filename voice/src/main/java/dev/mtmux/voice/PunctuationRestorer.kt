package dev.mtmux.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
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

data class PunctuationResult(val text: String, val elapsedMs: Long, val processed: Boolean, val failed: Boolean)

/** No model or inference thread is created until restore(). Every call closes its ONNX session. */
class PunctuationRestorer(private val context: Context) {
    private val model = BundledVoiceAsset(MODEL_NAME, MODEL_BYTES, MODEL_SHA256)
    private val srfVocabulary = BundledVoiceAsset("srf-vocab.json", 119_104,
        "76684d3d381bed15057f19e6b22972a00ec79e72fa4bfd5682afde4fdcc5a3a2")

    fun restore(text: String, cancellation: PunctuationCancellation = PunctuationCancellation()): PunctuationResult {
        cancellation.check()
        if (!PunctuationText.shouldRestore(text)) return PunctuationResult(text, 0, false, false)
        val started = SystemClock.elapsedRealtime()
        try {
            val file = model.file(context)
            cancellation.check()
            val environment = OrtEnvironment.getEnvironment()
            val srf = BuildConfig.PUNCTUATION_KIND == "srf"
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
                        val json = JSONObject(srfVocabulary.file(context).readText())
                        json.keys().asSequence().associateWith { json.getInt(it) }
                    } else requireNotNull(metadata["tokens"]).split('|').withIndex().associate { it.value to it.index }
                    val labels = if (srf) listOf("_", "，", "。", "？", "！") else requireNotNull(metadata["punctuations"]).split('|')
                    val unknown = requireNotNull(vocabulary[if(srf) "<UNK>" else metadata["unk_symbol"]])
                    require(vocabulary.size == if(srf) 8000 else metadata["vocab_size"]?.toInt())
                    require(labels.containsAll(listOf("_", "，", "。", "？")))
                    val ids = tokens.map { vocabulary[if(srf) it.text else it.text.lowercase(Locale.ROOT)] ?: unknown }
                    // Overlap retains context; each token keeps the prediction furthest from a window edge.
                    for (start in windowStarts(tokens.size)) {
                        cancellation.check()
                        val length = minOf(WINDOW, tokens.size - start)
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
                                                predictions[start + j] = PunctuationPrediction.choose(scores, j * labels.size, labels,
                                                    conservative = srf, terminal = start + j == tokens.lastIndex)
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
            // A model/runtime failure must not discard otherwise valid Whisper text.
            return PunctuationResult(text, SystemClock.elapsedRealtime() - started, false, true)
        } catch (error: LinkageError) {
            cancellation.check()
            return PunctuationResult(text, SystemClock.elapsedRealtime() - started, false, true)
        }
    }

    companion object {
        const val MODEL_NAME = BuildConfig.PUNCTUATION_MODEL_NAME
        const val MODEL_BYTES = BuildConfig.PUNCTUATION_MODEL_BYTES
        const val MODEL_SHA256 = BuildConfig.PUNCTUATION_MODEL_SHA256
        private const val WINDOW = 128
        internal fun windowStarts(size: Int): List<Int> = if (size <= WINDOW) listOf(0)
            else ((0 until size - WINDOW step 96).toList() + (size - WINDOW)).distinct()
    }
}
