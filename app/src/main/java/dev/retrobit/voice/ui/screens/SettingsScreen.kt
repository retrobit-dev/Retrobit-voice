package dev.retrobit.voice.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import dev.retrobit.voice.data.AppSettings
import dev.retrobit.voice.data.CleanupMode
import dev.retrobit.voice.data.CloudSttProvider
import dev.retrobit.voice.data.DictationTrigger
import dev.retrobit.voice.data.InsertMethod
import dev.retrobit.voice.data.LlmProvider
import dev.retrobit.voice.data.SettingsRepository
import dev.retrobit.voice.data.SettingsSnapshotFormat
import dev.retrobit.voice.data.SttEngineChoice
import dev.retrobit.voice.dictation.a11y.TextInsertAccessibilityService
import dev.retrobit.voice.dictation.insert.ClipboardHelper
import dev.retrobit.voice.ui.AppViewModel
import dev.retrobit.voice.ui.DeviceState
import dev.retrobit.voice.ui.components.BracketButton
import dev.retrobit.voice.ui.components.Divider8
import dev.retrobit.voice.ui.components.LabeledValue
import dev.retrobit.voice.ui.components.Panel
import dev.retrobit.voice.ui.components.PillsColumn
import dev.retrobit.voice.ui.components.SettingTextField
import dev.retrobit.voice.ui.components.SwitchRow
import dev.retrobit.voice.ui.components.TerminalHeader
import dev.retrobit.voice.ui.components.str

/** Pengaturan: kumpulan panel konfigurasi bernomor, gaya menu servis perangkat. */
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val current = settings ?: AppSettings()
    val context = LocalContext.current
    var log by remember { mutableStateOf("") }
    var deviceCheck by remember { mutableStateOf("") }

    // Pemilih berkas sistem (SAF): aplikasi tidak perlu izin penyimpanan.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) vm.exportSettingsTo(uri) }

    // Sengaja menerima semua tipe berkas: sebagian pengelola berkas melaporkan
    // .json sebagai application/octet-stream sehingga berkasnya jadi tak terlihat.
    // Isi berkas tetap divalidasi saat dibaca.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importSettingsFrom(uri) }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TerminalHeader(title = "SETUP", right = "0.1.0")

        // ---------- Tampilan ----------
        Panel(title = str(R.string.settings_display_title), code = "00") {
            SwitchRow(
                title = str(R.string.settings_epaper_mode),
                subtitle = str(R.string.settings_epaper_mode_note),
                checked = current.epaperLightMode,
                onChange = { value -> vm.update { it.copy(epaperLightMode = value) } },
            )
        }

        // ---------- Mesin dikte ----------
        Panel(title = str(R.string.settings_stt_title), code = "01", subtitle = str(R.string.settings_stt_subtitle)) {
            Text(str(R.string.settings_engine_choice), style = MaterialTheme.typography.bodyMedium)
            PillsColumn(
                options = listOf(
                    SttEngineChoice.AUTO to str(R.string.engine_auto),
                    SttEngineChoice.ON_DEVICE to str(R.string.engine_on_device),
                    SttEngineChoice.CLOUD to str(R.string.engine_cloud),
                    SttEngineChoice.SYSTEM_LIVE to str(R.string.engine_system_live),
                ),
                selected = current.sttEngine,
                onSelect = { value -> vm.update { it.copy(sttEngine = value) } },
            )

            Text(str(R.string.settings_cloud_provider), style = MaterialTheme.typography.bodyMedium)
            PillsColumn(
                options = listOf(
                    CloudSttProvider.OPENAI to "OpenAI",
                    CloudSttProvider.GROQ to "Groq",
                    CloudSttProvider.DEEPGRAM to "Deepgram",
                ),
                selected = current.cloudSttProvider,
                onSelect = { provider ->
                    vm.update {
                        it.copy(
                            cloudSttProvider = provider,
                            cloudSttBaseUrl = SettingsRepository.defaultBaseUrl(provider),
                            cloudSttModel = SettingsRepository.defaultModel(provider),
                        )
                    }
                },
            )
            SettingTextField(
                label = str(R.string.settings_api_key_stt),
                value = current.cloudSttApiKey,
                onChange = { value -> vm.update { it.copy(cloudSttApiKey = value) } },
                placeholder = "sk-…",
            )
            SettingTextField(
                label = str(R.string.settings_base_url),
                value = current.cloudSttBaseUrl,
                onChange = { value -> vm.update { it.copy(cloudSttBaseUrl = value) } },
                placeholder = SettingsRepository.defaultBaseUrl(current.cloudSttProvider),
            )
            SettingTextField(
                label = str(R.string.settings_model),
                value = current.cloudSttModel,
                onChange = { value -> vm.update { it.copy(cloudSttModel = value) } },
                placeholder = SettingsRepository.defaultModel(current.cloudSttProvider),
            )
            Text(
                str(R.string.settings_byok_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------- Bahasa & pembersihan ----------
        Panel(title = str(R.string.settings_text_title), code = "02") {
            SettingTextField(
                label = str(R.string.settings_language),
                value = current.dictationLanguage,
                onChange = { value -> vm.update { it.copy(dictationLanguage = value) } },
                placeholder = "id-ID",
            )
            PillsColumn(
                options = listOf(
                    "id-ID" to "Indonesia",
                    "en-US" to "English (US)",
                    "ms-MY" to "Melayu",
                    "jv-ID" to "Jawa",
                ),
                selected = current.dictationLanguage,
                onSelect = { value -> vm.update { it.copy(dictationLanguage = value) } },
            )
            Text(str(R.string.settings_cleanup_mode), style = MaterialTheme.typography.bodyMedium)
            PillsColumn(
                options = listOf(
                    CleanupMode.OFF to str(R.string.cleanup_off),
                    CleanupMode.LOCAL to str(R.string.cleanup_local),
                    CleanupMode.AI to str(R.string.cleanup_ai),
                ),
                selected = current.cleanupMode,
                onSelect = { value -> vm.update { it.copy(cleanupMode = value) } },
            )
            SwitchRow(
                title = str(R.string.settings_auto_punctuation),
                checked = current.autoPunctuation,
                onChange = { value -> vm.update { it.copy(autoPunctuation = value) } },
            )
            SwitchRow(
                title = str(R.string.settings_auto_capitalize),
                checked = current.autoCapitalize,
                onChange = { value -> vm.update { it.copy(autoCapitalize = value) } },
            )
        }

        // ---------- Penempelan & pemicu ----------
        Panel(title = str(R.string.settings_insert_title), code = "03") {
            Text(str(R.string.settings_insert_method), style = MaterialTheme.typography.bodyMedium)
            PillsColumn(
                options = listOf(
                    InsertMethod.AUTO to str(R.string.insert_auto),
                    InsertMethod.IME to str(R.string.insert_ime),
                    InsertMethod.ACCESSIBILITY to str(R.string.insert_a11y),
                    InsertMethod.CLIPBOARD to str(R.string.insert_clipboard),
                ),
                selected = current.insertMethod,
                onSelect = { value -> vm.update { it.copy(insertMethod = value) } },
            )
            Text(str(R.string.settings_trigger), style = MaterialTheme.typography.bodyMedium)
            PillsColumn(
                options = listOf(
                    DictationTrigger.HOLD_TO_TALK to str(R.string.trigger_hold),
                    DictationTrigger.HANDS_FREE to str(R.string.trigger_hands_free),
                    DictationTrigger.HYBRID to str(R.string.trigger_hybrid),
                ),
                selected = current.dictationTrigger,
                onSelect = { value -> vm.update { it.copy(dictationTrigger = value) } },
            )
            // Pemicu lewat tombol volume: hanya bisa dibaca dari layanan aksesibilitas,
            // jadi status layanan itu ditampilkan apa adanya di sini.
            val accessibilityActive = DeviceState.isAccessibilityEnabled(context) || TextInsertAccessibilityService.isConnected()
            SwitchRow(
                title = str(R.string.settings_volume_trigger),
                subtitle = when {
                    !current.volumeKeyTriggerEnabled -> str(R.string.settings_volume_trigger_note)
                    !accessibilityActive -> str(R.string.settings_volume_trigger_needs_a11y)
                    current.dictationTrigger == DictationTrigger.HOLD_TO_TALK ->
                        str(R.string.settings_volume_trigger_ready_hold)
                    else -> str(R.string.settings_volume_trigger_ready_toggle)
                },
                checked = current.volumeKeyTriggerEnabled,
                onChange = { value -> vm.update { it.copy(volumeKeyTriggerEnabled = value) } },
            )
            Text(
                text = str(R.string.settings_silence_timeout) + ": " + current.silenceTimeoutMs + " MS",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = current.silenceTimeoutMs.toFloat(),
                onValueChange = { value -> vm.update { it.copy(silenceTimeoutMs = value.toInt()) } },
                valueRange = 400f..4000f,
                steps = 8,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }

        // ---------- Asisten ----------
        Panel(title = str(R.string.settings_assistant_title), code = "04", subtitle = str(R.string.settings_assistant_subtitle)) {
            Text(str(R.string.label_llm), style = MaterialTheme.typography.bodyMedium)
            PillsColumn(
                options = listOf(
                    LlmProvider.OFF to str(R.string.llm_off),
                    LlmProvider.OPENAI to "OpenAI",
                    LlmProvider.GEMINI to "Gemini",
                    LlmProvider.OLLAMA to "Ollama/Lokal",
                ),
                selected = current.llmProvider,
                onSelect = { provider ->
                    vm.update {
                        it.copy(
                            llmProvider = provider,
                            llmBaseUrl = SettingsRepository.defaultLlmBaseUrl(provider),
                            llmModel = SettingsRepository.defaultLlmModel(provider),
                        )
                    }
                },
            )
            SettingTextField(
                label = str(R.string.settings_api_key_llm),
                value = current.llmApiKey,
                onChange = { value -> vm.update { it.copy(llmApiKey = value) } },
                placeholder = "sk-… / AIza…",
            )
            SettingTextField(
                label = str(R.string.settings_base_url),
                value = current.llmBaseUrl,
                onChange = { value -> vm.update { it.copy(llmBaseUrl = value) } },
                placeholder = SettingsRepository.defaultLlmBaseUrl(current.llmProvider),
            )
            SettingTextField(
                label = str(R.string.settings_llm_model),
                value = current.llmModel,
                onChange = { value -> vm.update { it.copy(llmModel = value) } },
                placeholder = SettingsRepository.defaultLlmModel(current.llmProvider),
            )
            SwitchRow(
                title = str(R.string.settings_speak_replies),
                checked = current.speakReplies,
                onChange = { value -> vm.update { it.copy(speakReplies = value) } },
            )
            SwitchRow(
                title = str(R.string.settings_wake_word),
                subtitle = str(R.string.settings_wake_word_note),
                checked = current.wakeWordEnabled,
                onChange = { value ->
                    vm.update { it.copy(wakeWordEnabled = value) }
                    if (value) vm.appGraph().assistant.startWakeWordIfEnabled()
                    else vm.appGraph().assistant.stopWakeWord()
                },
            )
            SettingTextField(
                label = str(R.string.settings_wake_word_phrase),
                value = current.wakeWordPhrase,
                onChange = { value -> vm.update { it.copy(wakeWordPhrase = value) } },
                placeholder = "hai retro",
            )
        }

        // ---------- Data & bubble ----------
        Panel(title = str(R.string.settings_data_title), code = "05") {
            SwitchRow(
                title = str(R.string.settings_overlay),
                subtitle = str(R.string.settings_overlay_note),
                checked = current.overlayBubbleEnabled && DeviceState.hasOverlayPermission(context),
                onChange = { value -> if (value) vm.startOverlayBubble() else vm.stopOverlayBubble() },
            )
            SwitchRow(
                title = str(R.string.settings_save_history),
                checked = current.saveHistory,
                onChange = { value -> vm.update { it.copy(saveHistory = value) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BracketButton(text = str(R.string.settings_clear_history), onClick = { vm.clearAssistantHistory() })
                BracketButton(
                    text = str(R.string.settings_battery),
                    onClick = { context.startActivity(DeviceState.batterySettingsIntent()) },
                )
            }

            Divider8()
            Text(
                text = str(R.string.settings_backup_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BracketButton(
                    text = str(R.string.settings_export),
                    onClick = { exportLauncher.launch(SettingsSnapshotFormat.SUGGESTED_FILE_NAME) },
                )
                BracketButton(
                    text = str(R.string.settings_import),
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                )
            }
            Text(
                text = str(R.string.settings_backup_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------- Diagnostik ----------
        Panel(title = str(R.string.settings_diagnostics), code = "06") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BracketButton(
                    text = str(R.string.action_refresh),
                    onClick = {
                        vm.refreshLog()
                        log = vm.log.value
                    },
                )
                BracketButton(
                    text = str(R.string.action_copy),
                    onClick = { ClipboardHelper.copy(context, log.ifBlank { vm.log.value }) },
                )
                BracketButton(
                    text = str(R.string.settings_check_engine),
                    onClick = {
                        vm.refreshLog()
                        deviceCheck = vm.sttStatusSummary()
                    },
                )
            }
            if (deviceCheck.isNotBlank()) {
                LabeledValue("MESIN", deviceCheck)
            }
            Text(
                text = log.ifBlank { vm.log.value }.ifBlank { str(R.string.settings_log_empty) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "RETROBIT//VOICE ${str(R.string.settings_version)} 0.1.0 · MIT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "█▚▚▚▚▚▚▚▚▚▚▚▚▚▚▚▚▚▚▚▚▚▚▚▚█",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
