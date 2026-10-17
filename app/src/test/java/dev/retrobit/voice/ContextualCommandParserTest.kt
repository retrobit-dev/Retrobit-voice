package dev.retrobit.voice

import dev.retrobit.voice.assistant.ContextualCommandParser
import dev.retrobit.voice.core.AssistantAction
import dev.retrobit.voice.core.MediaCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextualCommandParserTest {

    @Test
    fun `mainkan dan jeda ditafsirkan sesuai aplikasi media yang terlihat`() {
        assertEquals(
            AssistantAction.ControlMedia(MediaCommand.PLAY),
            ContextualCommandParser.parse("Tolong mainkan", "Aplikasi: com.google.android.youtube\n- Play video"),
        )
        assertEquals(
            AssistantAction.ControlMedia(MediaCommand.PAUSE),
            ContextualCommandParser.parse("pause", "Aplikasi: com.spotify.music\n- Now playing"),
        )
        assertEquals(
            AssistantAction.ControlMedia(MediaCommand.NEXT),
            ContextualCommandParser.parse("lagu berikutnya", "Aplikasi: org.videolan.vlc\n- Pemutar video"),
        )
    }

    @Test
    fun `perintah media tidak dijalankan jika konteks layar bukan media`() {
        assertNull(ContextualCommandParser.parse("mainkan", "Aplikasi: com.whatsapp\n- Chat dengan Budi"))
        assertNull(ContextualCommandParser.parse("jeda", null))
    }

    @Test
    fun `mengenali kemampuan media tanpa memerlukan konteks atau menjalankan aksi`() {
        assertEquals(true, ContextualCommandParser.isSupportedCommand("tolong play"))
        assertEquals(false, ContextualCommandParser.isSupportedCommand("I want to play football"))
    }
}
