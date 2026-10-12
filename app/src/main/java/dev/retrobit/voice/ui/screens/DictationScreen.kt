package dev.retrobit.voice.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.retrobit.voice.R
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.ui.AppViewModel
import dev.retrobit.voice.ui.components.BigMicButton
import dev.retrobit.voice.ui.components.SectionCard
import dev.retrobit.voice.ui.components.str
import dev.retrobit.voice.ui.theme.RetroColors

/**
 * Layar Dikte: uji coba langsung di dalam aplikasi, lengkap dengan hasil,
 * jalur penempelan yang dipakai, dan riwayat.
 */
@Composable
fun DictationScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val state by vm.dictation.collectAsStateWithLifecycle()
    val history by vm.dictationHistory.collectAsStateWithLifecycle()

    LazyColumn(
        modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BigMicButton(
                    state = state,
                    onClick = {
                        when (state.phase) {
                            DictationController.Phase.LISTENING -> vm.stopDictation()
                            DictationController.Phase.IDLE, DictationController.Phase.DONE,
                            DictationController.Phase.ERROR,
                            -> {
                                vm.cancelDictation()
                                vm.startDictation(DictationController.Source.MANUAL)
                            }
                            else -> Unit
                        }
                    },
                )
                Text(
                    text = when (state.phase) {
                        DictationController.Phase.IDLE -> str(R.string.dictation_idle)
                        DictationController.Phase.LISTENING -> str(R.string.dictation_listening)
                        DictationController.Phase.TRANSCRIBING -> str(R.string.dictation_transcribing)
                        DictationController.Phase.CLEANING -> str(R.string.dictation_cleaning)
                        DictationController.Phase.INSERTING -> str(R.string.dictation_inserting)
                        DictationController.Phase.DONE -> str(R.string.dictation_done)
                        DictationController.Phase.ERROR -> str(R.string.dictation_error)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (state.stage.isNotBlank()) {
                    Text(
                        state.stage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (state.emphasizedPartial().isNotBlank()) {
                    Text(
                        state.emphasizedPartial(),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item {
            SectionCard(title = str(R.string.dictation_result_title)) {
                Text(
                    state.finalText.ifBlank { state.rawText.ifBlank { str(R.string.dictation_result_empty) } },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.error != null) {
                    Text(state.error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.copyLastResult() }, enabled = state.finalText.isNotBlank()) {
                        Text(str(R.string.action_copy))
                    }
                    OutlinedButton(onClick = { vm.shareText(state.finalText) }, enabled = state.finalText.isNotBlank()) {
                        Text(str(R.string.action_share))
                    }
                    OutlinedButton(
                        onClick = { vm.sendToAssistant(state.finalText) },
                        enabled = state.finalText.isNotBlank(),
                    ) {
                        Text(str(R.string.action_send_to_assistant))
                    }
                }
            }
        }

        item {
            SectionCard(title = str(R.string.dictation_tech_title)) {
                Text(
                    str(R.string.label_engine) + ": " + state.engine.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    str(R.string.label_inserted_via) + ": " + (state.insertChannel ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    str(R.string.label_available_channels) + ": " + vm.appGraph().textInserter.describeAvailable(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Text(str(R.string.dictation_history_title), style = MaterialTheme.typography.titleMedium) }

        if (history.isEmpty()) {
            item {
                Text(
                    str(R.string.dictation_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(history) { entry ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.padding(12.dp)) {
                        Column {
                            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                            Row(
                                Modifier.fillMaxWidth().height(28.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    entry.engine + " · " + entry.language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RetroColors.accent,
                                )
                                OutlinedButton(onClick = { vm.shareText(entry.text) }) {
                                    Text(str(R.string.action_share), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun DictationController.UiState.emphasizedPartial(): String = partial
