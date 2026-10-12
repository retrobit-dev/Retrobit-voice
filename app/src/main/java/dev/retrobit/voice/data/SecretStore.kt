package dev.retrobit.voice.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Penyimpanan rahasia sederhana (API key) di Android Keystore.
 *
 * Format tersimpan: "enc:<base64(iv + ciphertext)>".
 * Kalau Keystore tidak tersedia (mis. saat unit test di JVM), otomatis
 * memakai "plain:" supaya aplikasi tidak crash — dan UI memberi peringatan.
 */
object SecretStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "retrobit_voice_master"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun keyOrNull(): SecretKey? = try {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()
    } catch (t: Throwable) {
        null
    }

    private fun generateKey(): SecretKey? = try {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        gen.generateKey()
    } catch (t: Throwable) {
        null
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val key = keyOrNull() ?: return "plain:$plain"
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + out
            "enc:" + Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (t: Throwable) {
            "plain:$plain"
        }
    }

    fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        if (stored.startsWith("plain:")) return stored.removePrefix("plain:")
        if (!stored.startsWith("enc:")) return stored
        val key = keyOrNull() ?: return ""
        return try {
            val packed = Base64.decode(stored.removePrefix("enc:"), Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, IV_LEN)
            val body = packed.copyOfRange(IV_LEN, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }
}
