# Glasses Downloader

An Android app that pulls photos, videos and audio off a CT01-class smart-glasses camera
("Star Glasses" and similar white-label hardware) **without the vendor's phone app and without
any cloud account**. It talks to the glasses directly: Bluetooth LE to wake their Wi-Fi access
point, then plain HTTP on their own network to list, download, verify and delete files.

Everything stays on the phone. The app never contacts the vendor's servers, has no analytics, and
keeps media in its own private storage rather than the shared gallery.

> Unofficial project. Not affiliated with, endorsed by, or supported by Watchfun, Star Glasses or
> any rebrand of this hardware. Use at your own risk — deleting files is permanent.

## What it does

- **Connect & download** over the glasses' access point, with resumable transfers and a size check.
  Unfinished downloads carry on where they stopped; a file is only accepted when the byte count matches
  the index and its SHA-256 matches the receipt.
- **Verify** — every stored file gets a SHA-256 receipt, and the gallery can re-check itself against them.
- **Private gallery** — photos, videos and audio in app-private storage, with an in-app viewer. No
  media scanner, no other app can read it, and uninstalling the app removes it.
- **Delete on the glasses**, selectively or all at once. Two gates: a checkbox list, then a dialog
  naming the file count and total size. After the batch the app re-lists the device and reports what is
  actually gone, rather than trusting per-file replies that the firmware sometimes drops.
- **Extra settings** — recording clip length (1–15 minutes, written over BLE and confirmed by the device),
  device parameters read/written over HTTP, and a resolution test mode.
- **Stays awake through hand-offs** — Bluetooth to Wi-Fi transitions, session recovery, watchdogs and a
  plain-language log you can read while it works.

## Requirements

- Android 8.0 (API 26) or newer. Shared-gallery export needs Android 10+.
- Bluetooth LE and Wi-Fi (both are required features in the manifest).
- A compatible pair of glasses: CT01-family hardware that exposes the BLE service below.

## Build

You need JDK 17 and Android SDK Platform 35 with build-tools. No SDK path is committed; point Gradle at
your own SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testDebugUnitTest    # 174 JVM tests
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Windows: `gradlew.bat` works the same way, or open the folder in Android Studio and press Run.
See `START-HERE.txt` for the click-by-click version.

## Using it

1. Power the glasses on. If they have been asleep, open the temples or hold the shutter for ~3 seconds
   until the LED blinks — that is the pairing state.
2. Open the app and tap **Connect & download**. It scans for the glasses, sends the BLE command that
   switches their Wi-Fi on, joins that network, and lists what is on the device.
3. Pick what you want, or take everything. Progress, retries and any hand-off problems appear in the log.
4. **Extra Settings** holds the things that are not transfers: clip length, device parameters, resolution test.
5. **FILES ON THE GLASSES** deletes on the device. Read the confirmation — it names the count and size,
   and it cannot be undone.

The glasses' access point uses a fixed vendor-default passphrase (`12345678`). It is the same on every
unit of this family; it is not an Android secret and this app stores nothing else.

## How it talks to the device

**Bluetooth LE.** Service `aa12`, characteristic `aa13`. Frames are `AC 55 <len> <cmd> <payload…> <checksum>`.
The app uses `0x39` (open Wi-Fi, with/without P2P), `0x25` (report SSID), `0x02` (clip length, seconds,
big-endian), `0x48` (device status burst) and `0x16` (capacity query, which this firmware ignores).
Replies arrive as notifications, sometimes as a burst of several commands; the parser only consumes the
value it asked for so later lines stay quiet.

**HTTP on the glasses' network.** The device is the access point (`192.168.169.1` on the units tested)
and serves a small JSON/REST surface:

| Purpose | Request |
| --- | --- |
| File index (T-series) | `GET /app/getfilelist` (chunked JSON: folder → files, name/size/time) |
| Delete (T-series) | `GET /app/deletefile?file=<FOLDER>/<name>` |
| Parameters | `GET /app/getparamitems?param=all`, `/app/getparamvalue?param=<n>`, `/app/setparamvalue?param=<n>&value=<v>` |
| File index (older firmware) | `GET /?custom=1&cmd=3015` (XML list) |
| Delete (older firmware) | `GET /?custom=1&cmd=4003&str=A:\DCIM\100HUNTI\<name>` |
| Supported commands | `GET /?custom=1&cmd=3012` |
| OTA trigger (unused) | `GET /?custom=1&cmd=5001&par=1` |

RTSP lives on port 554 on these units. This app does not use it.

Both dialects are built in one place (`Endpoint`), so no screen hand-writes a device URL. The delete path is
a test-pinned single call site.

## Privacy and security

- No account, no cloud, no vendor endpoints. The app's only network traffic is to the glasses' own access point.
- No analytics, no crash reporting, no advertising SDKs. The only dependencies are JUnit and `org.json` for tests.
- Media lives under the app's private `files/glasses-media/` with SHA-256 receipts in `files/media-receipts/`;
  nothing is written to shared storage unless you export it yourself.
- Permissions are limited to what the radio hand-off needs: `INTERNET`, `ACCESS_NETWORK_STATE`,
  `CHANGE_NETWORK_STATE`, Wi-Fi state, `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`, plus
  legacy location permissions capped at API 32 (Android required them for Wi-Fi scanning). None of them are
  used to read your location.
- The repository contains no keys, tokens, device identifiers or AP credentials. `local.properties`,
  keystores and downloaded media are ignored by `.gitignore`.

## Repository layout

```
app/src/main/java/com/open/glassesdownloader/   app code (BLE, Wi-Fi, HTTP, gallery, UI)
app/src/test/java/com/open/glassesdownloader/   174 JVM tests, incl. source-wiring guards
docs/PROTOCOL.md                                BLE framing, command table, HTTP surfaces, failure modes
samples/                                        redacted device replies used by the parsers
mockups/                                        HTML mockup of the tab layout
tools/                                          helper scripts for device diagnostics
```

## Known limitations

- Tested against one CT01-family unit, the IONCT with UPC 787603324169 (ASIN B0HFMB2MGN). Other rebrands of this hardware may answer only the legacy CGI
  dialect, or use a different BLE service.
- The glasses stop answering BLE once they sleep; wake them before connecting.
- The firmware does not report storage capacity (it answers `unsupport.`), so the app shows used space only.
- Transfers run over the glasses' access point, which is slow and occasionally drops a connection. The
  resumable downloader and watchdogs handle that, but a large clip can take a while.
- Clip length is firmware state and survives reconnects, but a firmware update could change the range.

## License

MIT — see `LICENSE`. Protocol details were established by observing this hardware and its companion app;
no vendor code is included.
