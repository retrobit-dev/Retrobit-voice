package dev.retrobit.voice

import dev.retrobit.voice.assistant.PhoneActionConfirmation
import dev.retrobit.voice.core.AssistantAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneActionConfirmationTest {

    @Test
    fun `cellular outgoing and incoming call actions require explicit confirmation`() {
        assertTrue(PhoneActionConfirmation.isRequired(AssistantAction.CallContact("Ibu")))
        assertTrue(PhoneActionConfirmation.isRequired(AssistantAction.Dial("+628123456789")))
        assertTrue(PhoneActionConfirmation.isRequired(AssistantAction.AnswerCall))
    }

    @Test
    fun `non-call actions including WhatsApp chat opening do not become phone calls`() {
        assertFalse(PhoneActionConfirmation.isRequired(AssistantAction.OpenApp("WhatsApp")))
        assertFalse(PhoneActionConfirmation.isRequired(AssistantAction.OpenWhatsAppChat("Ibu", preparingCall = true)))
        assertFalse(PhoneActionConfirmation.isRequired(AssistantAction.ControlMedia(dev.retrobit.voice.core.MediaCommand.STOP)))
    }

    @Test
    fun `phone confirmation prompt says no call has happened before approval`() {
        val outgoing = PhoneActionConfirmation.prompt(AssistantAction.CallContact("Ibu"), english = false)
        val incoming = PhoneActionConfirmation.prompt(AssistantAction.AnswerCall, english = true)

        assertTrue(outgoing.contains("Belum ada panggilan yang dibuat"))
        assertTrue(incoming.contains("has not been answered yet"))
    }
}
