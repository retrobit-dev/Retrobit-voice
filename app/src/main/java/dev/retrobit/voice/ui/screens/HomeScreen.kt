package dev.retrobit.voice.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.retrobit.voice.R
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.ui.AppViewModel
import dev.retrobit.voice.ui.DeviceState
import dev.retrobit.voice.ui.components.BracketButton
import dev.retrobit.voice.ui.components.LabeledValue
import dev.retrobit.voice.ui.components.Panel
import dev.retrobit.voice.ui.components.SetupRow
import dev.retrobit.voice.ui.components.StatusStrip
import dev.retrobit.voice.ui.components.TerminalHeader
import dev.retrobit.voice.ui.components.str

/**
 * Beranda bergaya panel cyberdeck: header terminal, strip status 1-bit,
 * lalu panel-panel instrumen.
 */
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onRequestMicrophone: () -> Unit,
    onRequestContacts: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenAssistant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val dictation by vm.dictation.collectAsStateWithLifecycle()
    var tick by remember { mutableStateOf(0) }

    val mic = DeviceState.hasMicrophone(context)
    val imeSelected = DeviceState.isImeSelected(context)
    val a11y = DeviceState.isAccessibilityEnabled(context)
    val overlay = DeviceState.hasOverlayPermission(context)
    val assistantDefault = DeviceState.isDefaultAssistant(context)
    val notifications = DeviceState.hasNotificationPermission(context)

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TerminalHeader(
            title = "RETROBIT//VOICE",
            right = "v0.1.0",
            subtitle = str(R.string.home_tagline),
        )

        StatusStrip(
            items = listOf(
                "MIC" to mic,
                "IME" to imeSelected,
                "A11Y" to a11y,
                "OVR" to overlay,
                "AST" to assistantDefault,
            ),
        )

        Panel(title = str(R.string.home_quick_title), code = "01") {
            BracketButton(
                text = when {
                    dictation.phase == DictationController.Phase.LISTENING -> str(R.string.action_finish)
                    dictation.isBusy -> str(R.string.action_processing)
                    else -> str(R.string.home_start_dictation)
                },
                onClick = { vm.startDictation(DictationController.Source.MANUAL) },
                enabled = !dictation.isBusy,
                filled = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BracketButton(
                    text = str(R.string.action_finish),
                    onClick = { vm.stopDictation() },
                    enabled = dictation.phase == DictationController.Phase.LISTENING,
                )
                BracketButton(
                    text = str(R.string.action_cancel),
                    onClick = { vm.cancelDictation() },
                    enabled = dictation.isBusy,
                )
                BracketButton(text = str(R.string.home_open_assistant), onClick = onOpenAssistant)
            }

            Text(
                text = when {
                    dictation.needsMicrophonePermission -> "> " + str(R.string.error_need_mic)
                    dictation.error != null -> "! " + dictation.error!!
                    dictation.stage.isNotBlank() -> "> " + dictation.stage
                    dictation.finalText.isNotBlank() -> "< " + dictation.finalText
                    else -> "> " + str(R.string.home_idle_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Panel(title = str(R.string.home_engine_title), code = "02") {
            LabeledValue(str(R.string.label_engine), vm.sttStatusSummary())
            LabeledValue(str(R.string.home_insert_channels), vm.appGraph().textInserter.describeAvailable())
            settings?.let { s ->
                LabeledValue("MODE", s.sttEngine.name.lowercase())
                LabeledValue(str(R.string.settings_cleanup_mode), s.cleanupMode.name.lowercase())
                LabeledValue(str(R.string.label_llm), s.llmProvider.name.lowercase())
            }
        }

        Panel(title = str(R.string.home_setup_title), code = "03", subtitle = str(R.string.home_setup_subtitle)) {
            SetupRow(
                title = str(R.string.setup_mic_title),
                subtitle = str(R.string.setup_mic_subtitle),
                done = mic,
                actionLabel = str(R.string.action_allow),
                onAction = onRequestMicrophone,
            )
            SetupRow(
                title = str(R.string.setup_ime_title),
                subtitle = str(R.string.setup_ime_subtitle),
                done = imeSelected,
                actionLabel = str(R.string.action_select),
                onAction = { pickKeyboard(context) },
            )
            SetupRow(
                title = str(R.string.setup_a11y_title),
                subtitle = str(R.string.setup_a11y_subtitle),
                done = a11y,
                actionLabel = str(R.string.action_enable),
                onAction = { context.startActivity(DeviceState.accessibilitySettingsIntent()) },
            )
            SetupRow(
                title = str(R.string.setup_overlay_title),
                subtitle = str(R.string.setup_overlay_subtitle),
                done = overlay,
                actionLabel = str(R.string.action_allow),
                onAction = { context.startActivity(DeviceState.overlaySettingsIntent(context)) },
            )
            SetupRow(
                title = str(R.string.setup_assistant_title),
                subtitle = str(R.string.setup_assistant_subtitle),
                done = assistantDefault,
                actionLabel = str(R.string.action_set),
                onAction = { DeviceState.assistantRoleIntent(context)?.let { context.startActivity(it) } },
            )
            if (!notifications) {
                SetupRow(
                    title = str(R.string.setup_notif_title),
                    subtitle = str(R.string.setup_notif_subtitle),
                    done = false,
                    actionLabel = str(R.string.action_allow),
                    onAction = { context.startActivity(DeviceState.appDetailsIntent(context)) },
                )
            }
            SetupRow(
                title = str(R.string.setup_contacts_title),
                subtitle = str(R.string.setup_contacts_subtitle),
                done = DeviceState.hasContacts(context),
                actionLabel = str(R.string.action_allow),
                onAction = onRequestContacts,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BracketButton(text = str(R.string.guide_open), onClick = onOpenGuide)
                BracketButton(text = str(R.string.action_refresh), onClick = { tick++ })
            }
        }

        Panel(title = str(R.string.settings_data_title), code = "04", inactive = true) {
            Text(
                text = "SYS: ${if (tick % 2 == 0) "NOMINAL" else "NOMINAL"} · ${vm.appGraph().textInserter.describeAvailable()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun pickKeyboard(context: Context) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    if (imm != null) imm.showInputMethodPicker() else context.startActivity(DeviceState.imeSettingsIntent())
}
