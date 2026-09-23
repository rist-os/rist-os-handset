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

**The manifest expires, and nothing warns you.** `expires` is part of the signed bytes.
`tools/ota_publish.sh` sets it 14 days out by default (`RIST_OTA_EXPIRES_DAYS`), and the handset
refuses any manifest more than **30 days** out as well as any that has passed — so the ceiling is
30 days and the practical heartbeat is 14. A manifest that lapses does not degrade gracefully:
every handset rejects it, backs off, and silently stops being offered anything, whatever is sitting
in the bucket. Re-signing is the same operation as promoting a channel to itself:

```
tools/ota_publish.sh --promote stallion stable stable
```

That refetches the live manifest, rewrites nothing but `expires`, re-signs it and puts it back. It
prompts for the minisign passphrase, so it cannot be automated — put a reminder somewhere a person
will see it before the fortnight is out.

**A build that a handset refuses is refused forever.** When the device classifies a failure as
permanent — a bad signature, a payload it cannot parse, a hash mismatch — it records that build
number and will not consider it again. There is no expiry on that record and no way to clear it
from the UI. So a corrected package **must be published under a new build number**: republishing a
fix under the old number reaches every handset except the ones that already rejected it, which are
exactly the ones that need it.

**A wrong clock rejects everything.** The device takes "now" as the later of its own clock and its
build date, which stops an unset clock from treating a current manifest as expired. The mirror case
is real though: a handset that has been switched off for longer than the manifest's lifetime, with
no network time yet, can compute a lifetime beyond the 30-day cap and reject every manifest until
it gets the time. If a phone out of a drawer will not update, let it see the network for a minute
first.

**Using your own update server.** The server address is compiled into the image
(`OtaState.DEFAULT_BASE_URL`). A build from source can point it anywhere that serves the same two
files; the manifest must then be signed with a key the image trusts.
