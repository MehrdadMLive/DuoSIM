package io.github.simbridge

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tiles on this phone for the SIM phone's hotspot, mobile data and battery saver.
 * A tap switches it on the SIM phone; the tile shows the SIM phone's last known state.
 */
abstract class SimTile(private val action: String, private val label: Int, private val icon: Int) : TileService() {
    abstract fun state(s: GatewayStatus): Boolean

    override fun onStartListening() {
        val core = LinkApp.core(this)
        core.control.refresh()
        draw()
    }

    private fun draw() {
        val core = LinkApp.core(this)
        val s = core.control.status.value
        val online = core.status.value.state == LinkState.CONNECTED
        qsTile?.apply {
            label = getString(this@SimTile.label)
            icon = Icon.createWithResource(this@SimTile, this@SimTile.icon)
            state = when { s == null || !online -> Tile.STATE_UNAVAILABLE; state(s) -> Tile.STATE_ACTIVE; else -> Tile.STATE_INACTIVE }
            subtitle = if (!online) getString(R.string.dash_offline) else getString(R.string.tab_sim_phone)
            updateTile()
        }
    }

    override fun onClick() {
        val run = {
            val core = LinkApp.core(this)
            val s = core.control.status.value
            if (s != null) {
                core.control.control(action, !state(s))
                qsTile?.apply { state = if (state(s)) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; updateTile() }
            }
        }
        // Changing the SIM phone needs the screen unlocked.
        if (isLocked) unlockAndRun(run) else run()
    }
}

class HotspotTile : SimTile("hotspot", R.string.dash_hotspot, R.drawable.ic_link) {
    override fun state(s: GatewayStatus) = s.hotspot
}

class MobileDataTile : SimTile("mobile-data", R.string.dash_mobile_data, R.drawable.ic_link) {
    override fun state(s: GatewayStatus) = s.dataOn
}

class SaverTile : SimTile("power-saver", R.string.home_saver, R.drawable.ic_link) {
    override fun state(s: GatewayStatus) = s.powerSaver == true
}
