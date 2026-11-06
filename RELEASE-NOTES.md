# Release notes — 0.13.0

First public build. Android 8.0+ (API 26), `versionCode` 31.

## Download

| File | Size | SHA-256 |
| --- | --- | --- |
| `app-debug.apk` | 975,947 bytes | `bf1301a704c64e4e0459bc3a36ae98a76122c4d8d2c976f636933a43bf634f2c` |

This APK is built with the `debug` variant: it is installable and runs normally, but it is not signed
with a release key, so it cannot be published to Play and `adb install` may warn. Build from source with
`./gradlew :app:assembleDebug` if you would rather not trust a binary — the two are equivalent.

```bash
adb install -r app-debug.apk
```

## What's new

**Delete on the glasses — selectively or all at once.** The Manual pane lists what the device holds,
with sizes, and lets you tick files or take everything. A second dialog names the file count and total
size before anything happens. After the batch, the app re-reads the device index and reports what is
actually gone, so a dropped acknowledgement is never reported as a failed delete. There is no undo.

**Extra Settings tab.** Recording clip length from 1 to 15 minutes, written over BLE, echoed by the
device and read back in a fresh session; device parameters loaded and applied over HTTP; and a
resolution test mode. Settings that need the glasses' network now offer to start it and connect rather
than failing.

**Private gallery and receipts.** Media is stored in app-private storage with a SHA-256 receipt per
file, so a transfer can be checked long after it finished, and the gallery can re-verify itself.

**Resumable transfers.** Partial downloads continue from the last byte, with watchdogs around the
Bluetooth-to-Wi-Fi hand-off.

## Verified on hardware

- Clip length: set to 15 min over BLE, confirmed by the device, read back as 900 s in a new session, and
  the next recording measured 900.010 s.
- Delete-all of 31 files (2.9 GB): device index empty afterwards, all local copies still matching their
  receipts. Two delete replies were lost during the burst — which is what prompted the index-based
  verification described above.
- 174 JVM unit tests pass; the tree builds from a clean checkout with JDK 17 and SDK 35.

## Known issues

- The firmware does not report storage capacity; the app shows used space only and says so.
- BLE stops answering when the glasses sleep — wake them before connecting.
- Transfers run over the glasses' access point and are slow; a 15-minute clip can take several minutes.
- Tested on one CT01-family unit. Other rebrands may speak only the legacy CGI dialect.
- Deleting is permanent. Files already saved on the phone are untouched, but there is no recycle bin on
  the glasses.

## Permissions

Bluetooth scan/connect, Wi-Fi and network state, and `NEARBY_WIFI_DEVICES`; legacy location permissions
are capped at API 32 because Android required them for Wi-Fi scanning. No internet access to any vendor
service: the only host the app talks to is the glasses' own access point.

## Protocol

See `docs/PROTOCOL.md` for the BLE framing, command table, HTTP surfaces for both firmware dialects, and
the failure modes this app works around.
