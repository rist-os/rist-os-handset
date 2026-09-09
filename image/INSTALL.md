# Installing RistOS on a Pixel 10a

> RistOS is experimental, pre-release software. Installing it erases the device, not all features
> have been thoroughly tested, and emergency calling in particular has not been verified.

### [⬇ Download RistOS for Pixel 10a (3.5 GB)](https://dl.ristos.org/2026090701/stallion-factory-2026090701.zip)

## Before you Install

- This works on the **Pixel 10a (`stallion`) only** that is not carrier-locked for now
- Before downloading, check the phone can be unlocked at all: tap **Settings → About phone → Build number** seven times, then look at **Settings → System → Developer
options → OEM unlocking**. If it is greyed out, which is usual on a carrier-locked handset, stop
here.
- **Flashing erases the phone.** Back it up first and open the backup to check it. If you use an
eSIM, ask your carrier what re-issuing involves before you wipe.
- You need a **USB cable that carries data**
- This should take around half an hour

## How to Install

**Step 1 - Plug the phone into the computer.** Leave the cable connected throughout. Every step
below assumes it is still in.

**Step 2 - Install platform-tools** — one download from Google containing both `fastboot`, which does the
install, and `adb`, which you need later for updates. Click on one of the links appropriate for your operating system:
[macOS](https://dl.google.com/android/repository/platform-tools-latest-darwin.zip) ·
[Windows](https://dl.google.com/android/repository/platform-tools-latest-windows.zip) ·
[Linux](https://dl.google.com/android/repository/platform-tools-latest-linux.zip), or from the
[release-notes page](https://developer.android.com/tools/releases/platform-tools), which is where
those three links come from.

**If you already have Android Studio, you already have these** — check the version, then use that
folder instead of downloading anything:
`~/Library/Android/sdk/platform-tools` (macOS) ·
`%LOCALAPPDATA%\Android\Sdk\platform-tools` (Windows) ·
`~/Android/Sdk/platform-tools` (Linux).

Any current download works. (This project needs 35.0.1 at minimum) The floor exists because an older
`fastboot` fails on modern Pixels in ways that look like hardware faults; the usual source of one is
a Linux distribution package rather than Google.

It arrives as a zip that unzips to a folder called **`platform-tools`**. Unzipping does **not** make
`fastboot` a command you can type — you have to point the terminal at that folder.

**macOS or Linux** - open **Terminal**. Type `cd ` (with a space after it), then drag the
`platform-tools` folder onto the Terminal window, which fills in its location. Press Enter. Now
paste these two lines:

```sh
export PATH="$PWD:$PATH"
fastboot --version
adb --version
```

Both should print a version. **Nothing in those lines needs editing**: `$PWD` is "the folder I am
in" and `$PATH` is "everything already there" — neither is a placeholder.

On macOS the first run may say *"cannot be opened because the developer cannot be verified"*.
**System Settings → Privacy & Security** has an **Allow Anyway** button underneath.

**Windows** - the download is `platform-tools-latest-windows.zip`. Open **PowerShell**. Type `cd `
(with a space), then drag the `platform-tools` folder onto the window and press Enter. Now paste
these two lines:

```powershell
$env:Path = "$PWD;$env:Path"
fastboot --version
```

You should get back a version line, `fastboot version 37.0.1` or similar. If you get `command not
found`, the `cd` did not land in the right folder — check you dragged the folder that directly
contains the `fastboot` file.

Either way, this applies to that terminal window only. Run every step below in the same window. On
macOS or Linux you can make it permanent with `echo 'export
PATH="$HOME/Downloads/platform-tools:$PATH"' >> ~/.zshrc`. On Windows the permanent setting lives
under **Environment Variables** (Start menu: *Edit the system environment variables*).

<details>
<summary><strong>Windows: install Google's USB Driver</strong> — and what usually goes wrong here</summary>

Windows also needs [Google's USB Driver](https://developer.android.com/studio/run/win-usb), or the
phone is not detected. Google states it is required for `adb` with its own devices, and a Pixel in
the bootloader commonly is not listed by `fastboot devices` until it is installed. To install it:

1. Put the phone in the bootloader first (step 3). A Pixel presents a different USB interface there
than when booted, so a driver bound while the phone is booted does not help.
2. Open **Device Manager** and find the unrecognised device.
3. Right-click it → **Update driver** → point it at the unzipped driver folder.

**`zsh: command not found: fastboot`** is the most common thing to go wrong here, and it means the
`PATH` line above has not been run in this window — not that anything is broken. Step 4 needs it
too: `flash-all.sh` calls `fastboot` by name and stops with `fastboot not found` without it.

Once `PATH` is set, steps 3 and 5 are typed exactly the same on Windows; step 4 differs, and says
how.

</details>

**Step 3 - Unlock the bootloader. This erases the phone.**
- On the phone, go to Settings > About phone > Build number, and tap **Build number** seven times. It should tell you that you are now a developer.
- Go to Settings > System > Developer options, and ensure "OEM unlocking" is enabled.
- Power off, then hold **Volume Down + Power** for the bootloader.

```sh
fastboot devices            # must list exactly one device
fastboot flashing unlock    # confirm on the phone's screen
```

The bootloader screen has its own menu (Start / Restart bootloader / …). Ignore it and run the two
commands above from the computer. Pressing **Power** on the default entry boots the phone normally,
which is not what you want here.

The second command makes the phone show a confirmation prompt. The touchscreen does nothing there:
press **Volume Up/Down** to change the selection to "Unlock the bootloader", then **Power** to
accept. That is what erases the phone.

The phone wipes and comes back to a screen reading **Fastboot Mode**, which should now say **Device
state: unlocked** near the bottom. Leave it there and press nothing on it — step 4 drives it from
the computer.

**Step 4 - Flash.** On macOS or Linux, in the same terminal, navigate to the directory with your
stallion-factory downloads and run:

```sh
unzip stallion-factory-*.zip
cd stallion-factory-*/
bash flash-all.sh
```

On Windows, unzip it in Explorer, then run `flash-all.bat` from the same PowerShell window you set
`PATH` in — not by double-clicking it, which starts with the old `PATH` and fails.

This will take some time. You can follow the progress on the terminal. Do not press anything on the
phone during this process. Ten minutes and several reboots. **fastbootd** on the screen is normal.
Do not interrupt it — if it stops, run it again, the flash is safe to repeat.

**Step 5 - Boot it, then re-lock.** If it is still on **Fastboot Mode** after the process has stopped
running in your terminal, press **Power** on `Start`, or run `fastboot reboot` in your terminal. If
it is already booting, skip that command — it is only for a phone still sitting in the bootloader,
and on a booted phone it waits forever on `< waiting for any device >`.

First boot takes several minutes and comes up in the kiosk. It shows an **orange** warning screen
first, saying the device cannot be checked for corruption. That is correct right now: the bootloader
is still unlocked. Do not press anything on this screen, and let the phone load.

**Use the phone before you lock**: make a call, check the signal. Locking on top of an OS that does
not boot can leave the phone unrecoverable. Do not add contacts or save anything onto the phone
because the next step will delete the data again. Power off, hold **Volume Down + Power**, and run
these in order:

```sh
fastboot flashing get_unlock_ability   # must print 1 -- if it prints 0, DO NOT LOCK
fastboot flashing lock                 # erases the phone
```

If it prints `0`, boot the phone, enable **OEM unlocking** again under Developer options, and
re-check. If that toggle will not enable, leave the bootloader unlocked and stop. An unlocked phone
that boots is a working phone.

If the first command prints `1`, run the flashing lock command. Look at the phone, and it should
show "Do not lock the bootloader" next to the power button. Press **Volume Down** to change it to
"Lock the bootloader", then press the power button to select it. It erases the phone again, which is
unavoidable and is the same protection that stops anyone else locking your phone onto their own key.

It will show the Fastboot Mode screen, and you can press the power button to restart the phone.

The phone now boots to a **yellow** screen with a key fingerprint, and pauses there for a few
seconds every time it starts. That is the correct result: yellow means the bootloader is locked onto
a key that is not Google's, which is exactly what you just installed. Green would mean the image did
not take, orange that the bootloader is still unlocked.

<details> <summary>Why re-lock, and what the yellow screen means</summary>

Unlocked, the bootloader boots anything anyone puts on the phone. Re-locking onto the RistOS AVB key
installed in step 4 is what makes the phone boot only firmware signed by that key.

`get_unlock_ability` decides whether a locked phone can ever be unlocked again. At `1`, a device
that fails verified boot can be rescued. At `0`, it cannot be, by anyone.

The fingerprint on the yellow screen identifies that key, not the image, and there is no published
value to compare it against. "Verifying the download" under Reference establishes authorship.

The bootloader and modem are checked against a Google key fused into the chip, which re-locking does
not touch.

</details>

**Step 6 - Point it at a backend.** A public build has **no endpoint compiled in**, so the assistant has
nowhere to send anything until you give it one — open the app drawer on the phone (the gear on the
home screen), choose **Settings**, and set the endpoint. If your backend needs a token, there is a
field for it there too — see
[docs/BACKEND.md](https://github.com/rist-os/rist-os-handset/blob/main/docs/BACKEND.md).

[docs/BACKEND.md](https://github.com/rist-os/rist-os-handset/blob/main/docs/BACKEND.md) is the
guide: the request/response contract, and a complete working server in Python you can run as-is,
then transport, streaming, the wake channel and every field as reference.
`app/src/main/proto/rist_request.proto` is the contract itself.

Any server that speaks that protocol works. Rist runs one, and you are not required to use it.

---

## After it is running

There is no Google account and no setup wizard.

The phone asks once about network location. It is a Settings change and it materially changes what
this phone can do in an emergency — see `SAFETY.md`.

**Updating later.** The phone checks for updates on its own: it polls `https://ota.ristos.org` every
six hours and posts a "System update" notification when a newer build is published. The endpoint
ships configured, and nothing on the phone changes or clears it — turning the checking off, or
pointing it at your own bucket, means editing code.

What has never been watched through end to end is the phone installing one of those updates itself,
so the path we trust is still the manual one. It does not require going through any of this again. A
signed update package is applied from recovery, and it does not wipe the phone:

```sh
adb reboot recovery
# on the phone: Apply update -> Apply from ADB
adb sideload <the RistOS update zip>
```

Recovery verifies the package against the certificate built into the running image, so a package we
did not sign will be refused.

---

## Reference

## Verifying the download (optional)

Skip it if you are only trying RistOS out. Do it if you are going to re-lock the bootloader in step
4, which ties the phone to whatever you flashed.

Download `SHA256SUMS` and `SHA256SUMS.minisig` into the folder the zip is in, and run these there.
The bucket serves files but does not list directories, so link the files themselves rather than the
folder — <https://dl.ristos.org/2026090701/> returns 404, and a reader following it lands on an
error page in the middle of the verification step:

- <https://dl.ristos.org/2026090701/SHA256SUMS>
- <https://dl.ristos.org/2026090701/SHA256SUMS.minisig>

```sh
# macOS: brew install minisign  |  Debian/Ubuntu: apt install minisign
minisign -Vm SHA256SUMS -P RWThr8fGz/71qbSM4R8F9UvI7KYW++0i8dAQwyU+Fz24xqirceHqqHUX
sha256sum --ignore-missing -c SHA256SUMS   # macOS: shasum -a 256 --ignore-missing -c SHA256SUMS
```

The first must print `Signature and comment signature verified`; the second must print `OK` for the
zip. The key above comes from this repository, not from the download server — that is the point of
checking, since whoever can swap the zip can swap a checksum sitting beside it. `SHA256SUMS` covers
every file in the release, including ones you did not download, which is what `--ignore-missing` is
for — without it you get `FAILED open or read` lines for files that were never meant to be there.

If the signature fails, delete the download — that is not a bad transfer to retry.

**On Windows** there is no `sha256sum`. The built-in is `certutil -hashfile
stallion-factory-2026090701.zip SHA256`, which prints the hash of one file for you to compare by eye
against that file's line in `SHA256SUMS` — certutil has no check mode that reads a sums file. For
minisign, use `scoop install minisign` or `choco install minisign`, or take
`minisign-0.12-win64.zip` from <https://github.com/jedisct1/minisign/releases>; the `-Vm` line above
is then identical.

## What you get

RistOS boots into a single-app kiosk. The Rist assistant is the launcher, and the apps you can reach
are a fixed row — Dialer, Messaging, Camera, Gallery, Organic Maps, Settings. There is no app store
and you cannot install arbitrary apps. The phone checks for updates every six hours on its own and
tells you when one is waiting.

## What is in the download

`stallion-factory-<build>.zip` unzips to:

| File | What it is | Whose bytes |
|---|---|---|
| `bootloader-stallion-*.img` | the bootloader | **Google's, unmodified** |
| `radio-stallion-*.img` | the modem/baseband firmware | **Google's, unmodified** |
| the nested `image-*.zip` | every OS partition we build and sign | ours, with Google's proprietary vendor files inside |
| `avb_pkmd.bin` | the RistOS AVB public key, which the phone re-locks onto | ours |
| `flash-all.sh` / `.bat` | the installer | ours |

This download redistributes Google's proprietary Pixel firmware, unmodified. RistOS is not
affiliated with, sponsored by, or endorsed by Google.

The bootloader and modem are checked against a Google key fused into the chip, which flashing and
re-locking do not touch. Everything RistOS builds is verified against the RistOS key, which is what
step 5 locks the phone onto.

## If something goes wrong

Work down this list. Almost everything here is recoverable, and the one thing that is not has an
entry of its own so you can see exactly what to avoid.

### `command not found: fastboot` (or `adb`)

Unzipping platform-tools does not put it on your `PATH`. Re-run the `export PATH=...` line from step
1 in this terminal window — it does not survive opening a new one. `flash-all.sh` needs it too; it
calls `fastboot` by name.

Check you are pointing at the right folder: `ls ~/Downloads/platform-tools/fastboot` should print
the path, not "No such file".

On Windows the same fault reads `'fastboot' is not recognized as an internal or external command` in
`cmd`, or `The term 'fastboot' is not recognized...` in PowerShell. Re-run the `$env:Path` line.

### `fastboot devices` or `adb devices` lists nothing

Try a different cable first — a charge-only cable is the most common cause by a wide margin. Then
try a different USB port, preferably one directly on the computer rather than through a hub. On
Linux you may need udev rules. On Windows, install Google's USB Driver (step 2) — without it a Pixel
in the bootloader is not recognised and `fastboot devices` prints nothing. On any platform, use
Google's platform-tools rather than a packaged `fastboot`.

### `adb devices` says `unauthorized`

Look at the phone's screen. There is an "Allow USB debugging?" dialog waiting. Accept it. If it
never appears, revoke the authorisations in Developer options and replug.

### `flash-all.sh` stops with `requirement ... not satisfied`

That is fastboot reading the `require` lines out of the image's own `android-info.txt` and finding a
device this release was not built for. Almost always it means the phone is not a Pixel 10a
(`stallion`). It is the check working. Do not go looking for the flag that skips it: that flag
exists for people building their own images, and using it here puts an OS on hardware it was never
compiled against.

### The flash stopped partway through

The phone is probably not bootable right now. This is the ordinary case, not the bad one.

**Do not run `fastboot flashing lock`.** Not to "reset" it, not for any reason. Your bootloader is
still unlocked, which means every door is still open, and locking is the one action that can close
them.

Get the phone back into the bootloader — hold **Power** for 30 seconds to force it off, then hold
**Volume Down + Power** — and run the same script again:

```sh
cd stallion-factory-*/
bash flash-all.sh          # Windows: flash-all.bat
```

The flash is safe to repeat from the beginning. There is nothing to resume and nothing to clean up
first.

### It does not boot, and the bootloader is still unlocked

You are fine. Run `flash-all.sh` again. If you would rather go back to a stock phone instead,
download Google's factory image for the Pixel 10a from
<https://developers.google.com/android/images> and follow "Going back to stock" below. Both are
available to you.

### It boots but there is no mobile signal, no SIM, or no calls

The modem firmware in this download is the one this OS was built against, so a firmware/OS mismatch
is unlikely.

Check the mundane causes first: an eSIM that did not survive the wipe (call your carrier), or a
physical SIM that was not reseated.

### It does not boot, and you already re-locked

If `get_unlock_ability` printed `1` when you checked it in step 5, unlocking still works:

```sh
fastboot flashing unlock      # erases the phone
```

Then flash again, or go back to stock.

### It does not boot, you re-locked, and `get_unlock_ability` is 0

This is the one true brick, and there is no command here that fixes it. A locked device that fails
verified boot and cannot be unlocked has no path back that does not involve Google or a service
centre.

The way people get here is by re-locking without checking the flag, on an OS that had never been
booted.

### The yellow screen shows a fingerprint

That is expected, and there is currently nothing to compare it against — see step 5. The fingerprint
identifies the key your bootloader is locked to, not the image, and we do not yet publish the value.

What *would* mean something is wrong: a **green** screen (you are on Google's key, so our image did
not take) or an **orange** one (the bootloader is unlocked). If you see either after following step
5, re-read that step before using the phone.

### Going back to stock Android permanently

This is the one part of this document that needs a download from Google. Get the factory image for
your Pixel from <https://developers.google.com/android/images>, check it against the SHA-256 Google
publishes beside it, and read Google's terms on that page — they govern that download, not ours.

```sh
# bootloader must be unlocked
fastboot erase avb_custom_key       # remove our key from the device
cd <Google's unzipped factory image>
bash flash-all.sh                   # Google's image, Google's script
```

**Then boot Android and check it comes all the way up**, exactly as in step 5 — Google's
`flash-all.sh` reboots the phone into the OS when it finishes, so the phone is not in the bootloader
at this point. Only once it has booted, put it back into the bootloader (**Volume Down + Power**)
and lock:

```sh
fastboot flashing get_unlock_ability   # must print 1 -- if it prints 0, DO NOT LOCK
fastboot flashing lock                 # erases the phone; boot state returns to GREEN
```

Locking blind here bricks a phone the same way locking blind in step 5 does; the fact that it is
Google's image rather than ours changes nothing.

Erase the key **before** you re-lock. Locking to Google's image with our key still installed leaves
a key on the device that nothing verifies against, and there is no reason to leave it there.

---

## Related

`SAFETY.md` covers emergency calling and Wireless Emergency Alerts. `docs/OTA.md` covers updates:
the phone checks for them on its own, there is no way to turn that off from the phone, and applying
one automatically is unproven, so sideload from recovery is still the path we trust.

`android-info.txt`, inside the nested image zip, names the device this release is built for.
`fastboot update` enforces it whether or not you use our script. Do not edit it to force a flash
through — it does not change what is on your phone, only your warning that something is wrong.
