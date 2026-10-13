package dev.retrobit.voice.core

import android.content.Context
import dev.retrobit.voice.ai.LlmClient
import dev.retrobit.voice.assistant.AppResolver
import dev.retrobit.voice.assistant.AssistantBrain
import dev.retrobit.voice.assistant.AssistantSession
import dev.retrobit.voice.assistant.AssistantExecutor
import dev.retrobit.voice.assistant.TtsSpeaker
import dev.retrobit.voice.data.HistoryStore
import dev.retrobit.voice.data.SettingsRepository
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.dictation.insert.TextInserter
import dev.retrobit.voice.stt.HybridSttEngine

/**
 * Service locator sederhana. Aplikasi ini kecil (satu modul, satu proses),
 * jadi kita tidak memerlukan kerangka dependency injection penuh.
 * Kalau nanti tumbuh, kelas ini adalah satu-satunya tempat yang perlu diganti.
 */
class AppGraph private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    val settings: SettingsRepository = SettingsRepository(appContext)
    val history: HistoryStore = HistoryStore(appContext)
    val stt: HybridSttEngine = HybridSttEngine(appContext)
    val llm: LlmClient = LlmClient()
    val tts: TtsSpeaker = TtsSpeaker(appContext)
    val textInserter: TextInserter = TextInserter(settings)
    val assistantExecutor: AssistantExecutor = AssistantExecutor(appContext, history)
    val brain: AssistantBrain = AssistantBrain(llm, AppResolver(appContext))
    val dictation: DictationController = DictationController(appContext, this)
    val assistant: AssistantSession = AssistantSession(appContext, this)

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun init(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }

        val require: AppGraph
            get() = instance ?: error("AppGraph belum diinisialisasi — panggil AppGraph.init() dari Application")
    }
}
