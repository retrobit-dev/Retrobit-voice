package dev.retrobit.voice.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.retrobit.voice.R
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.ui.AppViewModel
import dev.retrobit.voice.ui.DeviceState
import dev.retrobit.voice.ui.components.SectionCard
import dev.retrobit.voice.ui.components.SetupRow
import dev.retrobit.voice.ui.components.StatusPill
import dev.retrobit.voice.ui.components.str
import dev.retrobit.voice.ui.theme.RetroColors

/**
 * Beranda: status kesiapan + tombol dikte cepat + daftar langkah aktivasi.
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

    // Tick dipakai untuk memaksa pembacaan ulang status izin setelah kembali dari pengaturan sistem.
    var tick by remember { mutableStateOf(0) }

    val mic = DeviceState.hasMicrophone(context)
    val imeEnabled = DeviceState.isImeEnabled(context)
    val imeSelected = DeviceState.isImeSelected(context)
    val a11y = DeviceState.isAccessibilityEnabled(context)
    val overlay = DeviceState.hasOverlayPermission(context)
    val assistantDefault = DeviceState.isDefaultAssistant(context)
    val notifications = DeviceState.hasNotificationPermission(context)

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(str(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(
            str(R.string.home_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                text = if (mic) str(R.string.status_mic_ok) else str(R.string.status_mic_missing),
                color = if (mic) RetroColors.success else RetroColors.processing,
            )
            StatusPill(
                text = if (imeSelected) str(R.string.status_ime_active) else str(R.string.status_ime_idle),
                color = if (imeSelected) RetroColors.success else RetroColors.accent,
            )
            if (a11y) StatusPill(str(R.string.status_a11y_on), RetroColors.success)
        }

        SectionCard(title = str(R.string.home_engine_title), subtitle = vm.sttStatusSummary()) {
            Text(
                str(R.string.home_insert_channels) + ": " + vm.appGraph().textInserter.describeAvailable(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            settings?.let { s ->
                Text(
                    str(R.string.home_current_config) + ": " + s.sttEngine.name + " · " +
                        s.cleanupMode.name + " · " + str(R.string.label_llm) + " " + s.llmProvider.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = str(R.string.home_quick_title)) {
            Button(
                onClick = { vm.startDictation(DictationController.Source.MANUAL) },
                enabled = !dictation.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        dictation.phase == DictationController.Phase.LISTENING -> str(R.string.action_finish)
                        dictation.isBusy -> str(R.string.action_processing)
                        else -> str(R.string.home_start_dictation)
                    },
                )
            }
            if (dictation.phase == DictationController.Phase.LISTENING) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.stopDictation() }) { Text(str(R.string.action_finish)) }
                    OutlinedButton(onClick = { vm.cancelDictation() }) { Text(str(R.string.action_cancel)) }
                }
            }
            OutlinedButton(onClick = onOpenAssistant, modifier = Modifier.fillMaxWidth()) {
                Text(str(R.string.home_open_assistant))
            }
            val status = when {
                dictation.needsMicrophonePermission -> str(R.string.error_need_mic)
                dictation.error != null -> dictation.error!!
                dictation.stage.isNotBlank() -> dictation.stage
                dictation.finalText.isNotBlank() -> dictation.finalText
                else -> str(R.string.home_idle_hint)
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (dictation.engine.isNotBlank()) {
                Text(
                    str(R.string.label_engine) + ": " + dictation.engine +
                        (dictation.insertChannel?.let { " · " + str(R.string.label_inserted_via) + " " + it } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = str(R.string.home_setup_title), subtitle = str(R.string.home_setup_subtitle)) {
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
                done = imeEnabled && imeSelected,
                actionLabel = if (imeEnabled) str(R.string.action_select) else str(R.string.action_enable),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenGuide) { Text(str(R.string.guide_open)) }
                OutlinedButton(onClick = { tick++ }) { Text(str(R.string.action_refresh)) }
            }
        }
    }
}

private fun pickKeyboard(context: Context) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    if (imm != null) {
        imm.showInputMethodPicker()
    } else {
        context.startActivity(DeviceState.imeSettingsIntent())
    }
}
