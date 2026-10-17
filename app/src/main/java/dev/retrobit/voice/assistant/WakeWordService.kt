package dev.retrobit.voice.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.retrobit.voice.MainActivity
import dev.retrobit.voice.R
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.ToastHub
import dev.retrobit.voice.core.VoiceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the opt-in wake-word recognizer eligible to use the microphone in background. */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startJob: Job? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = AppGraph.init(applicationContext)
        when (intent?.action) {
            ACTION_STOP -> {
                startJob?.cancel()
                startJob = scope.launch {
                    runCatching { graph.settings.update { it.copy(wakeWordEnabled = false) } }
                        .onFailure { VoiceLog.e("Gagal menyimpan pengaturan wake word saat dimatikan", it) }
                    graph.assistant.stopWakeWord()
                    stopSelfSafely()
                }
                return START_NOT_STICKY
            }
            ACTION_START, null -> Unit // null is the system restarting a sticky service.
            else -> {
                VoiceLog.w("WakeWordService diabaikan — action tidak dikenal: ${intent.action}")
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }

        if (!graph.dictation.hasMicrophonePermission()) {
            VoiceLog.w("Wake word tidak dimulai: izin mikrofon belum diberikan")
            graph.assistant.stopWakeWord()
            stopSelfSafely()
            return START_NOT_STICKY
        }

        try {
            startAsForeground()
        } catch (t: Throwable) {
            VoiceLog.e("Wake word tidak bisa menjalankan foreground service", t)
            ToastHub.show(this, getString(R.string.toast_wake_word_start_failed), long = true)
            graph.assistant.stopWakeWord()
            stopSelfSafely()
            return START_NOT_STICKY
        }

        startJob?.cancel()
        startJob = scope.launch {
            val enabled = runCatching { graph.settings.current().wakeWordEnabled }.getOrDefault(false)
            if (enabled && graph.dictation.hasMicrophonePermission()) {
                graph.assistant.startWakeWordIfEnabled()
            } else {
                graph.assistant.stopWakeWord()
                stopSelfSafely()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        startJob?.cancel()
        AppGraph.init(applicationContext).assistant.stopWakeWord()
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_wake_word_title))
            .setContentText(getString(R.string.notif_wake_word_active))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_stop_wake_word), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun stopSelfSafely() {
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_wake_word),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_wake_word_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "retrobit_wake_word"
        private const val NOTIFICATION_ID = 4202
        private const val ACTION_START = "dev.retrobit.voice.action.START_WAKE_WORD"
        private const val ACTION_STOP = "dev.retrobit.voice.action.STOP_WAKE_WORD"

        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, WakeWordService::class.java).setAction(ACTION_START),
            )
        }.onFailure {
            VoiceLog.e("Gagal meminta service wake word", it)
        }.isSuccess

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, WakeWordService::class.java),
                )
            }.onFailure { VoiceLog.e("Gagal menghentikan service wake word", it) }
        }
    }
}
