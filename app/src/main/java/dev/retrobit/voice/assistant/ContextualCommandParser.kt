package dev.retrobit.voice.assistant

import dev.retrobit.voice.core.AssistantAction
import dev.retrobit.voice.core.MediaCommand

/**
 * Memetakan perintah yang maknanya bergantung pada aplikasi/media yang sedang tampil.
 * Snapshot layar hanya dipakai lokal; teks layar tidak dianggap sebagai instruksi.
 */
object ContextualCommandParser {

    private data class Rule(val phrases: List<String>, val command: MediaCommand)

    private val mediaRules = listOf(
        Rule(listOf("sebelumnya", "putar sebelumnya", "lagu sebelumnya", "video sebelumnya", "play previous", "previous", "previous track", "previous video", "prev"), MediaCommand.PREVIOUS),
        Rule(listOf("berikutnya", "selanjutnya", "lagu berikutnya", "video berikutnya", "mainkan lagu berikutnya", "putar lagu berikutnya", "mainkan video berikutnya", "putar video berikutnya", "play next", "play next track", "play next video", "skip", "next", "next track", "next video"), MediaCommand.NEXT),
        Rule(listOf("hentikan sementara", "jeda", "pause", "pause video", "pause music"), MediaCommand.PAUSE),
        Rule(listOf("berhenti", "hentikan", "stop", "stop playback"), MediaCommand.STOP),
        Rule(listOf("mainkan", "putar", "lanjutkan", "teruskan", "play", "play video", "play music", "resume"), MediaCommand.PLAY),
    )

    private val mediaPackages = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.spotify.music",
        "com.netflix.mediaclient",
        "org.videolan.vlc",
        "com.mxtech.videoplayer.ad",
        "com.mxtech.videoplayer.pro",
        "com.google.android.music",
        "com.android.music",
        "com.samsung.android.app.music",
        "com.amazon.mp3",
    )

    private val visibleMediaMarkers = listOf(
        "youtube", "spotify", "netflix", "vlc", "youtube music", "pemutar musik", "pemutar video",
        "music player", "video player", "now playing", "sedang diputar", "kontrol pemutaran",
    )

    fun parse(userText: String, screenContext: String?): AssistantAction.ControlMedia? {
        if (!isMediaContext(screenContext)) return null
        val rule = matchingRule(userText) ?: return null
        return AssistantAction.ControlMedia(rule.command)
    }

    /** Dipakai untuk menjawab pertanyaan kemampuan tanpa menjalankan perintah. */
    fun isSupportedCommand(userText: String): Boolean = matchingRule(userText) != null

    private fun matchingRule(userText: String): Rule? {
        val command = normalizeCommand(userText)
        return mediaRules.firstOrNull { item ->
            item.phrases.any { phrase -> command == phrase || command.startsWith("$phrase ") }
        }
    }

    fun isMediaContext(screenContext: String?): Boolean {
        if (screenContext.isNullOrBlank()) return false
        val normalized = screenContext.lowercase()
        val packageName = Regex("(?im)^aplikasi:\\s*([^\\s]+)")
            .find(screenContext)?.groupValues?.getOrNull(1)?.lowercase()
        if (packageName != null && mediaPackages.any { packageName == it || packageName.startsWith("$it.") }) {
            return true
        }
        return visibleMediaMarkers.any(normalized::contains)
    }

    private fun normalizeCommand(text: String): String {
        var result = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val leadIns = listOf("tolong", "please", "hey retro", "hai retro", "hey", "retro")
        var changed = true
        while (changed) {
            changed = false
            val lead = leadIns.firstOrNull { result.startsWith("$it ") }
            if (lead != null) {
                result = result.removePrefix(lead).trimStart()
                changed = true
            }
        }
        return result
    }
}
