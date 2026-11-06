package com.open.glassesdownloader

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Network
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Entire app is native Android views and platform SDK calls; no WebView, telemetry, or account. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val journalExecutor = Executors.newSingleThreadExecutor()
    private lateinit var journal: OperationStatusJournal
    private var journalSessionId = ""
    private var journalStartedAtMillis = 0L
    private var journalStartedElapsedMillis: Long? = null
    private var lastTransferSummary: MediaImporter.Summary? = null
    private var cancelled = AtomicBoolean(true)
    private val operations = RequestGate()
    private var operationToken = 0L
    private var wifiCycle = 0L
    private lateinit var ble: GlassesBle
    private lateinit var wifi: GlassesWifi
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var targetName: EditText
    private lateinit var ssidView: EditText
    private lateinit var alternateMode: CheckBox
    private lateinit var privateRadio: RadioButton
    private lateinit var sharedRadio: RadioButton
    private lateinit var galleryButton: Button
    private lateinit var deviceButton: Button
    private lateinit var deviceLabel: TextView
    private lateinit var startButton: Button
    private lateinit var styleButton: Button
    private lateinit var manualButton: Button
    private lateinit var cancelButton: Button
    private lateinit var manualPane: LinearLayout
    private lateinit var extraPane: LinearLayout
    private lateinit var manualTab: TabStrip
    private lateinit var extraTab: TabStrip
    private lateinit var videoCurrentLine: TextView
    private val videoChips = LinkedHashMap<Int, TextView>()
    private var videoSeconds = 180
    private var pendingVideoApply = false
    private lateinit var paramsStatusLine: TextView
    private lateinit var paramsContainer: LinearLayout
    private var deviceParams = emptyList<DeviceParam>()
    private var deviceParamValues = mapOf<String, Int>()
    private enum class SettingsTask { NONE, LOAD, APPLY, DELETE }
    private var settingsTask = SettingsTask.NONE
    private var pendingSettingsTask = SettingsTask.NONE
    private var pendingCapacityProbe = false
    private var pendingParam: DeviceParam? = null
    private var pendingParamPosition = -1
    /** Kept while the settings pane is open so further changes skip another BLE + Wi-Fi round trip. */
    private var settingsNetwork: Network? = null
    private lateinit var progress: ProgressBar
    private var busy = false
    private var activeSsid = ""
    private var selectedAddress: String? = null
    private var selectedName: String = ""
    private var wifiWarmupTask: Runnable? = null
    private var recoveryTask: Runnable? = null
    private var sessionRetries = 0
    private var transferStarted = false
    private var styleOperation = false
    private val accent = Color.rgb(105, 218, 203)
    private val secondary = Color.rgb(174, 187, 200)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        journal = OperationStatusJournal(File(filesDir, "diagnostics/last-operation.json"))
        loadDeviceSelection()
        wifi = GlassesWifi(this)
        ble = GlassesBle(this,
            log = { append(it) },
            onSsid = { found ->
                runOnUiThread {
                    activeSsid = found
                    ssidView.setText(found)
                    append("Wi-Fi advertised as $found")
                    if (busy && recoveryTask == null) startWifiWarmup(found)
                }
            },
            onError = { handleBleError(it) },
            onStyleResult = { acknowledged ->
                append(if (acknowledged) "2K style command written over BLE. Record a fresh short clip to verify resolution; no recording was started by the app."
                    else "2K style write was not acknowledged.")
                finishOperation(if (acknowledged) "2K command sent (unverified)" else "2K write failed")
            },
            onVideoLength = { seconds, confirmed ->
                val fromDevice = confirmed || !pendingVideoApply
                runOnUiThread {
                    if (::videoCurrentLine.isInitialized && fromDevice) {
                        videoCurrentLine.text = "Current on glasses: ${seconds / 60} min ($seconds s)"
                        selectVideo(seconds)
                    }
                }
                append(when {
                    confirmed -> "Glasses confirmed the clip length: ${seconds / 60} min ($seconds s)."
                    fromDevice -> "Glasses report clip length ${seconds / 60} min ($seconds s)."
                    else -> "Clip length ${seconds / 60} min ($seconds s) was written but not echoed back; verify with Read."
                })
                finishOperation(when {
                    confirmed -> "Clip length set"
                    fromDevice -> "Clip length read"
                    else -> "Clip length written (unconfirmed)"
                })
            }
        )
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 24, 32))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val monitor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(9))
            background = bg(Color.rgb(23, 36, 43), 8)
        }
        monitor.addView(text("GLASSES DOWNLOADER  ·  BLE + WI-FI", 12f, accent, Typeface.BOLD), margin(0, 0, 0, 4))
        val stateLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status = text("Ready", 14f, Color.WHITE, Typeface.BOLD)
        stateLine.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        stateLine.addView(progress, LinearLayout.LayoutParams(dp(24), dp(24)))
        monitor.addView(stateLine, margin(0, 0, 0, 7))
        logScroll = ScrollView(this).apply {
            isFillViewport = true
            background = bg(Color.rgb(12, 21, 26), 6)
        }
        logView = text("No access to your existing photos or videos is requested. Nothing is removed from the glasses.\n",
            12f, secondary).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true)
        }
        logScroll.addView(logView)
        monitor.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(monitor, LinearLayout.LayoutParams(-1, 0, 1f))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 2f))
        setContentView(root)

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = button("Connect & download", true) { start() }
        actionRow.addView(startButton, LinearLayout.LayoutParams(0, -2, 2f))
        cancelButton = button("Cancel / disconnect", false) { stop() }
        cancelButton.isEnabled = false
        actionRow.addView(cancelButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(7) })
        body.addView(actionRow, margin(0, 10, 0, 12))

        body.addView(text("DEVICE", 11f, accent, Typeface.BOLD), margin(0, 0, 0, 4))
        val deviceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        deviceLabel = text(deviceSummary(), 13f, secondary)
        deviceRow.addView(deviceLabel, LinearLayout.LayoutParams(0, -2, 1f))
        deviceButton = button("Choose Bluetooth device", false) { pickDevice() }
        deviceRow.addView(deviceButton, LinearLayout.LayoutParams(dp(126), -2).apply { leftMargin = dp(8) })
        body.addView(deviceRow, margin(0, 0, 0, 12))

        body.addView(text("WHERE TO SAVE FILES", 12f, accent, Typeface.BOLD))
        body.addView(text("Neither option needs permission to read your personal photos.", 13f, secondary), margin(0, 6, 0, 6))
        val storageChoices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        privateRadio = RadioButton(this).apply {
            text = "Private app gallery (recommended)"
            setTextColor(Color.WHITE)
            textSize = 14f
            id = View.generateViewId()
            minimumHeight = dp(48)
        }
        sharedRadio = RadioButton(this).apply {
            text = "Android gallery (Pictures / Movies / Music)"
            setTextColor(Color.WHITE)
            textSize = 14f
            id = View.generateViewId()
            minimumHeight = dp(48)
        }
        storageChoices.addView(privateRadio)
        storageChoices.addView(sharedRadio)
        val saved = StorageTarget.selected(this)
        val sharedSupported = Build.VERSION.SDK_INT >= 29
        if (!sharedSupported) {
            sharedRadio.isEnabled = false
            sharedRadio.text = "Android gallery (needs Android 10+)"
        }
        val effective = if (saved == StorageTarget.PRIVATE || !sharedSupported) StorageTarget.PRIVATE else StorageTarget.ANDROID_GALLERY
        storageChoices.check(if (effective == StorageTarget.PRIVATE) privateRadio.id else sharedRadio.id)
        if (effective != saved) StorageTarget.select(this, effective)
        body.addView(storageChoices, margin(0, 0, 0, 4))
        val storageWarning = text(
            "Uninstalling this app or clearing its data deletes downloads in the private gallery. Export files you want to keep first.",
            12f, Color.rgb(255, 215, 166), Typeface.BOLD
        ).apply {
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = bg(Color.rgb(68, 47, 36), 6)
        }
        // Previously saved private files remain at risk even if new downloads use Android gallery.
        body.addView(storageWarning, margin(0, 0, 0, 8))
        storageChoices.setOnCheckedChangeListener { _, checkedId ->
            StorageTarget.select(this,
                if (checkedId == privateRadio.id) StorageTarget.PRIVATE else StorageTarget.ANDROID_GALLERY)
        }
        if (!sharedSupported) body.addView(
            text("Android 8-9: use Export in the private gallery to move files out.", 12f, secondary),
            margin(0, 0, 0, 8)
        )
        galleryButton = button("Open private gallery", false) {
            startActivity(Intent(this, PrivateGalleryActivity::class.java))
        }
        body.addView(galleryButton, margin(0, 0, 0, 12))

        // Tabs sit directly under the action row: the manual Wi-Fi path vs device-side settings.
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manualTab = tab("Manual") { showTab(true) }
        extraTab = tab("Extra Settings") { showTab(false) }
        tabRow.addView(manualTab.box, LinearLayout.LayoutParams(0, -2, 1f))
        tabRow.addView(extraTab.box, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(tabRow, margin(0, 0, 0, 10))

        manualPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        extraPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        body.addView(manualPane)
        body.addView(extraPane)

        // ---- Manual: existing no-Bluetooth download path ----
        manualPane.addView(text("WI-FI FALLBACK", 11f, accent, Typeface.BOLD))
        manualPane.addView(text("Already connected to the glasses' Wi-Fi? Download directly; no Bluetooth needed.", 13f, secondary), margin(0, 6, 0, 8))
        ssidView = edit("", "Glasses SSID (reported via BLE)").apply { isEnabled = false }
        manualPane.addView(ssidView, margin(0, 0, 0, 8))
        manualButton = button("Download using current Wi-Fi", false) { startManual() }
        manualPane.addView(manualButton, margin(0, 0, 0, 12))

        manualPane.addView(text("FILES ON THE GLASSES", 11f, accent, Typeface.BOLD))
        manualPane.addView(text("Deleting is permanent and happens on the glasses. Files already saved on this phone are not touched.", 12f, Color.rgb(255, 215, 166), Typeface.BOLD).apply {
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = bg(Color.rgb(68, 47, 36), 6)
        }, margin(0, 4, 0, 6))
        manualPane.addView(button("List glasses files & delete…", false) { openDeleteList() }, margin(0, 0, 0, 12))

        manualPane.addView(text("ADVANCED CONNECTION", 11f, accent, Typeface.BOLD))
        targetName = edit("", "Scan filter (optional) — name or prefix")
        manualPane.addView(targetName, margin(0, 6, 0, 8))
        alternateMode = CheckBox(this).apply {
            text = "Try alternate Wi-Fi mode (P2P / 0x31)"
            setTextColor(secondary)
            textSize = 13f
            minimumHeight = dp(48)
            isChecked = false
        }
        manualPane.addView(alternateMode, margin(0, 0, 0, 12))

        // ---- Extra Settings: device-side values, never recording commands ----
        extraPane.addView(text("VIDEO LENGTH", 11f, accent, Typeface.BOLD))
        videoCurrentLine = text("Current on glasses: unknown — tap Read from glasses.", 13f, secondary)
        extraPane.addView(videoCurrentLine, margin(0, 5, 0, 7))
        extraPane.addView(videoChipRow(VIDEO_LENGTH_CHOICES.copyOfRange(0, 3)), margin(0, 0, 0, 6))
        extraPane.addView(videoChipRow(VIDEO_LENGTH_CHOICES.copyOfRange(3, 6)), margin(0, 0, 0, 8))
        selectVideo(videoSeconds)
        extraPane.addView(text("Each clip stops at this length and the next one starts a new file. 15 min is the longest the firmware offers; this setting neither records nor deletes anything.", 12f, secondary), margin(0, 0, 0, 8))
        val videoActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        videoActions.addView(button("Read from glasses", false) { readVideoLength() }, LinearLayout.LayoutParams(0, -2, 1f))
        videoActions.addView(button("Apply to glasses", true) { applyVideoLength() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(7) })
        extraPane.addView(videoActions, margin(0, 0, 0, 12))

        extraPane.addView(text("EXPERIMENTAL VIDEO MODE", 11f, accent, Typeface.BOLD))
        extraPane.addView(text("Vendor BLE labels say 2K / 16:9, but support on this CT01 is unverified. This button only changes a setting; it does not record or delete files.", 12f, secondary), margin(0, 6, 0, 6))
        styleButton = button("Try 2K / 16:9 video mode (experimental)", false) { startStyle2k() }
        extraPane.addView(styleButton, margin(0, 0, 0, 14))

        extraPane.addView(text("EXPERIMENTAL", 11f, accent, Typeface.BOLD), margin(0, 6, 0, 4))
        extraPane.addView(text("Reads and writes the glasses' own HTTP settings — resolution, clip splitting, microphone, timelapse, parking mode and the rest. Needs the phone on the glasses' Wi-Fi. Every change applies to the device immediately; nothing here records, downloads or deletes.", 12f, Color.rgb(255, 215, 166), Typeface.BOLD).apply {
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = bg(Color.rgb(68, 47, 36), 6)
        }, margin(0, 4, 0, 8))
        paramsStatusLine = text("Not loaded.", 12f, secondary)
        extraPane.addView(paramsStatusLine, margin(0, 0, 0, 6))
        extraPane.addView(button("Load device settings", false) { loadDeviceParams() }, margin(0, 0, 0, 8))
        paramsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        extraPane.addView(paramsContainer, margin(0, 0, 0, 10))
        extraPane.addView(button("Probe device capacity (BLE 0x16)", false) { probeCapacity() }, margin(0, 0, 0, 6))
        extraPane.addView(text("Read-only probe: asks the glasses for their storage capacity and logs whatever they answer. It does not record, write or delete anything.", 12f, secondary), margin(0, 0, 0, 18))
        showTab(true)
    }

    private companion object {
        val journalClock = AtomicLong(0L)
        /** The only clip lengths the firmware offers; 900 s (15 min) is the ceiling. */
        val VIDEO_LENGTH_CHOICES = intArrayOf(60, 180, 300, 420, 600, 900)
        const val REQUEST_TRANSFER = 100
        const val REQUEST_PICKER = 101
        const val REQUEST_DEVICE = 102
        const val REQUEST_STYLE = 103
        const val REQUEST_VIDEO = 104
        const val REQUEST_SETTINGS = 105
        const val KEY_ADDRESS = "device_address"
        const val KEY_NAME = "device_name"
        const val WIFI_WARMUP_MS = 5000L
        const val EXTRA_DIAGNOSTIC_CROSSOVER = "diagnostic_ble_wifi_crossover"
        const val EXTRA_DIAGNOSTIC_HOLD_SECONDS = "diagnostic_hold_seconds"
    }

    private fun start() {
        if (busy) return
        val missing = Permissions.missing(this)
        if (missing.isNotEmpty()) {
            requestPermissions(missing, REQUEST_TRANSFER)
            return
        }
        startBleAfterPermission()
    }
    private fun startStyle2k() {
        if (busy) return
        if (selectedAddress == null) {
            append("Choose the CT01 Bluetooth device first; the experimental command will not be broadcast to an unknown device.")
            return
        }
        val missing = Permissions.missingBluetooth(this)
        if (missing.isNotEmpty()) {
            requestPermissions(missing, REQUEST_STYLE)
            return
        }
        val bt = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bt.adapter?.isEnabled != true) return fail("Bluetooth is off. Enable it before changing the camera mode.")
        styleOperation = true
        transferStarted = false
        setBusy(true, "Connecting to set 2K")
        append("Connecting to the selected device only; no Wi-Fi or recording command will be sent.")
        ble.startStyle2k(selectedAddress!!)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQUEST_TRANSFER -> {
                if (granted) startBleAfterPermission()
                else fail("Nearby-device permissions denied; allow them in Android Settings to use automatic transfer.")
            }
            REQUEST_STYLE -> {
                if (granted) startStyle2k()
                else append("Nearby-device permission denied; 2K command was not sent.")
            }
            REQUEST_SETTINGS -> {
                if (granted) {
                    if (pendingCapacityProbe) {
                        pendingCapacityProbe = false
                        probeCapacity()
                    } else startSettingsSession(pendingSettingsTask)
                }
                else append("Nearby-device permission denied; the glasses' Wi-Fi was not started.")
            }
            REQUEST_VIDEO -> {
                if (granted) {
                    if (pendingVideoApply) applyVideoLength() else readVideoLength()
                } else append("Nearby-device permission denied; the clip length was left unchanged.")
            }
            REQUEST_PICKER -> {
                if (granted) openDevicePicker()
                else append("Nearby-device permission is needed to list Bluetooth devices.")
            }
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DEVICE || resultCode != RESULT_OK) return
        val address = data?.getStringExtra(DevicePickerActivity.EXTRA_ADDRESS).orEmpty()
        if (address.isBlank()) {
            selectedAddress = null
            selectedName = ""
            append("Device choice cleared; will connect to any glasses advertising AA12.")
        } else {
            selectedAddress = address
            selectedName = data?.getStringExtra(DevicePickerActivity.EXTRA_NAME).orEmpty()
            append("Selected ${selectedName.ifBlank { address }} ($address).")
        }
        saveDeviceSelection()
        updateDeviceLabel()
    }

    private fun pickDevice() {
        if (busy) return
        val missing = Permissions.missingBluetooth(this)
        if (missing.isNotEmpty()) {
            requestPermissions(missing, REQUEST_PICKER)
            return
        }
        openDevicePicker()
    }

    private fun openDevicePicker() {
        startActivityForResult(Intent(this, DevicePickerActivity::class.java), REQUEST_DEVICE)
    }

    private fun deviceSummary(): String = when {
        selectedAddress == null -> "No device chosen — will connect to any glasses advertising AA12."
        selectedName.isBlank() -> "Selected device: $selectedAddress"
        else -> "Selected device: $selectedName ($selectedAddress)"
    }

    private fun updateDeviceLabel() {
        if (::deviceLabel.isInitialized) deviceLabel.text = deviceSummary()
    }

    private fun loadDeviceSelection() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        selectedAddress = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() }
        selectedName = prefs.getString(KEY_NAME, "").orEmpty()
    }

    private fun saveDeviceSelection() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString(KEY_ADDRESS, selectedAddress)
            .putString(KEY_NAME, selectedName)
            .apply()
    }

    private fun startBleAfterPermission() {
        if (busy) return
        styleOperation = false
        transferStarted = false
        sessionRetries = 0
        setBusy(true, "Checking Bluetooth")
        val bt = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bt.adapter?.isEnabled != true) {
            fail("Bluetooth is off. Please enable it in Quick Settings.")
            return
        }
        status.text = "Finding glasses"
        activeSsid = ""
        launchBleSession()
    }

    private fun launchBleSession() {
        if (!busy || cancelled.get()) return
        status.text = "Finding glasses (${sessionRetries + 1}/${ConnectionRecoveryPolicy.MAX_SESSION_RETRIES + 1})"
        append("Starting new BLE session ${sessionRetries + 1}/${ConnectionRecoveryPolicy.MAX_SESSION_RETRIES + 1}. If connecting fails, try disconnecting other apps using the glasses.")
        try { ble.start(selectedAddress, targetName.text.toString().trim(), alternateMode.isChecked) }
        catch (e: Exception) { handleBleError("Bluetooth failed: ${e.message}") }
    }

    private fun handleBleError(message: String) {
        main.post {
            if (!busy || recoveryTask != null) return@post
            if (!retryBleSession(message)) fail(
                if (!transferStarted && !styleOperation)
                    "$message After ${ConnectionRecoveryPolicy.MAX_SESSION_RETRIES + 1} sessions, power-cycle the glasses and disconnect other Bluetooth users before retrying."
                else message
            )
        }
    }

    /** Re-open the ordinary AP via BLE; never retry a transfer already in progress. */
    private fun retryBleSession(reason: String): Boolean {
        if (!busy || !ConnectionRecoveryPolicy.shouldRestart(sessionRetries, transferStarted, styleOperation)) return false
        sessionRetries++
        append("Connection interrupted: $reason")
        append("Restarting Bluetooth and glasses Wi-Fi in 3 seconds " +
            "(session ${sessionRetries + 1}/${ConnectionRecoveryPolicy.MAX_SESSION_RETRIES + 1}). No media is deleted.")
        status.text = "Retrying connection ${sessionRetries + 1}/${ConnectionRecoveryPolicy.MAX_SESSION_RETRIES + 1}"
        cancelWifiWarmup()
        wifiCycle++
        wifi.close()
        ble.close()
        activeSsid = ""
        val task = Runnable {
            recoveryTask = null
            if (busy && !cancelled.get()) launchBleSession()
        }
        recoveryTask = task
        main.postDelayed(task, 3000L)
        return true
    }
    private fun startWifiWarmup(ssid: String) {
        if (wifiWarmupTask != null) return
        val startedAt = SystemClock.elapsedRealtime()
        append("Glasses reported Wi-Fi. Waiting 5 seconds before the first join request.")
        val task = object : Runnable {
            override fun run() {
                if (!busy || activeSsid != ssid) { cancelWifiWarmup(); return }
                val remaining = WIFI_WARMUP_MS - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining <= 0L) {
                    wifiWarmupTask = null
                    requestGlassesWifi(ssid)
                } else {
                    status.text = "Wi-Fi starting in ${(remaining + 999L) / 1000L}s"
                    main.postDelayed(this, minOf(remaining, 1000L))
                }
            }
        }
        wifiWarmupTask = task
        task.run()
    }
    private fun cancelWifiWarmup() {
        wifiWarmupTask?.let { main.removeCallbacks(it) }
        wifiWarmupTask = null
    }
    private fun cancelRecoveryTask() {
        recoveryTask?.let { main.removeCallbacks(it) }
        recoveryTask = null
    }
    private fun requestGlassesWifi(ssid: String) {
        if (Build.VERSION.SDK_INT < 33 && !locationAvailable()) {
            append("Turn on Location (Settings > Location). Android 12 and older will not join a Wi-Fi network with it off.")
            finishOperation("Turn on Location")
            return
        }
        status.text = "Joining Wi-Fi"
        if (Build.VERSION.SDK_INT >= 29) append("Android may show a system connection approval. Accept to continue.")
        else append("Joining $ssid directly; Android 8-9 has no approval dialog for this.")
        val token = operationToken
        val cycle = ++wifiCycle
        try {
            wifi.request(ssid,
                onAvailable = { network ->
                    main.post {
                        if (operations.isCurrent(token) && cycle == wifiCycle && !transferStarted && busy && recoveryTask == null) {
                            append("Connected to $ssid.")
                            transferStarted = true
                            if (settingsTask != SettingsTask.NONE) settingsNetwork = network
                            when (settingsTask) {
                                SettingsTask.LOAD -> runSettingsLoad(network)
                                SettingsTask.APPLY -> runSettingsApply(network)
                                SettingsTask.DELETE -> runDeleteListing(network)
                                SettingsTask.NONE -> {
                                    append("Opening media library…")
                                    transfer(network)
                                }
                            }
                        }
                    }
                },
                onUnavailable = { reason -> main.post {
                    if (operations.isCurrent(token) && cycle == wifiCycle && !transferStarted && busy && recoveryTask == null) {
                        val detail = "Automatic Wi-Fi join failed: $reason"
                        if (!retryBleSession(detail)) {
                            append(detail)
                            append("The app tried ${ConnectionRecoveryPolicy.MAX_SESSION_RETRIES + 1} Bluetooth/AP sessions. Power-cycle the glasses, disconnect other phones using their Wi-Fi, then tap Connect & download again.")
                            finishOperation("Wi-Fi needs attention")
                        }
                    }
                }},
                onProgress = { message -> if (operations.isCurrent(token) && cycle == wifiCycle) append(message) }
            )
        } catch (e: Exception) {
            append("Auto join failed: ${e.message}. Try the current-Wi-Fi fallback.")
            finishOperation("Manual Wi-Fi needed")
        }
    }
    private fun startManual() {
        if (busy) return
        transferStarted = true
        styleOperation = false
        setBusy(true, "Using current Wi-Fi")
        append("Trying local Wi-Fi connection directly…")
        val candidates = try { wifi.connectedWifiNetworks() } catch (e: Exception) { emptyList() }
        if (candidates.isEmpty()) {
            fail("No connected Wi-Fi network. Join the glasses' network in Android Settings, then retry.")
        } else {
            transferCandidates(candidates)
        }
    }
    private fun transferCandidates(networks: List<Network>) {
        val token = operationToken
        val workerCancellation = cancelled
        executor.execute {
            var lastError: Exception? = null
            for (network in networks) {
                if (workerCancellation.get() || !operations.isCurrent(token)) break
                try {
                    performTransfer(network, token, workerCancellation)
                    return@execute
                } catch (e: TransferCancelled) {
                    return@execute
                } catch (e: Exception) { lastError = e }
            }
            if (!workerCancellation.get() && operations.isCurrent(token))
                fail("No connected Wi-Fi interface could reach the glasses: ${lastError?.message ?: "unknown error"}", token)
        }
    }
    private fun transfer(network: Network?) {
        val token = operationToken
        val workerCancellation = cancelled
        executor.execute {
            if (workerCancellation.get() || !operations.isCurrent(token)) return@execute
            try { performTransfer(network, token, workerCancellation) }
            catch (e: TransferCancelled) { main.post { if (operations.isCurrent(token)) finishOperation("Cancelled") } }
            catch (e: Exception) { if (operations.isCurrent(token)) fail("Transfer failed: ${e.message}", token) }
        }
    }
    private fun performTransfer(network: Network?, token: Long, workerCancellation: AtomicBoolean) {
        if (workerCancellation.get() || !operations.isCurrent(token)) throw TransferCancelled()
        append("Downloading originals. Keep this screen open until finished.")
        main.post { if (operations.isCurrent(token)) status.text = "Downloading…" }
        val target = StorageTarget.selected(this)
        append("Saving to: ${if (target == StorageTarget.PRIVATE) "private app gallery" else "Android gallery"}")
        val result = MediaImporter(this, { append(it) }, workerCancellation, target).transfer(network)
        if (workerCancellation.get() || !operations.isCurrent(token)) throw TransferCancelled()
        append("Complete: ${result.downloaded} new, ${result.skipped} already saved, ${result.failed} failed, of ${result.found} total.")
        if (result.failed > 0) append("Some files failed. Keep the glasses powered and near the phone, then tap Connect & download again. Completed files will be skipped; unfinished files will be retried.")
        main.post { if (operations.isCurrent(token)) lastTransferSummary = result }
        if (intent.getBooleanExtra(EXTRA_DIAGNOSTIC_CROSSOVER, false) && network != null)
            runBleWifiCrossover(network, token, workerCancellation, result.failed > 0)
        else main.post { if (operations.isCurrent(token)) finishOperation(if (result.failed > 0) "Finished with errors" else "Transfer complete") }
    }
    /** One Wi-Fi association for both phases: keep GATT, then close only GATT. */
    private fun runBleWifiCrossover(network: Network, token: Long, workerCancellation: AtomicBoolean, transferFailed: Boolean) {
        val folder = File(filesDir, "diagnostics")
        folder.mkdirs()
        val output = File(folder, "crossover-${System.currentTimeMillis()}.csv")
        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val holdSeconds = intent.getIntExtra(EXTRA_DIAGNOSTIC_HOLD_SECONDS, 90).coerceIn(15, 180)
        append("Diagnostic: one Wi-Fi connection, ${holdSeconds}s with BLE held, then 90s after BLE closes. Probing HTTP every 5s. File: ${output.name}")
        var diagnosticFailed = false
        try {
            output.bufferedWriter().use { writer ->
                writer.write("epoch_ms,phase,phase_second,wifi_joined,ble_active,http_status,latency_ms,error\n")
                writer.flush()
                for (phase in listOf("BLE_HELD", "BLE_CLOSED")) {
                    if (workerCancellation.get() || !operations.isCurrent(token)) break
                    if (phase == "BLE_CLOSED") {
                        val closed = CountDownLatch(1)
                        // A second main-loop post runs after GlassesBle.closeOnMain, so the
                        // closed phase cannot start before the GATT disconnect request.
                        main.post { if (operations.isCurrent(token)) ble.close(); main.post { closed.countDown() } }
                        if (!closed.await(3, TimeUnit.SECONDS)) throw IllegalStateException("GATT close did not run")
                        append("Diagnostic: GATT closed; no new Wi-Fi request will be made.")
                    } else append("Diagnostic: holding GATT open after transfer.")
                    val phaseStart = SystemClock.elapsedRealtime()
                    val phaseDuration = if (phase == "BLE_HELD") holdSeconds * 1_000L else 90_000L
                    for (slot in 0 until (phaseDuration / 5_000L).toInt()) {
                        val due = phaseStart + slot * 5_000L
                        while (!workerCancellation.get() && operations.isCurrent(token) && SystemClock.elapsedRealtime() < due)
                            Thread.sleep(minOf(500L, due - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                        if (workerCancellation.get() || !operations.isCurrent(token)) break
                        val started = SystemClock.elapsedRealtime()
                        val joined = runCatching {
                            val info = manager.connectionInfo
                            LegacyWifiPolicy.isJoined(activeSsid, info.ssid,
                                info.supplicantState == SupplicantState.COMPLETED, info.ipAddress)
                        }.getOrDefault(false)
                        val active = ble.isSessionActive()
                        var code = 0
                        var error = ""
                        var conn: HttpURLConnection? = null
                        try {
                            conn = network.openConnection(URL("http://192.168.169.1/app/getfilelist")) as HttpURLConnection
                            conn.connectTimeout = 2000
                            conn.readTimeout = 2000
                            conn.useCaches = false
                            conn.setRequestProperty("Connection", "close")
                            code = conn.responseCode
                            if (code == 200) conn.inputStream.use { it.read() }
                        } catch (e: Exception) {
                            error = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
                                .replace(',', ';').replace('\n', ' ').take(120)
                        } finally { conn?.disconnect() }
                        if (code != 200 || error.isNotEmpty()) diagnosticFailed = true
                        val latency = SystemClock.elapsedRealtime() - started
                        writer.write("${System.currentTimeMillis()},$phase,${slot * 5},$joined,$active,$code,$latency,$error\n")
                        writer.flush()
                        append("$phase +${slot * 5}s: Wi-Fi=$joined BLE=$active HTTP=${if (error.isEmpty()) code else error} (${latency}ms)")
                        main.post { if (operations.isCurrent(token)) status.text = "$phase ${slot * 5}/${phaseDuration / 1_000L}s" }
                    }
                    while (!workerCancellation.get() && operations.isCurrent(token) && SystemClock.elapsedRealtime() < phaseStart + phaseDuration)
                        Thread.sleep(minOf(500L, phaseStart + phaseDuration - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                }
            }
        } catch (e: Exception) {
            diagnosticFailed = true
            append("Diagnostic failed: ${e.message}. Keep the glasses powered and near the phone, then retry.")
        }
        finally {
            if (output.exists()) append("Diagnostic results saved to ${output.absolutePath}")
            if (diagnosticFailed && !workerCancellation.get())
                append("Diagnostic HTTP/probe errors occurred. Inspect the saved CSV, keep the glasses powered and nearby, then retry.")
            main.post { if (operations.isCurrent(token)) finishOperation(
                DiagnosticTerminalStatus.resolve(workerCancellation.get(), transferFailed, diagnosticFailed)
            ) }
        }
    }
    private fun stop() {
        cancelled.set(true)
        cancelWifiWarmup()
        ble.close()
        wifi.close()
        finishOperation("Cancelled")
    }
    private fun setBusy(value: Boolean, title: String) {
        val wasBusy = busy
        busy = value
        if (value) {
            journalSessionId = UUID.randomUUID().toString()
            journalStartedAtMillis = journalClock.updateAndGet { previous ->
                maxOf(System.currentTimeMillis(), previous + 1)
            }
            journalStartedElapsedMillis = SystemClock.elapsedRealtime()
            lastTransferSummary = null
            cancelled = AtomicBoolean(false)
            operationToken = operations.begin()
        } else {
            if (!wasBusy) {
                journalSessionId = UUID.randomUUID().toString()
                journalStartedAtMillis = 0L
                journalStartedElapsedMillis = null
                lastTransferSummary = null
            }
            cancelled.set(true)
            operations.close()
        }
        startButton.isEnabled = !value
        styleButton.isEnabled = !value
        manualButton.isEnabled = !value
        cancelButton.isEnabled = value
        targetName.isEnabled = !value
        deviceButton.isEnabled = !value
        alternateMode.isEnabled = !value
        privateRadio.isEnabled = !value
        sharedRadio.isEnabled = !value && Build.VERSION.SDK_INT >= 29
        progress.visibility = if (value) View.VISIBLE else View.GONE
        status.text = title
        persistStatus(if (value) "running" else "terminal", title)
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun persistStatus(phase: String, title: String) {
        val snapshot = OperationStatusJournal.Snapshot(
            journalSessionId, phase, title,
            summary = lastTransferSummary?.let {
                OperationStatusJournal.Counts(it.found, it.downloaded, it.skipped, it.failed)
            },
            recordedAtMillis = journalClock.updateAndGet { previous ->
                maxOf(System.currentTimeMillis(), previous + 1)
            },
            startedAtMillis = journalStartedAtMillis,
            startedElapsedMillis = journalStartedElapsedMillis
        )
        try {
            journalExecutor.execute {
                try { journal.write(snapshot) }
                catch (e: Exception) { append("Diagnostic status could not be saved: ${e.message}") }
            }
        } catch (_: RejectedExecutionException) {
            // A late device callback after Activity destruction must not crash the app.
        }
    }
    private fun finishOperation(title: String) {
        Log.i("GlassesDownloader", "STATE: $title")
        cancelWifiWarmup()
        cancelRecoveryTask()
        settingsNetwork = null
        clearSettingsTask()
        wifi.close()
        ble.close()
        setBusy(false, title)
    }
    private fun fail(message: String, token: Long = operationToken) {
        main.post {
            if (operationToken != token || (busy && !operations.isCurrent(token))) return@post
            append(message)
            finishOperation("Needs attention")
        }
    }

    /** Android 10-12 will not join a Wi-Fi network with location services off. */
    @Suppress("DEPRECATION") // Settings.Secure.LOCATION_MODE is the only check below API 28.
    private fun locationAvailable(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 28) getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true
        else Settings.Secure.getInt(
            contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF
        ) != Settings.Secure.LOCATION_MODE_OFF
    } catch (_: Exception) { true }
    private fun append(line: String) {
        Log.i("GlassesDownloader", line)
        main.post {
            if (!::logView.isInitialized) return@post
            logView.text = UserLog.append(logView.text.toString(), line)
            // Live tail: the newest line must always end up in view, even when the
            // reader had scrolled up (no "was at bottom" gate — this is a status log,
            // not a document the user parks in the middle of).
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
    private class TabStrip(val box: LinearLayout, val title: TextView, val underline: View)

    private fun tab(label: String, action: () -> Unit): TabStrip {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { action() }
        }
        val title = text(label, 13f, secondary, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(11), dp(6), dp(9))
        }
        val underline = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        box.addView(title, LinearLayout.LayoutParams(-1, -2))
        box.addView(underline, LinearLayout.LayoutParams(-1, dp(2)))
        return TabStrip(box, title, underline)
    }

    private fun showTab(manual: Boolean) {
        if (!::manualPane.isInitialized) return
        manualPane.visibility = if (manual) View.VISIBLE else View.GONE
        extraPane.visibility = if (manual) View.GONE else View.VISIBLE
        manualTab.title.setTextColor(if (manual) accent else secondary)
        manualTab.underline.setBackgroundColor(if (manual) accent else Color.TRANSPARENT)
        extraTab.title.setTextColor(if (manual) secondary else accent)
        extraTab.underline.setBackgroundColor(if (manual) Color.TRANSPARENT else accent)
    }

    private fun videoChipRow(values: IntArray): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEach { seconds ->
            val chip = text("${seconds / 60} min", 13f, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(11), dp(4), dp(11))
                background = bg(Color.rgb(43, 57, 72), 18)
                setOnClickListener { selectVideo(seconds) }
            }
            videoChips[seconds] = chip
            row.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        }
        return row
    }

    private fun selectVideo(seconds: Int) {
        videoSeconds = seconds
        for ((value, chip) in videoChips) {
            val on = value == seconds
            chip.background = bg(if (on) accent else Color.rgb(43, 57, 72), 18)
            chip.setTextColor(if (on) Color.rgb(18, 29, 35) else Color.WHITE)
        }
    }

    /** Clip length is a device setting: no Wi-Fi, no recording, and only ever sent to a chosen device. */
    private fun applyVideoLength() {
        if (busy) return
        if (selectedAddress == null) {
            append("Choose the glasses' Bluetooth device first; the clip-length command is only sent to a chosen device.")
            return
        }
        val missing = Permissions.missingBluetooth(this)
        if (missing.isNotEmpty()) {
            pendingVideoApply = true
            requestPermissions(missing, REQUEST_VIDEO)
            return
        }
        val bt = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bt.adapter?.isEnabled != true) return fail("Bluetooth is off. Enable it before changing the clip length.")
        transferStarted = false
        setBusy(true, "Setting clip length")
        append("Sending clip length ${videoSeconds / 60} min ($videoSeconds s) to the selected glasses. Nothing is recorded.")
        ble.setVideoLength(selectedAddress!!, videoSeconds)
    }

    private fun readVideoLength() {
        if (busy) return
        if (selectedAddress == null) {
            append("Choose the glasses' Bluetooth device first; the clip length cannot be read from an unknown device.")
            return
        }
        val missing = Permissions.missingBluetooth(this)
        if (missing.isNotEmpty()) {
            pendingVideoApply = false
            requestPermissions(missing, REQUEST_VIDEO)
            return
        }
        val bt = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bt.adapter?.isEnabled != true) return fail("Bluetooth is off. Enable it before reading the clip length.")
        transferStarted = false
        setBusy(true, "Reading clip length")
        append("Asking the selected glasses for their current clip length.")
        ble.readVideoLength(selectedAddress!!)
    }

    /** GET against the glasses' HTTP server on whichever connected network answers first. */
    private fun deviceGet(path: String, timeoutMs: Int = 4000): Pair<String, String>? {
        settingsNetwork?.let { live ->
            for (endpoint in Endpoint.ALL) {
                try { return endpoint.host to httpGet(live, "http://${endpoint.host}$path", timeoutMs) } catch (_: Exception) { }
            }
        }
        val networks = runCatching { wifi.connectedWifiNetworks() }.getOrDefault(emptyList())
        for (network in networks) {
            for (endpoint in Endpoint.ALL) {
                try {
                    return endpoint.host to httpGet(network, "http://${endpoint.host}$path", timeoutMs)
                } catch (_: Exception) { }
            }
        }
        return null
    }

    private fun httpGet(network: Network?, url: String, timeoutMs: Int): String {
        val address = URL(url)
        val connection = (network?.openConnection(address) ?: address.openConnection()) as HttpURLConnection
        return try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "GET"
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText().take(200_000) }.orEmpty()
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** Lists every parameter the device exposes, with the options each one accepts. */
    private fun loadDeviceParams() {
        if (busy) return
        setBusy(true, "Reading device settings")
        append("Reading the glasses' parameter list over HTTP…")
        val token = operationToken
        executor.execute {
            val reply = deviceGet(DeviceParams.itemsPath())
            val params = reply?.let { runCatching { DeviceParams.parseItems(it.second) }.getOrDefault(emptyList()) }
                ?: emptyList()
            var values = emptyMap<String, Int>()
            if (params.isNotEmpty()) {
                deviceGet("/app/getparamvalue?param=all")?.let {
                    values = runCatching { DeviceParams.parseValues(it.second) }.getOrDefault(emptyMap())
                }
            }
            main.post {
                if (operationToken != token) return@post
                if (params.isEmpty()) {
                    paramsStatusLine.text = "Not reachable — the glasses' Wi-Fi is off."
                    append("Device settings could not be read: no connected network answered /app/getparamitems.")
                    setBusy(false, "Glasses Wi-Fi is off")
                    offerToStartWifiForLoad()
                } else {
                    deviceParams = params
                    deviceParamValues = values
                    renderDeviceParams()
                    paramsStatusLine.text = "Loaded ${params.size} settings" +
                        if (values.isEmpty()) " (current values unknown)." else " with current values."
                    append("Device settings: ${params.joinToString { it.name }}")
                    setBusy(false, "Device settings loaded")
                }
            }
        }
    }

    private fun renderDeviceParams() {
        paramsContainer.removeAllViews()
        for (param in deviceParams) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = bg(Color.rgb(27, 37, 48), 8)
                isClickable = true
                setOnClickListener { openParamDialog(param) }
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(text(param.name, 13f, Color.WHITE, Typeface.BOLD), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(text(param.labelFor(deviceParamValues[param.name]), 13f, accent, Typeface.BOLD))
            row.addView(head)
            row.addView(text(param.items.joinToString("  ·  "), 11.5f, secondary), margin(0, 3, 0, 0))
            paramsContainer.addView(row, margin(0, 0, 0, 6))
        }
    }

    private fun openParamDialog(param: DeviceParam) {
        if (busy) return
        val checked = deviceParamValues[param.name]?.let { param.values.indexOf(it) } ?: -1
        AlertDialog.Builder(this)
            .setTitle(param.name.replace('_', ' '))
            .setSingleChoiceItems(param.items.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                applyDeviceParam(param, which)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyDeviceParam(param: DeviceParam, position: Int) {
        if (busy) return
        val wire = param.wireValueAt(position)
        val label = param.items.getOrNull(position) ?: wire.toString()
        setBusy(true, "Setting ${param.name}")
        append("Setting ${param.name} = $label on the glasses (device change over HTTP, not a download).")
        val token = operationToken
        executor.execute {
            val reply = deviceGet(DeviceParams.setPath(param.name, wire))
            if (reply == null) {
                main.post {
                    if (operationToken != token) return@post
                    setBusy(false, "Glasses Wi-Fi is off")
                    offerToStartWifiForParam(param, position)
                }
                return@execute
            }
            val accepted = runCatching { DeviceParams.setAccepted(reply.second) }.getOrDefault(false)
            val fresh = if (accepted) deviceGet("/app/getparamvalue?param=all")?.let {
                runCatching { DeviceParams.parseValues(it.second)[param.name] }.getOrNull()
            } else null
            main.post {
                if (operationToken != token) return@post
                reportParamWrite(param, label, reply.second, accepted, fresh)
                setBusy(false, if (accepted) "Setting applied" else "Setting refused")
            }
        }
    }

    /** Lists what is on the glasses so the user can remove files, individually or all at once. */
    private fun openDeleteList() {
        if (busy) return
        setBusy(true, "Reading glasses files")
        append("Reading the file list so you can choose what to delete. Nothing is deleted yet.")
        val token = operationToken
        executor.execute {
            val items = fetchGlassesIndex(null)
            main.post {
                if (operationToken != token) return@post
                if (items == null) {
                    setBusy(false, "Glasses Wi-Fi is off")
                    offerToStartWifiForDelete()
                } else if (items.isEmpty()) {
                    append("The glasses report no files to delete.")
                    setBusy(false, "Nothing to delete")
                } else {
                    append("Glasses list ${items.size} file(s). Choose what to delete.")
                    setBusy(false, "Choose files to delete")
                    showDeleteChooser(items)
                }
            }
        }
    }

    private fun runDeleteListing(network: android.net.Network?) {
        clearSettingsTask()
        val token = operationToken
        executor.execute {
            val items = fetchGlassesIndex(network)
            main.post {
                if (operationToken != token) return@post
                if (items == null) {
                    append("The glasses answered Wi-Fi but not the file list.")
                    finishOperation("Glasses list unavailable")
                } else {
                    append("Glasses list ${items.size} file(s). Choose what to delete. Nothing is deleted yet.")
                    setBusy(false, "Choose files to delete")
                    showDeleteChooser(items)
                }
            }
        }
    }

    private fun fetchGlassesIndex(network: android.net.Network?): List<MediaIndex.Item>? {
        val networks = if (network != null) listOf(network)
            else runCatching { wifi.connectedWifiNetworks() }.getOrDefault(emptyList())
        for (net in networks) {
            for (endpoint in Endpoint.ALL) {
                try {
                    val body = httpGet(net, endpoint.indexUrl(), 8000)
                    val parsed = MediaIndex.parse(FileListCleaner.clean(body), endpoint.sizeUnit)
                    return parsed.items
                } catch (_: Exception) { }
            }
        }
        return null
    }

    private fun showDeleteChooser(items: List<MediaIndex.Item>) {
        if (items.isEmpty()) {
            append("Nothing to delete.")
            return
        }
        // Own action row inside the dialog: with 31 rows the platform button bar clips its
        // third button, so "Delete ALL" used to fall off the screen.
        val labels = items.map { "${it.localName}  ·  ${sizeLabel(it)}" }
        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_multiple_choice, labels)
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
        }
        lateinit var dialog: AlertDialog
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Delete ALL", false) {
            dialog.dismiss()
            confirmDelete(items)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Delete selected", true) {
            val chosen = items.filterIndexed { index, _ -> list.checkedItemPositions.get(index) }
            dialog.dismiss()
            if (chosen.isEmpty()) append("Nothing selected; nothing was deleted.")
            else confirmDelete(chosen)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(7) })
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            addView(text("Files on the glasses (${items.size})", 15f, Color.WHITE, Typeface.BOLD), margin(0, 0, 0, 6))
            addView(list, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.48f).toInt()))
            addView(actions, margin(0, 8, 0, 6))
            addView(button("Cancel", false) { dialog.dismiss() })
        }
        dialog = AlertDialog.Builder(this).setView(container).create()
        dialog.show()
    }

    /** Second gate: nothing is removed until this dialog is accepted. */
    private fun confirmDelete(targets: List<MediaIndex.Item>) {
        AlertDialog.Builder(this)
            .setTitle("Delete from the glasses?")
            .setMessage("${targets.size} file(s), ${totalSizeLabel(targets)}, will be removed from the glasses permanently. " +
                "This cannot be undone. Files already saved on this phone are not affected.")
            .setPositiveButton("Delete ${targets.size} file(s)") { _: DialogInterface, _: Int -> runDelete(targets) }
            .setNegativeButton("Keep files", null)
            .show()
    }

    private fun runDelete(targets: List<MediaIndex.Item>) {
        if (busy) return
        setBusy(true, "Deleting on the glasses")
        append("Deleting ${targets.size} file(s) on the glasses. This is permanent.")
        val token = operationToken
        executor.execute {
            var accepted = 0
            for (item in targets) if (deleteRemote(item)) accepted++
            // A delete reply can be lost while the device is busy, so the index is the authority:
            // re-list and judge by what is actually still there rather than by the replies.
            val remaining = fetchGlassesIndex(null)?.map { it.remotePath }?.toSet()
            main.post {
                if (operationToken != token) return@post
                when {
                    remaining == null -> {
                        append("Deleted $accepted of ${targets.size} file(s). The glasses did not return a fresh list, so this is unconfirmed — tap the list button again to check.")
                        setBusy(false, "Deletes sent (unconfirmed)")
                    }
                    else -> {
                        val stillThere = targets.filter { it.remotePath in remaining }
                        val gone = targets.size - stillThere.size
                        if (stillThere.isEmpty()) {
                            append("Deleted ${targets.size} file(s) from the glasses — verified, none of them are listed any more.")
                            if (accepted < targets.size)
                                append("${targets.size - accepted} delete request(s) got no usable reply, but the files are gone.")
                            setBusy(false, "Deleted ${targets.size} file(s)")
                        } else {
                            append("Deleted $gone of ${targets.size} file(s). Still on the glasses: ${stillThere.joinToString { it.relativeName }}")
                            setBusy(false, "Some files remain")
                        }
                    }
                }
            }
        }
    }

    /** Single call site for deletion; [Endpoint.deleteUrl] decides the firmware dialect. */
    private fun deleteRemote(item: MediaIndex.Item): Boolean {
        val networks = runCatching { wifi.connectedWifiNetworks() }.getOrDefault(emptyList())
        for (net in networks) {
            for (endpoint in Endpoint.ALL) {
                val path = if (endpoint.legacyCgi && item.remotePath.contains('/')) item.localName else item.remotePath
                try {
                    if (DeviceParams.parseResult(httpGet(net, endpoint.deleteUrl(path), 8000))) return true
                } catch (_: Exception) { }
            }
        }
        return false
    }

    private fun sizeLabel(item: MediaIndex.Item): String {
        val bytes = item.expectedSize?.let {
            if (item.sizeUnit == MediaIndex.SizeUnit.KIB_FLOOR) it * 1024 else it
        } ?: return "size unknown"
        return "%.1f MB".format(bytes / 1048576.0)
    }

    private fun totalSizeLabel(items: List<MediaIndex.Item>): String {
        val bytes = items.sumOf {
            it.expectedSize?.let { size -> if (it.sizeUnit == MediaIndex.SizeUnit.KIB_FLOOR) size * 1024 else size } ?: 0L
        }
        return "%.1f MB".format(bytes / 1048576.0)
    }

    private fun offerToStartWifiForDelete() {
        AlertDialog.Builder(this)
            .setTitle("Glasses Wi-Fi is off")
            .setMessage("The file list and the delete commands come from the glasses themselves. Start their Wi-Fi and connect this phone now?")
            .setPositiveButton("Start Wi-Fi & list") { _: DialogInterface, _: Int -> startSettingsSession(SettingsTask.DELETE) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Read-only: asks the firmware for its storage capacity and logs the raw reply. */
    private fun probeCapacity() {
        if (busy) return
        if (selectedAddress == null) {
            append("Choose the glasses' Bluetooth device first; the capacity probe is only sent to a chosen device.")
            return
        }
        val missing = Permissions.missingBluetooth(this)
        if (missing.isNotEmpty()) {
            pendingCapacityProbe = true
            requestPermissions(missing, REQUEST_SETTINGS)
            return
        }
        val bt = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bt.adapter?.isEnabled != true) return fail("Bluetooth is off. Enable it to probe capacity.")
        // One-shot probe: a timeout must not be treated as a connection failure that
        // restarts the session and begins a download.
        styleOperation = true
        transferStarted = false
        setBusy(true, "Probing capacity")
        append("Asking the glasses for storage capacity over BLE (command 0x16). Read-only; nothing is recorded or deleted.")
        ble.probeCapacity(selectedAddress!!)
    }

    /** Prompt shown when a parameter write cannot reach the glasses because their AP is off. */
    private fun offerToStartWifiForParam(param: DeviceParam, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Glasses Wi-Fi is off")
            .setMessage("Changing ${param.name.replace('_', ' ')} needs the glasses' own Wi-Fi. Start it on the glasses and connect this phone now? Nothing will be downloaded.")
            .setPositiveButton("Start Wi-Fi & apply") { _: DialogInterface, _: Int ->
                pendingParam = param
                pendingParamPosition = position
                startSettingsSession(SettingsTask.APPLY)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun offerToStartWifiForLoad() {
        AlertDialog.Builder(this)
            .setTitle("Glasses Wi-Fi is off")
            .setMessage("The settings list is served by the glasses themselves. Start their Wi-Fi and connect this phone now? Nothing will be downloaded.")
            .setPositiveButton("Start Wi-Fi & load") { _: DialogInterface, _: Int -> startSettingsSession(SettingsTask.LOAD) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Opens the glasses' AP over BLE and joins it, only to read or write device settings. */
    private fun startSettingsSession(task: SettingsTask) {
        if (busy) return
        if (selectedAddress == null) {
            append("Choose the glasses' Bluetooth device first; their Wi-Fi can only be started over Bluetooth.")
            return
        }
        val missing = Permissions.missingBluetooth(this)
        if (missing.isNotEmpty()) {
            pendingSettingsTask = task
            requestPermissions(missing, REQUEST_SETTINGS)
            return
        }
        val bt = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bt.adapter?.isEnabled != true) return fail("Bluetooth is off. Enable it to start the glasses' Wi-Fi.")
        settingsTask = task
        transferStarted = false
        sessionRetries = 0
        setBusy(true, "Starting glasses Wi-Fi")
        append("Asking the glasses to start their Wi-Fi for this setting; no files will be transferred.")
        status.text = "Finding glasses"
        activeSsid = ""
        launchBleSession()
    }

    private fun deviceGetOn(network: Network?, path: String, timeoutMs: Int = 5000): String? {
        for (endpoint in Endpoint.ALL) {
            try { return httpGet(network, "http://${endpoint.host}$path", timeoutMs) } catch (_: Exception) { }
        }
        return null
    }

    private fun runSettingsLoad(network: Network?) {
        val token = operationToken
        executor.execute {
            val itemsReply = deviceGetOn(network, DeviceParams.itemsPath())
            val params = itemsReply?.let { runCatching { DeviceParams.parseItems(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val values = if (params.isEmpty()) emptyMap<String, Int>() else
                deviceGetOn(network, "/app/getparamvalue?param=all")?.let {
                    runCatching { DeviceParams.parseValues(it) }.getOrDefault(emptyMap())
                } ?: emptyMap()
            main.post {
                if (operationToken != token) return@post
                clearSettingsTask()
                if (params.isEmpty()) {
                    paramsStatusLine.text = "The glasses answered but listed no settings."
                    append("No settings returned by /app/getparamitems.")
                    finishOperation("Device settings unavailable")
                } else {
                    deviceParams = params
                    deviceParamValues = values
                    renderDeviceParams()
                    paramsStatusLine.text = "Loaded ${params.size} settings" +
                        if (values.isEmpty()) " (current values unknown)." else " with current values."
                    append("Device settings: ${params.joinToString { it.name }}")
                    // Keep BLE and Wi-Fi up: the next change then needs no extra round trip.
                    setBusy(false, "Device settings loaded")
                }
            }
        }
    }

    private fun runSettingsApply(network: Network?) {
        val param = pendingParam ?: return
        val position = pendingParamPosition
        val wire = param.wireValueAt(position)
        val label = param.items.getOrNull(position) ?: wire.toString()
        val token = operationToken
        executor.execute {
            val reply = deviceGetOn(network, DeviceParams.setPath(param.name, wire))
            val accepted = reply != null && runCatching { DeviceParams.setAccepted(reply) }.getOrDefault(false)
            val fresh = if (accepted) deviceGetOn(network, "/app/getparamvalue?param=all")?.let {
                runCatching { DeviceParams.parseValues(it)[param.name] }.getOrNull()
            } else null
            main.post {
                if (operationToken != token) return@post
                clearSettingsTask()
                reportParamWrite(param, label, reply, accepted, fresh)
                setBusy(false, if (accepted) "Setting applied" else "Setting not applied")
            }
        }
    }

    private fun reportParamWrite(param: DeviceParam, label: String, reply: String?, accepted: Boolean, fresh: Int?) {
        when {
            reply == null -> append("No answer from the glasses for ${param.name}.")
            !accepted -> append("The glasses refused ${param.name} = $label (reply: ${reply.trim().take(80)}).")
            fresh != null -> {
                deviceParamValues = deviceParamValues + (param.name to fresh)
                renderDeviceParams()
                append("Glasses now report ${param.name} = ${param.labelFor(fresh)}.")
            }
            else -> append("Glasses accepted ${param.name} = $label; the value could not be re-read.")
        }
    }

    private fun clearSettingsTask() {
        settingsTask = SettingsTask.NONE
        pendingParam = null
        pendingParamPosition = -1
    }

    private fun margin(l: Int, t: Int, r: Int, b: Int) =
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()
    private fun text(value: String, size: Float, color: Int, weight: Int = Typeface.NORMAL) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setTypeface(null, weight)
    }
    private fun edit(initial: String, hintText: String) = EditText(this).apply {
        setSingleLine(true); setText(initial); hint = hintText
        minimumHeight = dp(48)
        setTextColor(Color.WHITE); setHintTextColor(secondary)
        background = bg(Color.rgb(27, 37, 48), 12)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        minimumHeight = dp(48)
        setTextColor(if (primary) Color.rgb(18, 29, 35) else Color.WHITE)
        background = bg(if (primary) accent else Color.rgb(43, 57, 72), 7)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        setOnClickListener { action() }
    }
    private fun bg(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    override fun onDestroy() {
        cancelled.set(true)
        operations.close()
        if (busy) persistStatus("terminal", "Interrupted — reopen the app and retry")
        cancelWifiWarmup()
        cancelRecoveryTask()
        ble.close()
        wifi.close()
        executor.shutdownNow()
        journalExecutor.shutdown()
        super.onDestroy()
    }
}
