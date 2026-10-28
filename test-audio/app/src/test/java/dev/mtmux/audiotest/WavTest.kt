package dev.mtmux.audiotest

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    private fun encoded() = ByteArrayOutputStream().also { out -> Wav.write(out, FloatArray(3200) { (it % 100 - 50) / 100f }) }.toByteArray()
    @Test fun preservesRecordedPcmBitForBit() {
        val original = FloatArray(3200) { (it % 65536 - 32768) / 32768f }
        val out = ByteArrayOutputStream(); Wav.write(out, original)
        assertArrayEquals(original, Wav.read(ByteArrayInputStream(out.toByteArray())), 0f)
    }
    @Test fun rejectsWrongSampleRateInsteadOfChangingTheBenchmark() {
        val bytes = encoded(); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 44100)
        assertThrows(IllegalArgumentException::class.java) { Wav.read(bytes.inputStream()) }
    }
    @Test fun rejectsStereo() {
        val bytes = encoded(); bytes[22] = 2
        assertThrows(IllegalArgumentException::class.java) { Wav.read(bytes.inputStream()) }
    }
    @Test fun rejectsTruncatedAudio() {
        assertThrows(IllegalArgumentException::class.java) { Wav.read(encoded().dropLast(1).toByteArray().inputStream()) }
    }
    @Test fun skipsUnknownRiffChunks() {
        val bytes = encoded()
        val chunk = byteArrayOf(74,85,78,75,3,0,0,0,7,8,9,0)
        val withJunk = bytes.take(12).toByteArray() + chunk + bytes.drop(12).toByteArray()
        assertArrayEquals(Wav.read(bytes.inputStream()), Wav.read(withJunk.inputStream()), 0f)
    }
    @Test fun rejectsOverlongClip() {
        val bytes = ByteArrayOutputStream().also { Wav.write(it, FloatArray(Wav.MAX_SAMPLES + 1)) }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { Wav.read(bytes.inputStream()) }
    }
    @Test fun acceptsFullMinute() {
        val bytes = ByteArrayOutputStream().also { Wav.write(it, FloatArray(Wav.MAX_SAMPLES)) }.toByteArray()
        assertEquals(Wav.MAX_SAMPLES, Wav.read(bytes.inputStream()).size)
    }
}
