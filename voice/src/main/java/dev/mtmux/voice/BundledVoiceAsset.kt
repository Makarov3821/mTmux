package dev.mtmux.voice

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Only an exact pinned asset is installed. Call from a background worker. */
internal class BundledVoiceAsset(private val name: String, private val bytes: Long, private val sha256: String) {
    @Volatile private var verified: File? = null

    @Synchronized fun file(context: Context): File {
        verified?.takeIf { it.isFile && it.length() == bytes }?.let { return it }
        val directory = File(context.noBackupFilesDir, "voice").apply { mkdirs() }
        val target = File(directory, name).also { it.parentFile!!.mkdirs() }
        if (!valid(target)) {
            target.delete()
            val temporary = File(directory, "$name.part").apply { delete() }
            try {
                context.assets.open("models/$name").use { source ->
                    temporary.outputStream().buffered().use { output -> source.copyTo(output, 1024 * 1024) }
                }
                check(valid(temporary)) { "VOICE_MODEL_CHECKSUM" }
                check(temporary.renameTo(target)) { "VOICE_MODEL_INSTALL" }
            } finally { temporary.delete() }
        }
        verified = target
        return target
    }

    private fun valid(file: File): Boolean {
        if (!file.isFile || file.length() != bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val block = ByteArray(1024 * 1024)
            while (true) { val count = input.read(block); if (count < 0) break; digest.update(block, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == sha256
    }
}
