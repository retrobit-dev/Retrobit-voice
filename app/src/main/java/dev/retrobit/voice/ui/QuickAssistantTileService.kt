package dev.retrobit.voice.ui

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.retrobit.voice.R
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.VoiceLog

/**
 * Tile di panel Pengaturan Cepat: sekali tarik panel → tekan tile → asisten
 * langsung mendengarkan. Cara cepat mengakses tanpa meninggalkan aplikasi lain.
 */
class QuickAssistantTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(R.string.tile_assistant)
            state = Tile.STATE_INACTIVE
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                icon = Icon.createWithResource(this@QuickAssistantTileService, R.drawable.ic_mic)
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
        try {
            AppGraph.require.assistant.openAssistantUi(autoListen = true, captureScreenContext = true)
        } catch (t: Throwable) {
            VoiceLog.e("Tile gagal membuka asisten", t)
        }
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
