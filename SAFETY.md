# Safety — read this before flashing

This project replaces the entire operating system on a phone. 

This is a pre-release, experimental operating system that has not been fully tested yet. There may be problems with emergency calling and other essential features on the OS, so use it at your own risk. 

---

## What may not work

Treat all of this as unproven on this OS:

- **Emergency calling.** Calls to 911 may not connect or may not carry your location correctly.
  Asking the assistant to call for help does not place the call — dial from the phone app.
- **Location in an emergency.** Network location is off unless you turn it on. Without it the phone
  falls back to GNSS, which indoors can take tens of seconds and be tens of metres out. If you do
  turn it on, those lookups go through `loc.ristos.org`, a relay **Rist runs**, which sees your IP
  address but not the network identifiers being looked up.
- **Wireless Emergency Alerts.** The siren and vibration fire; the alert text may not appear.

---

## Certification and carrier terms

The hardware is a certified Google Pixel and its **radio firmware is untouched** by this project —
modem, RF and the certified module are stock. What changes is the application-processor OS.

**Those stock files are in the download, and we are the ones handing them to you.** A published
RistOS image is a complete factory package: it carries Google's bootloader and modem firmware,
byte-identical to the ones in Google's own factory image for the device, and the OS partitions carry
Google's proprietary Pixel vendor files. We do not modify them. RistOS is
**not affiliated with, sponsored by, or endorsed by Google**.

`image/INSTALL.md` is the procedure.

This project makes **no claim of conformance** with FCC equipment authorisation, PTCRB, GCF or any
carrier certification programme, and has not been submitted to any of them. Carriers may allowlist
devices by IMEI and are under no obligation to support or permit a device running modified software
on their network.

This build has been used on one carrier, T-Mobile. IMS registration, which carries voice calls on a
VoLTE-only network, has not been checked on any other.

---

## The boot warning screen is normal, and it never goes away

Every time the phone starts, before Android loads, it shows a warning that the device has loaded a
different operating system, along with a long ID string, and waits about ten seconds. Then it shows
a Google logo. Neither screen can be removed.

**This is expected and it is not a fault.** It is not a sign that the flash failed or that the
device is compromised.

Yellow means the bootloader is locked onto a non-Google key; every custom OS on a Pixel shows it.

The ID shown is the fingerprint of the key the image is signed with and does not change between
boots. If it ever changes, something else has been flashed onto your device. If the screen ever
turns **red**, or says the device is **unlocked** when you locked it, stop and re-flash from a known-good image — those states mean verification failed or the bootloader
was unlocked without you.

---

## Warranty and recovery

Unlocking the bootloader, flashing a custom OS and relocking are all things you do at your own risk.
They may void your warranty, and a mistake during flashing can leave the device unbootable.

Relocking means **only images signed by the key the phone is locked onto will boot**. If you flashed
a published image, that key is ours: there is nothing for you to generate, hold or back up, and the
way out of a bad state is to unlock and flash again — see "Going back to stock" in
`image/INSTALL.md`.

**If you built and signed the image yourself, back those keys up off-machine before you relock.**
They are then the only thing that can produce a bootable image for that device, and losing them
means it can never be updated again.
