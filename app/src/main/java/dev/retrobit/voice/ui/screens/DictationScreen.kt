package dev.retrobit.voice.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.retrobit.voice.R
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.ui.AppViewModel
import dev.retrobit.voice.ui.components.BigMicButton
import dev.retrobit.voice.ui.components.BracketButton
import dev.retrobit.voice.ui.components.LabeledValue
import dev.retrobit.voice.ui.components.Panel
import dev.retrobit.voice.ui.components.TerminalHeader
import dev.retrobit.voice.ui.components.str

/** Layar dikte: panel besar sebagai tombol, telemetri, lalu riwayat. */
@Composable
fun DictationScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val state by vm.dictation.collectAsStateWithLifecycle()
    val history by vm.dictationHistory.collectAsStateWithLifecycle()

    LazyColumn(
        modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TerminalHeader(
                title = str(R.string.tab_dictation).uppercase(),
                right = "CH-01",
                subtitle = str(R.string.dictation_idle),
            )
        }

        item {
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
        }

        item {
            Panel(title = str(R.string.dictation_result_title), code = "OUT") {
                Text(
                    text = state.finalText.ifBlank {
                        state.rawText.ifBlank { "> " + str(R.string.dictation_result_empty) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state.error != null) {
                    Text(
                        text = "! " + state.error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BracketButton(
                        text = str(R.string.action_copy),
                        onClick = { vm.copyLastResult() },
                        enabled = state.finalText.isNotBlank(),
                    )
                    BracketButton(
                        text = str(R.string.action_share),
                        onClick = { vm.shareText(state.finalText) },
                        enabled = state.finalText.isNotBlank(),
                    )
                    BracketButton(
                        text = str(R.string.action_send_to_assistant),
                        onClick = { vm.sendToAssistant(state.finalText) },
                        enabled = state.finalText.isNotBlank(),
                    )
                }
            }
        }

        item {
            Panel(title = str(R.string.dictation_tech_title), code = "TEL") {
                LabeledValue(str(R.string.label_engine), state.engine)
                LabeledValue(str(R.string.label_inserted_via), state.insertChannel.orEmpty())
                LabeledValue(str(R.string.label_available_channels), vm.appGraph().textInserter.describeAvailable())
                LabeledValue("FASE", state.phase.name.lowercase())
            }
        }

        item {
            TerminalHeader(title = str(R.string.dictation_history_title), right = "${history.size} ENTRI")
        }

        if (history.isEmpty()) {
            item {
                Text(
                    text = "> " + str(R.string.dictation_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(history) { entry ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .padding(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "< " + entry.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "# ${entry.engine} · ${entry.language}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            BracketButton(text = str(R.string.action_share), onClick = { vm.shareText(entry.text) })
                        }
                    }
                }
            }
        }
    }
}
