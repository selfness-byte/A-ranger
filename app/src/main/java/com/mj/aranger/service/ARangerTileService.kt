package com.mj.aranger.service

import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mj.aranger.store.MacroRepository

class ARangerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        if (FloatingOverlayService.isRunning) {
            FloatingOverlayService.stop(applicationContext)
        } else if (Settings.canDrawOverlays(applicationContext)) {
            MacroRepository.setMasterEnabled(applicationContext, true)
            FloatingOverlayService.start(applicationContext)
        } else {
            val enabled = !MacroRepository.isMasterEnabled(applicationContext)
            MacroRepository.setMasterEnabled(applicationContext, enabled)
        }
        syncTileState()
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val active = FloatingOverlayService.isRunning || MacroRepository.isMasterEnabled(applicationContext)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (FloatingOverlayService.isRunning) "A-ranger 리모컨 ON" else "A-ranger"
        tile.updateTile()
    }
}
