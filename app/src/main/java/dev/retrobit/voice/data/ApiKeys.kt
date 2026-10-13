package dev.retrobit.voice.data

/**
 * Normalisasi API key milik pengguna (BYOK).
 *
 * Pengguna sering menempel key dari halaman web, chat, atau catatan sehingga
 * ikut tersalin spasi, baris baru, tanda kutip, atau koma — semuanya membuat
 * header otentikasi ditolak (401) padahal key-nya sendiri benar. API key
 * tidak pernah memuat spasi/kutip, jadi aman dibersihkan di sini.
 *
 * Diterapkan saat nilai disimpan DAN saat nilai dipakai, supaya key lama
 * yang sudah telanjur tersimpan dengan kotor ikut terbentulkan tanpa perlu
 * pengguna mengetik ulang.
 */
object ApiKeys {

    fun sanitize(raw: String): String = buildString(raw.length) {
        for (c in raw) {
            when {
                c.isWhitespace() -> Unit
                c == '"' || c == '\'' -> Unit
                c == ',' -> Unit
                else -> append(c)
            }
        }
    }
}
