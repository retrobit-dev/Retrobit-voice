package dev.retrobit.voice

import dev.retrobit.voice.dictation.VolumeTriggerLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji keputusan pemicu tombol volume. Logika ini sengaja bebas dari API Android
 * supaya bisa diuji di JVM pada setiap build CI.
 */
class VolumeTriggerLogicTest {

    @Test
    fun `mode tahan - tekan mulai, lepas selesai, volume tidak berubah`() {
        val logic = VolumeTriggerLogic(holdToTalk = true)

        val down = logic.onDown(atMs = 1_000L, repeatCount = 0)
        assertEquals(VolumeTriggerLogic.Action.START, down.action)
        assertTrue("volume harus ditelan saat menahan tombol", down.consume)

        // Auto-repeat selama ditahan tidak boleh memulai rekaman baru.
        val repeat = logic.onDown(atMs = 1_400L, repeatCount = 3)
        assertEquals(VolumeTriggerLogic.Action.NONE, repeat.action)
        assertTrue("auto-repeat tetap harus ditelan", repeat.consume)

        val up = logic.onUp(atMs = 2_500L)
        assertEquals(VolumeTriggerLogic.Action.FINISH, up.action)
        assertTrue(up.consume)
    }

    @Test
    fun `mode sekali tekan - satu ketukan memicu, volume tetap berubah`() {
        val logic = VolumeTriggerLogic(holdToTalk = false)

        val down = logic.onDown(atMs = 0L, repeatCount = 0)
        assertEquals("penekanan saja belum memicu apa-apa", VolumeTriggerLogic.Action.NONE, down.action)
        assertFalse("volume harus tetap berubah", down.consume)

        val up = logic.onUp(atMs = 120L)
        assertEquals(VolumeTriggerLogic.Action.TOGGLE, up.action)
        assertFalse("volume harus tetap berubah", up.consume)
    }

    @Test
    fun `mode sekali tekan - dua ketukan menghasilkan dua aksi, bukan mulai-berhenti sekaligus`() {
        val logic = VolumeTriggerLogic(holdToTalk = false)

        logic.onDown(atMs = 0L, repeatCount = 0)
        assertEquals(VolumeTriggerLogic.Action.TOGGLE, logic.onUp(atMs = 100L).action)

        // Ketukan berikutnya baru menghasilkan aksi setelah tombol dilepas.
        assertEquals(VolumeTriggerLogic.Action.NONE, logic.onDown(atMs = 3_000L, repeatCount = 0).action)
        assertEquals(VolumeTriggerLogic.Action.TOGGLE, logic.onUp(atMs = 3_100L).action)
    }

    @Test
    fun `mode sekali tekan - tombol ditahan lama tidak memicu dikte`() {
        val logic = VolumeTriggerLogic(holdToTalk = false)

        logic.onDown(atMs = 0L, repeatCount = 0)
        val up = logic.onUp(atMs = 1_500L)

        assertEquals(VolumeTriggerLogic.Action.NONE, up.action)
        assertFalse(up.consume)
    }

    @Test
    fun `lepas tanpa tekan diabaikan`() {
        val logic = VolumeTriggerLogic(holdToTalk = true)
        val up = logic.onUp(atMs = 500L)
        assertEquals(VolumeTriggerLogic.Action.NONE, up.action)
        assertFalse(up.consume)
    }

    @Test
    fun `mengganti mode membatalkan tombol yang masih ditahan`() {
        val logic = VolumeTriggerLogic(holdToTalk = true)
        logic.onDown(atMs = 0L, repeatCount = 0)
        assertTrue(logic.isPressed)

        logic.applyMode(holdToTalk = false)
        assertFalse("status tombol harus direset saat mode berubah", logic.isPressed)

        // Di mode baru, penekanan berikutnya tidak lagi langsung memulai.
        assertEquals(VolumeTriggerLogic.Action.NONE, logic.onDown(atMs = 10L, repeatCount = 0).action)
        assertEquals(VolumeTriggerLogic.Action.TOGGLE, logic.onUp(atMs = 60L).action)
    }

    @Test
    fun `reset mengembalikan ke keadaan awal`() {
        val logic = VolumeTriggerLogic(holdToTalk = true)
        logic.onDown(atMs = 0L, repeatCount = 0)
        logic.reset()
        assertFalse(logic.isPressed)
        assertEquals(VolumeTriggerLogic.Action.NONE, logic.onUp(atMs = 900L).action)
    }
}
