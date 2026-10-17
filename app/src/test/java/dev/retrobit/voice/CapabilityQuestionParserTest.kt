package dev.retrobit.voice

import dev.retrobit.voice.assistant.CapabilityQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityQuestionParserTest {

    @Test
    fun `mendeteksi pertanyaan kemampuan tanpa mengubahnya menjadi perintah`() {
        val query = CapabilityQuestionParser.parse("Apakah kamu bisa buka YouTube?")
        assertNotNull(query)
        assertEquals("buka youtube", query?.requestedCommand)
        assertEquals(CapabilityQuestionParser.Topic.GENERAL, query?.topic)
        assertNull(CapabilityQuestionParser.parse("buka YouTube"))
    }

    @Test
    fun `menandai pertanyaan WhatsApp tentang kontak chat dan panggilan`() {
        val overview = CapabilityQuestionParser.parse(
            "Apakah asisten dapat melihat daftar kontak WhatsApp, chat, atau telepon kontak di WhatsApp?",
        )
        assertEquals(CapabilityQuestionParser.Topic.WHATSAPP_OVERVIEW, overview?.topic)
        assertEquals(
            CapabilityQuestionParser.Topic.WHATSAPP_CALL,
            CapabilityQuestionParser.parse("Bisakah kamu telepon kontak di WhatsApp?")?.topic,
        )
        assertEquals(
            CapabilityQuestionParser.Topic.WHATSAPP_CONTACTS,
            CapabilityQuestionParser.parse("Can you see my WhatsApp contact list?")?.topic,
        )
    }
}
