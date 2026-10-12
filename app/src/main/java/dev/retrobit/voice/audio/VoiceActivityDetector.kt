package dev.retrobit.voice.audio

/**
 * Detektor aktivitas suara sederhana berbasis energi (RMS) dengan noise floor
 * adaptif. Dipakai untuk:
 *  - memutus rekaman otomatis setelah pengguna berhenti bicara,
 *  - menampilkan indikator "sedang bicara" di UI.
 *
 * Logikanya murni (tanpa Android) sehingga bisa diuji di JVM.
 */
class VoiceActivityDetector(
    /** Ambang "bicara" relatif terhadap noise floor. */
    private val speechFactor: Float = 2.2f,
    /** RMS minimum absolut agar dianggap bicara (menghindari derau ruangan sunyi). */
    private val absoluteFloor: Float = 0.004f,
    /** Durasi bicara minimum (ms) sebelum dianggap ada ucapan nyata. */
    private val minSpeechMs: Int = 150,
) {

    private var noiseFloor = absoluteFloor
    private var speechMs = 0
    private var lastSpeechTimestamp = -1L

    var hasSpeech: Boolean = false
        private set

    /** RMS sampul terakhir, untuk indikator visual. */
    var lastRms: Float = 0f
        private set

    val threshold: Float get() = maxOf(noiseFloor * speechFactor, absoluteFloor)

    fun reset() {
        noiseFloor = absoluteFloor
        speechMs = 0
        lastSpeechTimestamp = -1L
        hasSpeech = false
        lastRms = 0f
    }

    /**
     * Proses satu bingkai audio.
     * @return true kalau bingkai ini dianggap ucapan.
     */
    fun process(rms: Float, frameMs: Int, timestampMs: Long): Boolean {
        lastRms = rms
        val isSpeech = rms > threshold
        if (isSpeech) {
            speechMs += frameMs
            lastSpeechTimestamp = timestampMs
            if (speechMs >= minSpeechMs) hasSpeech = true
        } else {
            speechMs = (speechMs - frameMs).coerceAtLeast(0)
            // Adaptasi noise floor hanya ketika tidak ada ucapan (EMA lambat).
            noiseFloor = (noiseFloor * 0.95f) + (rms * 0.05f)
        }
        return isSpeech
    }

    /** Sudah berapa lama hening sejak ucapan terakhir (ms). */
    fun silenceDurationMs(timestampMs: Long): Long =
        if (lastSpeechTimestamp < 0) 0L else (timestampMs - lastSpeechTimestamp).coerceAtLeast(0L)
}
