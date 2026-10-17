package dev.retrobit.voice

import dev.retrobit.voice.data.AppSettings
import dev.retrobit.voice.data.CleanupMode
import dev.retrobit.voice.data.CloudSttProvider
import dev.retrobit.voice.data.DictationTrigger
import dev.retrobit.voice.data.InsertMethod
import dev.retrobit.voice.data.InvalidSnapshotException
import dev.retrobit.voice.data.LlmProvider
import dev.retrobit.voice.data.SettingsSnapshotFormat
import dev.retrobit.voice.data.SttEngineChoice
import dev.retrobit.voice.data.decodeSettingsSnapshot
import dev.retrobit.voice.data.describe
import dev.retrobit.voice.data.encode
import dev.retrobit.voice.data.toSettings
import dev.retrobit.voice.data.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji cadangan setelan (ekspor/impor). Berkas ini murni Kotlin tanpa API Android,
 * jadi seluruh alurnya bisa diuji di JVM pada setiap build CI — termasuk yang paling
 * penting: tidak ada preferensi yang tertinggal saat ekspor.
 */
class SettingsExportTest {

    /** Setelan dengan nilai sengaja berbeda dari bawaan, supaya kesalahan mapping ketahuan. */
    private val custom = AppSettings(
        sttEngine = SttEngineChoice.CLOUD,
        cloudSttProvider = CloudSttProvider.GROQ,
        cloudSttApiKey = "sk-uji-cloud",
        cloudSttBaseUrl = "https://contoh.test/openai",
        cloudSttModel = "whisper-uji",
        dictationLanguage = "jv-ID",
        cleanupMode = CleanupMode.AI,
        autoPunctuation = false,
        autoCapitalize = false,
        insertMethod = InsertMethod.ACCESSIBILITY,
        dictationTrigger = DictationTrigger.HANDS_FREE,
        volumeKeyTriggerEnabled = false,
        volumeKeyAssistantEnabled = true,
        silenceTimeoutMs = 2500,
        keepScreenOnWhileDictating = true,
        llmProvider = LlmProvider.OLLAMA,
        llmApiKey = "sk-uji-llm",
        llmBaseUrl = "http://192.168.0.9:11434",
        llmModel = "llama-uji",
        wakeWordEnabled = true,
        wakeWordPhrase = "halo retro",
        assistantSilenceTimeoutMs = 4_500,
        confirmBeforeActions = false,
        screenContextEnabled = true,
        speakReplies = false,
        ttsVoiceName = "en-us-x-sfg#female_1-local",
        ttsPitch = 1.2f,
        ttsSpeechRate = 0.9f,
        assistantLanguage = "en-US",
        overlayBubbleEnabled = true,
        saveHistory = false,
        epaperLightMode = true,
        lastOnboardingStep = 3,
    )

    @Test
    fun `putaran ekspor lalu impor tidak menghilangkan satu preferensi pun`() {
        val json = custom.toSnapshot(versionName = "0.1.0", nowMs = 1_700_000_000_000L).encode()
        val restored = decodeSettingsSnapshot(json).toSettings(custom)

        // Perbandingan objek utuh: kalau ada field baru di AppSettings yang lupa
        // ditambahkan ke SettingsSnapshot, uji ini gagal.
        assertEquals(custom, restored)
    }

    @Test
    fun `pemicu asisten dan konteks layar opt in mati secara bawaan`() {
        val defaults = AppSettings()

        assertFalse(defaults.volumeKeyAssistantEnabled)
        assertFalse(defaults.screenContextEnabled)

        val legacy = decodeSettingsSnapshot(
            """{"app":"${SettingsSnapshotFormat.APP_ID}","schema":1}""",
        ).toSettings(defaults)
        assertFalse(legacy.volumeKeyAssistantEnabled)
        assertFalse(legacy.screenContextEnabled)
    }

    @Test
    fun `jeda akhir perintah asisten berbawaan tiga detik dan impor dibatasi`() {
        assertEquals(3_000, AppSettings().assistantSilenceTimeoutMs)

        val preset = """{"app":"${SettingsSnapshotFormat.APP_ID}","schema":${SettingsSnapshotFormat.SCHEMA_VERSION},"assistantSilenceTimeoutMs":9000}"""
        val restored = decodeSettingsSnapshot(preset).toSettings()

        assertEquals(5_000, restored.assistantSilenceTimeoutMs)
    }

    @Test
    fun `json hasil ekspor memuat penanda aplikasi dan skema`() {
        val json = custom.toSnapshot(versionName = "0.1.0").encode()

        assertTrue("penanda aplikasi", json.contains("\"app\": \"${SettingsSnapshotFormat.APP_ID}\""))
        assertTrue("nomor skema", json.contains("\"schema\": ${SettingsSnapshotFormat.SCHEMA_VERSION}"))
        assertTrue("nama versi", json.contains("\"versionName\": \"0.1.0\""))
        assertTrue("api key ikut (memang disengaja)", json.contains("sk-uji-cloud"))
    }

    @Test
    fun `berkas dari aplikasi lain ditolak`() {
        val asing = """{"app":"aplikasi-lain","schema":1,"sttEngine":"CLOUD"}"""

        val error = runCatching { decodeSettingsSnapshot(asing) }.exceptionOrNull()

        assertTrue("harus InvalidSnapshotException", error is InvalidSnapshotException)
        assertTrue("pesannya menyebut bukan cadangan Retrobit", error!!.message!!.contains("bukan cadangan"))
    }

    @Test
    fun `cadangan dari skema lebih baru ditolak dengan pesan yang jelas`() {
        val masaDepan = """{"app":"${SettingsSnapshotFormat.APP_ID}","schema":${SettingsSnapshotFormat.SCHEMA_VERSION + 1}}"""

        val error = runCatching { decodeSettingsSnapshot(masaDepan) }.exceptionOrNull()

        assertTrue(error is InvalidSnapshotException)
        assertTrue("pesan menyuruh memperbarui aplikasi", error!!.message!!.contains("perbarui"))
    }

    @Test
    fun `bukan json sama sekali ditolak`() {
        val error = runCatching { decodeSettingsSnapshot("ini bukan json") }.exceptionOrNull()

        assertTrue(error is InvalidSnapshotException)
        assertEquals("Berkas bukan cadangan setelan Retrobit yang sah", error!!.message)
    }

    @Test
    fun `field asing diabaikan supaya berkas versi lain tetap bisa diimpor`() {
        val adaTambahan = """
            {
              "app": "${SettingsSnapshotFormat.APP_ID}",
              "schema": 1,
              "sttEngine": "ON_DEVICE",
              "fieldYangBelumAda": {"a": 1}
            }
        """.trimIndent()

        val restored = decodeSettingsSnapshot(adaTambahan).toSettings(AppSettings())

        assertEquals(SttEngineChoice.ON_DEVICE, restored.sttEngine)
    }

    @Test
    fun `preset parsial hanya mengubah field yang dicantumkan dan mempertahankan API key`() {
        val aktif = AppSettings(
            sttEngine = SttEngineChoice.AUTO,
            cloudSttApiKey = "key-cloud-yang-ada",
            llmProvider = LlmProvider.OPENAI,
            llmApiKey = "key-llm-yang-ada",
            wakeWordEnabled = true,
            cleanupMode = CleanupMode.AI,
            ttsVoiceName = "id-id-x-test-local",
            ttsPitch = 1.3f,
            ttsSpeechRate = 0.8f,
            epaperLightMode = true,
        )
        val preset = """{"app":"${SettingsSnapshotFormat.APP_ID}","schema":${SettingsSnapshotFormat.SCHEMA_VERSION},"sttEngine":"SYSTEM_LIVE","wakeWordEnabled":false}"""

        val restored = decodeSettingsSnapshot(preset).toSettings(aktif)

        assertEquals(
            aktif.copy(sttEngine = SttEngineChoice.SYSTEM_LIVE, wakeWordEnabled = false),
            restored,
        )
    }

    @Test
    fun `preset TTS parsial mempertahankan nilai yang hilang dan membatasi pitch serta rate`() {
        val aktif = AppSettings(ttsVoiceName = "suara-aktif", ttsPitch = 1.3f, ttsSpeechRate = 0.8f)
        val lama = decodeSettingsSnapshot(
            """{"app":"${SettingsSnapshotFormat.APP_ID}","schema":${SettingsSnapshotFormat.SCHEMA_VERSION}}""",
        ).toSettings(aktif)
        val diLuarRentang = decodeSettingsSnapshot(
            """{"app":"${SettingsSnapshotFormat.APP_ID}","schema":${SettingsSnapshotFormat.SCHEMA_VERSION},"ttsPitch":3.0,"ttsSpeechRate":0.1}""",
        ).toSettings(aktif)

        assertEquals(aktif.ttsVoiceName, lama.ttsVoiceName)
        assertEquals(aktif.ttsPitch, lama.ttsPitch, 0f)
        assertEquals(aktif.ttsSpeechRate, lama.ttsSpeechRate, 0f)
        assertEquals(1.6f, diLuarRentang.ttsPitch, 0f)
        assertEquals(0.6f, diLuarRentang.ttsSpeechRate, 0f)
    }

    @Test
    fun `nilai enum tak dikenal memakai setelan yang sedang aktif, bukan bawaan`() {
        val rusak = """{"app":"${SettingsSnapshotFormat.APP_ID}","schema":1,"sttEngine":"MESIN_MASA_DEPAN"}"""
        val aktif = AppSettings(sttEngine = SttEngineChoice.SYSTEM_LIVE)

        val restored = decodeSettingsSnapshot(rusak).toSettings(aktif)

        assertEquals("nilai aktif dipertahankan", SttEngineChoice.SYSTEM_LIVE, restored.sttEngine)
    }

    @Test
    fun `progres onboarding tidak ikut dipulihkan dari berkas`() {
        val sudahSelesai = AppSettings(lastOnboardingStep = 5)
        val cadanganLama = sudahSelesai.toSnapshot().encode()

        val restored = decodeSettingsSnapshot(cadanganLama).toSettings(AppSettings(lastOnboardingStep = 0))

        assertEquals("status UI, bukan preferensi", 0, restored.lastOnboardingStep)
        assertNotEquals(sudahSelesai, restored)
    }

    @Test
    fun `ringkasan menyebut mesin dan keberadaan api key`() {
        val ringkas = custom.toSnapshot().describe()

        assertTrue(ringkas, ringkas.contains("STT cloud"))
        assertTrue(ringkas, ringkas.contains("LLM ollama"))
        assertTrue(ringkas, ringkas.contains("dengan API key"))
    }

    @Test
    fun `ringkasan tanpa api key tidak menyebut api key`() {
        val tanpaKunci = AppSettings(sttEngine = SttEngineChoice.ON_DEVICE)

        val ringkas = tanpaKunci.toSnapshot().describe()

        assertTrue(ringkas, ringkas.contains("tanpa API key"))
        assertTrue(ringkas, ringkas.contains("asisten nonaktif"))
    }
}
