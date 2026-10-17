package dev.retrobit.voice.assistant

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Serialized, validated payload for an encrypted personal wake-word profile. */
data class WakeWordProfile(
    val phrase: String,
    val templates: List<WakeWordEmbeddingSequence>,
)

/** Pure codec so profile validation and round-tripping can be unit-tested without Android Keystore. */
object WakeWordProfileCodec {
    private const val MAGIC = 0x52565750 // RWVP
    private const val VERSION = 1
    const val MAX_PHRASE_LENGTH = 128
    private const val REQUIRED_TEMPLATE_COUNT = 3
    private const val MAX_TEMPLATE_FRAMES = 128
    private const val MAX_PAYLOAD_BYTES = 1_000_000

    fun encode(phrase: String, templates: List<WakeWordEmbeddingSequence>): ByteArray {
        val normalizedPhrase = WakeWordAudioFeatures.normalizePhrase(phrase)
        require(normalizedPhrase.isNotBlank() && normalizedPhrase.length <= MAX_PHRASE_LENGTH) {
            "Frasa wake word kosong atau terlalu panjang"
        }
        require(templates.size == REQUIRED_TEMPLATE_COUNT) { "Profil harus memuat tiga sampel wake word" }
        require(templates.all { it.frameCount in 1..MAX_TEMPLATE_FRAMES }) { "Durasi sampel wake word tidak didukung" }

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeUTF(normalizedPhrase)
            output.writeInt(templates.size)
            templates.forEach { template ->
                output.writeInt(template.frameCount)
                template.values.forEach { value ->
                    require(value.isFinite()) { "Embedding wake word mengandung nilai tidak valid" }
                    output.writeFloat(value)
                }
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_PAYLOAD_BYTES) { "Profil wake word terlalu besar" } }
    }

    fun decode(payload: ByteArray): WakeWordProfile {
        require(payload.size in 12..MAX_PAYLOAD_BYTES) { "Ukuran profil wake word tidak valid" }
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw IllegalStateException("Versi profil wake word tidak didukung")
            }
            val phrase = input.readUTF()
            if (
                phrase.isBlank() || phrase.length > MAX_PHRASE_LENGTH ||
                phrase != WakeWordAudioFeatures.normalizePhrase(phrase)
            ) {
                throw IllegalStateException("Frasa profil wake word tidak valid")
            }
            val templateCount = input.readInt()
            if (templateCount != REQUIRED_TEMPLATE_COUNT) {
                throw IllegalStateException("Profil wake word harus memuat tiga sampel")
            }
            val templates = List(templateCount) {
                val frameCount = input.readInt()
                if (frameCount !in 1..MAX_TEMPLATE_FRAMES) {
                    throw IllegalStateException("Durasi sampel wake word tidak valid")
                }
                val values = FloatArray(frameCount * WakeWordAudioFeatures.EMBEDDING_DIMENSION)
                for (index in values.indices) {
                    values[index] = input.readFloat()
                    if (!values[index].isFinite()) throw IllegalStateException("Embedding wake word rusak")
                }
                WakeWordEmbeddingSequence(frameCount, values)
            }
            if (input.available() != 0) throw IllegalStateException("Data tambahan pada profil wake word")
            return WakeWordProfile(phrase, templates)
        }
    }
}

/** Device-local authenticated encryption for speech-embedding templates; never stores raw audio. */
class WakeWordProfileStore(filesDir: File) {
    private val profileFile = AtomicFile(File(filesDir, PROFILE_FILE_NAME))

    fun hasProfileForPhrase(phrase: String): Boolean = readProfile(phrase) != null

    fun readProfile(phrase: String): WakeWordProfile? {
        val packed = try {
            profileFile.openRead().use { input -> input.readBytes() }
        } catch (_: FileNotFoundException) {
            return null
        }
        if (packed.size !in (IV_LENGTH + GCM_TAG_BYTES)..MAX_ENCRYPTED_BYTES) {
            throw IllegalStateException("Ukuran profil wake word rusak")
        }
        val iv = packed.copyOfRange(0, IV_LENGTH)
        val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyOrCreate(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AUTHENTICATED_DATA)
        val profile = WakeWordProfileCodec.decode(cipher.doFinal(ciphertext))
        return profile.takeIf { it.phrase == WakeWordAudioFeatures.normalizePhrase(phrase) }
    }

    fun writeProfile(phrase: String, templates: List<WakeWordEmbeddingSequence>) {
        val payload = WakeWordProfileCodec.encode(phrase, templates)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyOrCreate())
        cipher.updateAAD(AUTHENTICATED_DATA)
        val packed = cipher.iv + cipher.doFinal(payload)
        if (packed.size > MAX_ENCRYPTED_BYTES) throw IllegalArgumentException("Profil wake word terlalu besar")

        var output: FileOutputStream? = null
        try {
            output = profileFile.startWrite()
            output.write(packed)
            profileFile.finishWrite(output)
        } catch (error: Throwable) {
            output?.let(profileFile::failWrite)
            throw error
        }
    }

    fun clear() {
        profileFile.delete()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun keyOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "retrobit_wake_word_profile_v1"
        const val PROFILE_FILE_NAME = "wake-word-profile-v1.enc"
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val MAX_ENCRYPTED_BYTES = 1_000_000
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AUTHENTICATED_DATA = "retrobit-wake-word-profile-v1".toByteArray(Charsets.UTF_8)
    }
}
