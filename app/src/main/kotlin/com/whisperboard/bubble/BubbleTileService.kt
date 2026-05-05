package com.whisperboard.bubble

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that summons the bubble. Tapping the tile:
 *
 * - If the bubble is currently hidden (summoned-only mode and not summoned,
 *   or always-visible but auto-hidden) → calls [BubbleOverlayService.summon]
 *   so the service shows the overlay.
 * - If the user has not granted `SYSTEM_ALERT_WINDOW` → opens the system
 *   overlay-permission settings screen so the user can grant it without
 *   leaving the tile flow.
 *
 * The tile reflects the bubble's "is the service alive" state in its label
 * and active flag; it is intentionally not a toggle for visibility mode —
 * the brief calls for it as a summon path, not a configuration knob.
 */
class BubbleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val context = applicationContext

        if (!BubbleOverlayService.hasOverlayPermission(context)) {
            // No overlay permission — kick the user into the system
            // settings to grant it. The tile cannot show the bubble until
            // they come back and tap again.
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(intent)
            return
        }

        BubbleOverlayService.summon(context)
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        if (BubbleOverlayService.hasOverlayPermission(this)) {
            tile.state = Tile.STATE_ACTIVE
            tile.contentDescription = "Summon Whisper Board bubble"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.contentDescription = "Tap to grant overlay permission"
        }
        tile.updateTile()
    }
}
