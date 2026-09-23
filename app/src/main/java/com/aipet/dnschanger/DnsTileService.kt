package com.aipet.dnschanger

import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DnsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (DnsVpnService.isRunning) {
            startService(DnsVpnService.stopIntent(this))
        } else {
            val prepare = VpnService.prepare(this)
            if (prepare == null) {
                val prefs = getSharedPreferences("dns_prefs", MODE_PRIVATE)
                val dns = prefs.getString("selected_dns", "1.1.1.1") ?: "1.1.1.1"
                startService(DnsVpnService.startIntent(this, dns))
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        if (DnsVpnService.isRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "DNS: ${DnsVpnService.currentDns}"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "DNS Changer"
        }
        tile.updateTile()
    }
}
