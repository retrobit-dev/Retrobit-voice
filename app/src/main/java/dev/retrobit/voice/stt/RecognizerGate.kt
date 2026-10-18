package dev.retrobit.voice.stt

import kotlinx.coroutines.sync.Mutex

/**
 * Gerbang sesi recognizer seluruh aplikasi.
 *
 * Layanan pengenalan suara Android hanya melayani satu sesi
 * `SpeechRecognizer` dalam satu waktu; klien kedua yang memanggil
 * `startListening` saat sesi lain masih berjalan akan ditolak dengan
 * `ERROR_RECOGNIZER_BUSY`. Di aplikasi ini ada tiga pemakai yang bisa
 * berjalan bersamaan: mesin on-device (transkripsi berkas), mesin sistem
 * (mode siaran langsung), dan pemantau kata kunci. Tanpa gerbang ini,
 * dikte bisa gagal "sibuk" hanya karena loop wake word kebetulan mulai
 * sesi sepersekian detik sebelumnya.
 */
object RecognizerGate {

    private val mutex = Mutex()

    /**
     * Jalankan [block] dengan sesi eksklusif — menunggu bila sesi lain
     * sedang berjalan. Dipakai jalur yang harus berhasil (transkripsi dikte).
     */
    suspend fun <T> withSession(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Jalankan [block] hanya bila tidak ada sesi lain; bila gerbang sedang
     * terkunci, langsung kembalikan null tanpa menunggu. Dipakai pemantau
     * kata kunci yang harus mengalah lalu mencoba lagi nanti — bukan menunggu
     * (kalau menunggu, dikte justru tertunda oleh wake word).
     */
    suspend fun <T> trySession(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
