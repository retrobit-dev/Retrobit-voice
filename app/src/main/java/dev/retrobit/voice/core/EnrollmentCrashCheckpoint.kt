package dev.retrobit.voice.core

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
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
