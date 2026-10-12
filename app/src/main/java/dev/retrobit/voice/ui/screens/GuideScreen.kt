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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.retrobit.voice.R
import dev.retrobit.voice.ui.AppViewModel
import dev.retrobit.voice.ui.DeviceState
import dev.retrobit.voice.ui.components.BracketButton
import dev.retrobit.voice.ui.components.LabeledValue
import dev.retrobit.voice.ui.components.Panel
import dev.retrobit.voice.ui.components.TerminalHeader
import dev.retrobit.voice.ui.components.str

/** Panduan aktivasi — disusun seperti prosedur servis perangkat. */
@Composable
fun GuideScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TerminalHeader(title = str(R.string.guide_title).uppercase(), right = "PROSEDUR")
        Text(
            text = str(R.string.guide_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Step(
            number = 1,
            title = str(R.string.guide_step1_title),
            body = str(R.string.guide_step1_body),
            actionLabel = str(R.string.action_select),
            onAction = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showInputMethodPicker()
            },
        )
        Step(
            number = 2,
            title = str(R.string.guide_step2_title),
            body = str(R.string.guide_step2_body),
            actionLabel = str(R.string.action_enable),
            onAction = { context.startActivity(DeviceState.accessibilitySettingsIntent()) },
        )
        Step(
            number = 3,
            title = str(R.string.guide_step3_title),
            body = str(R.string.guide_step3_body),
            actionLabel = str(R.string.action_allow),
            onAction = { context.startActivity(DeviceState.overlaySettingsIntent(context)) },
        )
        Step(
            number = 4,
            title = str(R.string.guide_step4_title),
            body = str(R.string.guide_step4_body),
            actionLabel = str(R.string.action_set),
            onAction = { DeviceState.assistantRoleIntent(context)?.let { context.startActivity(it) } },
        )
        Step(
            number = 5,
            title = str(R.string.guide_step5_title),
            body = str(R.string.guide_step5_body),
            actionLabel = str(R.string.guide_open_settings),
            onAction = { context.startActivity(DeviceState.appDetailsIntent(context)) },
        )

        Panel(title = str(R.string.guide_tips_title), code = "NOTE") {
            Text("· " + str(R.string.guide_tip_battery), style = MaterialTheme.typography.bodySmall)
            Text("· " + str(R.string.guide_tip_offline), style = MaterialTheme.typography.bodySmall)
            Text("· " + str(R.string.guide_tip_privacy), style = MaterialTheme.typography.bodySmall)
            Text("· " + str(R.string.guide_tip_llm), style = MaterialTheme.typography.bodySmall)
        }

        Panel(title = str(R.string.settings_diagnostics), code = "DIAG") {
            BracketButton(text = str(R.string.guide_refresh_status), onClick = { vm.refreshLog() })
            LabeledValue("MESIN", vm.sttStatusSummary())
        }
    }
}

@Composable
private fun Step(
    number: Int,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Panel(
        title = str(R.string.guide_step_label) + " " + number,
        code = "→",
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.bodyMedium)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BracketButton(text = actionLabel, onClick = onAction, filled = true)
        }
    }
}
