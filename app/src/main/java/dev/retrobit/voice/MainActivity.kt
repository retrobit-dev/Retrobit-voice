package dev.retrobit.voice

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.retrobit.voice.core.ToastHub
import dev.retrobit.voice.ui.AppViewModel
import dev.retrobit.voice.ui.components.epaperTexture
import dev.retrobit.voice.ui.screens.AssistantScreen
import dev.retrobit.voice.ui.screens.DictationScreen
import dev.retrobit.voice.ui.screens.GuideScreen
import dev.retrobit.voice.ui.screens.HomeScreen
import dev.retrobit.voice.ui.screens.SettingsScreen
import dev.retrobit.voice.ui.theme.Deck
import dev.retrobit.voice.ui.theme.RetrobitTheme

/**
 * Satu Activity untuk seluruh aplikasi.
 *
 * Kerangka tampilan: tekstur panel e-paper sebagai latar, isi layar di tengah,
 * dan bilah navigasi bawah bergaya tombol instrumen (bukan Material NavigationBar)
 * supaya seluruh antarmuka konsisten dengan tema cyberdeck monokrom.
 *
 * Latar digambar penuh sampai tepi layar (edge-to-edge), sedangkan isinya digeser
 * masuk sebesar inset aman sehingga tidak tertutup status bar, navigasi bar, atau
 * keyboard.
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private var startOnAssistantTab = mutableStateOf(false)
    private var autoListenRequested = mutableStateOf(false)

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        ToastHub.show(this, getString(if (granted) R.string.toast_mic_granted else R.string.toast_mic_denied))
    }

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        ToastHub.show(this, getString(if (granted) R.string.toast_contacts_granted else R.string.toast_contacts_denied))
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge dipaksa oleh Android 15 untuk aplikasi targetSdk 35:
        // jendela menggambar di belakang system bar, jadi inset-nya ditangani
        // sendiri di bawah (dan juga di versi Android yang lebih lama, agar
        // tampilannya seragam). Opsi opt-out sudah dimatikan di Android 16.
        enableEdgeToEdge()
        dev.retrobit.voice.core.AppGraph.init(applicationContext)
        handleIntent(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val paperMode = settings?.epaperLightMode ?: false

            RetrobitTheme(paperMode = paperMode) {
                val textureColor = MaterialTheme.colorScheme.onBackground.copy(alpha = if (paperMode) 0.06f else 0.045f)
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    // Urutannya penting: tekstur e-paper digambar lebih dulu supaya
                    // tetap penuh sampai tepi layar (termasuk di belakang status &
                    // navigasi bar), baru isinya digeser masuk sebesar inset aman.
                    //
                    // Insets ini juga mencakup keyboard. Sejak edge-to-edge aktif,
                    // `windowSoftInputMode="adjustResize"` tidak lagi menyusutkan
                    // jendela, jadi tanpa ini kolom isian bisa tertutup keyboard —
                    // dan bilah tab bawah tertutup navigasi bar (3 tombol ≈ 48 dp).
                    Box(
                        Modifier
                            .fillMaxSize()
                            .epaperTexture(textureColor)
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Menangani intent yang membuka layar ini.
     *
     * Intent yang benar-benar kosong (tanpa extras) langsung diabaikan, dan
     * setiap extra dibuang setelah dipakai. Tanpa itu, intent yang sama akan
     * memicu ulang — mis. mendengarkan otomatis menyala lagi setiap layar
     * dibuat kembali (putar layar, kembali dari pengaturan sistem).
     */
    private fun handleIntent(intent: Intent?) {
        val extras = intent?.extras
        if (extras == null || extras.keySet().isEmpty()) return

        val openAssistant = extras.getBoolean(EXTRA_OPEN_ASSISTANT, false)
        val autoListen = extras.getBoolean(EXTRA_AUTO_LISTEN, false)

        if (openAssistant) startOnAssistantTab.value = true
        if (autoListen) autoListenRequested.value = true

        if (openAssistant) extras.remove(EXTRA_OPEN_ASSISTANT)
        if (autoListen) extras.remove(EXTRA_AUTO_LISTEN)
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

private enum class Tab(val labelRes: Int, val iconRes: Int, val code: String) {
    HOME(R.string.tab_home, R.drawable.ic_home, "F1"),
    DICTATION(R.string.tab_dictation, R.drawable.ic_mic, "F2"),
    ASSISTANT(R.string.tab_assistant, R.drawable.ic_spark, "F3"),
    SETTINGS(R.string.tab_settings, R.drawable.ic_settings_sliders, "F4"),
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
    val context = LocalContext.current

    LaunchedEffect(toast) {
        // Disimpan ke variabel lokal supaya bisa dipastikan tidak kosong
        // (properti hasil delegate tidak bisa di-smart-cast).
        val message = toast
        if (!message.isNullOrBlank()) {
            ToastHub.show(context, message)
            vm.consumeToast()
        }
    }
    LaunchedEffect(startOnAssistant) {
        if (startOnAssistant) tab = Tab.ASSISTANT
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(bottom = 46.dp)) {
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

        DeckNavBar(
            selected = if (showGuide) null else tab,
            onSelect = { entry ->
                tab = entry
                showGuide = false
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Bilah navigasi bergaya tombol instrumen, menempel di dasar panel. */
@Composable
private fun DeckNavBar(
    selected: Tab?,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, outline)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { entry ->
            val isSelected = entry == selected
            Row(
                Modifier
                    .weight(1f)
                    .border(1.dp, if (isSelected) ink else Color.Transparent)
                    .background(if (isSelected) ink else Color.Transparent)
                    .clickable { onSelect(entry) }
                    .padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(entry.iconRes),
                    contentDescription = null,
                    tint = if (isSelected) paper else dim,
                    modifier = Modifier.size(14.dp),
                )
                Box(Modifier.size(6.dp))
                Text(
                    text = str(entry.labelRes).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) paper else dim,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = Deck.GLYPH_BLOCK,
            style = MaterialTheme.typography.labelSmall,
            color = outline,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun str(id: Int): String = androidx.compose.ui.res.stringResource(id)
