package dev.retrobit.voice.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bentuk berkas cadangan setelan (JSON) — dipakai tombol Ekspor/Impor di Pengaturan.
 *
 * Sengaja dipisah dari [AppSettings]:
 *  - `schema` dan `app` membuat berkas asing bisa ditolak dengan pesan yang jelas,
 *  - enum disimpan sebagai teks supaya berkas lama tetap terbaca walau daftar
 *    nilai enum berubah kelak,
 *  - kolom preferensi bersifat opsional agar preset parsial dapat mengubah beberapa
 *    setelan tanpa menghapus preferensi lain (termasuk API key) yang sudah aktif,
 *  - [AppSettings] sendiri tetap bebas dari anotasi serialisasi.
 *
 * Ekspor cadangan penuh memuat API key dalam bentuk teks biasa — itu memang
 * tujuannya, supaya setelan bisa dipulihkan setelah uninstall, saat kunci Android
 * Keystore ikut terhapus. Karena itu berkas ekspor penuh harus disimpan aman.
 */
@Serializable
data class SettingsSnapshot(
    val schema: Int = SettingsSnapshotFormat.SCHEMA_VERSION,
    val app: String = SettingsSnapshotFormat.APP_ID,
    val versionName: String = "",
    val exportedAtMs: Long = 0L,

    // ---- Dikte (null = pertahankan nilai aktif; ekspor penuh selalu mengisinya) ----
    val sttEngine: String? = null,
    val cloudSttProvider: String? = null,
    val cloudSttApiKey: String? = null,
    val cloudSttBaseUrl: String? = null,
    val cloudSttModel: String? = null,
    val dictationLanguage: String? = null,
    val cleanupMode: String? = null,
    val autoPunctuation: Boolean? = null,
    val autoCapitalize: Boolean? = null,
    val insertMethod: String? = null,
    val dictationTrigger: String? = null,
    val volumeKeyTriggerEnabled: Boolean? = null,
    val volumeKeyAssistantEnabled: Boolean? = null,
    val silenceTimeoutMs: Int? = null,
    val keepScreenOnWhileDictating: Boolean? = null,

    // ---- Asisten ----
    val llmProvider: String? = null,
    val llmApiKey: String? = null,
    val llmBaseUrl: String? = null,
    val llmModel: String? = null,
    val wakeWordEnabled: Boolean? = null,
    val wakeWordPhrase: String? = null,
    val confirmBeforeActions: Boolean? = null,
    val screenContextEnabled: Boolean? = null,
    val speakReplies: Boolean? = null,
    val ttsVoiceName: String? = null,
    val ttsPitch: Float? = null,
    val ttsSpeechRate: Float? = null,
    val assistantLanguage: String? = null,

    // ---- Tampilan & data ----
    val overlayBubbleEnabled: Boolean? = null,
    val saveHistory: Boolean? = null,
    val epaperLightMode: Boolean? = null,
)

/** Berkas cadangan tidak bisa dipakai; [message] sudah siap ditampilkan ke pengguna. */
class InvalidSnapshotException(message: String) : Exception(message)

/** Aturan tetap format cadangan: nomor skema, penanda aplikasi, dan parser JSON. */
object SettingsSnapshotFormat {

    /** Skema 2 menambahkan impor parsial; skema 1 (ekspor penuh lama) tetap didukung. */
    const val SCHEMA_VERSION: Int = 2
    const val APP_ID: String = "retrobit-voice"

    /** Nama berkas yang disarankan saat pengguna memilih lokasi ekspor. */
    const val SUGGESTED_FILE_NAME: String = "retrobit-setelan.json"

    /**
     * `encodeDefaults = true` supaya berkas hasil ekspor bisa dibaca & disunting manusia
     * (semua preferensi tampak, bukan hanya yang berbeda dari bawaan).
     * `ignoreUnknownKeys = true` supaya berkas dari versi lebih baru tetap bisa
     * diimpor sebagian alih-alih gagal total.
     */
    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}

/** Ubah setelan aktif menjadi bentuk yang bisa disimpan ke berkas. */
fun AppSettings.toSnapshot(
    versionName: String = "",
    nowMs: Long = System.currentTimeMillis(),
): SettingsSnapshot = SettingsSnapshot(
    versionName = versionName,
    exportedAtMs = nowMs,
    sttEngine = sttEngine.name,
    cloudSttProvider = cloudSttProvider.name,
    cloudSttApiKey = cloudSttApiKey,
    cloudSttBaseUrl = cloudSttBaseUrl,
    cloudSttModel = cloudSttModel,
    dictationLanguage = dictationLanguage,
    cleanupMode = cleanupMode.name,
    autoPunctuation = autoPunctuation,
    autoCapitalize = autoCapitalize,
    insertMethod = insertMethod.name,
    dictationTrigger = dictationTrigger.name,
    volumeKeyTriggerEnabled = volumeKeyTriggerEnabled,
    volumeKeyAssistantEnabled = volumeKeyAssistantEnabled,
    silenceTimeoutMs = silenceTimeoutMs,
    keepScreenOnWhileDictating = keepScreenOnWhileDictating,
    llmProvider = llmProvider.name,
    llmApiKey = llmApiKey,
    llmBaseUrl = llmBaseUrl,
    llmModel = llmModel,
    wakeWordEnabled = wakeWordEnabled,
    wakeWordPhrase = wakeWordPhrase,
    confirmBeforeActions = confirmBeforeActions,
    screenContextEnabled = screenContextEnabled,
    speakReplies = speakReplies,
    ttsVoiceName = ttsVoiceName,
    ttsPitch = ttsPitch,
    ttsSpeechRate = ttsSpeechRate,
    assistantLanguage = assistantLanguage,
    overlayBubbleEnabled = overlayBubbleEnabled,
    saveHistory = saveHistory,
    epaperLightMode = epaperLightMode,
)

/**
 * Terapkan isi cadangan/preset ke setelan.
 *
 * [current] dipakai sebagai dasar: field yang tidak ada (atau null) dan enum
 * yang tidak dikenal tetap memakai nilai aktif, bukan direset ke bawaan. Nilai
 * string kosong yang eksplisit tetap bisa mengosongkan field seperti API key.
 * `lastOnboardingStep` memang tidak ikut dipulihkan: itu status UI, bukan preferensi.
 */
fun SettingsSnapshot.toSettings(current: AppSettings = AppSettings()): AppSettings = current.copy(
    sttEngine = enumOrDefault(sttEngine, current.sttEngine),
    cloudSttProvider = enumOrDefault(cloudSttProvider, current.cloudSttProvider),
    cloudSttApiKey = cloudSttApiKey ?: current.cloudSttApiKey,
    cloudSttBaseUrl = cloudSttBaseUrl ?: current.cloudSttBaseUrl,
    cloudSttModel = cloudSttModel ?: current.cloudSttModel,
    dictationLanguage = dictationLanguage?.ifBlank { current.dictationLanguage } ?: current.dictationLanguage,
    cleanupMode = enumOrDefault(cleanupMode, current.cleanupMode),
    autoPunctuation = autoPunctuation ?: current.autoPunctuation,
    autoCapitalize = autoCapitalize ?: current.autoCapitalize,
    insertMethod = enumOrDefault(insertMethod, current.insertMethod),
    dictationTrigger = enumOrDefault(dictationTrigger, current.dictationTrigger),
    volumeKeyTriggerEnabled = volumeKeyTriggerEnabled ?: current.volumeKeyTriggerEnabled,
    volumeKeyAssistantEnabled = volumeKeyAssistantEnabled ?: current.volumeKeyAssistantEnabled,
    silenceTimeoutMs = silenceTimeoutMs ?: current.silenceTimeoutMs,
    keepScreenOnWhileDictating = keepScreenOnWhileDictating ?: current.keepScreenOnWhileDictating,
    llmProvider = enumOrDefault(llmProvider, current.llmProvider),
    llmApiKey = llmApiKey ?: current.llmApiKey,
    llmBaseUrl = llmBaseUrl ?: current.llmBaseUrl,
    llmModel = llmModel ?: current.llmModel,
    wakeWordEnabled = wakeWordEnabled ?: current.wakeWordEnabled,
    wakeWordPhrase = wakeWordPhrase?.ifBlank { current.wakeWordPhrase } ?: current.wakeWordPhrase,
    confirmBeforeActions = confirmBeforeActions ?: current.confirmBeforeActions,
    screenContextEnabled = screenContextEnabled ?: current.screenContextEnabled,
    speakReplies = speakReplies ?: current.speakReplies,
    ttsVoiceName = ttsVoiceName ?: current.ttsVoiceName,
    ttsPitch = ttsPitch?.coerceIn(0.6f, 1.6f) ?: current.ttsPitch,
    ttsSpeechRate = ttsSpeechRate?.coerceIn(0.6f, 1.6f) ?: current.ttsSpeechRate,
    assistantLanguage = assistantLanguage?.ifBlank { current.assistantLanguage } ?: current.assistantLanguage,
    overlayBubbleEnabled = overlayBubbleEnabled ?: current.overlayBubbleEnabled,
    saveHistory = saveHistory ?: current.saveHistory,
    epaperLightMode = epaperLightMode ?: current.epaperLightMode,
)

/** Ringkasan isi backup/preset untuk konfirmasi setelah impor. */
fun SettingsSnapshot.describe(): String = buildList {
    val engine = sttEngine?.takeIf { it.isNotBlank() }?.lowercase() ?: "tidak diubah"
    add("STT $engine")
    when (val llm = llmProvider) {
        null -> add("asisten tidak diubah")
        LlmProvider.OFF.name -> add("asisten nonaktif")
        else -> add("LLM ${llm.lowercase()}")
    }
    val keys = listOf(cloudSttApiKey, llmApiKey)
    when {
        keys.any { !it.isNullOrBlank() } -> add("dengan API key")
        keys.all { it != null } -> add("tanpa API key")
        else -> add("API key tidak diubah")
    }
}.joinToString(" · ")

/** Serialisasi ke teks JSON siap tulis. */
fun SettingsSnapshot.encode(): String = SettingsSnapshotFormat.json.encodeToString(SettingsSnapshot.serializer(), this)

/**
 * Baca teks JSON menjadi [SettingsSnapshot].
 *
 * @throws InvalidSnapshotException kalau berkasnya bukan cadangan Retrobit Voice,
 *   dibuat aplikasi versi lebih baru, atau bukan JSON yang sah.
 */
fun decodeSettingsSnapshot(text: String): SettingsSnapshot {
    val snapshot = runCatching {
        SettingsSnapshotFormat.json.decodeFromString(SettingsSnapshot.serializer(), text)
    }.getOrElse {
        throw InvalidSnapshotException("Berkas bukan cadangan setelan Retrobit yang sah")
    }

    if (snapshot.app != SettingsSnapshotFormat.APP_ID) {
        throw InvalidSnapshotException("Berkas ini bukan cadangan dari Retrobit Voice")
    }
    if (snapshot.schema > SettingsSnapshotFormat.SCHEMA_VERSION) {
        throw InvalidSnapshotException(
            "Cadangan dibuat aplikasi versi lebih baru (skema ${snapshot.schema}) — perbarui Retrobit Voice dulu",
        )
    }
    return snapshot
}

private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
