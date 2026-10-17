package dev.mtmux.voicebenchmark

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/** Strict V0 input: one complete RIFF/WAVE PCM, mono, 16 kHz, signed 16-bit, at most 60 seconds. */
internal object WavPcm16 {
    fun readMono16k(file: File): FloatArray = RandomAccessFile(file, "r").use { input ->
        require(file.length() in 44..MAX_FILE_BYTES) { "invalid WAV file size" }
        require(input.readAscii(4) == "RIFF") { "not a RIFF file" }
        val riffBytes = input.readU32()
        require(input.readAscii(4) == "WAVE") { "not a WAVE file" }
        val riffEnd = riffBytes + 8
        // Reject trailing bytes as well as a container that claims less/more than the file.
        require(riffBytes >= 36 && riffEnd == file.length()) { "RIFF size does not match file" }

        var format: Format? = null
        var dataOffset = -1L
        var dataBytes = -1L
        while (input.filePointer < riffEnd) {
            require(riffEnd - input.filePointer >= 8) { "truncated WAV chunk header" }
            val type = input.readAscii(4)
            val bytes = input.readU32()
            val start = input.filePointer
            val paddedBytes = bytes + (bytes and 1)
            require(bytes >= 0 && paddedBytes >= bytes && paddedBytes <= riffEnd - start) { "WAV chunk exceeds RIFF container" }
            when (type) {
                "fmt " -> {
                    require(format == null && bytes >= 16) { "invalid or duplicate WAV format" }
                    format = Format(input.readU16(), input.readU16(), input.readU32(), input.readU32(), input.readU16(), input.readU16())
                }
                "data" -> {
                    require(dataOffset < 0) { "duplicate WAV data" }
                    dataOffset = start
                    dataBytes = bytes
                }
            }
            // The padded byte for every odd-sized chunk must physically exist inside RIFF.
            input.seek(start + paddedBytes)
        }
        require(input.filePointer == riffEnd) { "invalid RIFF boundary" }
        val f = requireNotNull(format) { "missing WAV format" }
        require(f.encoding == 1 && f.channels == 1 && f.sampleRate == 16000L && f.bits == 16) { "WAV must be mono 16 kHz PCM16" }
        require(f.blockAlign == 2 && f.byteRate == 32000L) { "invalid PCM layout" }
        require(dataOffset >= 0 && dataBytes in 3200..(16000L * 60 * 2) && dataBytes % 2 == 0L) { "audio must be 0.1 to 60 seconds" }

        input.seek(dataOffset)
        FloatArray((dataBytes / 2).toInt()) {
            val low = input.read()
            val high = input.read()
            if (low < 0 || high < 0) throw EOFException()
            ((high shl 8 or low).toShort() / 32768.0f)
        }
    }

    private data class Format(val encoding: Int, val channels: Int, val sampleRate: Long,
                              val byteRate: Long, val blockAlign: Int, val bits: Int)
    private fun RandomAccessFile.readAscii(n: Int) = ByteArray(n).also { readFully(it) }.toString(Charsets.US_ASCII)
    private fun RandomAccessFile.readU16(): Int {
        val low = read(); val high = read()
        if (low < 0 || high < 0) throw EOFException()
        return low or (high shl 8)
    }
    private fun RandomAccessFile.readU32(): Long = readU16().toLong() or (readU16().toLong() shl 16)
    private const val MAX_FILE_BYTES = 2_000_000L
}
