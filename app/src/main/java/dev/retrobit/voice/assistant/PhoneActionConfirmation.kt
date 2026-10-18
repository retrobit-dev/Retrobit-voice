package dev.retrobit.voice.assistant

import dev.retrobit.voice.core.AssistantAction

/** Central policy and plain-language prompts for actions that can connect a call. */
object PhoneActionConfirmation {

    fun isRequired(action: AssistantAction?): Boolean = when (action) {
        is AssistantAction.CallContact, is AssistantAction.Dial, AssistantAction.AnswerCall -> true
        else -> false
    }

    fun prompt(action: AssistantAction, english: Boolean): String {
        require(isRequired(action)) { "Only cellular call actions require phone confirmation" }
        return when (action) {
            is AssistantAction.CallContact -> if (english) {
                "I will place a regular phone call to ${action.name}. No call has been placed yet. Say yes to continue or cancel."
            } else {
                "Saya akan menelepon ${action.name} lewat panggilan telepon biasa. Belum ada panggilan yang dibuat. Ucapkan ‘ya’ untuk lanjut atau ‘batal’."
            }
            is AssistantAction.Dial -> {
                val target = action.contactName?.takeIf { it.isNotBlank() } ?: action.target
                if (english) {
                    "I will place a regular phone call to $target. No call has been placed yet. Say yes to continue or cancel."
                } else {
                    "Saya akan menelepon $target lewat panggilan telepon biasa. Belum ada panggilan yang dibuat. Ucapkan ‘ya’ untuk lanjut atau ‘batal’."
                }
            }
            AssistantAction.AnswerCall -> if (english) {
                "I will answer the incoming phone call. It has not been answered yet. Say yes to continue or cancel."
            } else {
                "Saya akan menjawab panggilan telepon masuk. Panggilan belum dijawab. Ucapkan ‘ya’ untuk lanjut atau ‘batal’."
            }
            else -> error("Unreachable: action does not require phone confirmation")
        }
    }

    fun cancellationReply(action: AssistantAction, english: Boolean): String {
        require(isRequired(action)) { "Only cellular call actions require phone confirmation" }
        return when {
            action == AssistantAction.AnswerCall && english -> "Okay, I did not answer the incoming call."
            action == AssistantAction.AnswerCall -> "Baik, panggilan masuk tidak dijawab."
            english -> "Okay, I did not place the call."
            else -> "Baik, panggilan tidak dibuat."
        }
    }

    fun expiredReply(action: AssistantAction, english: Boolean): String {
        require(isRequired(action)) { "Only cellular call actions require phone confirmation" }
        return when {
            action == AssistantAction.AnswerCall && english -> "The confirmation expired. I did not answer the incoming call."
            action == AssistantAction.AnswerCall -> "Konfirmasi kedaluwarsa. Panggilan masuk tidak dijawab."
            english -> "The confirmation expired. I did not place the call."
            else -> "Konfirmasi kedaluwarsa. Panggilan tidak dibuat."
        }
    }
}
