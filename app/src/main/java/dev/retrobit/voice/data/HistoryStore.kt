package dev.retrobit.voice.data

import android.content.Context
import dev.retrobit.voice.core.AssistantTurn
import dev.retrobit.voice.core.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Riwayat lokal (dikte + percakapan asisten). Disimpan sebagai JSONL di
 * penyimpanan internal aplikasi — tidak pernah dikirim ke mana pun kecuali
 * pengguna sendiri yang menekan tombol bagikan.
 */
@Serializable
data class DictationHistoryEntry(
    val text: String,
    val engine: String,
    val language: String,
    val timestampMs: Long,
)

@Serializable
data class AssistantHistoryEntry(
    val user: String,
    val reply: String,
    val action: String,
    val source: String,
    val timestampMs: Long,
)

class HistoryStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dictationFile = File(context.filesDir, "dictation_history.jsonl")
    private val assistantFile = File(context.filesDir, "assistant_history.jsonl")
    private val notesFile = File(context.filesDir, "notes.txt")

    private val _dictation = MutableStateFlow<List<DictationHistoryEntry>>(emptyList())
    val dictation: StateFlow<List<DictationHistoryEntry>> = _dictation.asStateFlow()

    private val _assistant = MutableStateFlow<List<AssistantHistoryEntry>>(emptyList())
    val assistant: StateFlow<List<AssistantHistoryEntry>> = _assistant.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _dictation.value = readAll(dictationFile) { json.decodeFromString<DictationHistoryEntry>(it) }
        _assistant.value = readAll(assistantFile) { json.decodeFromString<AssistantHistoryEntry>(it) }
    }

    suspend fun addDictation(transcript: Transcript, enabled: Boolean) = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        val entry = DictationHistoryEntry(transcript.text, transcript.engine, transcript.language, System.currentTimeMillis())
        _dictation.value = (listOf(entry) + _dictation.value).take(MAX_ENTRIES)
        dictationFile.appendText(json.encodeToString(DictationHistoryEntry.serializer(), entry) + "\n")
    }

    suspend fun addAssistant(turn: AssistantTurn, enabled: Boolean) = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        val entry = AssistantHistoryEntry(
            user = turn.userText,
            reply = turn.reply,
            action = turn.action?.let { it::class.simpleName } ?: "-",
            source = turn.source,
            timestampMs = turn.timestampMs,
        )
        _assistant.value = (listOf(entry) + _assistant.value).take(MAX_ENTRIES)
        assistantFile.appendText(json.encodeToString(AssistantHistoryEntry.serializer(), entry) + "\n")
    }

    suspend fun appendNote(body: String) = withContext(Dispatchers.IO) {
        notesFile.appendText("- ${System.currentTimeMillis()} :: $body\n")
    }

    fun notesText(): String = if (notesFile.exists()) notesFile.readText() else ""

    suspend fun clear() = withContext(Dispatchers.IO) {
        dictationFile.delete()
        assistantFile.delete()
        _dictation.value = emptyList()
        _assistant.value = emptyList()
    }

    private fun <T> readAll(file: File, parse: (String) -> T): List<T> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { parse(line) }.getOrNull() }
                .reversed()
                .take(MAX_ENTRIES)
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val MAX_ENTRIES = 200
    }
}
