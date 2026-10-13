package dev.retrobit.voice

import dev.retrobit.voice.assistant.CommandParser
import dev.retrobit.voice.core.AssistantAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji parser perintah offline. Ini inti dari "mode asisten tanpa internet":
 * kalau tes ini hijau, puluhan perintah tetap bekerja walau pengguna belum
 * memasang API key apa pun.
 */
class CommandParserTest {

    private fun action(text: String): AssistantAction? = CommandParser.parse(text)?.action

    @Test
    fun `membuka aplikasi`() {
        val a = action("buka whatsapp")
        assertTrue(a is AssistantAction.OpenApp)
        assertEquals("whatsapp", (a as AssistantAction.OpenApp).appQuery.lowercase())
    }

    @Test
    fun `membuka aplikasi dengan kata pengantar`() {
        val a = action("tolong buka aplikasi Instagram dong")
        assertTrue("hasil: $a", a is AssistantAction.OpenApp)
    }

    @Test
    fun `membuka aplikasi saat STT mengkapitalkan awal kalimat`() {
        val a = action("Buka WhatsApp")
        assertTrue("hasil: $a", a is AssistantAction.OpenApp)
        assertEquals("WhatsApp", (a as AssistantAction.OpenApp).appQuery)
    }

    @Test
    fun `koreksi salah dengar puka hanya pada perintah buka aplikasi`() {
        val a = action("puka WhatsApp")
        assertTrue("hasil: $a", a is AssistantAction.OpenApp)
        assertEquals("WhatsApp", (a as AssistantAction.OpenApp).appQuery)
    }

    @Test
    fun `menghapus kata aplikasi walau kapitalisasi hasil STT berbeda`() {
        val a = action("Buka Aplikasi WhatsApp")
        assertTrue("hasil: $a", a is AssistantAction.OpenApp)
        assertEquals("WhatsApp", (a as AssistantAction.OpenApp).appQuery)
    }

    @Test
    fun `pencarian web`() {
        val a = action("cari resep rendang")
        assertTrue(a is AssistantAction.WebSearch)
        assertTrue((a as AssistantAction.WebSearch).query.contains("resep rendang"))
    }

    @Test
    fun `pencarian di youtube`() {
        val a = action("cari di youtube lagu nostalgia")
        assertTrue(a is AssistantAction.WebSearch)
        val search = a as AssistantAction.WebSearch
        assertTrue(search.site?.contains("youtube") == true)
        assertTrue(search.query.contains("lagu nostalgia"))
    }

    @Test
    fun `menelepon nomor`() {
        val a = action("telepon 081234567890")
        assertTrue(a is AssistantAction.Dial)
        assertEquals("081234567890", (a as AssistantAction.Dial).target)
    }

    @Test
    fun `menelepon kontak`() {
        val a = action("telepon ibu")
        assertTrue(a is AssistantAction.CallContact)
        assertEquals("ibu", (a as AssistantAction.CallContact).name)
    }

    @Test
    fun `mengatur alarm dengan angka`() {
        val a = action("alarm jam 5 pagi")
        assertTrue(a is AssistantAction.SetAlarm)
        assertEquals(5, (a as AssistantAction.SetAlarm).hour)
    }

    @Test
    fun `mengatur alarm dengan kata`() {
        val a = action("set alarm jam tujuh")
        assertTrue("hasil: $a", a is AssistantAction.SetAlarm)
        assertEquals(7, (a as AssistantAction.SetAlarm).hour)
    }

    @Test
    fun `alarm malam dalam format 24 jam`() {
        val a = action("alarm jam 8 malam")
        assertTrue(a is AssistantAction.SetAlarm)
        assertEquals(20, (a as AssistantAction.SetAlarm).hour)
    }

    @Test
    fun `timer menit menjadi detik`() {
        val a = action("timer 10 menit")
        assertTrue(a is AssistantAction.SetTimer)
        assertEquals(600, (a as AssistantAction.SetTimer).seconds)
    }

    @Test
    fun `timer gabungan jam dan menit`() {
        val a = action("pasang timer 1 jam 15 menit")
        assertTrue("hasil: $a", a is AssistantAction.SetTimer)
        assertEquals(4500, (a as AssistantAction.SetTimer).seconds)
    }

    @Test
    fun `senter hidup dan mati`() {
        assertTrue(action("nyalakan senter") is AssistantAction.SetTorch)
        val off = action("matikan senter")
        assertTrue(off is AssistantAction.SetTorch)
        assertEquals(false, (off as AssistantAction.SetTorch).on)
    }

    @Test
    fun `volume persen dan relatif`() {
        val set = action("volume 40 persen")
        assertTrue(set is AssistantAction.SetVolume)
        assertEquals(40, (set as AssistantAction.SetVolume).percent)

        val up = action("keraskan volume")
        assertTrue(up is AssistantAction.SetVolume)
        assertTrue((up as AssistantAction.SetVolume).relative)
    }

    @Test
    fun `pengaturan sistem`() {
        val a = action("buka pengaturan wifi")
        assertTrue(a is AssistantAction.OpenSettingsPanel)
        assertEquals("wifi", (a as AssistantAction.OpenSettingsPanel).panel)
    }

    @Test
    fun `catatan`() {
        val a = action("catat besok bayar listrik")
        assertTrue(a is AssistantAction.SaveNote)
        assertTrue((a as AssistantAction.SaveNote).body.contains("bayar listrik"))
    }

    @Test
    fun `kirim pesan dengan isi`() {
        val a = action("kirim pesan ke budi isinya aku otw ya")
        assertTrue("hasil: $a", a is AssistantAction.SendMessage)
        val msg = a as AssistantAction.SendMessage
        assertEquals("budi", msg.contactName)
        assertTrue(msg.body.contains("aku otw"))
    }

    @Test
    fun `kalimat bebas tidak diklaim sebagai perintah`() {
        assertEquals(null, action("apa kabar hari ini bagaimana"))
    }

    @Test
    fun `fallback pencarian web tersedia`() {
        val fallback = CommandParser.asWebSearchFallback("tolong jelaskan fenomena el nino")
        assertNotNull(fallback.query)
        assertTrue(fallback.query.isNotBlank())
    }

    @Test
    fun `pembacaan durasi untuk label`() {
        assertEquals("10 menit", dev.retrobit.voice.assistant.ActionText.humanDuration(600))
        assertEquals("1 jam 5 menit", dev.retrobit.voice.assistant.ActionText.humanDuration(3900))
    }
}
