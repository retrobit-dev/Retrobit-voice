package dev.retrobit.voice.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Satu pintu untuk semua pesan singkat (toast).
 *
 * Sebelum ini setiap pemanggil membuat Toast sendiri, sehingga dua pesan yang
 * muncul berurutan saling menimpa: pesan pertama langsung hilang sebelum sempat
 * dibaca (mis. "izin mikrofon diberikan" menutupi pesan berikutnya).
 *
 * Di sini toast yang masih tampil selalu dibatalkan lebih dulu, jadi yang
 * terlihat selalu pesan terakhir — tidak ada lagi pesan yang tertimpa.
 */
object ToastHub {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var current: Toast? = null

    /** Tampilkan [text]. Aman dipanggil dari thread mana pun. */
    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        if (text.isBlank()) return
        val app = context.applicationContext
        val runnable = Runnable {
            current?.cancel()
            val toast = Toast.makeText(app, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
            current = toast
            toast.show()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run() else main.post(runnable)
    }

    /** Batalkan pesan yang sedang tampil (dipakai saat layar ditutup). */
    fun clear() {
        val runnable = Runnable {
            current?.cancel()
            current = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run() else main.post(runnable)
    }
}
