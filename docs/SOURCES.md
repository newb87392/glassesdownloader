# Sources

What this project's claims rest on, so a reader can check them. Nothing here is copied code: the app is
an independent implementation, and the protocol notes in `docs/PROTOCOL.md` describe observed wire
behaviour.

## 1. Primary evidence

| Source | What it established |
| --- | --- |
| **[Eyevue E09 protocol reference](https://github.com/sctg-development/ai-smart-glasses-e09/blob/main/REVERSE_ENGINEERING_EYEVUE_BLE_PROTOCOL.md)** — sctg-development, *Eyevue Smart Glasses (E09) — BLE Protocol & AI Assistant Developer Reference*, sections 2, 4, 5, 10 and Appendix A | The starting map for this hardware family, and the baseline this project was checked against: GATT `AA12`/`AA13`/`AA14`, the `AB 55`/`AC 55` framing with its 8-bit checksum, `OPEN_WIFI` `0x39` in AP mode, the `0x25` raw-SSID reply, the fixed AP passphrase, the `/app/getfilelist` JSON shape, and that a file's `name` already carries its folder prefix so the download URL is just `http://<glasses>/<name>`. Their `0x16` capacity reply is documented as a 2-byte payload "unit unconfirmed" — on this unit it is never sent at all. Their documented delete route is the BLE finish command (`0x44`, `value2 = 0x00` CLEAR); this project instead deletes over HTTP (`/app/deletefile`), which is what the hardware here actually honours, and their observed folder set (`EVENT`, `AAC`) is confirmed and extended here (`LOOP`, `EMR`). |
| **[ai-smart-glasses-e09-reverse](https://github.com/sctg-development/ai-smart-glasses-e09-reverse)** — sctg-development, MIT (© 2026 Ronan Le Meillat, SCTG Development) | An independent Swift/macOS implementation of the same protocol; used to cross-check the service UUIDs, the AP-mode command and local media transfer. No code from it is included here, and its MIT terms are compatible with this project's anyway. |
| **The glasses themselves** — a CT01-family unit (BLE name `CT01-XXXXXX`), firmware as shipped, driven over BLE and its own HTTP access point | Everything in `docs/PROTOCOL.md` as verified on this hardware: each frame and response shape, that `0x02` clip length persists and changes the next recording, that the capacity probe is unanswered, that `/app/deletefile` works, and that a delete acknowledgement can be dropped while the file is still removed. |
| **Vendor companion app** `com.starglasses.app`, version 1.0.23 — [Google Play listing](https://play.google.com/store/apps/details?id=com.starglasses.app), mirrored on [APKPure](https://apkpure.com/star-glasses/com.starglasses.app) | Which BLE commands the vendor app sends, which device-local HTTP paths it uses (`/app/getfilelist`, `/app/deletefile`, `/app/getparamitems`, the legacy `?custom=1&cmd=` CGI), and that it has no parser for the capacity reply. Used as a *behavioural reference only* — no code, resource, asset or string table from it is included in this repository, and no decompiled output is redistributed. |

Where the two disagree, the hardware wins: the documentation says what the device actually answers, not
what the vendor app expects.

## 2. Published references

| Source | Used for |
| --- | --- |
| Randhana, *[Reverse-Engineering Car Dashcam: Unlocking Its Hidden RTSP Feed Without Flashing Anything](https://www.randhana.com/blog/reverse-engineering-my-dashcams-hidden-rtsp-stream)* | Background on the sibling `/app/…` REST family on the same `192.168.169.1` address — `/app/enterrecorder` as the call that opens TCP/554, the `track1`/`track2` layout, `switchcam`. None of these endpoints is called by this app; the article is cited because it explains why this app avoids them and why port 554 is closed until woken. |
| Android Developers, [`BluetoothGatt`](https://developer.android.com/reference/android/bluetooth/BluetoothGatt) | Descriptor-based notifications (`2902` CCCD), MTU negotiation, connection-state handling. |
| Android Developers, [`WifiNetworkSpecifier`](https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier) | Joining the glasses' access point on API 29+ without registering a persistent network; `WifiNetworkSuggestion` and `WifiManager` config are used on older releases. |

## 3. Specifications

| Specification | Where it matters |
| --- | --- |
| RFC 7230 §4.1, *Chunked Transfer Coding* | The glasses answer HTTP/1.0 with `Transfer-Encoding: chunked` and no `Content-Length`; responses are de-chunked before parsing. |
| FIPS 180-4, *Secure Hash Standard* (SHA-256) | Per-file receipts and gallery verification, via `java.security.MessageDigest`. |
| [Bluetooth Core Specification](https://www.bluetooth.com/specifications/specs/core-specification-5-4/) (5.4), GATT — services, characteristics, CCCD `0x2902` | The `aa12`/`aa13`/`aa14` layout and notification subscription. |

## 4. Dependency metadata checked for licensing

| Component | Licence source |
| --- | --- |
| JUnit 4.13.2 | [POM](https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom) — Eclipse Public License 1.0 |
| org.json 20240303 | [POM](https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.pom) — Public Domain |
| Kotlin stdlib 2.0.21 | [POM](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom) — Apache License 2.0 |
| Gradle 8.9 wrapper | Apache-2.0, text embedded in `gradle-wrapper.jar` (`META-INF/LICENSE`) |

See `THIRD-PARTY-LICENSES.md` for what ships where and how each obligation is met.

## 5. Legal position

- **Independent implementation.** The code in this repository was written from observed behaviour —
  requests and replies on a device the author owns, plus the vendor app's own network traffic. No
  vendor source, decompiled class, resource or asset is redistributed here.
- **Interoperability.** Studying a program's behaviour to build a compatible one is a recognised purpose
  in several jurisdictions (for example DMCA §1201(f) in the United States, and Art. 6 of Directive
  2009/24/EC in the EU). The connected use — pulling your own photos off your own camera — is the
  ordinary purpose of the hardware.
- **Nothing is circumvented.** There is no DRM, no authentication bypass and no key extraction here: the
  glasses' access point password is a fixed vendor default printed nowhere secret, and every endpoint
  used is unauthenticated by design. Firmware is never modified or flashed.
- **Trademarks.** "Star Glasses", "Watchfun" and any product names are used descriptively to say what
  this software interoperates with. No affiliation or endorsement is claimed; see the notice at the top
  of `README.md`.
- **Your risk.** Not legal advice. Local rules on reverse engineering differ; if you plan to redistribute
  or sell a build, take your own advice on that jurisdiction.

## 6. Corrections

If a claim here does not reproduce on your hardware, the claim is wrong and the fix belongs in
`docs/PROTOCOL.md` — describe what your unit answers, including the firmware version and how you woke it.
