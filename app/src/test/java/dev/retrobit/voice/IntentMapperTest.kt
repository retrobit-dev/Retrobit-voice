package dev.retrobit.voice

import dev.retrobit.voice.assistant.IntentMapper
import dev.retrobit.voice.core.AssistantAction
import dev.retrobit.voice.core.MediaCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class IntentMapperTest {

    @Test
    fun `maps system control intents`() {
        assertEquals(
            AssistantAction.SetBrightness(45),
            IntentMapper.map("""{"intent":"brightness","slots":{"percent":45,"relative":false},"reply":"Kecerahan diatur."}""").action,
        )
        assertEquals(
            AssistantAction.SetDnd(true),
            IntentMapper.map("""{"intent":"dnd","slots":{"on":true},"reply":"Mode DND aktif."}""").action,
        )
        assertEquals(
            AssistantAction.SetBluetooth(false),
            IntentMapper.map("""{"intent":"bluetooth","slots":{"on":false},"reply":"Bluetooth mati."}""").action,
        )
        assertEquals(
            AssistantAction.AnswerCall,
            IntentMapper.map("""{"intent":"answer_call","slots":{},"reply":"Menjawab panggilan."}""").action,
        )
        assertEquals(
            AssistantAction.GetCurrentTime,
            IntentMapper.map("""{"intent":"current_time","slots":{},"reply":"Saya periksa."}""").action,
        )
        assertEquals(
            AssistantAction.GetBatteryStatus,
            IntentMapper.map("""{"intent":"battery_status","slots":{},"reply":"Saya periksa."}""").action,
        )
        assertEquals(
            AssistantAction.GetWeather("Jepara"),
            IntentMapper.map("""{"intent":"weather","slots":{"location":"Jepara"},"reply":"Saya periksa."}""").action,
        )
        assertEquals(
            AssistantAction.TypeText("Halo"),
            IntentMapper.map("""{"intent":"type_text","slots":{"text":"Halo"},"reply":"Saya ketik."}""").action,
        )
        assertEquals(
            AssistantAction.StartDictation,
            IntentMapper.map("""{"intent":"start_dictation","slots":{},"reply":"Dikte siap."}""").action,
        )
        assertEquals(
            AssistantAction.OpenWhatsAppChat("Budi"),
            IntentMapper.map("""{"intent":"open_whatsapp_chat","slots":{"contact":"Budi"},"reply":"Membuka chat."}""").action,
        )
        assertEquals(
            AssistantAction.ControlMedia(MediaCommand.PLAY),
            IntentMapper.map("""{"intent":"media_control","slots":{"command":"PLAY"},"reply":"Memutar."}""").action,
        )
    }
}
