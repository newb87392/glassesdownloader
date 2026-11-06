# Device protocol

How these glasses are driven, written down so the app does not have to be read to understand it.
Everything here was established by watching a CT01-family unit answer its own companion app; no
vendor code is included and nothing is copied from a decompiled binary beyond the wire format itself.

Two radios are involved:

| Role | Transport | Default address |
| --- | --- | --- |
| Control, wake-up | Bluetooth LE GATT | device is discovered by name, e.g. `CT01-XXXXXX` |
| Media, settings | HTTP over the glasses' Wi-Fi access point | `192.168.169.1` (T-series), `192.168.49.207` (older firmware) |

The glasses are the access point. Their SSID and passphrase are fixed by firmware; the passphrase is
the vendor default `12345678` on every unit of this family.

## 1. Bluetooth LE

```
service        0000aa12-0000-1000-8000-00805f9b34fb
write char     0000aa13-0000-1000-8000-00805f9b34fb
notify char    0000aa14-0000-1000-8000-00805f9b34fb
```

Commands are written to `aa13`; the device answers as notifications on `aa14` (subscribe via the
standard `2902` descriptor). A notification is not guaranteed to contain a whole frame, and several
frames can arrive back to back, so the reader buffers bytes and reassembles: it scans for the header,
waits until the declared length has arrived, validates the checksum, and only then emits a frame.
Frames are capped at 1024 bytes.

### Frame format

App → device:

```
AB 55 LEN_HI LEN_LO CMD [payload …] CHECKSUM
```

Device → app:

```
AC 55 LEN_HI LEN_LO CMD [payload …] CHECKSUM
```

- `LEN` counts `CMD` + payload + the checksum byte, so a bare command is `00 03`.
- On the device→app side the same `LEN` is the whole frame size minus the four header bytes.
- `CHECKSUM` is the 8-bit sum of every byte from `CMD` to the end of the payload, modulo 256.

### Commands used

| Cmd | Direction | Meaning | Payload | Example frame |
| --- | --- | --- | --- | --- |
| `0x39` | app → device | start the access point | `30` = AP, `31` = P2P variant | `AB 55 00 03 39 30 69` |
| `0x63` | app → device | vendor "AP mode" job, used as a fallback when `0x39` is acked but no AP appears | `30` / `31` | `AB 55 00 03 63 30 93` |
| `0x25` | device → app | reports the SSID it is broadcasting | ASCII SSID, up to 32 bytes | `AC 55 00 0F 25 …` |
| `0x02` | both | clip length in seconds, big-endian; write to set, read from status | 2 bytes | 900 s → `AB 55 00 04 02 03 84 89` |
| `0x48` | app → device | request a status burst | `00` | `AB 55 00 03 48 00 48` |
| `0x16` | app → device | storage capacity request | `00` | `AB 55 00 03 16 00 16` |
| `0x35` | app → device | resolution/style toggle, `30 30` = 2K | 2 bytes | `AB 55 00 04 35 30 30 95` |

Notes that cost time to learn:

- **A status request answers with a burst**, not one frame. A `0x48` typically produces `0x01`, `0x02`,
  `0x04`, `0x06` and `0x07` in quick succession. A reader that expects a single reply either shows the
  wrong value or never returns; this app consumes the frames until the one it asked for arrives, then
  ignores the rest without logging noise.
- **`0x02` is real state, not UI.** A written value is echoed back, survives a disconnect and a fresh
  session reads the same number, and the next recording matches it (a 900 s setting produced a
  900.010 s clip).
- **`0x16` gets no answer on this firmware.** The vendor app sends the question and has no parser for
  the reply, which is consistent with the HTTP side answering `unsupport.`
- Some units need `0x63` rather than `0x39` before the access point comes up. The app tries `0x39`
  first and falls back.

## 2. HTTP on the glasses' access point

The device serves a small REST surface. Responses are HTTP/1.0 with `Transfer-Encoding: chunked` and
no `Content-Length`, so a client must de-chunk rather than read to EOF; the app strips the chunk
framing before parsing.

### T-series (`/app/...`)

| Request | Response |
| --- | --- |
| `GET /app/getfilelist` | `{"result":0,"info":[{"folder":"EVENT","files":[…],"count":4},{"folder":"AAC",…},{"folder":"LOOP",…},{"folder":"EMR",…}]}` |
| `GET /app/deletefile?file=<FOLDER>/<name>` | `{"result":0,…}` on acceptance |
| `GET /app/getparamitems?param=all` | 16 parameter entries, each with name, current value and allowed values |
| `GET /app/getparamvalue?param=<name>` | `{"result":0,"info":{"value":<n>}}` |
| `GET /app/setparamvalue?param=<name>&value=<v>` | `{"result":0,…}` |
| `GET /app/getdeviceattr` | device attribute block |
| `GET /app/getstorageinfo` | `{"result":98,"info":"unsupport."}` on this firmware |
| `GET /app/capability` | capability list |

A file entry carries `name`, `size`, `createtimestr` (`yyyyMMddHHmmss`) and `type` (1 = photo,
2 = video, 3 = audio). Media itself is fetched by plain path: `GET /LOOP/<name>`, `GET /EVENT/<name>`.

`count` is authoritative per folder, but `files` is what the app parses; the two agree on this
firmware. An empty device answers `{"result":0,"info":[{"folder":"EVENT","files":[],"count":0},…]}`.

### Older firmware (CGI)

| Request | Response |
| --- | --- |
| `GET /?custom=1&cmd=3015` | XML `<LIST><ALLFile><FileCount>N</FileCount><File>…</File></ALLFile></LIST>` |
| `GET /?custom=1&cmd=4003&str=A:\DCIM\100HUNTI\<name>` | delete one file |
| `GET /?custom=1&cmd=3012` | list of supported commands |
| `GET /?custom=1&cmd=3001&par=1` | start recording (not used by this app) |
| `GET /?custom=1&cmd=5001&par=1` | Wi-Fi OTA trigger (not used) |

Files live at `/DCIM/100HUNTI/<name>`; RTSP is on port 554 and is not used here.

## 3. Failure modes worth knowing

- **A delete acknowledgement can vanish.** During a burst of 31 deletes the device dropped two replies
  and sent empty bodies instead, although both files were gone. Replies are therefore advisory: after a
  batch the app re-reads `/app/getfilelist` and reports what is actually still present. Treat the index
  as the source of truth, never the per-file ack.
- **Empty body with `200 OK`** happens when the device is busy; it is not proof of failure.
- **BLE goes quiet when the unit sleeps.** Wake it (open the temples, or hold the shutter ~3 s until the
  LED blinks) before connecting; retrying a sleeping unit just produces timeouts.
- **The access point is slow** and drops connections. Downloads resume by byte offset, and a file is only
  accepted when the received size matches the index and its SHA-256 matches the receipt.
- **Capacity is not available.** Both the BLE probe and `/app/getstorageinfo` refuse on this firmware, so
  used space can only be derived from the file index.

## 4. Deliberately not implemented

- **Firmware flashing.** There is no USB data path on these units (the port is charge-only); the vendor
  updates over BLE DFU or Wi-Fi OTA. Nothing here touches either.
- **Live view.** RTSP exists on the device but is out of scope.
- **Remote record trigger.** `/app/enterrecorder` and `param=rec` exist; this app does not call them.

## 5. Prior art, and where this differs

An independent write-up for the same hardware family exists: sctg-development's
[Eyevue E09 BLE protocol reference](https://github.com/sctg-development/ai-smart-glasses-e09/blob/main/REVERSE_ENGINEERING_EYEVUE_BLE_PROTOCOL.md),
with a MIT-licensed Swift implementation in
[ai-smart-glasses-e09-reverse](https://github.com/sctg-development/ai-smart-glasses-e09-reverse). It is the
baseline this document was checked against — service UUIDs, framing, `0x39`, `0x25`, the passphrase and the
`/app/getfilelist` shape all agree.

Three places where this unit differs, all confirmed on hardware:

| Topic | E09 reference | This unit |
| --- | --- | --- |
| Deleting media | BLE `0x44` FILE_DOWNLOAD_FINISH with `value2 = 0x00` (CLEAR) after a fully successful transfer | `GET /app/deletefile?file=<FOLDER>/<name>` over the access point; selectively or all at once, independent of any transfer |
| Storage capacity | `0x16` answers with a 2-byte big-endian value ("unit unconfirmed") | No reply to `0x16`, and `/app/getstorageinfo` returns `unsupport.` |
| Media folders | `EVENT` (photos) and `AAC` (memos); a video folder was suspected but not captured | `EVENT`, `AAC`, `LOOP` (video) and `EMR` all present and enumerated |

Treat the tables above as the current state of knowledge for this firmware; where the two disagree, the
device wins.

## 6. How this was derived

By observing one unit: the companion app's own traffic, the BLE frames it writes, the HTTP requests it
makes to the glasses (which are on the glasses' own network, not the vendor's cloud), and by testing
writes against the hardware — for example setting a clip length and then checking the length of the next
recording. Every finding was also checked against the published E09 reference in §5 — and where that,
the vendor app and the device disagreed, the device won and this document says so.
