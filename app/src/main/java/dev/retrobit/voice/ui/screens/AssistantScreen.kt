package dev.retrobit.voice.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.retrobit.voice.R
import dev.retrobit.voice.ui.AppViewModel
import dev.retrobit.voice.ui.DeviceState
import dev.retrobit.voice.ui.components.BracketButton
import dev.retrobit.voice.ui.components.Panel
import dev.retrobit.voice.ui.components.SettingTextField
import dev.retrobit.voice.ui.components.SwitchRow
import dev.retrobit.voice.ui.components.TerminalHeader
import dev.retrobit.voice.ui.components.TerminalLogLine
import dev.retrobit.voice.ui.components.str

/**
 * Layar asisten: percakapan ditampilkan seperti log terminal
 * (`> perintah` dan `< jawaban` dengan metadata aksi di bawahnya).
 */
@Composable
fun AssistantScreen(
    vm: AppViewModel,
    autoListenOnOpen: Boolean,
    onAutoListenConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.assistant.collectAsStateWithLifecycle()
    val turns by vm.assistantHistory.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val wakeWordMicrophonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.update { it.copy(wakeWordEnabled = true) }
    }

    LaunchedEffect(autoListenOnOpen) {
        if (autoListenOnOpen) {
            vm.listenToAssistant()
            onAutoListenConsumed()
        }
    }

    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TerminalHeader(
            title = "ASSISTANT",
            right = if (state.listening) "REC" else if (state.thinking) "BUSY" else "RDY",
            subtitle = str(R.string.assistant_subtitle),
        )

        if (state.error != null) {
            Text(
                text = "! " + state.error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (state.status.isNotBlank() && !state.thinking) {
            Text(
                text = "$ ${state.status}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (turns.isEmpty()) {
                item {
                    Text(
                        text = "$ " + str(R.string.assistant_examples),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(turns) { turn ->
                TerminalLogLine(
                    prompt = ">",
                    text = turn.userText,
                    meta = null,
                    emphasis = true,
                )
                TerminalLogLine(
                    prompt = "<",
                    text = turn.reply,
                    meta = "# ${turn.action?.let { it::class.simpleName } ?: "pemahaman"} · ${turn.source}",
                )
            }
            if (state.listening) {
                item { Text("> " + str(R.string.assistant_listening), style = MaterialTheme.typography.bodySmall) }
            }
            if (state.thinking) {
                item { Text("… " + str(R.string.assistant_thinking), style = MaterialTheme.typography.bodySmall) }
            }
        }

        state.pendingMessage?.let { message ->
            Panel(title = str(R.string.assistant_confirmation_title), code = "SEND") {
                Text(
                    text = str(R.string.assistant_confirmation_to) + ": " + message.contactName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = str(R.string.assistant_confirmation_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BracketButton(
                        text = str(R.string.action_cancel),
                        onClick = { vm.cancelPendingAssistantMessage() },
                        enabled = !state.thinking && !state.listening,
                        modifier = Modifier.weight(1f),
                    )
                    BracketButton(
                        text = str(R.string.assistant_confirm_prepare),
                        onClick = { vm.confirmPendingAssistantMessage() },
                        enabled = !state.thinking && !state.listening,
                        filled = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        state.pendingPhoneAction?.let {
            Panel(title = str(R.string.assistant_phone_confirmation_title), code = "CALL") {
                Text(
                    text = state.pendingPhoneActionPrompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = str(R.string.assistant_phone_confirmation_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BracketButton(
                        text = str(R.string.action_cancel),
                        onClick = { vm.cancelPendingPhoneAction() },
                        enabled = !state.thinking && !state.listening,
                        modifier = Modifier.weight(1f),
                    )
                    BracketButton(
                        text = str(R.string.assistant_confirm_phone_action),
                        onClick = { vm.confirmPendingPhoneAction() },
                        enabled = !state.thinking && !state.listening,
                        filled = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        SettingTextField(
            label = str(R.string.assistant_input_label),
            value = input,
            onChange = { input = it },
            placeholder = "buka whatsapp / alarm jam 5 pagi",
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BracketButton(
                text = str(R.string.action_send),
                onClick = {
                    vm.sendToAssistant(input)
                    input = ""
                },
                enabled = input.isNotBlank() && !state.thinking && !state.listening,
                filled = true,
                modifier = Modifier.weight(1f),
            )
            BracketButton(
                text = str(R.string.action_speak),
                onClick = { vm.listenToAssistant() },
                enabled = !state.listening && !state.thinking,
                modifier = Modifier.weight(1f),
            )
        }

        Panel(title = str(R.string.assistant_settings_title), code = "CFG") {
            val wakePhrase = settings?.wakeWordPhrase.orEmpty()
            SwitchRow(
                title = str(R.string.settings_wake_word),
                subtitle = str(R.string.settings_wake_word_phrase) + ": " + wakePhrase,
                checked = settings?.wakeWordEnabled ?: false,
                onChange = { enabled ->
                    if (enabled && !DeviceState.hasMicrophone(context)) {
                        wakeWordMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        vm.update { it.copy(wakeWordEnabled = enabled) }
                    }
                },
            )
            SwitchRow(
                title = str(R.string.settings_speak_replies),
                checked = settings?.speakReplies ?: true,
                onChange = { value -> vm.update { it.copy(speakReplies = value) } },
            )
            BracketButton(
                text = str(R.string.assistant_clear_history),
                onClick = { vm.clearAssistantHistory() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
