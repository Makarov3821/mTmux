package dev.mtmux.audiotest

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    const val MAX_SAMPLES = 16000 * 60
    fun read(input: InputStream): FloatArray {
        // Bounded even for an untrusted content provider. Only lossless 16k mono PCM16 is accepted.
        val bytes = input.use { source ->
            val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
            val limit = MAX_SAMPLES * 2 + 65536 + 1
            while (out.size() < limit) {
                val n = source.read(chunk, 0, minOf(chunk.size, limit - out.size()))
                if (n < 0) break
                out.write(chunk, 0, n)
            }
            out.toByteArray()
        }
        require(bytes.size <= MAX_SAMPLES * 2 + 65536) { "WAV 过大，最长 60 秒" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun four() = ByteArray(4).also { require(b.remaining() >= 4); b.get(it) }.toString(Charsets.US_ASCII)
        require(four() == "RIFF"); require(b.remaining() >= 4); b.int; require(four() == "WAVE")
        var validFormat = false; var samples: FloatArray? = null
        while (b.remaining() >= 8) {
            val id = four(); val size = b.int.toLong() and 0xffffffffL
            require(size <= b.remaining()) { "WAV 文件截断" }
            val end = b.position() + size.toInt()
            when (id) {
                "fmt " -> {
                    require(size >= 16)
                    val format = b.short.toInt(); val channels = b.short.toInt(); val rate = b.int
                    b.int; val align = b.short.toInt(); val bits = b.short.toInt()
                    require(format == 1 && channels == 1 && rate == 16000 && bits == 16 && align == 2) { "请导入 16 kHz、单声道、PCM16 WAV" }
                    validFormat = true
                }
                "data" -> {
                    require(samples == null && size % 2 == 0L && size / 2 in 1600..MAX_SAMPLES) { "录音应为 0.1～60 秒" }
                    samples = FloatArray(size.toInt() / 2) { b.short / 32768f }
                }
            }
            b.position(end)
            if (size % 2 == 1L) { require(b.remaining() > 0); b.get() }
        }
        require(validFormat && samples != null) { "WAV 缺少 fmt 或 data" }
        return samples
    }
    fun write(output: OutputStream, samples: FloatArray) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples.size * 2)
        output.write(header.array())
        val chunk = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        for (x in samples) {
            if (chunk.remaining() < 2) { output.write(chunk.array(), 0, chunk.position()); chunk.clear() }
            chunk.putShort((x * 32768).toInt().coerceIn(-32768, 32767).toShort())
        }
        output.write(chunk.array(), 0, chunk.position())
    }
}
