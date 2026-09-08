# Changelog

Build numbers are `YYYYMMDDNN`. The log starts with 2026083110, the first published build.

---

## 2026090701

**Device:** Pixel 10a (`stallion`) only. **Android security patch level:** 2026-08-05.
**Base:** GrapheneOS. **Download:** <https://dl.ristos.org/index.html>

### What works

- Push-to-talk assistant as the sole launcher, with a fixed app row: phone, messages, camera,
  gallery, offline maps (Organic Maps, bundled), settings.
- Telephony — calls, a full-screen incoming-call UI, SMS, carrier voicemail surfaced in the dialer.
- Turn-by-turn navigation, backend-rendered tiles over a dependency-free on-device C nav core.
- Timers, alarms and calendar; media playback; camera and torch.
- Device-Owner kiosk with a lock-task allowlist.
- Verified boot re-locked onto the RistOS AVB key shipped in the download, so the phone boots only
  images signed with it. A build you make and sign yourself locks onto your key instead.

### What does not

- **Emergency calling is not validated.** There is no E911 row in the bring-up checklist and no
  verification step has been run. See `SAFETY.md`.
- **Wireless Emergency Alert text may not display.** The siren, vibration and speech fire; whether
  the words render over the kiosk has not been confirmed on a handset.
- **Location is GNSS-only by default.** The phone asks you once and you can change the answer.
- **Updates.** The phone checks for updates every six hours and offers one when it finds it, but
  the phone installing an update by itself has not been verified end to end. The trusted path is
  `adb sideload` from recovery, which applies a signed package and does **not** wipe the phone.
- **The published image is not reproducible from this repository.**
- **No app store; arbitrary apps cannot be installed.**

### Verifying the download

`SHA256SUMS` covers every file; `SHA256SUMS.minisig` signs that list. The public key is in `SECURITY.md`.

```sh
# macOS: brew install minisign  |  Debian/Ubuntu: apt install minisign
minisign -Vm SHA256SUMS -P RWThr8fGz/71qbSM4R8F9UvI7KYW++0i8dAQwyU+Fz24xqirceHqqHUX
sha256sum --ignore-missing -c SHA256SUMS   # macOS: shasum -a 256 --ignore-missing -c SHA256SUMS
```

### Source

The kernel this build ships is published as source in the same directory as the image, no request
needed. The GPL/LGPL userspace components are available under the written offer in `NOTICE`.

---

## 2026090600

Superseded by 2026090701.

## 2026083110

Superseded by 2026090701.
