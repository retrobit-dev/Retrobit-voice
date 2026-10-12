package dev.retrobit.voice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.retrobit.voice.R
import dev.retrobit.voice.ui.AppViewModel
import dev.retrobit.voice.ui.components.SectionCard
import dev.retrobit.voice.ui.components.SwitchRow
import dev.retrobit.voice.ui.components.str
import dev.retrobit.voice.ui.theme.RetroColors

/**
 * Layar Asisten: percakapan (suara atau teks), aksi yang dijalankan, dan
 * pengaturan cepat untuk kata kunci & suara balasan.
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(str(R.string.assistant_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            str(R.string.assistant_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.error != null) {
            Text(state.error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.status.isNotBlank() && !state.thinking) {
            Text(state.status, style = MaterialTheme.typography.bodySmall, color = RetroColors.accent)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (turns.isEmpty()) {
                item {
                    Text(
                        str(R.string.assistant_examples),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(turns) { turn ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                ) {
                    Text(turn.userText, style = MaterialTheme.typography.bodyMedium)
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                ) {
                    Text(turn.reply, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        (turn.action?.let { it::class.simpleName } ?: "-") + " · " + turn.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.listening) {
                item {
                    Text(str(R.string.assistant_listening), style = MaterialTheme.typography.bodySmall, color = RetroColors.listening)
                }
            }
            if (state.thinking) {
                item { Text(str(R.string.assistant_thinking), style = MaterialTheme.typography.bodySmall, color = RetroColors.processing) }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(str(R.string.assistant_input_label)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.sendToAssistant(input)
                    input = ""
                },
                enabled = input.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(str(R.string.action_send))
            }
            OutlinedButton(
                onClick = { vm.listenToAssistant() },
                enabled = !state.listening && !state.thinking,
                modifier = Modifier.weight(1f),
            ) {
                Text(str(R.string.action_speak))
            }
        }

        SectionCard(title = str(R.string.assistant_settings_title)) {
            val wakePhrase = settings?.wakeWordPhrase.orEmpty()
            SwitchRow(
                title = str(R.string.settings_wake_word),
                subtitle = str(R.string.settings_wake_word_phrase) + ": \"" + wakePhrase + "\"",
                checked = settings?.wakeWordEnabled ?: false,
                onChange = { enabled ->
                    vm.update { it.copy(wakeWordEnabled = enabled) }
                    if (enabled) vm.appGraph().assistant.startWakeWordIfEnabled()
                    else vm.appGraph().assistant.stopWakeWord()
                },
            )
            SwitchRow(
                title = str(R.string.settings_speak_replies),
                checked = settings?.speakReplies ?: true,
                onChange = { value -> vm.update { it.copy(speakReplies = value) } },
            )
            OutlinedButton(onClick = { vm.clearAssistantHistory() }, modifier = Modifier.fillMaxWidth()) {
                Text(str(R.string.assistant_clear_history))
            }
        }
    }
}
