package com.open.glassesdownloader

import android.annotation.SuppressLint
import android.content.Context
import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build

/**
 * Joins the glasses' access point. Android 10+ uses the network-request API (local-only network,
 * normal Internet stays on the other connection); Android 8/9 fall back to [LegacyWifiJoin].
 */
@SuppressLint("MissingPermission")
class GlassesWifi(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var legacy: LegacyWifiJoin? = null
    private val gate = RequestGate()

    fun request(ssid: String, onAvailable: (Network?) -> Unit, onUnavailable: (String) -> Unit,
                onProgress: (String) -> Unit = {}) {
        close()
        val token = gate.begin()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestLocalOnlyNetwork(ssid, token, onAvailable, onUnavailable)
        } else {
            val joiner = LegacyWifiJoin(context)
            legacy = joiner
            joiner.join(
                ssid, AP_PASSPHRASE,
                { network -> if (gate.isCurrent(token)) onAvailable(network) },
                { reason -> if (gate.isCurrent(token)) onUnavailable(reason) },
                onProgress = { message -> if (gate.isCurrent(token)) onProgress(message) }
            )
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun requestLocalOnlyNetwork(ssid: String, token: Long, onAvailable: (Network?) -> Unit, onUnavailable: (String) -> Unit) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(AP_PASSPHRASE)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (gate.isCurrent(token)) onAvailable(network) }
            override fun onUnavailable() { if (gate.isCurrent(token)) onUnavailable("the network request was declined or timed out") }
            override fun onLost(network: Network) { /* Transfer timeout/retry handles lost connectivity. */ }
        }
        callback = listener
        try { cm.requestNetwork(request, listener, 30000) }
        catch (e: Exception) {
            if (gate.isCurrent(token)) {
                close()
                onUnavailable(e.message ?: "the network request failed")
            }
        }
    }

    /** For a manually joined AP, try all Wi-Fi networks, including local-only ones with no Internet. */
    @Suppress("DEPRECATION") // allNetworks is the only way to see a manually joined local-only network.
    fun connectedWifiNetworks(): List<Network> = cm.allNetworks.filter { network ->
        cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun close() {
        gate.close()
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) { } }
        callback = null
        legacy?.close()
        legacy = null
    }

    companion object {
        /** Hard-coded in the glasses' own companion app; it is not an Android secret. */
        const val AP_PASSPHRASE = "12345678"
    }
}
