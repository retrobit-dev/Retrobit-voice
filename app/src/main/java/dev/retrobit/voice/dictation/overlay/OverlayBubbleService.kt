package dev.retrobit.voice.dictation.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import dev.retrobit.voice.R
import dev.retrobit.voice.assistant.AssistantSession
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.ToastHub
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.dictation.DictationService
import dev.retrobit.voice.dictation.a11y.TextInsertAccessibilityService
import dev.retrobit.voice.dictation.insert.ClipboardHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Overlay mikrofon melayang dan popup jawaban asisten (butuh izin "tampil di atas aplikasi lain").
 *
 * Bubble tampil selama pengguna mengaktifkan overlay. Ketuk untuk mulai/berhenti dikte;
 * tanpa kolom teks hasil muncul di kartu pratinjau dan tidak ditempel otomatis.
 * Tekan lama membuka asisten. Popup asisten tampil saat sesi berlangsung lalu
 * menghilang otomatis setelah sepuluh detik idle.
 */
class OverlayBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleIcon: ImageView? = null
    private var waveformView: MicWaveformView? = null
    private var params: WindowManager.LayoutParams? = null
    private var assistantPopupView: View? = null
    private var dictationPreviewView: View? = null
    private var dictationPreviewStatusText: TextView? = null
    private var dictationPreviewBodyText: TextView? = null
    private var dictationPreviewFooterText: TextView? = null
    private var dictationPreviewCopyText: TextView? = null
    private var dictationPreviewCloseText: TextView? = null
    private var dictationPreviewDismissed = false
    private var popupTitleText: TextView? = null
    private var popupStatusText: TextView? = null
    private var popupCommandLabelText: TextView? = null
    private var popupCommandText: TextView? = null
    private var popupReplyLabelText: TextView? = null
    private var popupReplyText: TextView? = null
    private var popupFooterText: TextView? = null
    private var popupCloseText: TextView? = null
    private var popupDivider: View? = null
    private var popupSectionDivider: View? = null
    private var popupMascot: ImageView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var assistantStateJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var popupDismissRunnable: Runnable? = null
    private var assistantPopupRequested = false
    private var assistantWasActive = false
    private var paperMode = false
    private var persistentBubbleEnabled = false
    private var assistantSessionActive = false

    private var longPressRunnable: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_ASSISTANT_POPUP) {
            if (!Settings.canDrawOverlays(this)) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            assistantPopupRequested = true
            cancelPopupDismiss()
            runCatching { AppGraph.require.assistant.state.value }
                .onSuccess(::renderAssistantPopup)
                .onFailure { VoiceLog.e("Gagal membaca status popup asisten", it) }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            ToastHub.show(this, getString(R.string.overlay_permission_needed), long = true)
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForegroundCompat()
        addBubble()
        observeState()
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.overlay_notif_title))
            .setContentText(getString(R.string.overlay_notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun addBubble() {
        if (bubbleView != null) return
        val size = dp(58)

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
        }
        // Gaya e-paper: persegi (tanpa lengkung), dasar kertas gelap, garis tinta tipis.
        container.background = paneDrawable(fill = PANEL, stroke = LINE_STRONG, strokeWidth = 1)
        container.visibility = View.GONE
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.parseColor("#E9E9E4"))
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
        }
        container.addView(icon)
        bubbleIcon = icon

        val waveform = MicWaveformView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(34), dp(30), Gravity.CENTER)
            visibility = View.GONE
        }
        container.addView(waveform)
        waveformView = waveform

        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
        }

        container.setOnTouchListener { view, event -> handleTouch(view, event) }

        windowManager.addView(container, layoutParams)
        bubbleView = container
        params = layoutParams
        VoiceLog.i("Bubble dikte ditampilkan")
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val layoutParams = params ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = layoutParams.x
                startY = layoutParams.y
                moved = false
                longPressRunnable = Runnable {
                    if (!moved) {
                        AppGraph.require.assistant.openAssistantUi(autoListen = true, captureScreenContext = true)
                    }
                }.also { handler.postDelayed(it, 700) }
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downX).toInt()
                val dy = (event.rawY - downY).toInt()
                if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) {
                    moved = true
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                }
                layoutParams.x = startX + dx
                layoutParams.y = startY + dy
                runCatching { windowManager.updateViewLayout(view, layoutParams) }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = null
                if (!moved && event.action == MotionEvent.ACTION_UP) toggleDictation()
            }
        }
        return true
    }

    private fun toggleDictation() {
        val graph = AppGraph.require
        val hasFocusedTextField = TextInsertAccessibilityService.hasFocusedTextField()
        when (graph.dictation.state.value.phase) {
            DictationController.Phase.LISTENING -> DictationService.stop(this)
            DictationController.Phase.TRANSCRIBING, DictationController.Phase.CLEANING, DictationController.Phase.INSERTING ->
                ToastHub.show(this, getString(R.string.overlay_processing))
            else -> {
                graph.dictation.reset()
                val source = if (hasFocusedTextField) {
                    DictationController.Source.OVERLAY
                } else {
                    // Tanpa kolom teks, hentikan otomatis setelah jeda hening dan
                    // tampilkan hasil di overlay tanpa mencoba menempel ke aplikasi lain.
                    DictationController.Source.OVERLAY_PREVIEW
                }
                DictationService.start(this, source)
            }
        }
    }

    private fun observeState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            AppGraph.require.dictation.state.collect { state ->
                // Bubble selalu menjadi tombol overlay saat fitur ini diaktifkan;
                // tanpa kolom fokus, hasil ditampilkan untuk disalin, bukan ditempel.
                bubbleView?.visibility = if (persistentBubbleEnabled && !assistantSessionActive) View.VISIBLE else View.GONE

                // Monokrom: keadaan dibedakan lewat isi/garis, bukan warna.
                val recording = state.phase == DictationController.Phase.LISTENING
                val (fill, stroke, width) = when (state.phase) {
                    DictationController.Phase.LISTENING -> Triple(INK, INK, 2)
                    DictationController.Phase.TRANSCRIBING, DictationController.Phase.CLEANING,
                    DictationController.Phase.INSERTING -> Triple(PANEL_DIM, INK, 2)
                    DictationController.Phase.DONE -> Triple(PANEL, INK, 2)
                    DictationController.Phase.ERROR -> Triple(PANEL, LINE_STRONG, 1)
                    DictationController.Phase.IDLE -> Triple(PANEL, LINE_STRONG, 1)
                }
                bubbleView?.background = paneDrawable(fill, stroke, width)
                bubbleView?.contentDescription = getString(
                    if (recording) R.string.overlay_dictation_stop else R.string.overlay_dictation_start,
                )
                bubbleIcon?.visibility = if (recording) View.GONE else View.VISIBLE
                waveformView?.visibility = if (recording) View.VISIBLE else View.GONE
                waveformView?.setLevel(state.amplitude, if (fill == INK) PANEL else INK)
                renderDictationPreview(state)
            }
        }

        assistantStateJob?.cancel()
        assistantStateJob = scope.launch {
            combine(
                AppGraph.require.assistant.state,
                AppGraph.require.settings.flow,
            ) { state, settings -> state to settings }
                .collect { (state, settings) ->
                    paperMode = settings.epaperLightMode
                    persistentBubbleEnabled = settings.overlayBubbleEnabled
                    assistantSessionActive = state.listening || state.thinking
                    bubbleView?.visibility = if (persistentBubbleEnabled && !assistantSessionActive) View.VISIBLE else View.GONE
                    renderDictationPreview(AppGraph.require.dictation.state.value)
                    renderAssistantPopup(state)
                }
        }
    }

    private fun renderDictationPreview(state: DictationController.UiState) {
        if (state.phase == DictationController.Phase.IDLE) {
            dictationPreviewDismissed = false
            removeDictationPreview()
            return
        }

        val showPreview = state.isBusy || state.phase == DictationController.Phase.DONE ||
            state.phase == DictationController.Phase.ERROR
        if (!persistentBubbleEnabled || assistantSessionActive || !showPreview || dictationPreviewDismissed) {
            removeDictationPreview()
            return
        }

        if (dictationPreviewView == null) addDictationPreview()
        val palette = popupPalette()
        dictationPreviewView?.background = paneDrawable(palette.panel, palette.line, 2)
        dictationPreviewStatusText?.apply {
            setTextColor(palette.dim)
            text = when (state.phase) {
                DictationController.Phase.LISTENING -> getString(R.string.overlay_dictation_listening)
                DictationController.Phase.TRANSCRIBING,
                DictationController.Phase.CLEANING,
                DictationController.Phase.INSERTING -> state.stage.ifBlank { getString(R.string.overlay_dictation_processing) }
                DictationController.Phase.DONE -> getString(R.string.overlay_dictation_done)
                DictationController.Phase.ERROR -> getString(R.string.overlay_dictation_error)
                DictationController.Phase.IDLE -> ""
            }
        }
        val errorText = state.error.orEmpty()
        dictationPreviewBodyText?.apply {
            setTextColor(palette.ink)
            text = when {
                state.phase == DictationController.Phase.DONE && state.finalText.isNotBlank() -> state.finalText
                state.phase == DictationController.Phase.ERROR && errorText.isNotBlank() -> errorText
                state.partial.isNotBlank() -> state.partial
                state.finalText.isNotBlank() -> state.finalText
                state.rawText.isNotBlank() -> state.rawText
                errorText.isNotBlank() -> errorText
                state.phase == DictationController.Phase.LISTENING -> getString(R.string.overlay_dictation_speak_now)
                else -> state.stage.ifBlank { getString(R.string.overlay_dictation_speak_now) }
            }
        }
        dictationPreviewFooterText?.apply {
            setTextColor(palette.dim)
            text = if (state.phase == DictationController.Phase.LISTENING) {
                getString(R.string.overlay_dictation_stop_hint)
            } else {
                getString(R.string.overlay_dictation_preview_hint)
            }
        }
        dictationPreviewCopyText?.apply {
            setTextColor(palette.ink)
            visibility = if (state.finalText.isNotBlank()) View.VISIBLE else View.GONE
        }
        dictationPreviewCloseText?.setTextColor(palette.ink)
    }

    private fun addDictationPreview() {
        val palette = popupPalette()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = paneDrawable(palette.panel, palette.line, 2)
            elevation = dp(8).toFloat()
            isClickable = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = popupText(getString(R.string.overlay_dictation_preview_title), 10f, bold = true).apply {
            setTextColor(palette.dim)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = popupText("×", 22f, bold = false).apply {
            setTextColor(palette.ink)
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.overlay_dictation_close)
            isClickable = true
            setOnClickListener {
                dictationPreviewDismissed = true
                removeDictationPreview()
            }
        }
        header.addView(title)
        header.addView(close, LinearLayout.LayoutParams(dp(36), dp(34)))
        panel.addView(header)

        val status = popupText(getString(R.string.overlay_dictation_listening), 10f, bold = true).apply {
            setTextColor(palette.dim)
            setPadding(0, dp(4), 0, dp(8))
        }
        panel.addView(status)

        val body = popupText(getString(R.string.overlay_dictation_speak_now), 15f, bold = false).apply {
            setTextColor(palette.ink)
            maxLines = 5
            setPadding(0, 0, 0, dp(8))
        }
        panel.addView(body)

        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val footer = popupText(getString(R.string.overlay_dictation_stop_hint), 9f, bold = true).apply {
            setTextColor(palette.dim)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val copy = popupText(getString(R.string.overlay_dictation_copy), 10f, bold = true).apply {
            setTextColor(palette.ink)
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = true
            contentDescription = getString(R.string.action_copy)
            setOnClickListener {
                val text = AppGraph.require.dictation.state.value.finalText
                ClipboardHelper.copy(this@OverlayBubbleService, text)
            }
        }
        footerRow.addView(footer)
        footerRow.addView(copy)
        panel.addView(footerRow)

        val screenWidth = resources.displayMetrics.widthPixels
        val previewWidth = (screenWidth - dp(32)).coerceAtMost(dp(560)).coerceAtLeast(dp(240))
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val previewParams = WindowManager.LayoutParams(
            previewWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(88)
        }

        try {
            windowManager.addView(panel, previewParams)
            dictationPreviewView = panel
            dictationPreviewStatusText = status
            dictationPreviewBodyText = body
            dictationPreviewFooterText = footer
            dictationPreviewCopyText = copy
            dictationPreviewCloseText = close
        } catch (t: Throwable) {
            VoiceLog.e("Gagal menampilkan preview dikte overlay", t)
            dictationPreviewDismissed = true
        }
    }

    private fun removeDictationPreview() {
        dictationPreviewView?.let { runCatching { windowManager.removeView(it) } }
        dictationPreviewView = null
        dictationPreviewStatusText = null
        dictationPreviewBodyText = null
        dictationPreviewFooterText = null
        dictationPreviewCopyText = null
        dictationPreviewCloseText = null
    }

    private fun renderAssistantPopup(state: AssistantSession.UiState) {
        if (!assistantPopupRequested) return
        val active = state.listening || state.thinking
        val command = state.currentCommand.ifBlank { state.partial }
        val hasContent = active || command.isNotBlank() || state.currentReply.isNotBlank() || !state.error.isNullOrBlank()
        if (!hasContent && assistantPopupView == null) return
        if (assistantPopupView == null) addAssistantPopup()
        if (assistantPopupView == null) return

        val palette = popupPalette()
        assistantPopupView?.background = paneDrawable(palette.panel, palette.line, 2)
        popupTitleText?.setTextColor(palette.ink)
        popupStatusText?.apply {
            setTextColor(palette.dim)
            text = when {
                !state.error.isNullOrBlank() -> "[ ! ERROR ]"
                state.listening -> getString(R.string.assistant_popup_listening)
                state.thinking && state.currentReply.isNotBlank() -> getString(R.string.assistant_popup_responding)
                state.thinking -> getString(R.string.assistant_popup_processing)
                else -> getString(R.string.assistant_popup_finished)
            }
        }
        popupCommandLabelText?.setTextColor(palette.dim)
        popupCommandText?.apply {
            setTextColor(palette.ink)
            text = command.ifBlank {
                when {
                    state.listening -> getString(R.string.assistant_popup_listening_hint)
                    state.thinking -> getString(R.string.assistant_popup_processing_hint)
                    else -> "—"
                }
            }
        }
        popupReplyLabelText?.setTextColor(palette.dim)
        popupReplyText?.apply {
            setTextColor(palette.ink)
            text = state.currentReply.ifBlank {
                when {
                    !state.error.isNullOrBlank() -> "! ${state.error}"
                    state.listening -> getString(R.string.assistant_popup_reply_waiting)
                    state.thinking -> getString(R.string.assistant_popup_processing_hint)
                    else -> "—"
                }
            }
        }
        popupFooterText?.setTextColor(palette.dim)
        popupCloseText?.setTextColor(palette.ink)
        popupDivider?.setBackgroundColor(palette.line)
        popupSectionDivider?.setBackgroundColor(palette.line)
        popupMascot?.setColorFilter(palette.ink)

        if (active) {
            assistantWasActive = true
            cancelPopupDismiss()
        } else {
            val shouldScheduleDismiss = assistantWasActive || popupDismissRunnable == null
            assistantWasActive = false
            if (shouldScheduleDismiss) schedulePopupDismiss()
        }
    }

    private fun addAssistantPopup() {
        val palette = popupPalette()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = paneDrawable(palette.panel, palette.line, 2)
            elevation = dp(8).toFloat()
            isClickable = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mascot = ImageView(this).apply {
            setImageResource(R.drawable.ic_retrobit_mascot)
            setColorFilter(palette.ink)
            contentDescription = getString(R.string.assistant_popup_avatar)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) }
        }
        header.addView(mascot)

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = popupText(getString(R.string.assistant_popup_title), 13f, bold = true).apply {
            setTextColor(palette.ink)
        }
        val status = popupText(getString(R.string.assistant_popup_listening), 10f, bold = true).apply {
            setTextColor(palette.dim)
            setPadding(0, dp(4), 0, 0)
        }
        heading.addView(title)
        heading.addView(status)
        header.addView(heading)

        val close = popupText("×", 25f, bold = false).apply {
            setTextColor(palette.ink)
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.assistant_popup_close)
            isClickable = true
            isFocusable = false
            setOnClickListener { dismissAssistantPopup() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(38), dp(42)))
        panel.addView(header)

        val divider = View(this).apply {
            setBackgroundColor(palette.line)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        panel.addView(divider)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (commandLabel, commandText) = addPopupSection(content, getString(R.string.assistant_popup_command), palette)
        val sectionDivider = View(this).apply {
            setBackgroundColor(palette.line)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        }
        content.addView(sectionDivider)
        val (replyLabel, replyText) = addPopupSection(content, getString(R.string.assistant_popup_answer), palette)

        val maxScrollHeight = minOf(dp(280), (resources.displayMetrics.heightPixels * 0.42f).toInt())
        val scroll = BoundedScrollView(this, maxScrollHeight).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val footer = popupText(getString(R.string.assistant_popup_autohide), 9f, bold = true).apply {
            setTextColor(palette.dim)
            setPadding(0, dp(8), 0, 0)
        }
        panel.addView(footer)

        val screenWidth = resources.displayMetrics.widthPixels
        val popupWidth = (screenWidth - dp(32)).coerceAtMost(dp(560)).coerceAtLeast(dp(240))
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val windowParams = WindowManager.LayoutParams(
            popupWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }

        try {
            windowManager.addView(panel, windowParams)
            assistantPopupView = panel
            popupTitleText = title
            popupStatusText = status
            popupCommandLabelText = commandLabel
            popupCommandText = commandText
            popupReplyLabelText = replyLabel
            popupReplyText = replyText
            popupFooterText = footer
            popupCloseText = close
            popupDivider = divider
            popupSectionDivider = sectionDivider
            popupMascot = mascot
            VoiceLog.i("Popup asisten Retrobit ditampilkan")
        } catch (t: Throwable) {
            assistantPopupRequested = false
            VoiceLog.e("Gagal menampilkan popup asisten", t)
            if (!persistentBubbleEnabled) stopSelf()
        }
    }

    private fun addPopupSection(
        parent: LinearLayout,
        label: String,
        palette: PopupPalette,
    ): Pair<TextView, TextView> {
        val labelView = popupText(label, 10f, bold = true).apply { setTextColor(palette.dim) }
        val valueView = popupText("—", 13f, bold = false).apply {
            setTextColor(palette.ink)
            setPadding(0, dp(4), 0, 0)
        }
        parent.addView(labelView)
        parent.addView(valueView)
        return labelView to valueView
    }

    private fun popupText(text: String, sizeSp: Float, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        letterSpacing = 0.06f
        setLineSpacing(dp(2).toFloat(), 1f)
        includeFontPadding = false
    }

    private fun popupPalette(): PopupPalette = if (paperMode) {
        PopupPalette(
            panel = Color.parseColor("#F2F2EE"),
            ink = Color.parseColor("#111111"),
            dim = Color.parseColor("#5E5E58"),
            line = Color.parseColor("#8A8A84"),
        )
    } else {
        PopupPalette(
            panel = Color.parseColor("#141414"),
            ink = Color.parseColor("#E9E9E4"),
            dim = Color.parseColor("#8E8E88"),
            line = Color.parseColor("#4C4C4C"),
        )
    }

    private fun cancelPopupDismiss() {
        popupDismissRunnable?.let(handler::removeCallbacks)
        popupDismissRunnable = null
    }

    private fun schedulePopupDismiss() {
        if (popupDismissRunnable != null) return
        val runnable = Runnable {
            popupDismissRunnable = null
            val latestState = runCatching { AppGraph.require.assistant.state.value }.getOrNull()
            if (latestState?.listening == true || latestState?.thinking == true) {
                assistantWasActive = true
                return@Runnable
            }
            assistantPopupRequested = false
            assistantWasActive = false
            removeAssistantPopup()
            if (!persistentBubbleEnabled) stopSelf()
        }
        popupDismissRunnable = runnable
        handler.postDelayed(runnable, POPUP_IDLE_TIMEOUT_MS)
    }

    private fun dismissAssistantPopup() {
        assistantPopupRequested = false
        assistantWasActive = false
        cancelPopupDismiss()
        removeAssistantPopup()
        if (!persistentBubbleEnabled) stopSelf()
    }

    private fun removeAssistantPopup() {
        assistantPopupView?.let { runCatching { windowManager.removeView(it) } }
        assistantPopupView = null
        popupTitleText = null
        popupStatusText = null
        popupCommandLabelText = null
        popupCommandText = null
        popupReplyLabelText = null
        popupReplyText = null
        popupFooterText = null
        popupCloseText = null
        popupDivider = null
        popupSectionDivider = null
        popupMascot = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_overlay),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { setShowBadge(false) },
        )
    }

    override fun onDestroy() {
        stateJob?.cancel()
        assistantStateJob?.cancel()
        cancelPopupDismiss()
        removeAssistantPopup()
        removeDictationPreview()
        scope.cancel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleIcon = null
        waveformView = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Kotak panel e-paper: sudut tajam, garis 1px. */
    private fun paneDrawable(fill: Int, stroke: Int, strokeWidth: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 0f
        setColor(fill)
        setStroke(dp(strokeWidth), stroke)
    }

    private data class PopupPalette(val panel: Int, val ink: Int, val dim: Int, val line: Int)

    /** Batasi tinggi konten popup; jawaban panjang tetap dapat digulir. */
    private class BoundedScrollView(context: Context, private val maxContentHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val boundedHeight = View.MeasureSpec.makeMeasureSpec(maxContentHeight, View.MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, boundedHeight)
        }
    }

    /** Gelombang mini di dalam bubble; tinggi batang mengikuti level mikrofon. */
    private class MicWaveformView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private var level = 0f
        private val shape = floatArrayOf(0.25f, 0.48f, 0.78f, 1f, 0.7f, 0.45f, 0.28f)

        fun setLevel(value: Float, color: Int) {
            level = value.coerceIn(0f, 1f)
            paint.color = color
            contentDescription = "Gelombang mikrofon, level ${(level * 100).toInt()} persen"
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val count = shape.size
            val gap = width * 0.075f
            val barWidth = ((width - gap * (count - 1)) / count).coerceAtLeast(1f)
            val center = height / 2f
            val maxHeight = height * 0.82f
            val minHeight = height * 0.16f
            shape.forEachIndexed { index, weight ->
                val response = (0.08f + level * (0.25f + weight * 0.72f)).coerceAtMost(1f)
                val barHeight = minHeight + (maxHeight - minHeight) * response
                val left = index * (barWidth + gap)
                canvas.drawRoundRect(
                    left,
                    center - barHeight / 2f,
                    left + barWidth,
                    center + barHeight / 2f,
                    barWidth / 2f,
                    barWidth / 2f,
                    paint,
                )
            }
        }
    }

    companion object {
        // Palet e-paper (monokrom) untuk bubble.
        private val PANEL = Color.parseColor("#141414")
        private val PANEL_DIM = Color.parseColor("#3A3A3A")
        private val INK = Color.parseColor("#E9E9E4")
        private val LINE_STRONG = Color.parseColor("#4C4C4C")

        private const val CHANNEL_ID = "retrobit_overlay"
        private const val NOTIFICATION_ID = 4202
        private const val ACTION_SHOW_ASSISTANT_POPUP = "dev.retrobit.voice.action.SHOW_ASSISTANT_POPUP"
        private const val POPUP_IDLE_TIMEOUT_MS = 10_000L

        fun showAssistantPopup(context: Context) {
            val appContext = context.applicationContext
            if (!hasPermission(appContext)) {
                VoiceLog.w("Popup asisten dilewati: izin tampil di atas aplikasi belum diberikan")
                return
            }
            val intent = Intent(appContext, OverlayBubbleService::class.java).setAction(ACTION_SHOW_ASSISTANT_POPUP)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }.onFailure { VoiceLog.e("Gagal memulai popup asisten", it) }
        }

        fun start(context: Context) {
            val intent = Intent(context, OverlayBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayBubbleService::class.java))
        }

        fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** Intent untuk membuka layar izin "tampil di atas aplikasi lain". */
        fun permissionIntent(context: Context): Intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }
}
