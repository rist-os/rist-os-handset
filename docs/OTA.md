# Over-the-air updates

**What the phone does.** An alarm re-arms at every boot and the phone checks for updates about
every six hours. It fetches a signed manifest from `https://ota.ristos.org/v1/ota/stallion/stable`
and the detached signature beside it (`stable.minisig`), verifies the signature against the RistOS
minisign public key published in [SECURITY.md](../SECURITY.md), and compares the manifest's build
timestamp with its own. Only a strictly newer build is offered. When one is found the phone posts a
"System update" notification; nothing downloads until you accept it, and the phone never reboots on
its own initiative. The same directory also carries `alpha`, `beta` and `testing` channel pointer
files; the shipped image is offered only the `stable` channel.

**Rollback protection.** The device refuses any package whose build timestamp is not newer than
its own, including an equal timestamp.

**Updating by cable.** A signed update package can also be applied with `adb sideload` through
recovery. It installs to the inactive slot, keeps your data, and works with the bootloader locked
(recovery verifies the package against the built-in OTA certificate).
[image/INSTALL.md](../image/INSTALL.md) has the steps under "After it is running".

**Checking a manifest yourself.** With `minisign` installed and the public key from SECURITY.md:

```
curl -sO https://ota.ristos.org/v1/ota/stallion/stable
curl -sO https://ota.ristos.org/v1/ota/stallion/stable.minisig
minisign -Vm stable -x stable.minisig -P <public key>
```

A valid signature means the manifest is the one RistOS signed; the `build` field inside it names
the build your phone will be offered.

**Using your own update server.** The server address is compiled into the image
(`OtaState.DEFAULT_BASE_URL`). A build from source can point it anywhere that serves the same two
files; the manifest must then be signed with a key the image trusts.
