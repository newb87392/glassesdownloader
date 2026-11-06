package com.open.glassesdownloader

/** Independent implementation of the AA12/AA13/AA14 BLE framing used by these glasses. */
object Protocol {
    const val WIFI_INFO = 0x25
    const val VIDEO_LENGTH = 0x02
    const val DEVICE_STATUS = 0x48
    const val MIN_VIDEO_SECONDS = 60
    const val MAX_VIDEO_SECONDS = 900
    private const val OPEN_WIFI = 0x39
    private const val SOF_DEVICE_TO_APP: Byte = 0xAC.toByte()
    private const val SECOND_HEADER_BYTE: Byte = 0x55
    const val MAX_FRAME_BYTES = 1024

    /** 0x30 = AP, 0x31 = alternate P2P command. No delete or firmware commands are present. */
    fun openWifi(p2p: Boolean): ByteArray = request(OPEN_WIFI, byteArrayOf(if (p2p) 0x31 else 0x30))

    /**
     * Vendor "AP mode" job (command 0x63) — what the official app sends for live view and Wi-Fi OTA.
     * Used as a fallback when 0x39 is acknowledged but the AP never starts.
     */
    fun openWifiVendor(p2p: Boolean): ByteArray = request(0x63, byteArrayOf(if (p2p) 0x31 else 0x30))

    /**
     * Clip length the device stops at: command 0x02, seconds as two big-endian bytes.
     * The firmware offers 60..900 s, so out-of-range values are clamped rather than rejected.
     */
    fun videoLength(seconds: Int): ByteArray {
        val clamped = seconds.coerceIn(MIN_VIDEO_SECONDS, MAX_VIDEO_SECONDS)
        return request(VIDEO_LENGTH, byteArrayOf((clamped shr 8).toByte(), clamped.toByte()))
    }

    /** Command 0x16 / parameter 0: the vendor app's storage-capacity request. Read-only. */
    fun deviceCapacity(): ByteArray = request(0x16, byteArrayOf(0x00))

    /** Command 0x48 / parameter 0 asks the device to emit status, including clip length (0x02). */
    fun deviceStatus(): ByteArray = request(DEVICE_STATUS, byteArrayOf(0x00))

    /** Clip length from a 0x02 notification; null for other commands or implausible values. */
    fun videoLengthSeconds(response: Response): Int? {
        if (response.command != VIDEO_LENGTH || response.data.size < 2) return null
        val seconds = ((response.data[0].toInt() and 0xff) shl 8) or (response.data[1].toInt() and 0xff)
        return seconds.takeIf { it in MIN_VIDEO_SECONDS..7200 }
    }

    /** Experimental vendor command 0x35: 2K=0x30, 16:9=0x30. Hardware support unverified. */
    fun cameraStyle2k(): ByteArray = request(0x35, byteArrayOf(0x30, 0x30))
    fun request(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(command in 0..255)
        val n = payload.size + 2 // command byte + parameters + checksum byte
        require(n <= 65535)
        val output = byteArrayOf(0xAB.toByte(), 0x55, (n shr 8).toByte(), n.toByte(), command.toByte()) + payload
        return output + byteArrayOf(checksum(output, 4, output.size).toByte())
    }

    data class Response(val command: Int, val data: ByteArray)

    fun parseResponse(frame: ByteArray): Response? {
        if (frame.size < 6 || frame[0] != SOF_DEVICE_TO_APP || frame[1] != SECOND_HEADER_BYTE) return null
        val expected = ((frame[2].toInt() and 0xff) shl 8) or (frame[3].toInt() and 0xff)
        if (frame.size != expected + 4 || expected < 2) return null
        if (checksum(frame, 4, frame.lastIndex) != (frame.last().toInt() and 0xff)) return null
        return Response(frame[4].toInt() and 0xff, frame.copyOfRange(5, frame.lastIndex))
    }

    fun ssid(response: Response): String? {
        if (response.command != WIFI_INFO) return null
        val value = response.data.toString(Charsets.UTF_8).trimEnd('\u0000')
        return value.takeIf { it.isNotBlank() && it.length <= 32 }
    }

    fun ssid(frame: ByteArray): String? = parseResponse(frame)?.let { ssid(it) }

    private fun checksum(bytes: ByteArray, start: Int, endExclusive: Int): Int =
        (start until endExclusive).sumOf { bytes[it].toInt() and 0xff } and 0xff
}

/**
 * Reassembles device→app frames from BLE notifications.
 *
 * The firmware sends one frame per notification only once the MTU is large enough; at the
 * default 20-byte ATT payload a frame is split. Buffering on the `AC 55 | length` header makes
 * the parser independent of the negotiated MTU.
 */
class FrameAssembler(private val maxFrame: Int = Protocol.MAX_FRAME_BYTES) {
    private var buffer = ByteArray(64)
    private var size = 0

    fun feed(chunk: ByteArray): List<Protocol.Response> {
        append(chunk)
        val frames = mutableListOf<Protocol.Response>()
        while (true) {
            val start = findHeader()
            if (start < 0) {
                // A header can straddle two notifications: keep a trailing 0xAC.
                keepTrailing(if (size > 0 && buffer[size - 1] == 0xAC.toByte()) 1 else 0)
                break
            }
            if (start > 0) drop(start)
            if (size < 4) break
            val length = ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
            if (length < 2 || length > maxFrame) {
                drop(1) // Not a real header; resynchronise.
                continue
            }
            val total = length + 4
            if (size < total) break
            Protocol.parseResponse(buffer.copyOfRange(0, total))?.let { frames.add(it) }
            drop(total)
        }
        return frames
    }

    fun reset() {
        size = 0
    }

    private fun findHeader(): Int {
        var i = 0
        while (i + 1 < size) {
            if (buffer[i] == 0xAC.toByte() && buffer[i + 1] == 0x55.toByte()) return i
            i++
        }
        return -1
    }

    private fun append(chunk: ByteArray) {
        if (size + chunk.size > buffer.size) {
            val grown = ByteArray(maxOf(buffer.size * 2, size + chunk.size + 64))
            buffer.copyInto(grown, 0, 0, size)
            buffer = grown
        }
        chunk.copyInto(buffer, size)
        size += chunk.size
    }

    private fun drop(count: Int) {
        if (count <= 0) return
        val remaining = size - count
        if (remaining > 0) buffer.copyInto(buffer, 0, count, size)
        size = remaining
    }

    private fun keepTrailing(count: Int) {
        if (count == 0) {
            size = 0
        } else if (size > count) {
            buffer[size - count] = buffer[size - 1]
            size = count
        }
    }
}

/** Some devices expose improperly labeled HTTP chunk frames as plain text. */
object FileListCleaner {
    private val chunkLine = Regex("(?m)^[ \\t]*[0-9A-Fa-f]{1,8}[ \\t]*(?:\\r?\\n|\\z)")

    fun clean(raw: String): String {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        // Remove literal chunk-size lines, including when Android already stripped the first one.
        val out = trimmed.replace(chunkLine, "").trim()
        require(out.startsWith("{")) { "The response was not JSON (first 100 characters: ${trimmed.take(100)})" }
        return out
    }
}
