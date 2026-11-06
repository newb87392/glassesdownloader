package com.open.glassesdownloader

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest

/** Bluetooth selection does not require permission to join a Wi-Fi network. */
@SuppressLint("InlinedApi") // Permission strings are selected by the injectable SDK level (tested down to 26).
object Permissions {
    fun bluetoothRequired(sdk: Int = Build.VERSION.SDK_INT): Array<String> = if (sdk >= 31) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    ) else locationRequired()

    fun wifiRequired(sdk: Int = Build.VERSION.SDK_INT): Array<String> = if (sdk >= 33) arrayOf(
        Manifest.permission.NEARBY_WIFI_DEVICES
    ) else locationRequired()

    private fun locationRequired() = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun required(): Array<String> = (bluetoothRequired() + wifiRequired()).distinct().toTypedArray()

    fun missingBluetooth(context: Context): Array<String> = missing(context, bluetoothRequired())

    fun missing(context: Context): Array<String> = missing(context, required())

    private fun missing(context: Context, required: Array<String>): Array<String> = required
        .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()
}
