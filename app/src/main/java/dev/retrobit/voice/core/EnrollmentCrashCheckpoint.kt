package dev.retrobit.voice.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * Persists only voice-enrollment phase/error diagnostics, never audio or the enrolled phrase.
 * If Android kills the process during capture or inference, the next launch can report the
 * last completed checkpoint in the in-app Diagnostics panel.
 */
object EnrollmentCrashCheckpoint {
    private const val CHECKPOINT_FILE = "voice-enrollment-checkpoint.txt"
    private const val FATAL_FILE = "voice-enrollment-fatal.txt"
    private const val MAX_FATAL_TRACE_CHARS = 8_192
    private const val MAX_NATIVE_TRACE_BYTES = 64 * 1_024
    private const val MAX_NATIVE_TRACE_SUMMARY_CHARS = 1_600
    private const val MAX_CHECKPOINT_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
    private val lock = Any()

    suspend fun mark(context: Context, feature: String, stage: String) {
        withContext(Dispatchers.IO) {
            val record = "${System.currentTimeMillis()}|${sanitize(feature).take(32)}|${sanitize(stage).take(64)}\n"
            writeAtomic(context, CHECKPOINT_FILE, record.toByteArray(Charsets.UTF_8), "checkpoint enrolmen")
        }
    }

    suspend fun clear(context: Context) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { synchronized(lock) { atomicFile(context, CHECKPOINT_FILE).delete() } }
                .onFailure { Log.w(VoiceLog.TAG, "Tidak dapat membersihkan checkpoint enrolmen", it) }
        }
    }

    /** Store class/frame diagnostics only while enrollment is active; exception messages may contain user data. */
    fun saveFatalException(context: Context, threadName: String, throwable: Throwable) {
        if (!baseFile(context, CHECKPOINT_FILE).exists()) return
        val trace = buildString {
            append("thread=").append(sanitize(threadName)).append('\n')
            var cause: Throwable? = throwable
            var depth = 0
            while (depth < 3 && length < MAX_FATAL_TRACE_CHARS) {
                val currentCause = cause ?: break
                append("cause[").append(depth).append("]=").append(currentCause.javaClass.name).append('\n')
                currentCause.stackTrace.take(24).forEach { frame ->
                    if (length < MAX_FATAL_TRACE_CHARS) {
                        append(" at ").append(frame.className).append('.').append(frame.methodName)
                            .append(':').append(frame.lineNumber).append('\n')
                    }
                }
                cause = currentCause.cause
                depth += 1
            }
        }.take(MAX_FATAL_TRACE_CHARS)
        writeAtomic(context, FATAL_FILE, trace.toByteArray(Charsets.UTF_8), "diagnostik crash enrolmen")
    }

    /** Consume once on the next app start so a stale marker does not recur forever. */
    fun consume(context: Context): String? = consumeRecord(context, CHECKPOINT_FILE)?.let { record ->
        val fields = record.split('|', limit = 3)
        if (fields.size != 3) return@let "checkpoint rusak"
        val time = fields[0].toLongOrNull()?.let { timestamp ->
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(java.util.Date(timestamp))
        } ?: "waktu tidak diketahui"
        "${fields[1]} / ${fields[2]} ($time)"
    }

    fun consumeFatalException(context: Context): String? = consumeRecord(context, FATAL_FILE)

    /** Read Android's exit record before consuming the enrollment checkpoint. */
    fun consumePreviousProcessExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val checkpointTimestamp = readCheckpointTimestamp(context) ?: return null
        if (System.currentTimeMillis() - checkpointTimestamp > MAX_CHECKPOINT_AGE_MS) return null

        return runCatching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching null
            val exit = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .filter { it.processName == context.packageName && it.timestamp >= checkpointTimestamp }
                .maxByOrNull { it.timestamp }
                ?: return@runCatching null
            val reason = exitReasonName(exit.reason)
            val signal = if (
                exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                exit.reason == ApplicationExitInfo.REASON_SIGNALED
            ) signalName(exit.status) else null
            val trace = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            ) {
                runCatching { exit.traceInputStream?.use(::summarizeNativeTrace) }.getOrNull()
            } else {
                null
            }
            buildString {
                append("reason=").append(reason)
                append(" status=").append(exit.status)
                if (signal != null) append(" signal=").append(signal)
                append(" pssKb=").append(exit.pss)
                append(" rssKb=").append(exit.rss)
                if (trace != null) append(' ').append(trace)
            }
        }.onFailure { Log.w(VoiceLog.TAG, "Tidak dapat membaca alasan proses enrolmen berhenti", it) }
            .getOrNull()
    }

    private fun readCheckpointTimestamp(context: Context): Long? = runCatching {
        synchronized(lock) {
            try {
                atomicFile(context, CHECKPOINT_FILE).openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.readLine()?.substringBefore('|')?.toLongOrNull()
                }
            } catch (_: FileNotFoundException) {
                null
            }
        }
    }.onFailure { Log.w(VoiceLog.TAG, "Tidak dapat membaca waktu checkpoint enrolmen", it) }
        .getOrNull()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        else -> "REASON_$reason"
    }

    private fun signalName(signal: Int): String = when (signal) {
        4 -> "SIGILL"
        6 -> "SIGABRT"
        7 -> "SIGBUS"
        9 -> "SIGKILL"
        11 -> "SIGSEGV"
        else -> "SIGNAL_$signal"
    }

    private fun summarizeNativeTrace(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(2_048)
        while (output.size() < MAX_NATIVE_TRACE_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_NATIVE_TRACE_BYTES - output.size()))
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
        val trace = output.toByteArray()
        val fragments = mutableListOf<String>()
        val current = StringBuilder()
        fun flushFragment() {
            if (current.length >= 4) fragments += current.toString()
            current.setLength(0)
        }
        trace.forEach { byte ->
            val value = byte.toInt() and 0xff
            if (value in 32..126) current.append(value.toChar()) else flushFragment()
        }
        flushFragment()
        val usefulFragments = fragments
            .filter { fragment ->
                val lower = fragment.lowercase(Locale.ROOT)
                listOf("onnx", "mlas", "sig", "libc", "libart", "arm", "fault", "abort", "retrobit")
                    .any { lower.contains(it) }
            }
            .distinct()
            .take(24)
            .joinToString(" | ")
            .take(MAX_NATIVE_TRACE_SUMMARY_CHARS)
        return "tombstoneBytes=${trace.size}" + usefulFragments.takeIf { it.isNotBlank() }?.let { " trace=$it" }.orEmpty()
    }

    private fun consumeRecord(context: Context, fileName: String): String? = runCatching {
        synchronized(lock) {
            val checkpoint = atomicFile(context, fileName)
            val record = try {
                checkpoint.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: FileNotFoundException) {
                return@synchronized null
            }
            checkpoint.delete()
            record.takeIf { it.isNotBlank() }
        }
    }.onFailure { Log.w(VoiceLog.TAG, "Tidak dapat membaca diagnostik enrolmen", it) }
        .getOrNull()

    private fun writeAtomic(context: Context, fileName: String, bytes: ByteArray, label: String) {
        val atomic = atomicFile(context, fileName)
        synchronized(lock) {
            var output: FileOutputStream? = null
            try {
                val stream = atomic.startWrite()
                output = stream
                stream.write(bytes)
                stream.fd.sync()
                atomic.finishWrite(stream)
                output = null
            } catch (error: Throwable) {
                runCatching { output?.let(atomic::failWrite) }
                    .onFailure { Log.w(VoiceLog.TAG, "Gagal membatalkan penulisan $label", it) }
                Log.w(VoiceLog.TAG, "Tidak dapat menyimpan $label", error)
            }
        }
    }

    private fun atomicFile(context: Context, fileName: String): AtomicFile = AtomicFile(baseFile(context, fileName))

    private fun baseFile(context: Context, fileName: String): File =
        File(context.applicationContext.noBackupFilesDir, fileName)

    private fun sanitize(value: String): String = value
        .lowercase(Locale.ROOT)
        .map { character -> if (character.isLetterOrDigit() || character == '-' || character == '_') character else '-' }
        .joinToString("")
        .trim('-')
}
