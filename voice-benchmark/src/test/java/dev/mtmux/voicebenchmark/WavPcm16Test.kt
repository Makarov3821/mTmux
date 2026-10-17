package dev.mtmux.voicebenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

class WavPcm16Test {
    @Test fun readsMono16kPcmAndNormalizesSamples() {
        val values = ShortArray(1600).also { it[0] = Short.MIN_VALUE; it[1] = -1; it[2] = 0; it[3] = 1; it[4] = Short.MAX_VALUE }
        val pcm = WavPcm16.readMono16k(temporaryWav(samples = values))
        assertEquals(1600, pcm.size)
        assertEquals(-1.0f, pcm[0], 0f)
        assertEquals(0.0f, pcm[2], 0f)
        assertEquals(Short.MAX_VALUE / 32768.0f, pcm[4], 0f)
    }

    @Test fun acceptsPaddedUnknownChunkInsideRiff() {
        val pcm = WavPcm16.readMono16k(temporaryWav(samples = ShortArray(1600), oddJunkWithPad = true))
        assertEquals(1600, pcm.size)
    }

    @Test fun rejectsRiffSizeMismatchAndMissingOddPadding() {
        val declaredShort = temporaryWav(samples = ShortArray(1600)).also { patchU32(it, 4, 36) }
        assertInvalid(declaredShort)
        val declaredLong = temporaryWav(samples = ShortArray(1600)).also { patchU32(it, 4, it.length().toInt()) }
        assertInvalid(declaredLong)
        assertInvalid(temporaryWav(samples = ShortArray(1600), oddJunkWithoutPad = true))
    }

    @Test fun rejectsWrongEncodingChannelsRateBitsAndLayout() {
        assertInvalid(temporaryWav(rate = 8000, samples = ShortArray(1600)))
        assertInvalid(temporaryWav(encoding = 3, samples = ShortArray(1600)))
        assertInvalid(temporaryWav(channels = 2, samples = ShortArray(1600)))
        assertInvalid(temporaryWav(bits = 8, samples = ShortArray(1600)))
        assertInvalid(temporaryWav(byteRate = 123, samples = ShortArray(1600)))
        assertInvalid(temporaryWav(blockAlign = 4, samples = ShortArray(1600)))
    }

    @Test fun rejectsTooShortTooLongTruncatedAndDuplicateChunks() {
        assertInvalid(temporaryWav(samples = ShortArray(1599)))
        assertInvalid(temporaryWav(samples = ShortArray(960001)))
        val truncated = temporaryWav(samples = ShortArray(1600)).apply {
            RandomAccessFile(this, "rw").use { it.setLength(length() - 1) }
        }
        assertInvalid(truncated)
        assertInvalid(temporaryWav(samples = ShortArray(1600), duplicateFormat = true))
        assertInvalid(temporaryWav(samples = ShortArray(1600), duplicateData = true))
    }

    private fun assertInvalid(file: File) {
        assertThrows(IllegalArgumentException::class.java) { WavPcm16.readMono16k(file) }
    }

    private fun temporaryWav(rate: Int = 16000, encoding: Int = 1, channels: Int = 1,
                             bits: Int = 16, byteRate: Int = rate * channels * bits / 8,
                             blockAlign: Int = channels * bits / 8, samples: ShortArray,
                             oddJunkWithPad: Boolean = false, oddJunkWithoutPad: Boolean = false,
                             duplicateFormat: Boolean = false, duplicateData: Boolean = false): File {
        val body = ByteArrayOutputStream()
        fun ascii(value: String) = body.write(value.toByteArray(Charsets.US_ASCII))
        fun u16(value: Int) { body.write(value and 255); body.write(value ushr 8 and 255) }
        fun u32(value: Int) { u16(value and 0xffff); u16(value ushr 16) }
        fun format() {
            ascii("fmt "); u32(16); u16(encoding); u16(channels); u32(rate); u32(byteRate); u16(blockAlign); u16(bits)
        }
        fun data() {
            ascii("data"); u32(samples.size * 2); samples.forEach { u16(it.toInt() and 0xffff) }
        }
        format()
        if (duplicateFormat) format()
        if (oddJunkWithPad || oddJunkWithoutPad) {
            ascii("JUNK"); u32(1); body.write(7)
            if (oddJunkWithPad) body.write(0)
        }
        data()
        if (duplicateData) data()
        val payload = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray()); writeU32(out, 4 + payload.size); out.write("WAVE".toByteArray()); out.write(payload)
        return File.createTempFile("mtmux-wav-", ".wav").apply { deleteOnExit(); writeBytes(out.toByteArray()) }
    }

    private fun patchU32(file: File, offset: Long, value: Int) = RandomAccessFile(file, "rw").use {
        it.seek(offset); repeat(4) { byte -> it.write(value ushr (8 * byte) and 255) }
    }
    private fun writeU32(out: ByteArrayOutputStream, value: Int) = repeat(4) { byte -> out.write(value ushr (8 * byte) and 255) }
}
