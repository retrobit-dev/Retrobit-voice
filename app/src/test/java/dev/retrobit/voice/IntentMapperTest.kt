package dev.retrobit.voice

import dev.retrobit.voice.assistant.IntentMapper
import dev.retrobit.voice.core.AssistantAction
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
    }
}
