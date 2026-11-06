package com.open.glassesdownloader

/** Main-thread-only discovery model. Published entries are immutable, including their identity. */
class PickerDevices {
    data class Entry(val name: String, val address: String, val rssi: Int, val paired: Boolean, val connected: Boolean) {
        val statusLabel: String get() = when {
            connected && paired -> "connected (GATT) · paired"
            connected -> "connected (GATT)"
            paired -> "paired"
            else -> "nearby"
        }
    }

    private val entries = LinkedHashMap<String, Entry>()

    fun update(name: String, address: String, rssi: Int, paired: Boolean, connected: Boolean) {
        val previous = entries[address]
        entries[address] = Entry(
            name.takeIf { it.isNotBlank() } ?: previous?.name ?: "(no name)",
            address, rssi, paired || previous?.paired == true, connected || previous?.connected == true
        )
    }

    fun snapshot(): List<Entry> = entries.values.sortedWith(
        compareByDescending<Entry> { it.connected }.thenByDescending { it.paired }
            .thenByDescending { it.rssi }.thenBy { it.address }
    )

    fun clear() = entries.clear()
}
