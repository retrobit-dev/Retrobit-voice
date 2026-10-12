package dev.retrobit.voice.dictation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.retrobit.voice.MainActivity
import dev.retrobit.voice.R
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.VoiceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service latar depan yang menjaga proses tetap hidup selama mikrofon dipakai.
 *
 * Android 11+ membatasi akses mikrofon dari latar belakang, jadi semua rekaman
 * (keyboard, bubble, tile) dijalankan lewat service ini dengan tipe
 * `microphone` sesuai persyaratan Android 14.
 */
class DictationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = AppGraph.require
        when (intent?.action) {
            ACTION_STOP -> {
                graph.dictation.stopAndFinish()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                graph.dictation.cancel()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_SHOW -> {
                startAsForeground(getString(R.string.notif_dictation_title), getString(R.string.notif_dictation_idle))
                return START_NOT_STICKY
            }
        }

        val source = runCatching {
            DictationController.Source.valueOf(intent?.getStringExtra(EXTRA_SOURCE) ?: DictationController.Source.MANUAL.name)
        }.getOrDefault(DictationController.Source.MANUAL)

        startAsForeground(getString(R.string.notif_dictation_title), getString(R.string.notif_dictation_listening))

        val started = graph.dictation.start(source)
        if (!started) {
            VoiceLog.w("Dikte tidak bisa dimulai (izin mikrofon belum ada)")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        stateJob?.cancel()
        stateJob = scope.launch {
            graph.dictation.state.collect { state ->
                val text = when {
                    state.needsMicrophonePermission -> getString(R.string.notif_dictation_no_permission)
                    state.partial.isNotBlank() -> state.partial
                    state.stage.isNotBlank() -> state.stage
                    state.finalText.isNotBlank() -> state.finalText
                    else -> getString(R.string.notif_dictation_listening)
                }
                notify(notification(text))

                if (state.phase == DictationController.Phase.DONE || state.phase == DictationController.Phase.ERROR) {
                    // Beri jeda singkat agar pengguna sempat membaca hasilnya.
                    kotlinx.coroutines.delay(1200)
                    stopSelfSafely()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(title: String, text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DictationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this,
            2,
            Intent(this, DictationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_dictation_title))
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(300)))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_finish), stop)
            .addAction(0, getString(R.string.action_cancel), cancel)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_dictation),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_dictation_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "retrobit_dictation"
        const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "dev.retrobit.voice.action.STOP_DICTATION"
        const val ACTION_CANCEL = "dev.retrobit.voice.action.CANCEL_DICTATION"
        const val ACTION_SHOW = "dev.retrobit.voice.action.SHOW_DICTATION"
        const val EXTRA_SOURCE = "source"

        fun start(context: Context, source: DictationController.Source) {
            val intent = Intent(context, DictationService::class.java).apply {
                putExtra(EXTRA_SOURCE, source.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DictationService::class.java).setAction(ACTION_STOP))
        }
    }
}
