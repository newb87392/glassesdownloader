package com.open.glassesdownloader

/**
 * A media API exposed by one glasses firmware family.
 *
 * The two families are not the same API on two addresses: the T-series serves `/app/...` from
 * the access point the phone joins, while the older family answers the `?custom=1&cmd=` CGI on a
 * Wi-Fi Direct address and keeps its files under `DCIM/100HUNTI/`. Kept free of Android types so
 * the URL rules can be unit tested.
 */
data class Endpoint(
    val host: String,
    val indexPath: String,
    val legacyCgi: Boolean,
    val label: String
) {
    val sizeUnit: MediaIndex.SizeUnit
        get() = if (legacyCgi) MediaIndex.SizeUnit.BYTES else MediaIndex.SizeUnit.KIB_FLOOR

    fun indexUrl(): String = "http://$host$indexPath"

    /**
     * The T-series reports full relative paths (`EVENT/2026….jpg`); the older firmware reports
     * bare file names under `DCIM/100HUNTI/`.
     *
     * [deleteUrl] removes a file permanently. The T-series takes `/app/deletefile?file=<path>`;
     * the older firmware takes the vendor CGI with a Windows-style path. Both are built the way
     * the vendor app builds them so the device sees the expected shape.
     */
    fun deleteUrl(remotePath: String): String = if (legacyCgi)
        "http://" + host + "/?custom=1&cmd=4003&str=A:" + BACKSLASH + "DCIM" + BACKSLASH +
            "100HUNTI" + BACKSLASH + remotePath
    else "http://$host/app/deletefile?file=$remotePath"

    fun fileUrl(remotePath: String): String {
        val path = if (legacyCgi && !remotePath.contains('/')) "DCIM/100HUNTI/$remotePath" else remotePath
        return "http://$host/$path"
    }

    companion object {
        private const val BACKSLASH = "\\"

        val ALL = listOf(
            Endpoint("192.168.169.1", "/app/getfilelist", false, "T-series (AP)"),
            Endpoint("192.168.49.207", "/?custom=1&cmd=3015", true, "older firmware (P2P CGI)")
        )
    }
}
