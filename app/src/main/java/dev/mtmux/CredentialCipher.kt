package dev.mtmux

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** A device-bound wrapping key, not an SSH signing key. Never export it. */
internal class CredentialCipher {
    private val alias = "mtmux.saved-credentials.v1"
    private fun key(create: Boolean): SecretKey = synchronized(CredentialCipher::class.java) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: run {
            if (!create) throw AppError(R.string.err_keystore_unavailable)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    }
    private fun aad(profile: ServerProfile) = JSONObject().put("v", 1).put("id", profile.id)
        .put("host", profile.host).put("port", profile.port).put("user", profile.user)
        .put("key", profile.keyAuthentication).also { if (profile.jumps.isNotEmpty()) it.put("route", profile.routeContext()) }.toString().toByteArray(Charsets.UTF_8)

    fun encrypt(profile: ServerProfile, bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad(profile))
        return JSONObject().put("v", 1).put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("data", Base64.getEncoder().encodeToString(cipher.doFinal(bytes))).toString()
    }
    fun decrypt(profile: ServerProfile, encoded: String): ByteArray {
        val record = JSONObject(encoded)
        if (record.getInt("v") != 1) throw AppError(R.string.err_credential_format)
        val iv = Base64.getDecoder().decode(record.getString("iv"))
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(profile))
        return cipher.doFinal(Base64.getDecoder().decode(record.getString("data")))
    }
}

/** Intentionally not a data class: toString must not reveal credentials. */
class SavedCredentials(val password: String = "", val privateKey: ByteArray? = null, val passphrase: String = "", val jumps: Map<String, SavedCredentials> = emptyMap())
