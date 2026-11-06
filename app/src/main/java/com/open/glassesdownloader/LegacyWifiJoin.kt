package com.open.glassesdownloader

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.SupplicantState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Android 8/9 have no `WifiNetworkSpecifier`, so the access point is configured directly through
 * `WifiManager` — the pre-API-29 equivalent of the "join this network" request. There is no system
 * confirmation dialog, and mobile data may stay the default route, which is fine because the
 * transfers always bind to the Wi-Fi [Network] when one is available.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class LegacyWifiJoin(private val context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null
    private var cancelled = false

    fun join(ssid: String, password: String, onJoined: (Network?) -> Unit, onFailed: (String) -> Unit,
             onProgress: (String) -> Unit = {}) {
        cancelled = false
        val manager = wifi ?: return onFailed("the Wi-Fi service is unavailable")
        if (!manager.isWifiEnabled) {
            val turnedOn = runCatching { manager.setWifiEnabled(true) }.getOrDefault(false)
            if (!turnedOn) return onFailed("turn Wi-Fi on first")
        }
        val quoted = "\"$ssid\""
        // LG/Android 8 can expose a saved network owned by Settings while refusing both
        // removeNetwork and addNetwork for this app. Reuse it rather than replacing it.
        val saved = runCatching { manager.configuredNetworks.orEmpty() }.getOrDefault(emptyList())
        val existingId = LegacyWifiPolicy.existingNetworkId(ssid, saved.map { it.SSID to it.networkId })
        val id = existingId ?: run {
            val config = WifiConfiguration().apply {
                SSID = quoted
                preSharedKey = "\"$password\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
                allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                allowedProtocols.set(WifiConfiguration.Protocol.WPA)
            }
            runCatching { manager.addNetwork(config) }.getOrDefault(-1)
        }
        if (id < 0) return onFailed("Android rejected the network configuration")
        fun joined(): Boolean = runCatching {
            val info = manager.connectionInfo
            LegacyWifiPolicy.isJoined(ssid, info?.ssid,
                info?.supplicantState == SupplicantState.COMPLETED, info?.ipAddress ?: 0)
        }.getOrDefault(false)
        if (joined()) return onJoined(wifiNetwork())

        fun startAttempt(attempt: Int) {
            if (cancelled) return
            // Do not interrupt a late join that completed during the retry pause.
            if (joined()) return onJoined(wifiNetwork())
            onProgress("Wi-Fi association attempt $attempt/${LegacyJoinRetry.MAX_ATTEMPTS} (keeping Bluetooth connected)…")
            runCatching { manager.disconnect() }
            if (!runCatching { manager.enableNetwork(id, true) }.getOrDefault(false))
                return onFailed("Android could not enable $ssid. Turn on Wi-Fi and retry.")
            if (!runCatching { manager.reconnect() }.getOrDefault(false))
                onProgress("Android did not start reconnect; checking for association before retrying.")
            val started = SystemClock.elapsedRealtime()
            val runnable = object : Runnable {
                override fun run() {
                    if (cancelled) return
                    when (LegacyJoinRetry.decide(SystemClock.elapsedRealtime(), started, attempt, joined())) {
                        JoinDecision.JOINED -> onJoined(wifiNetwork())
                        JoinDecision.WAIT -> handler.postDelayed(this, POLL_MS)
                        JoinDecision.RETRY -> {
                            onProgress("No completed Wi-Fi association after attempt $attempt; retrying in 2 seconds…")
                            val retry = Runnable { startAttempt(attempt + 1) }
                            poll = retry
                            handler.postDelayed(retry, RETRY_PAUSE_MS)
                        }
                        JoinDecision.FAIL -> onFailed("$ssid did not join after ${LegacyJoinRetry.MAX_ATTEMPTS} attempts. " +
                            "Power-cycle the glasses, keep the phone close, then tap Connect & download again. " +
                            "If Android Settings also cannot join, disconnect any other phone using the glasses.")
                    }
                }
            }
            poll = runnable
            handler.postDelayed(runnable, POLL_MS)
        }
        startAttempt(1)
    }

    private fun wifiNetwork(): Network? = runCatching {
        cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }.getOrNull()

    fun close() {
        cancelled = true
        poll?.let { handler.removeCallbacks(it) }
        poll = null
    }

    companion object {
        private const val RETRY_PAUSE_MS = 2000L
        private const val POLL_MS = 500L
    }
}
