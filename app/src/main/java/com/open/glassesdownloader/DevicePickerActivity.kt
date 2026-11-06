package com.open.glassesdownloader

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** System-connected GATT, paired, and nearby BLE devices, deduplicated by address. */
@SuppressLint("MissingPermission") // Scan entry is gated; revocation is caught at platform boundaries.
class DevicePickerActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val devices = PickerDevices()
    private var callback: ScanCallback? = null
    private var uiQueued = false
    private var permissionRequestPending = false
    private lateinit var status: TextView
    private lateinit var list: ListView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 24, 32))
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        root.addView(button("Back", false) { finish() })
        root.addView(TextView(this).apply {
            text = "Choose your glasses"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        })
        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 13f }
        root.addView(status)
        root.addView(button("Scan again", true) { restartScan() })
        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        list.adapter = PickerAdapter()
        list.setOnItemClickListener { _, view, _, _ ->
            // The rendered row owns its identity, never a fresh RSSI-sorted position.
            val picked = view.tag as? PickerDevices.Entry
            setResult(RESULT_OK, Intent()
                .putExtra(EXTRA_ADDRESS, picked?.address ?: "")
                .putExtra(EXTRA_NAME, picked?.name ?: ""))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        restartScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        permissionRequestPending = false
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) restartScan()
        else permissionDenied()
    }

    override fun onPause() {
        stopScan()
        super.onPause()
    }

    override fun onDestroy() {
        stopScan()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun permissionDenied() {
        stopScan()
        status.text = "Bluetooth permission is needed (Location on Android 11 and older). Tap Scan again to retry."
    }

    private fun restartScan() {
        stopScan()
        PickerScanAccess.run(
            missing = { Permissions.missingBluetooth(this) },
            request = { missing ->
                status.text = "Permission is needed to list Bluetooth devices."
                if (!permissionRequestPending) {
                    permissionRequestPending = true
                    requestPermissions(missing, REQUEST_PERMISSIONS)
                }
            },
            denied = { permissionDenied() },
            scan = { scanAfterPermission() }
        )
    }

    private fun scanAfterPermission() {
        devices.clear()
        (list.adapter as PickerAdapter).publish()
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            status.text = "This device has no Bluetooth adapter."
            return
        }
        if (!adapter.isEnabled) {
            status.text = "Turn Bluetooth on, then tap Scan again."
            return
        }
        // Public API: system GATT connections only, not arbitrary classic Bluetooth profiles.
        manager.getConnectedDevices(BluetoothProfile.GATT).forEach { device ->
            devices.update(device.name.orEmpty(), device.address, RSSI_UNKNOWN, false, true)
        }
        adapter.bondedDevices.forEach { device ->
            devices.update(device.name.orEmpty(), device.address, RSSI_UNKNOWN, true, false)
        }
        (list.adapter as PickerAdapter).publish()
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            status.text = "Bluetooth LE scanning is unavailable."
            return
        }
        val scan = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Ignore old callbacks and serialize model/UI access on the main thread.
                handler.post {
                    if (callback !== this) return@post
                    try {
                        val device = result.device
                        devices.update(result.scanRecord?.deviceName ?: device.name.orEmpty(),
                            device.address, result.rssi, false, false)
                        scheduleUi()
                    } catch (_: SecurityException) {
                        permissionDenied()
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    if (callback === this) {
                        stopScan()
                        status.text = "Scanning failed (Bluetooth error $errorCode)."
                    }
                }
            }
        }
        callback = scan
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
            status.text = "Scanning… connected (GATT), paired, then nearby devices. Tap your glasses."
            handler.postDelayed({
                if (callback === scan) {
                    stopScan()
                    status.text = "Scan finished. Tap Scan again if your glasses are missing; " +
                        "they must be in pairing mode (hold the shutter ~3 s)."
                }
            }, SCAN_MS)
        } catch (e: SecurityException) {
            throw e // Shared gate handles revocation, including all pre-scan reads.
        } catch (e: Exception) {
            stopScan()
            status.text = "Could not scan: ${e.message}"
        }
    }

    private fun stopScan() {
        val old = callback
        callback = null
        if (old != null) {
            try {
                (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(old)
            } catch (_: Exception) { }
        }
    }

    private fun scheduleUi() {
        if (uiQueued) return
        uiQueued = true
        handler.postDelayed({
            uiQueued = false
            if (!isFinishing) (list.adapter as PickerAdapter).publish()
        }, 400)
    }

    private inner class PickerAdapter : BaseAdapter() {
        private var rows: List<PickerDevices.Entry> = emptyList()

        fun publish() {
            rows = devices.snapshot()
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size + 1
        override fun getItem(position: Int): PickerDevices.Entry? = rows.getOrNull(position - 1)
        override fun hasStableIds(): Boolean = true
        // A Bluetooth address is 48 bits; -1 is reserved for Automatic.
        override fun getItemId(position: Int): Long =
            getItem(position)?.address?.replace(":", "")?.toLong(16) ?: -1L

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? TextView) ?: TextView(this@DevicePickerActivity).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(dp(6), dp(14), dp(6), dp(14))
            }
            val entry = getItem(position)
            row.tag = entry
            if (entry == null) {
                row.text = "Automatic — any glasses advertising AA12\nTap here to forget a chosen device"
                row.alpha = 0.75f
                return row
            }
            val details = buildString {
                append(entry.address)
                if (entry.rssi > RSSI_UNKNOWN) append(" · ${entry.rssi} dBm")
                append(" · ${entry.statusLabel}")
            }
            row.alpha = 1f
            row.text = "${entry.name}\n$details"
            return row
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) Color.rgb(18, 29, 35) else Color.WHITE)
        background = GradientDrawable().apply {
            setColor(if (primary) Color.rgb(105, 218, 203) else Color.rgb(43, 57, 72))
            cornerRadius = dp(12).toFloat()
        }
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { action() }
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"
        private const val REQUEST_PERMISSIONS = 10
        private const val SCAN_MS = 20000L
        private const val RSSI_UNKNOWN = -127
    }
}
