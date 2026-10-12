package dev.retrobit.voice

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.retrobit.voice.ui.AppViewModel
import dev.retrobit.voice.ui.screens.AssistantScreen
import dev.retrobit.voice.ui.screens.DictationScreen
import dev.retrobit.voice.ui.screens.GuideScreen
import dev.retrobit.voice.ui.screens.HomeScreen
import dev.retrobit.voice.ui.screens.SettingsScreen
import dev.retrobit.voice.ui.theme.RetrobitTheme

/**
 * Satu Activity untuk seluruh aplikasi:
 *  - tab Beranda, Dikte, Asisten, Pengaturan (plus Panduan),
 *  - pintu masuk untuk intent ACTION_ASSIST / VOICE_COMMAND dari sistem,
 *  - permintaan izin runtime (mikrofon, kontak, notifikasi).
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private var startOnAssistantTab = mutableStateOf(false)
    private var autoListenRequested = mutableStateOf(false)

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Toast.makeText(this, getString(R.string.toast_mic_granted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.toast_mic_denied), Toast.LENGTH_LONG).show()
        }
    }

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(
            this,
            getString(if (granted) R.string.toast_contacts_granted else R.string.toast_contacts_denied),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.retrobit.voice.core.AppGraph.init(applicationContext)
        handleIntent(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            RetrobitTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScaffold(
                        vm = vm,
                        startOnAssistant = startOnAssistantTab.value,
                        autoListen = autoListenRequested.value,
                        onAutoListenConsumed = { autoListenRequested.value = false },
                        onRequestMicrophone = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
                        onRequestContacts = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_ASSISTANT, false)) startOnAssistantTab.value = true
        if (intent.getBooleanExtra(EXTRA_AUTO_LISTEN, false)) autoListenRequested.value = true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !dev.retrobit.voice.ui.DeviceState.hasNotificationPermission(this)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_ASSISTANT = "dev.retrobit.voice.extra.OPEN_ASSISTANT"
        const val EXTRA_AUTO_LISTEN = "dev.retrobit.voice.extra.AUTO_LISTEN"
    }
}

private enum class Tab(val labelRes: Int, val iconRes: Int) {
    HOME(R.string.tab_home, R.drawable.ic_home),
    DICTATION(R.string.tab_dictation, R.drawable.ic_mic),
    ASSISTANT(R.string.tab_assistant, R.drawable.ic_spark),
    SETTINGS(R.string.tab_settings, R.drawable.ic_settings_sliders),
}

@Composable
private fun MainScaffold(
    vm: AppViewModel,
    startOnAssistant: Boolean,
    autoListen: Boolean,
    onAutoListenConsumed: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestContacts: () -> Unit,
) {
    var tab by remember { mutableStateOf(if (startOnAssistant) Tab.ASSISTANT else Tab.HOME) }
    var showGuide by remember { mutableStateOf(false) }
    val toast by vm.toast.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(toast) {
        if (!toast.isNullOrBlank()) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }
    androidx.compose.runtime.LaunchedEffect(startOnAssistant) {
        if (startOnAssistant) tab = Tab.ASSISTANT
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry && !showGuide,
                        onClick = {
                            tab = entry
                            showGuide = false
                        },
                        icon = { Icon(painterResource(entry.iconRes), contentDescription = null) },
                        label = { Text(stringRes(entry.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                showGuide -> GuideScreen(vm)
                tab == Tab.HOME -> HomeScreen(
                    vm = vm,
                    onRequestMicrophone = onRequestMicrophone,
                    onRequestContacts = onRequestContacts,
                    onOpenGuide = { showGuide = true },
                    onOpenAssistant = { tab = Tab.ASSISTANT },
                )
                tab == Tab.DICTATION -> DictationScreen(vm)
                tab == Tab.ASSISTANT -> AssistantScreen(
                    vm = vm,
                    autoListenOnOpen = autoListen,
                    onAutoListenConsumed = onAutoListenConsumed,
                )
                else -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)
