package dev.retrobit.voice.assistant

/** Selects what the wake word means at the moment it is heard. */
object WakeWordIntentRouter {
    enum class Route { DICTATION, ASSISTANT }

    fun route(textInputFocused: Boolean): Route =
        if (textInputFocused) Route.DICTATION else Route.ASSISTANT
}
