package dev.retrobit.voice.speaker

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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local, authenticated encryption for the speaker embedding. Never falls back to plaintext. */
class SpeakerProfileStore(filesDir: File) {
    private val profileFile = AtomicFile(File(filesDir, PROFILE_FILE_NAME))

    fun hasProfile(): Boolean = readEmbedding() != null

    fun readEmbedding(): FloatArray? {
        val packed = try {
            profileFile.openRead().use { input -> input.readBytes() }
        } catch (_: FileNotFoundException) {
            return null
        }
        if (packed.size < IV_LENGTH + GCM_TAG_BYTES) throw IllegalStateException("Profil suara rusak")
        val iv = packed.copyOfRange(0, IV_LENGTH)
        val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyOrCreate(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AUTHENTICATED_DATA)
        val payload = cipher.doFinal(ciphertext)
        return decodePayload(payload)
    }

    fun writeEmbedding(embedding: FloatArray) {
        val normalized = SpeakerAudioFeatures.normalize(embedding)
            ?: throw IllegalArgumentException("Embedding suara tidak valid")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyOrCreate())
        cipher.updateAAD(AUTHENTICATED_DATA)
        val ciphertext = cipher.doFinal(encodePayload(normalized))
        val packed = cipher.iv + ciphertext

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

    private fun encodePayload(embedding: FloatArray): ByteArray {
        val bytes = ByteArrayOutputStream(12 + embedding.size * Float.SIZE_BYTES)
        DataOutputStream(bytes).use { output ->
            output.writeInt(PROFILE_MAGIC)
            output.writeInt(PROFILE_VERSION)
            output.writeInt(embedding.size)
            val values = ByteBuffer.allocate(embedding.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            embedding.forEach(values::putFloat)
            output.write(values.array())
        }
        return bytes.toByteArray()
    }

    private fun decodePayload(payload: ByteArray): FloatArray {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            if (input.readInt() != PROFILE_MAGIC || input.readInt() != PROFILE_VERSION) {
                throw IllegalStateException("Versi profil suara tidak didukung")
            }
            val count = input.readInt()
            if (count !in MIN_EMBEDDING_SIZE..MAX_EMBEDDING_SIZE || input.available() != count * Float.SIZE_BYTES) {
                throw IllegalStateException("Dimensi profil suara tidak valid")
            }
            val bytes = ByteArray(input.available())
            input.readFully(bytes)
            val values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val embedding = FloatArray(count) { values.float }
            return SpeakerAudioFeatures.normalize(embedding)
                ?: throw IllegalStateException("Profil suara tidak valid")
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "retrobit_speaker_profile_v1"
        const val PROFILE_FILE_NAME = "speaker-profile-v1.enc"
        const val PROFILE_MAGIC = 0x52565350 // RVSP
        const val PROFILE_VERSION = 1
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val MIN_EMBEDDING_SIZE = 16
        const val MAX_EMBEDDING_SIZE = 2_048
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AUTHENTICATED_DATA = "retrobit-speaker-profile-v1".toByteArray(Charsets.UTF_8)
    }
}
