package dev.retrobit.voice.dictation.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import dev.retrobit.voice.R
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.ToastHub
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.dictation.DictationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Bubble mikrofon melayang (opsional, butuh izin "tampil di atas aplikasi lain").
 *
 * Ini pengalaman yang paling mirip Wispr Flow: bubble tersedia di aplikasi apa
 * pun, sekali tekan langsung merekam, dan teksnya ditempel ke kolom yang aktif
 * (lewat keyboard Retrobit atau layanan aksesibilitas).
 *
 * Ketuk = mulai/selesai dikte · Tekan lama = buka asisten.
 */
class OverlayBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleIcon: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    private var longPressRunnable: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    override fun onBind(intent: Intent?): IBinder? = null

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
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.parseColor("#E9E9E4"))
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
        }
        container.addView(icon)
        bubbleIcon = icon

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
                        AppGraph.require.assistant.openAssistantUi(autoListen = true)
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
        when (graph.dictation.state.value.phase) {
            DictationController.Phase.LISTENING -> DictationService.stop(this)
            DictationController.Phase.TRANSCRIBING, DictationController.Phase.CLEANING, DictationController.Phase.INSERTING ->
                ToastHub.show(this, getString(R.string.overlay_processing))
            else -> {
                graph.dictation.reset()
                DictationService.start(this, DictationController.Source.OVERLAY)
            }
        }
    }

    private fun observeState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            AppGraph.require.dictation.state.collect { state ->
                // Monokrom: keadaan dibedakan lewat isi/garis, bukan warna.
                val (fill, stroke, width) = when (state.phase) {
                    DictationController.Phase.LISTENING -> Triple(INK, INK, 2)        // kotak penuh = merekam
                    DictationController.Phase.TRANSCRIBING, DictationController.Phase.CLEANING,
                    DictationController.Phase.INSERTING -> Triple(PANEL_DIM, INK, 2)  // abu = memproses
                    DictationController.Phase.DONE -> Triple(PANEL, INK, 2)           // garis tegas = selesai
                    DictationController.Phase.ERROR -> Triple(PANEL, LINE_STRONG, 1)  // garis samar = galat
                    DictationController.Phase.IDLE -> Triple(PANEL, LINE_STRONG, 1)
                }
                bubbleView?.background = paneDrawable(fill, stroke, width)
                // Ikon ikut ter-inversi supaya tetap kontras di kotak yang penuh tinta.
                bubbleIcon?.setColorFilter(if (fill == INK) PANEL else INK)
            }
        }
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
        scope.cancel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
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

    companion object {
        // Palet e-paper (monokrom) untuk bubble.
        private val PANEL = Color.parseColor("#141414")
        private val PANEL_DIM = Color.parseColor("#3A3A3A")
        private val INK = Color.parseColor("#E9E9E4")
        private val LINE_STRONG = Color.parseColor("#4C4C4C")

        private const val CHANNEL_ID = "retrobit_overlay"
        private const val NOTIFICATION_ID = 4202

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
