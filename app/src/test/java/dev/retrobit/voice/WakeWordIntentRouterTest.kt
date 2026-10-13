package dev.retrobit.voice

import dev.retrobit.voice.assistant.WakeWordIntentRouter
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeWordIntentRouterTest {
    @Test
    fun focusedTextInputRoutesWakeWordToDictation() {
        assertEquals(WakeWordIntentRouter.Route.DICTATION, WakeWordIntentRouter.route(textInputFocused = true))
    }

    @Test
    fun noFocusedTextInputRoutesWakeWordToAssistant() {
        assertEquals(WakeWordIntentRouter.Route.ASSISTANT, WakeWordIntentRouter.route(textInputFocused = false))
    }
}
