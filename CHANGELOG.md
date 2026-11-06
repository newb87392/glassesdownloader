# Changelog

All notable changes to this app. Versions before 0.13.0 were local development builds.

## 0.13.0

### Added
- Selective and all-at-once deletion of files on the glasses, reached from the Manual pane.
  A checkbox list picks files; a second dialog names the count and total size before anything is
  removed. After the batch the app re-lists the device and reports what is actually gone, so a lost
  reply from a busy device is never reported as a failed delete.
- Extra Settings tab: recording clip length from 1 to 15 minutes (written over BLE, read back and
  confirmed by the device), device parameters loaded and applied over HTTP, and a resolution test mode.
- "Start Wi-Fi & apply / load" prompt, so a setting that needs the glasses' network offers to bring it
  up instead of failing.
- Capacity probe (read-only, BLE `0x16`). This firmware does not answer it; the app says so plainly.

### Changed
- Log pane always follows new lines.
- Device URLs are built in one place for both firmware dialects, and the activity never hand-writes
  one — destructive paths are a single, test-pinned call site.

### Fixed
- Clip-length parsing no longer floods the log with "ignored" lines after the value arrives.
- A failed capacity probe can no longer fall through into a reconnect and start a download.
