package com.open.glassesdownloader

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * BLE discovery, ordinary OPEN_WIFI, and an explicit experimental camera-style command.
 * All state, callbacks, deadlines, and client notifications are serialized on the main looper.
 * Each session has a generation; GATT and scan callbacks additionally check resource identity.
 */
@SuppressLint("MissingPermission")
class GlassesBle(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onSsid: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onStyleResult: (Boolean) -> Unit = {},
    private val onVideoLength: (Int, Boolean) -> Unit = { _, _ -> },
    private val onCapacityReply: (String) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val lifecycle = BleLifecycle()
    @Volatile private var gatt: BluetoothGatt? = null
    private var callback: ScanCallback? = null
    private var activeScanner: BluetoothLeScanner? = null
    private var requestedP2p = false
    private var style2k = false
    private var videoOp = VideoOp.NONE
    private var videoSeconds = 0
    private var videoReported = false
    private var gotSsid = false
    private var attempts = 0
    private var gattRetries = 0
    @Volatile private var phase = Phase.IDLE
    fun isSessionActive(): Boolean = gatt != null && phase == Phase.READY
    private val assembler = FrameAssembler()
    private val serviceId = uuid(0xAA12)
    private val writeId = uuid(0xAA13)
    private val notifyId = uuid(0xAA14)
    private val cccdId = uuid(0x2902)

    private enum class Phase { IDLE, SCANNING, CONNECTING, DISCOVERING, MTU, NOTIFICATIONS, READY }
    private enum class VideoOp { NONE, SET, READ, CAPACITY }

    // Always queue public calls, even on main, so client callbacks cannot re-enter a transition.
    fun start(targetAddress: String?, targetName: String, p2p: Boolean) {
        handler.post { startOnMain(targetAddress, targetName, p2p, false) }
    }
    /** Explicit, separate operation; never wakes Wi-Fi or starts a recording. */
    fun startStyle2k(targetAddress: String) {
        handler.post { startOnMain(targetAddress, "", false, true) }
    }
    /** Writes the clip length the device stops at; never opens Wi-Fi, records or deletes anything. */
    fun setVideoLength(targetAddress: String, seconds: Int) {
        handler.post { startOnMain(targetAddress, "", false, false, VideoOp.SET, seconds) }
    }
    /** Asks the glasses which clip length they are set to, then closes. */
    fun readVideoLength(targetAddress: String) {
        handler.post { startOnMain(targetAddress, "", false, false, VideoOp.READ, 0) }
    }
    /** Read-only probe: the vendor app's capacity request (0x16), logged whatever comes back. */
    fun probeCapacity(targetAddress: String) {
        handler.post { startOnMain(targetAddress, "", false, false, VideoOp.CAPACITY, 0) }
    }

    private fun startOnMain(
        targetAddress: String?, targetName: String, p2p: Boolean, setStyle2k: Boolean,
        nextVideoOp: VideoOp = VideoOp.NONE, nextVideoSeconds: Int = 0
    ) {
        closeOnMain()
        val token = lifecycle.begin()
        gotSsid = false
        attempts = 0
        gattRetries = 0
        assembler.reset()
        requestedP2p = p2p
        style2k = setStyle2k
        videoOp = nextVideoOp
        videoSeconds = nextVideoSeconds
        videoReported = false
        val prefix = targetName.trim()
        try {
            val adapter = manager.adapter
            if (adapter?.isEnabled != true) return fail(token, "Turn on Bluetooth first.")
            if (targetAddress != null) {
                val device = try { adapter.getRemoteDevice(targetAddress) } catch (_: Exception) { null }
                    ?: return fail(token, "That Bluetooth address is not valid: $targetAddress")
                val label = runCatching { device.name }.getOrNull() ?: targetAddress
                log("Connecting to $label ($targetAddress)…")
                connect(token, device)
                return
            }
            phase = Phase.SCANNING
            val scan = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val source = this
                    dispatch(token) {
                        if (callback !== source || phase != Phase.SCANNING) return@dispatch
                        val record = result.scanRecord
                        val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: ""
                        val advertisesService = record?.serviceUuids?.any { it.uuid == serviceId } == true
                        if (!BleDiscovery.matches(advertisesService, name, prefix)) return@dispatch
                        log("Found ${name.ifBlank { result.device.address }} (advertises AA12). Connecting over Bluetooth…")
                        stopScan()
                        connect(token, result.device)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    val source = this
                    dispatch(token) {
                        if (callback === source && phase == Phase.SCANNING)
                            fail(token, "Bluetooth scan failed (Android error $errorCode).")
                    }
                }
            }
            callback = scan
            val scanner = adapter.bluetoothLeScanner ?: throw IllegalStateException("Bluetooth LE scanner unavailable")
            activeScanner = scanner
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
            log(if (prefix.isEmpty()) "Searching for glasses advertising AA12…" else "Searching for AA12 glasses named \"$prefix\"…")
            deadline(token, SCAN_TIMEOUT_MS) {
                fail(token, if (prefix.isEmpty())
                    "No glasses found. Check power and pairing mode, then retry. If another app is connected to the glasses, try disconnecting it."
                else "Glasses not found. Clear the name filter to match any AA12 device, check power, and retry.")
            }
        } catch (e: Exception) {
            fail(token, "Could not start Bluetooth: ${e.message}")
        }
    }

    private fun connect(token: Long, device: BluetoothDevice) {
        phase = Phase.CONNECTING
        deadline(token, SETUP_TIMEOUT_MS) { fail(token, "Bluetooth connection timed out. Check the glasses and retry. If another app is connected, try disconnecting it.") }
        try {
            val connected = device.connectGatt(context, false, gattCallback(token), BluetoothDevice.TRANSPORT_LE)
                ?: return fail(token, "Android could not create a Bluetooth connection. Retry connecting.")
            gatt = connected
            lifecycle.bind(token, connected)
        } catch (e: Exception) {
            fail(token, "Could not connect over Bluetooth: ${e.message}")
        }
    }

    private fun retryGatt(token: Long, failed: BluetoothGatt) {
        if (!lifecycle.accepts(token, failed)) return
        val device = failed.device
        gattRetries++
        val delayMs = gattRetries * 2000L
        log("Bluetooth retry $gattRetries/${BleReconnectPolicy.MAX_RETRIES}: Android GATT 133; " +
            "closing the failed session and reconnecting in ${delayMs / 1000}s…")
        closeOnMain()
        val newToken = lifecycle.begin()
        handler.postDelayed({
            if (lifecycle.accepts(newToken) && phase == Phase.IDLE) connect(newToken, device)
        }, delayMs)
    }

    private fun stopScan() {
        val old = callback
        val scanner = activeScanner
        callback = null
        activeScanner = null
        if (old != null) try { scanner?.stopScan(old) } catch (_: Exception) { }
    }

    /** Binder callbacks never inspect session state until they reach the main looper. */
    private fun dispatch(token: Long, identity: BluetoothGatt? = null, action: () -> Unit) {
        handler.post {
            if (lifecycle.accepts(token, identity)) {
                try { action() } catch (e: Exception) { fail(token, "Bluetooth operation failed: ${e.message}") }
            }
        }
    }

    private fun deadline(token: Long, delay: Long, action: () -> Unit) {
        val task = lifecycle.deadline(token) {
            try { action() } catch (e: Exception) { fail(token, "Bluetooth operation failed: ${e.message}") }
        }
        handler.postDelayed({ task() }, delay)
    }

    private fun gattCallback(token: Long) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            dispatch(token, g) {
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (BleReconnectPolicy.shouldRetry(status, gotSsid, gattRetries)) {
                        retryGatt(token, g)
                    } else if (gotSsid) {
                        fail(token, "Bluetooth link to the glasses dropped (GATT $status). On this device, " +
                            "Wi-Fi usually stops shortly after Bluetooth disconnects. Power-cycle the glasses if needed, then tap Connect & download again.")
                    } else fail(token, "Glasses disconnected or connection failed (GATT status $status). " +
                        "Keep them close, check power, then retry. If it repeats, power-cycle the glasses.")
                } else if (newState == BluetoothProfile.STATE_CONNECTED && phase == Phase.CONNECTING) {
                    phase = Phase.DISCOVERING
                    log("Bluetooth connected. Discovering services…")
                    deadline(token, SETUP_TIMEOUT_MS) { fail(token, "Bluetooth service discovery timed out. Reconnect and retry.") }
                    if (!g.discoverServices()) fail(token, "Android rejected Bluetooth service discovery. Reconnect and retry.")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            dispatch(token, g) {
                if (phase != Phase.DISCOVERING) return@dispatch
                if (status != BluetoothGatt.GATT_SUCCESS) return@dispatch fail(token, "BLE services discovery failed ($status).")
                if (g.getService(serviceId) == null)
                    return@dispatch fail(token, "Service AA12 not found. This hardware may not support the protocol.")
                phase = Phase.MTU
                // Preserve MTU negotiation and fragmented-frame support on API 26 and later.
                deadline(token, MTU_TIMEOUT_MS) { setUp(token, g) }
                val requested = try { g.requestMtu(REQUESTED_MTU) } catch (_: Exception) { false }
                if (!requested) setUp(token, g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            dispatch(token, g) {
                if (phase != Phase.MTU) return@dispatch
                log("Bluetooth MTU is $mtu.")
                setUp(token, g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            dispatch(token, g) {
                if (phase != Phase.NOTIFICATIONS || descriptor.uuid != cccdId || descriptor.characteristic.uuid != notifyId) return@dispatch
                if (status != BluetoothGatt.GATT_SUCCESS) return@dispatch fail(token, "Bluetooth notification setup failed ($status).")
                lifecycle.cancelDeadline()
                phase = Phase.READY
                if (videoOp != VideoOp.NONE) { sendVideoCommand(token, g); return@dispatch }
                if (style2k) sendStyle2k(token, g) else sendOpenWifi(token, g)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val value = c.value?.copyOf() ?: return
                val id = c.uuid
                dispatch(token, g) { handleNotification(id, value) }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val bytes = value.copyOf()
            val id = c.uuid
            dispatch(token, g) { handleNotification(id, bytes) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            dispatch(token, g) {
                if (c.uuid != writeId || phase != Phase.READY) return@dispatch
                if (videoOp != VideoOp.NONE) {
                    if (status != BluetoothGatt.GATT_SUCCESS) return@dispatch fail(token, "Settings BLE write failed ($status).")
                    lifecycle.cancelDeadline()
                    if (videoOp == VideoOp.CAPACITY) {
                        // The probe is answered (or not) by a notification; give it a clear timeout.
                        log("Capacity request accepted; waiting for any reply.")
                        deadline(token, 8000L) { fail(token, "The glasses did not answer the capacity probe.") }
                        return@dispatch
                    }
                    deadline(token, 6000L) { fail(token, "The glasses did not confirm the clip length.") }
                    log(if (videoOp == VideoOp.SET) "Android accepted the clip-length write; waiting for the glasses to confirm."
                        else "Status request accepted; waiting for the glasses to report their clip length.")
                    if (videoOp == VideoOp.SET) {
                        // The firmware often applies the value without echoing it back; do not
                        // report a failure for a write the stack already accepted.
                        handler.postDelayed({
                            if (lifecycle.accepts(token, g) && !videoReported) {
                                videoReported = true
                                lifecycle.cancelDeadline()
                                onVideoLength(videoSeconds, false)
                            }
                        }, 3000L)
                    }
                    return@dispatch
                }
                if (style2k) {
                    lifecycle.cancelDeadline()
                    if (status != BluetoothGatt.GATT_SUCCESS) return@dispatch fail(token, "2K BLE write failed ($status).")
                    log("Android accepted the 2K style write; this is not proof of a new recording mode.")
                    handler.postDelayed({
                        if (lifecycle.accepts(token, g) && style2k) onStyleResult(true)
                    }, 1000L)
                } else if (status != BluetoothGatt.GATT_SUCCESS && attempts >= MAX_ATTEMPTS && !gotSsid)
                    fail(token, "BLE command not acknowledged ($status).")
            }
        }
    }

    /** MTU callback and fallback may race, but only the first can change the phase. */
    private fun setUp(token: Long, g: BluetoothGatt) {
        if (!lifecycle.accepts(token, g) || phase != Phase.MTU) return
        phase = Phase.NOTIFICATIONS
        deadline(token, SETUP_TIMEOUT_MS) { fail(token, "Bluetooth notification setup timed out. Reconnect and retry.") }
        try {
            val svc = g.getService(serviceId) ?: return fail(token, "Service AA12 not found.")
            val command = svc.getCharacteristic(writeId) ?: return fail(token, "Characteristic AA13 not found.")
            val response = svc.getCharacteristic(notifyId) ?: return fail(token, "Characteristic AA14 not found.")
            if ((command.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0)
                return fail(token, "AA13 is not writable on this device.")
            try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) { }
            if (!g.setCharacteristicNotification(response, true)) return fail(token, "Could not subscribe to AA14 notifications.")
            val cccd = response.getDescriptor(cccdId) ?: return fail(token, "Notification descriptor 2902 not available.")
            log("Enabling response notifications…")
            if (Build.VERSION.SDK_INT >= 33) {
                val result = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (result != BluetoothStatusCodes.SUCCESS) fail(token, "Enabling notifications failed ($result).")
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                if (!g.writeDescriptor(cccd)) fail(token, "Enabling notifications was rejected.")
            }
        } catch (e: Exception) { fail(token, "BLE subscribe error: ${e.message}") }
    }

    /** Command 0x02 writes the clip length; command 0x48 asks the device to report it. */
    private fun sendVideoCommand(token: Long, g: BluetoothGatt) {
        if (!lifecycle.accepts(token, g) || phase != Phase.READY || videoOp == VideoOp.NONE) return
        val characteristic = g.getService(serviceId)?.getCharacteristic(writeId)
            ?: return fail(token, "BLE write characteristic lost.")
        val packet = when (videoOp) {
            VideoOp.SET -> Protocol.videoLength(videoSeconds)
            VideoOp.READ -> Protocol.deviceStatus()
            else -> Protocol.deviceCapacity()
        }
        log(when (videoOp) {
            VideoOp.SET -> "Sending clip length ${videoSeconds / 60} min ($videoSeconds s) as a setting; no recording command."
            VideoOp.READ -> "Asking the glasses for their current clip length."
            else -> "Asking the glasses for storage capacity (command 0x16); read-only probe."
        })
        deadline(token, 8000L) {
            fail(token, if (videoOp == VideoOp.CAPACITY) "The glasses did not answer the capacity probe."
                        else "The glasses did not answer the clip-length command.")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val result = g.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (result != BluetoothStatusCodes.SUCCESS) fail(token, "Clip-length BLE write not queued ($result).")
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = packet
                @Suppress("DEPRECATION")
                if (!g.writeCharacteristic(characteristic)) fail(token, "Clip-length BLE write rejected.")
            }
        } catch (e: Exception) { fail(token, "Clip-length BLE write failed: ${e.message}") }
    }

    private fun sendStyle2k(token: Long, g: BluetoothGatt) {
        if (!lifecycle.accepts(token, g) || phase != Phase.READY || !style2k) return
        val characteristic = g.getService(serviceId)?.getCharacteristic(writeId)
            ?: return fail(token, "BLE write characteristic lost.")
        log("Sending experimental 2K / 16:9 style command to the selected glasses (no recording command).")
        deadline(token, 5000L) { fail(token, "No BLE write confirmation for 2K style.") }
        try {
            val packet = Protocol.cameraStyle2k()
            if (Build.VERSION.SDK_INT >= 33) {
                val result = g.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (result != BluetoothStatusCodes.SUCCESS) fail(token, "2K BLE write not queued ($result).")
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = packet
                @Suppress("DEPRECATION")
                if (!g.writeCharacteristic(characteristic)) fail(token, "2K BLE write rejected.")
            }
        } catch (e: Exception) { fail(token, "2K BLE write failed: ${e.message}") }
    }

    private fun sendOpenWifi(token: Long, g: BluetoothGatt) {
        if (!lifecycle.accepts(token, g) || phase != Phase.READY || gotSsid) return
        attempts++
        val command = g.getService(serviceId)?.getCharacteristic(writeId)
            ?: return fail(token, "BLE write characteristic lost.")
        val vendorJob = attempts > 2
        val packet = if (vendorJob) Protocol.openWifiVendor(requestedP2p) else Protocol.openWifi(requestedP2p)
        if (vendorJob) log("Retrying with the vendor AP command 0x63 instead of 0x39.")
        log("Requesting glasses Wi-Fi (${if (requestedP2p) "alternate P2P" else "AP"} mode, attempt $attempts of $MAX_ATTEMPTS)…")
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val result = g.writeCharacteristic(command, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (result != BluetoothStatusCodes.SUCCESS) log("BLE write not queued ($result).")
            } else {
                @Suppress("DEPRECATION")
                command.value = packet
                @Suppress("DEPRECATION")
                if (!g.writeCharacteristic(command)) log("BLE write was rejected.")
            }
        } catch (e: Exception) { log("BLE command failed: ${e.message}") }
        deadline(token, RETRY_DELAY_MS) {
            if (lifecycle.accepts(token, g) && !gotSsid) {
                if (attempts < MAX_ATTEMPTS) sendOpenWifi(token, g)
                else fail(token, "No Wi-Fi SSID reply after $MAX_ATTEMPTS attempts. Try alternate mode or reconnect.")
            }
        }
    }

    private fun handleNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic != notifyId || phase != Phase.READY) return
        val responses = assembler.feed(value)
        if (videoOp == VideoOp.CAPACITY) {
            val text = responses.joinToString("; ") {
                "cmd 0x${it.command.toString(16)} [" +
                    it.data.joinToString(" ") { b -> "%02x".format(b.toInt() and 0xff) } + "]"
            }
            if (text.isNotEmpty() && !videoReported) {
                videoReported = true
                lifecycle.cancelDeadline()
                onCapacityReply(text)
            }
            return
        }
        if (videoOp != VideoOp.NONE) {
            val reported = responses.asSequence().mapNotNull { Protocol.videoLengthSeconds(it) }.firstOrNull()
            if (reported != null && !videoReported) {
                videoReported = true
                lifecycle.cancelDeadline()
                onVideoLength(reported, videoOp == VideoOp.SET)
            } else if (!videoReported) {
                // Only narrate the replies that arrive before the value is known; the rest of
                // the status burst lands after the answer and would just be noise.
                for (response in responses)
                    log("Glasses reply: command 0x${response.command.toString(16)} (ignored for clip length)")
            }
            return
        }
        if (style2k) {
            for (response in responses) log("Glasses BLE reply to style test: command 0x${response.command.toString(16)}, payload ${response.data.joinToString("") { "%02x".format(it.toInt() and 0xff) }}")
            return
        }
        if (gotSsid) return
        val ssid = responses.asSequence().mapNotNull { Protocol.ssid(it) }.firstOrNull() ?: return
        gotSsid = true
        lifecycle.cancelDeadline()
        log("Glasses SSID: $ssid")
        // Already on main; do not queue an unguarded delivery that could outlive this session.
        onSsid(ssid)
    }

    private fun fail(token: Long, message: String) {
        if (!lifecycle.accepts(token)) return
        closeOnMain()
        onError(message)
    }

    fun close() { handler.post { closeOnMain() } }

    private fun closeOnMain() {
        lifecycle.end()
        phase = Phase.IDLE
        stopScan()
        val previous = gatt
        gatt = null
        try { previous?.disconnect() } catch (_: Exception) { }
        try { previous?.close() } catch (_: Exception) { }
        // Do not remove all handler messages: a newer start/close may already be queued.
        // Generation and deadline guards make every remaining callback inert.
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 20000L
        private const val SETUP_TIMEOUT_MS = 15000L
        private const val MTU_TIMEOUT_MS = 2000L
        private const val RETRY_DELAY_MS = 3000L
        private const val MAX_ATTEMPTS = 5
        private const val REQUESTED_MTU = 517

        private fun uuid(short: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))
    }
}
