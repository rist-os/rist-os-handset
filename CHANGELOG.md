# Changelog

Build numbers are `YYYYMMDDNN`. The log starts with 2026083110, the first published build.

---

## 2026092200

**Device:** Pixel 10a (`stallion`) only. **Android security patch level:** 2026-09-01.
**Base:** GrapheneOS. **Download:** <https://dl.ristos.org/index.html>

Updating from 2026090701 raises the security patch level from 2026-08 to 2026-09. That moves the
verified-boot rollback index, and a handset that has booted this build cannot be put back on any
2026-08 or earlier image.

### New

- **Streaming replies.** A live status line while the assistant works, a Stop button, and turns of
  up to three minutes.
- **Push notifications** over a held connection to your backend, so messages arrive without waiting
  for the next check.
- **Video calls.** A join screen and a browser that can only be in the call. A Meet, Zoom or Teams
  link in a text gets a Join button, and the call closes itself when it ends.
- **QR codes.** Scan one with the camera to open that site, and only that site, in a locked browser.
- **Photos.** The camera button takes a photo or picks existing ones, and you can caption a photo
  before it is sent. Pictures the assistant sends stay on the feed, where you can zoom and save them.
- **Pinning and retention.** Double-tap an answer to keep it until you unpin it, and choose how long
  messages are kept.
- **Volume panel.** The volume buttons open separate Voice, ringer, vibrate and silent controls.
- **Automatic time zone,** set from the phone's location with an offline lookup.
- **Alarms** survive a reboot and an app update, and repeat as scheduled.
- **Replies render markdown,** including tables.
- **Maps** use 512-pixel tiles, credit OpenStreetMap on every map, and stay smooth when pinched far
  out.
- **Audiobooks** queue as the whole book, with chapters numbered on from the one playing.

### Fixed

- The assistant app crashed on every phone call.
- The phone could soft-reboot when the home app was stopped during a screen transition (framework
  patch 0008).

---

## 2026092200

Not written yet.

---

## 2026090701

Superseded by 2026092200.

---

## 2026090600

Superseded by 2026090701.

## 2026083110

Superseded by 2026090701.
