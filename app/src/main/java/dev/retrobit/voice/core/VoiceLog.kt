package dev.retrobit.voice.core

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Logger ringan + ring buffer untuk layar "Diagnostik".
 * Semua lapisan memakai ini supaya pengguna bisa mengirim log tanpa adb.
 */
object VoiceLog {

    const val TAG = "RetrobitVoice"

    private const val MAX_LINES = 400
    private val lines = CopyOnWriteArrayList<String>()
    private val seq = AtomicLong(0)

    fun d(message: String) = log("D", message)
    fun i(message: String) = log("I", message)
    fun w(message: String) = log("W", message)
    fun e(message: String, t: Throwable? = null) = log("E", message + (t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    private fun log(level: String, message: String) {
        when (level) {
            "D" -> Log.d(TAG, message)
            "I" -> Log.i(TAG, message)
            "W" -> Log.w(TAG, message)
            else -> Log.e(TAG, message)
        }
        val n = seq.incrementAndGet()
        lines.add("$n [$level] ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $message")
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    fun snapshot(): String = lines.joinToString("\n").ifBlank { "(belum ada log)" }
}
