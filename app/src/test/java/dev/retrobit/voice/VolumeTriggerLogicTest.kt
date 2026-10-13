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
    fun `ketukan singkat dikembalikan sebagai satu langkah volume`() {
        val logic = VolumeTriggerLogic()

        val down = logic.onDown(atMs = 1_000L)
        assertEquals(VolumeTriggerLogic.Action.NONE, down.action)
        assertTrue("event ditahan sementara sampai diketahui ini klik atau hold", down.consume)

        val beforeThreshold = logic.onHold(atMs = 1_699L)
        assertEquals(VolumeTriggerLogic.Action.NONE, beforeThreshold.action)
        assertTrue(beforeThreshold.consume)

        val up = logic.onUp(atMs = 1_699L)
        assertEquals(VolumeTriggerLogic.Action.VOLUME_CLICK, up.action)
        assertTrue("klik singkat ditangani lalu disalurkan ke AudioManager", up.consume)
        assertFalse(logic.isPressed)
    }

    @Test
    fun `hold melewati ambang memicu aksi sekali dan release menyelesaikan hold`() {
        val logic = VolumeTriggerLogic()

        assertEquals(VolumeTriggerLogic.Action.NONE, logic.onDown(atMs = 0L).action)
        assertEquals(VolumeTriggerLogic.Action.NONE, logic.onHold(atMs = 699L).action)

        val trigger = logic.onHold(atMs = 700L)
        assertEquals(VolumeTriggerLogic.Action.START, trigger.action)
        assertTrue(trigger.consume)

        // Auto-repeat tidak mengatur ulang waktu tekan atau memicu ulang aksi.
        assertEquals(VolumeTriggerLogic.Action.NONE, logic.onDown(atMs = 900L).action)
        assertEquals(VolumeTriggerLogic.Action.NONE, logic.onHold(atMs = 1_500L).action)

        val up = logic.onUp(atMs = 1_600L)
        assertEquals(VolumeTriggerLogic.Action.FINISH, up.action)
        assertTrue(up.consume)
        assertFalse(logic.isPressed)
    }

    @Test
    fun `hold panjang tidak berubah menjadi klik volume jika timer dibatalkan`() {
        val logic = VolumeTriggerLogic()

        logic.onDown(atMs = 0L)
        val up = logic.onUp(atMs = 900L)

        assertEquals(VolumeTriggerLogic.Action.NONE, up.action)
        assertTrue("hold panjang tetap menelan perubahan volume", up.consume)
    }

    @Test
    fun `release tanpa down diabaikan`() {
        val logic = VolumeTriggerLogic()
        val up = logic.onUp(atMs = 500L)
        assertEquals(VolumeTriggerLogic.Action.NONE, up.action)
        assertFalse(up.consume)
    }

    @Test
    fun `reset membatalkan hold yang sedang berjalan`() {
        val logic = VolumeTriggerLogic()
        logic.onDown(atMs = 0L)
        assertTrue(logic.isPressed)

        logic.reset()

        assertFalse(logic.isPressed)
        assertEquals(VolumeTriggerLogic.Action.NONE, logic.onHold(atMs = 1_000L).action)
        assertEquals(VolumeTriggerLogic.Action.NONE, logic.onUp(atMs = 1_100L).action)
    }
}
