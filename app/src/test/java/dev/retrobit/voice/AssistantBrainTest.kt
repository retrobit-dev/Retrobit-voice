package dev.retrobit.voice

import dev.retrobit.voice.ai.LlmClient
import dev.retrobit.voice.assistant.AssistantBrain
import dev.retrobit.voice.core.AssistantAction
import dev.retrobit.voice.core.MediaCommand
import dev.retrobit.voice.data.AppSettings
import dev.retrobit.voice.data.LlmProvider
import dev.retrobit.voice.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantBrainTest {

    private val brain = AssistantBrain(LlmClient()) { emptyList() }

    @Test
    fun `Gemini memakai model Flash bawaan yang valid`() {
        assertEquals("gemini-3.8-flash", SettingsRepository.defaultLlmModel(LlmProvider.GEMINI))
    }

    @Test
    fun `kalimat percakapan yang tak dikenal diarahkan ke chatbot dan tidak menjadi pencarian`() = runBlocking {
        val result = brain.understand("jelaskan fenomena el nino", AppSettings())

        assertNull(result.action)
        assertTrue(result.reply.contains("chatbot"))
        assertTrue(result.reply.contains("cari"))
    }

    @Test
    fun `pertanyaan kemampuan WhatsApp dijawab tanpa menjalankan aksi`() = runBlocking {
        val result = brain.understand(
            "Apakah asisten juga dapat melihat daftar kontak WhatsApp, chat, atau telepon kontak di WhatsApp?",
            AppSettings(),
        )

        assertNull(result.action)
        assertTrue(result.reply.contains("buku alamat ponsel"))
        assertTrue(result.reply.contains("panggilan suara WhatsApp"))
    }

    @Test
    fun `pertanyaan kemampuan media menjawab siap tanpa mengontrol media`() = runBlocking {
        val result = brain.understand("Can you play?", AppSettings())

        assertNull(result.action)
        assertTrue(result.reply.contains("Bisa"))
    }

    @Test
    fun `perintah mainkan pada aplikasi media memakai sesi aktif`() = runBlocking {
        val result = brain.understand(
            userText = "Mainkan",
            settings = AppSettings(screenContextEnabled = true),
            screenContext = "Aplikasi: com.google.android.youtube",
            screenContextRequested = true,
        )

        assertEquals(AssistantAction.ControlMedia(MediaCommand.PLAY), result.action)
    }
}
