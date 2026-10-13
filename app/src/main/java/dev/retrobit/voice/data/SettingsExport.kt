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
 *  - [AppSettings] sendiri tetap bebas dari anotasi serialisasi.
 *
 * Berkas ini memuat API key dalam bentuk teks biasa — itu memang tujuannya, supaya
 * setelan bisa dipulihkan setelah uninstall, saat kunci Android Keystore sudah ikut
 * terhapus. Karena itu berkasnya harus disimpan di tempat aman (peringatan juga
 * ditampilkan di layar Pengaturan).
 */
@Serializable
data class SettingsSnapshot(
    val schema: Int = SettingsSnapshotFormat.SCHEMA_VERSION,
    val app: String = SettingsSnapshotFormat.APP_ID,
    val versionName: String = "",
    val exportedAtMs: Long = 0L,

    // ---- Dikte ----
    val sttEngine: String = SttEngineChoice.AUTO.name,
    val cloudSttProvider: String = CloudSttProvider.OPENAI.name,
    val cloudSttApiKey: String = "",
    val cloudSttBaseUrl: String = "",
    val cloudSttModel: String = "",
    val dictationLanguage: String = "id-ID",
    val cleanupMode: String = CleanupMode.LOCAL.name,
    val autoPunctuation: Boolean = true,
    val autoCapitalize: Boolean = true,
    val insertMethod: String = InsertMethod.AUTO.name,
    val dictationTrigger: String = DictationTrigger.HOLD_TO_TALK.name,
    val volumeKeyTriggerEnabled: Boolean = false,
    val silenceTimeoutMs: Int = 1600,
    val keepScreenOnWhileDictating: Boolean = false,

    // ---- Asisten ----
    val llmProvider: String = LlmProvider.OFF.name,
    val llmApiKey: String = "",
    val llmBaseUrl: String = "",
    val llmModel: String = "",
    val wakeWordEnabled: Boolean = false,
    val wakeWordPhrase: String = "hai retro",
    val confirmBeforeActions: Boolean = true,
    val speakReplies: Boolean = true,
    val assistantLanguage: String = "id-ID",

    // ---- Tampilan & data ----
    val overlayBubbleEnabled: Boolean = false,
    val saveHistory: Boolean = true,
    val epaperLightMode: Boolean = false,
)

/** Berkas cadangan tidak bisa dipakai; [message] sudah siap ditampilkan ke pengguna. */
class InvalidSnapshotException(message: String) : Exception(message)

/** Aturan tetap format cadangan: nomor skema, penanda aplikasi, dan parser JSON. */
object SettingsSnapshotFormat {

    const val SCHEMA_VERSION: Int = 1
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
    silenceTimeoutMs = silenceTimeoutMs,
    keepScreenOnWhileDictating = keepScreenOnWhileDictating,
    llmProvider = llmProvider.name,
    llmApiKey = llmApiKey,
    llmBaseUrl = llmBaseUrl,
    llmModel = llmModel,
    wakeWordEnabled = wakeWordEnabled,
    wakeWordPhrase = wakeWordPhrase,
    confirmBeforeActions = confirmBeforeActions,
    speakReplies = speakReplies,
    assistantLanguage = assistantLanguage,
    overlayBubbleEnabled = overlayBubbleEnabled,
    saveHistory = saveHistory,
    epaperLightMode = epaperLightMode,
)

/**
 * Terapkan isi cadangan ke setelan.
 *
 * [current] dipakai sebagai dasar, jadi field yang tidak ada di berkas (atau nilainya
 * tidak dikenal) tetap memakai nilai yang sedang aktif — bukan direset ke bawaan.
 * `lastOnboardingStep` memang tidak ikut dipulihkan: itu status UI, bukan preferensi.
 */
fun SettingsSnapshot.toSettings(current: AppSettings = AppSettings()): AppSettings = current.copy(
    sttEngine = enumOrDefault(sttEngine, current.sttEngine),
    cloudSttProvider = enumOrDefault(cloudSttProvider, current.cloudSttProvider),
    cloudSttApiKey = cloudSttApiKey,
    cloudSttBaseUrl = cloudSttBaseUrl,
    cloudSttModel = cloudSttModel,
    dictationLanguage = dictationLanguage.ifBlank { current.dictationLanguage },
    cleanupMode = enumOrDefault(cleanupMode, current.cleanupMode),
    autoPunctuation = autoPunctuation,
    autoCapitalize = autoCapitalize,
    insertMethod = enumOrDefault(insertMethod, current.insertMethod),
    dictationTrigger = enumOrDefault(dictationTrigger, current.dictationTrigger),
    volumeKeyTriggerEnabled = volumeKeyTriggerEnabled,
    silenceTimeoutMs = silenceTimeoutMs,
    keepScreenOnWhileDictating = keepScreenOnWhileDictating,
    llmProvider = enumOrDefault(llmProvider, current.llmProvider),
    llmApiKey = llmApiKey,
    llmBaseUrl = llmBaseUrl,
    llmModel = llmModel,
    wakeWordEnabled = wakeWordEnabled,
    wakeWordPhrase = wakeWordPhrase.ifBlank { current.wakeWordPhrase },
    confirmBeforeActions = confirmBeforeActions,
    speakReplies = speakReplies,
    assistantLanguage = assistantLanguage.ifBlank { current.assistantLanguage },
    overlayBubbleEnabled = overlayBubbleEnabled,
    saveHistory = saveHistory,
    epaperLightMode = epaperLightMode,
)

/** Ringkasan singkat isi cadangan, dipakai untuk konfirmasi setelah impor. */
fun SettingsSnapshot.describe(): String = buildList {
    add("STT ${sttEngine.lowercase()}")
    add(if (llmProvider == LlmProvider.OFF.name) "asisten nonaktif" else "LLM ${llmProvider.lowercase()}")
    add(if (cloudSttApiKey.isNotBlank() || llmApiKey.isNotBlank()) "dengan API key" else "tanpa API key")
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
