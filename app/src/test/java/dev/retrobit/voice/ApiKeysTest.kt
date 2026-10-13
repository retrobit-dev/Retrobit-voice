package dev.retrobit.voice

import dev.retrobit.voice.data.ApiKeys
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Uji normalisasi API key. Kasus nyata yang melatarbelakangi ini: pengguna
 * menempel key dari halaman web/chat, ikut tersalin spasi atau baris baru,
 * lalu otentikasi ditolak 401 padahal key-nya benar.
 */
class ApiKeysTest {

    @Test
    fun `key bersih tetap sama persis`() {
        assertEquals("sk-abc123XYZ", ApiKeys.sanitize("sk-abc123XYZ"))
    }

    @Test
    fun `spasi dan baris baru di ujung dibuang`() {
        assertEquals("sk-abc123", ApiKeys.sanitize("  sk-abc123 \n"))
    }

    @Test
    fun `spasi di tengah key ikut dibuang`() {
        assertEquals("sk-abc123", ApiKeys.sanitize("sk-abc 123"))
    }

    @Test
    fun `tanda kutip yang ikut tertempel dibuang`() {
        assertEquals("sk-abc123", ApiKeys.sanitize("\"sk-abc123\""))
        assertEquals("sk-abc123", ApiKeys.sanitize("'sk-abc123'"))
    }

    @Test
    fun `koma dari tempelan daftar ikut dibuang`() {
        assertEquals("sk-abc123", ApiKeys.sanitize("sk-abc123,"))
    }

    @Test
    fun `campuran spasi kutip dan baris baru`() {
        assertEquals("gsk_abcDEF-123", ApiKeys.sanitize(" \"gsk_abcDEF-123\"\r\n"))
    }

    @Test
    fun `key kosong dan hanya spasi menjadi kosong`() {
        assertEquals("", ApiKeys.sanitize(""))
        assertEquals("", ApiKeys.sanitize("   \n\t "))
    }

    @Test
    fun `tanda baca sah dalam key tidak disentuh`() {
        assertEquals("sk-proj-a_b.c-d/e+f=", ApiKeys.sanitize("sk-proj-a_b.c-d/e+f="))
    }
}
