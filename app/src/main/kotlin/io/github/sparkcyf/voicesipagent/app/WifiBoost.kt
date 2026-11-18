package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Keeps this phone's Wi-Fi out of power saving during a call. In power save the radio sleeps
 * between beacons, so voice packets arrive in bursts 100–300 ms late: heard as delay and gaps.
 * Low-latency mode needs the app on screen (Android's rule); high-performance covers the rest.
 */
object WifiBoost {
    private var low: WifiManager.WifiLock? = null
    private var high: WifiManager.WifiLock? = null
    private var holders = 0

    @Synchronized fun acquire(ctx: Context) {
        if (holders++ > 0) return
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return
        runCatching { low = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "simbridge:call-low").apply { setReferenceCounted(false); acquire() } }
        @Suppress("DEPRECATION")
        runCatching { high = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "simbridge:call").apply { setReferenceCounted(false); acquire() } }
        AppLog.i("wifi: low-latency on for the call")
    }

    @Synchronized fun release() {
        if (holders == 0 || --holders > 0) return
        runCatching { low?.release() }; runCatching { high?.release() }
        low = null; high = null
    }
}
