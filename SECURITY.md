# Security

## Reporting a vulnerability

Use GitHub's private vulnerability reporting: the *Security* tab on this repository, then *Report a
vulnerability*. There is no reporting email address.

Include a description and, ideally, a way to reproduce it. Do not open a public issue for anything
that would let someone read another user's data, take over a device, or interfere with emergency
calling. You get confirmation of receipt and an answer on whether we intend to fix it. There
is no bug bounty.

---

## Threat model

The device is a single-purpose appliance: one platform-signed app is the launcher and Device Owner,
running a LockTask kiosk against a backend the operator runs.

**In scope:** remote attacks over the network or the cellular interfaces (calls, SMS); kiosk escape;
disclosure of transcripts, message content, voicemail or the backend credential; anything that
degrades emergency calling.

**Out of scope:** an attacker with the unlock code, physical access over time to an unlocked device,
or a malicious operator — the operator runs the backend and can see everything that reaches it.

---

## Accepted risks

**Static bearer token over TLS.** Device-to-backend auth is a long-lived bearer token, issued at
enrolment and sent on every request over TLS — not mutual TLS, not rotating, no request signing. It
lives in Keystore-backed encrypted preferences and is never logged. *Residual:* whoever extracts it
can impersonate the device until it is rotated.

**Pairing codes.** A typed pairing code is redeemed over HTTPS; the SMS-nonce route still exists on
the server but is not triggered by the device. The token never travels by text.

**Caller-ID matching on the bridge.** The backend tells the device which number will ring and for how
long, and the device auto-answers only a matching call in that window. *Residual:* a PSTN call
carries no correlation identifier, so a genuine call from the same number in the window is also
answered. A short window and consuming the arm on first match bound it; it cannot be eliminated
device-side.

**Voicemail caller attribution.** The message-waiting signal carries no identity, so the device
infers the caller from the most recent missed call. **It can be wrong.**

---

## Known gaps

- **Emergency calling is unvalidated.** See `SAFETY.md`.
- **Duress unlock is the GrapheneOS one, and it destroys rather than hides.** A duress PIN or
  password, set in Settings -> Security & privacy -> Device unlock -> Duress password and entered
  instead of the real credential at any prompt, crypto-erases the device and powers it off in about a
  second, taking the backend `auth_token` with it. Rist does not implement it.
  Two residual gaps: there is no *hiding* duress, so a compelled user can only destroy the phone, not
  open a limited view; and it is not concealed, so anyone who opens Settings sees the row and whether
  this handset has one set. It only works where a credential is asked for, so it does nothing on a
  handset with no screen lock (`KioskManager.duressCredentialIsReachable`, pinned by
  `DuressReachabilityTest`). **Not verified on hardware:** that a device comes back up usable after a
  duress wipe on our image.
- **Developer options and adb are enabled on first boot** by `aosp/init/rist-provision-do.sh`.
  They are not re-forced later: turn USB
  debugging off and it stays off. A handset with no screen lock — the default, since the keyguard
  follows the user's own PIN — needs only physical access and a cable for an authorized adb shell.
  `com.android.systemui` is allowlisted in lock task so the adb authorization dialog can be answered.
- **Two Device Owner restrictions are deliberately not set.** Applied and read back:
  `DISALLOW_SAFE_BOOT`, `DISALLOW_ADD_USER`, `DISALLOW_INSTALL_UNKNOWN_SOURCES`,
  `DISALLOW_UNINSTALL_APPS`. Not applied: `DISALLOW_FACTORY_RESET`, `DISALLOW_DEBUGGING_FEATURES`.
- **Some personal identifiers appear in logs.** Credentials are masked everywhere, dialled numbers
  are masked (`Redact.maskNumber` in `DeviceCommands`), and outbound message bodies are logged as a
  character count rather than content.
- **The backend endpoint editor is always available on the public build.** Changing the host clears
  the stored token, so a repoint cannot carry an existing credential elsewhere.

- **On the bring-your-own-backend build, an attacker with physical access can point the device at
  their own server, and the pairing flow will then send a code there.**

  The chain needs physical access to an unlocked device (this build ships with no lock screen and
  adb enabled, both documented above), and the harvested code is redeemable only against the user's
  own backend — for a self-hosted deployment that may be unreachable to the attacker.

  If you run the bring-your-own build, treat physical access to an unlocked handset as sufficient to
  compromise pairing, and check the server address in Settings before entering a code you did not
  expect to need.

---

## Signing keys

**If you flashed a published image**, it is signed with the RistOS release key: you generate nothing
and hold nothing. **If you build the OS yourself**, you sign it with keys you generate. No key
material ships with this project.

### The release signing key

Releases are signed with minisign. The public half is published here, in the source repository,
deliberately **not** on the server the downloads come from.

```
RWThr8fGz/71qbSM4R8F9UvI7KYW++0i8dAQwyU+Fz24xqirceHqqHUX
```

Verifying is optional; the commands are under "Verifying the download" in `image/INSTALL.md`, and
`tools/check_published_release.sh` runs the same check against the live bucket.

If you relock the bootloader with your own keys, those keys become the only thing that can produce a
bootable image for that device. Back them up off-machine and encrypt them at rest.
