package com.open.glassesdownloader

import org.json.JSONArray
import org.json.JSONObject

/** One toggleable setting exposed by the glasses' own HTTP API (`/app/getparamitems`). */
data class DeviceParam(val name: String, val items: List<String>, val values: List<Int>) {
    /** Human label of the option the device currently reports, or an em dash when unknown. */
    fun labelFor(value: Int?): String {
        val at = values.indexOf(value)
        return if (at >= 0) items.getOrElse(at) { value.toString() } else "—"
    }

    /** Value to send back for the option at [position] (falls back to the position). */
    fun wireValueAt(position: Int): Int = values.getOrElse(position) { position }
}

/**
 * Pure parser and URL builder for the hardware settings API of this glasses family.
 *
 * Deliberately limited to the three parameter endpoints: nothing here can express a record,
 * delete or format request, so the settings screen cannot reach one by accident.
 */
object DeviceParams {
    fun itemsPath(): String = "/app/getparamitems?param=all"

    fun valuePath(name: String): String = "/app/getparamvalue?param=$name"

    fun setPath(name: String, value: Int): String = "/app/setparamvalue?param=$name&value=$value"

    fun itemsUrl(host: String): String = "http://$host${itemsPath()}"

    fun valueUrl(host: String, name: String): String = "http://$host${valuePath(name)}"

    fun setUrl(host: String, name: String, value: Int): String = "http://$host${setPath(name, value)}"

    /** Parses `{"result":0,"info":[{"name":…,"items":[…],"index":[…]}]}`. */
    fun parseItems(text: String): List<DeviceParam> {
        val root = JSONObject(FileListCleaner.clean(text))
        val info = root.optJSONArray("info") ?: return emptyList()
        val out = mutableListOf<DeviceParam>()
        for (i in 0 until info.length()) {
            val obj = info.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (!name.matches(Regex("[a-z0-9_]{2,32}"))) continue
            val items = obj.optJSONArray("items") ?: continue
            if (items.length() == 0) continue
            val index = obj.optJSONArray("index")
            val labels = (0 until items.length()).map { items.optString(it) }
            val wire = (0 until items.length()).map { position ->
                if (index != null && position < index.length()) index.optInt(position) else position
            }
            out.add(DeviceParam(name, labels, wire))
        }
        return out
    }

    /** Parses a single-value reply `{"result":0,"info":{"value":2}}` (or a bare number). */
    fun parseValue(text: String): Int? {
        val info = JSONObject(FileListCleaner.clean(text)).opt("info")
        return when (info) {
            is JSONObject -> if (info.has("value")) info.optInt("value") else null
            is Number -> info.toInt()
            else -> null
        }
    }

    /**
     * Parses an all-values reply. This firmware answers
     * `{"result":0,"info":[{"name":"mic","value":1},…]}`; a name→value map is also accepted.
     * Per-name getters are not usable here — `/app/getparamvalue?param=timelapse_rate` returns
     * an empty body, so every read goes through `param=all`.
     */
    fun parseValues(text: String): Map<String, Int> {
        val info = JSONObject(FileListCleaner.clean(text)).opt("info") ?: return emptyMap()
        val out = mutableMapOf<String, Int>()
        when (info) {
            is JSONArray -> for (i in 0 until info.length()) {
                val entry = info.optJSONObject(i) ?: continue
                val name = entry.optString("name").trim()
                if (name.isNotEmpty()) out[name] = entry.optInt("value", -1)
            }
            is JSONObject -> {
                for (key in info.keys()) {
                    when (val value = info.opt(key)) {
                        is Number -> out[key] = value.toInt()
                        is JSONObject -> if (value.has("value")) out[key] = value.optInt("value")
                    }
                }
                out.remove("value")
            }
        }
        return out
    }

    /** Any reply of the form `{"result":0,…}` means the device accepted the request. */
    fun parseResult(text: String): Boolean =
        JSONObject(FileListCleaner.clean(text)).optInt("result", -1) == 0

    /** `{"result":0}` means the device accepted the write; anything else is a refusal. */
    fun setAccepted(text: String): Boolean = JSONObject(FileListCleaner.clean(text)).optInt("result", -1) == 0
}
