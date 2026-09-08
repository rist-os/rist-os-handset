#!/usr/bin/env python3
"""Refuse to publish an OTA package that carries Google's firmware, or one that will brick a phone."""

import argparse
import base64
import hashlib
import io
import os
import re
import shutil
import struct
import sys
import tempfile
import zipfile

GOOGLE_FIRMWARE_PARTITIONS = frozenset("""
    abl bl1 bl2 bl31 bootloader gcf gsa gsa_bl1 ldfw modem pbl radio tzsw
""".split())

DEFAULT_KEEP_SET = frozenset("""
    boot dtbo init_boot pvmfw vendor_boot vendor_kernel_boot
    product system system_dlkm system_ext vendor vendor_dlkm
    vbmeta
""".split())

SECONDARY_PAYLOAD_BIN = 'secondary/payload.bin'
SECONDARY_PAYLOAD_PROPERTIES = 'secondary/payload_properties.txt'
METADATA_ENTRY = 'META-INF/com/android/metadata'
OTACERT_ENTRY = 'META-INF/com/android/otacert'
PAYLOAD_ENTRY = 'payload.bin'
PAYLOAD_PROPERTIES_ENTRY = 'payload_properties.txt'

# Enough of payload.bin to reach the end of the manifest (~124 KB for a 24-partition package).
MANIFEST_READ_CEILING = 8 << 20


class Report:
    def __init__(self):
        self.failed = False
        self.unchecked = False

    def ok(self, msg):
        print('ok    %s' % msg)

    def fail(self, msg):
        print('FAIL  %s' % msg)
        self.failed = True

    def unknown(self, msg):
        print('UNCHECKED  %s' % msg)
        self.unchecked = True

    def note(self, msg=''):
        print('      %s' % msg if msg else '')

    def verdict(self, what):
        print('')
        if self.failed:
            print('OTA FAIL -- do not publish %s' % what)
            if self.unchecked:
                print('      (and some questions above were left unanswered as well)')
            return 1
        if self.unchecked:
            print('OTA UNCHECKED -- nothing was found, but this run could not answer every')
            print('      question. The UNCHECKED lines say which, and each says what to supply.')
            print('      This is not a pass.')
            return 2
        print('OTA OK -- %s carries no Google firmware and is self-consistent for verified boot' % what)
        return 0


def _varint(b, i):
    r = 0
    s = 0
    while True:
        if i >= len(b):
            raise ValueError('truncated varint')
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        if not (c & 0x80):
            return r, i
        s += 7


def _fields(b):
    i = 0
    n = len(b)
    while i < n:
        key, i = _varint(b, i)
        fn, wt = key >> 3, key & 7
        if wt == 0:
            v, i = _varint(b, i)
        elif wt == 1:
            v = struct.unpack_from('<Q', b, i)[0]
            i += 8
        elif wt == 2:
            ln, i = _varint(b, i)
            v = b[i:i + ln]
            if len(v) != ln:
                raise ValueError('truncated length-delimited field %d' % fn)
            i += ln
        elif wt == 5:
            v = struct.unpack_from('<I', b, i)[0]
            i += 4
        else:
            raise ValueError('unsupported wire type %d for field %d' % (wt, fn))
        yield fn, wt, v


class Manifest:

    def __init__(self, raw):
        self.partitions = []
        self.postinstall = {}
        self.partial_update = None
        self.max_timestamp = None
        self.minor_version = None
        self.block_size = None
        self.groups = []
        for fn, wt, v in _fields(raw):
            if fn == 3:
                self.block_size = v
            elif fn == 12:
                self.minor_version = v
            elif fn == 14:
                self.max_timestamp = v
            elif fn == 16:
                self.partial_update = bool(v)
            elif fn == 13:
                name = None
                run_post = False
                post_path = None
                for pfn, pwt, pv in _fields(v):
                    if pfn == 1:
                        name = pv.decode('utf-8', 'replace')
                    elif pfn == 2:
                        run_post = bool(pv)
                    elif pfn == 3:
                        post_path = pv.decode('utf-8', 'replace')
                if name is None:
                    raise ValueError('a PartitionUpdate carries no partition_name')
                self.partitions.append(name)
                if run_post:
                    self.postinstall[name] = post_path
            elif fn == 15:
                for dfn, dwt, dv in _fields(v):
                    if dfn == 1:
                        gname = None
                        gparts = []
                        for gfn, gwt, gv in _fields(dv):
                            if gfn == 1:
                                gname = gv.decode('utf-8', 'replace')
                            elif gfn == 3:
                                gparts.append(gv.decode('utf-8', 'replace'))
                        self.groups.append((gname, gparts))


def read_manifest(stream):
    head = stream.read(24)
    if len(head) < 24:
        raise ValueError('payload is shorter than its own 24-byte header')
    magic = head[:4]
    if magic != b'CrAU':
        raise ValueError('payload magic is %r, not CrAU '
                         '(payload_constants.cc kDeltaMagic)' % magic)
    version = struct.unpack('>Q', head[4:12])[0]
    manifest_size = struct.unpack('>Q', head[12:20])[0]
    sig_size = struct.unpack('>I', head[20:24])[0]
    if version != 2:
        raise ValueError('payload major version %d; this gate only understands 2' % version)
    if manifest_size == 0 or manifest_size > MANIFEST_READ_CEILING:
        raise ValueError('manifest_size %d is not a plausible manifest '
                         '(refusing to read it)' % manifest_size)
    raw = stream.read(manifest_size)
    if len(raw) != manifest_size:
        raise ValueError('manifest is truncated: wanted %d bytes, got %d'
                         % (manifest_size, len(raw)))
    m = Manifest(raw)
    if not m.partitions:
        raise ValueError('the manifest parsed but named ZERO partitions. '
                         'That is a broken read, never a clean payload.')
    m.metadata_signature_size = sig_size
    return m


AVB_MAGIC = b'AVB0'
AVB_HEADER_FMT = '>4sII QQ I QQQQ QQQQ QQ Q I I 48s 80s'
AVB_TAG_PROPERTY, AVB_TAG_HASHTREE, AVB_TAG_HASH = 0, 1, 2
AVB_TAG_KERNEL_CMDLINE, AVB_TAG_CHAIN_PARTITION = 3, 4


class VbMeta:
    def __init__(self, data):
        if data[:4] != AVB_MAGIC:
            raise ValueError('not a vbmeta image: magic is %r, not %r' % (data[:4], AVB_MAGIC))
        h = struct.unpack(AVB_HEADER_FMT, data[:struct.calcsize(AVB_HEADER_FMT)])
        auth_size, desc_off, desc_size = h[3], h[14], h[15]
        self.rollback_index = h[16]
        self.flags = h[17]
        self.hashed = []      # HASH descriptors:      partition name -> digest hex
        self.hashtrees = []   # HASHTREE descriptors:  partition name -> root digest hex
        self.chains = []      # CHAIN_PARTITION descriptors: partition name
        off = 256 + auth_size + desc_off
        end = off + desc_size
        if end > len(data):
            raise ValueError('vbmeta descriptor block runs past the end of the image')
        while off < end:
            # Descriptor field offsets below are measured from after the 16-byte AvbDescriptor parent.
            tag, num_bytes = struct.unpack('>QQ', data[off:off + 16])
            body = data[off + 16:off + 16 + num_bytes]
            if len(body) != num_bytes:
                raise ValueError('vbmeta descriptor is truncated')
            if tag == AVB_TAG_HASH:
                pn_len, salt_len, dig_len = struct.unpack('>LLL', body[40:52])
                name = body[116:116 + pn_len].decode()
                dig = body[116 + pn_len + salt_len:116 + pn_len + salt_len + dig_len]
                self.hashed.append((name, dig.hex()))
            elif tag == AVB_TAG_HASHTREE:
                pn_len, salt_len, root_len = struct.unpack('>LLL', body[88:100])
                name = body[164:164 + pn_len].decode()
                root = body[164 + pn_len + salt_len:164 + pn_len + salt_len + root_len]
                self.hashtrees.append((name, root.hex()))
            elif tag == AVB_TAG_CHAIN_PARTITION:
                pn_len = struct.unpack('>L', body[4:8])[0]
                self.chains.append(body[76:76 + pn_len].decode())
            off += 16 + num_bytes
        if not (self.hashed or self.hashtrees or self.chains):
            raise ValueError('this vbmeta names no partitions at all -- a broken read')

    @property
    def covered(self):
        return set(n for n, _ in self.hashed) | set(n for n, _ in self.hashtrees)


def split_partitions(s):
    return [p for p in re.split(r'[,\s]+', s.strip()) if p]


def der_from_pem(data):
    if data[:1] == b'\x30':
        return data
    text = data.decode('ascii', 'replace')
    m = re.search(r'-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----', text, re.S)
    if not m:
        raise ValueError('neither DER (no 0x30 SEQUENCE tag) nor a PEM CERTIFICATE block')
    return base64.b64decode(re.sub(r'\s+', '', m.group(1)))


def cert_fingerprint(data):
    return hashlib.sha256(der_from_pem(data)).hexdigest()


def parse_metadata(text):
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or '=' not in line:
            continue
        k, v = line.split('=', 1)
        out[k.strip()] = v.strip()
    return out


def find_target_files(beside, explicit):
    if explicit:
        if not os.path.isfile(explicit):
            raise ValueError('--target-files %s is not a file' % explicit)
        return explicit, 'named by --target-files'
    d = os.path.dirname(os.path.abspath(beside))
    for cand in (d, os.path.dirname(d), os.path.dirname(os.path.dirname(d))):
        if not os.path.isdir(cand):
            continue
        hits = sorted(os.path.join(cand, f) for f in os.listdir(cand)
                      if 'target_files' in f and f.endswith('.zip'))
        if len(hits) == 1:
            return hits[0], 'found in %s' % cand
        if len(hits) > 1:
            raise ValueError('more than one *target_files*.zip in %s:\n        %s\n'
                             '      Refusing to guess which one produced this package. '
                             'Name it with --target-files.' % (cand, '\n        '.join(hits)))
    return None, None


def check(path, keep_set, vbmeta_path, target_files_arg, expect_otacert,
          min_security_patch, rep):
    if not os.path.exists(path):
        rep.unknown('no such path: %s' % path)
        return
    try:
        z = zipfile.ZipFile(path)
        names = z.namelist()
    except Exception as e:
        rep.unknown('could not open %s as a zip: %s' % (path, e))
        rep.note('Nothing was examined, so nothing is cleared.')
        return
    if not names:
        rep.unknown('%s listed ZERO entries. An empty listing is a broken read, never a '
                    'clean package.' % path)
        return
    rep.note('package: %d entries in %s' % (len(names), os.path.basename(path)))

    missing = [n for n in (PAYLOAD_ENTRY, PAYLOAD_PROPERTIES_ENTRY, METADATA_ENTRY)
               if n not in names]
    if missing:
        rep.unknown('this does not look like an A/B OTA package -- missing %s'
                    % ', '.join(missing))
        rep.note('Nothing below can be concluded from it. If this is a factory artefact, run')
        rep.note('  bash image/scripts/check_no_blobs.sh --artefact %s' % path)
        return

    secondary = [n for n in names
                 if n in (SECONDARY_PAYLOAD_BIN, SECONDARY_PAYLOAD_PROPERTIES)
                 or n.startswith('secondary/')]
    if secondary:
        rep.fail('this package carries a SECONDARY payload:')
        for n in secondary:
            rep.note(n)
        rep.note('')
        rep.note('`--include_secondary` is a developer convenience for update_device.py; the')
        rep.note('streaming path update_engine actually uses never reads it. It is produced by')
        rep.note('GetTargetFilesZipForSecondaryImages, which --partial does NOT filter -- so it')
        rep.note('re-adds every partition the partial removed, Google firmware included, and')
        rep.note('roughly doubles the package. Regenerate without --include_secondary.')
    else:
        rep.ok('no secondary payload (--include_secondary was not used)')

    try:
        with z.open(PAYLOAD_ENTRY) as f:
            manifest = read_manifest(f)
    except Exception as e:
        rep.unknown('could not read the payload manifest: %s' % e)
        rep.note('Every check below is about the partition list in that manifest, so none of')
        rep.note('them ran. This is not a pass.')
        return
    parts = sorted(set(manifest.partitions))
    rep.note('payload: %d partitions, partial_update=%s, max_timestamp=%s'
             % (len(parts), manifest.partial_update, manifest.max_timestamp))

    fw = sorted(p for p in parts if p in GOOGLE_FIRMWARE_PARTITIONS)
    if fw:
        rep.fail('Google firmware partitions are inside this payload:')
        for p in fw:
            rep.note(p)
        rep.note('')
        rep.note('These are the contents of bootloader-<device>-*.img (abl bl1 bl2 bl31 gcf gsa')
        rep.note('gsa_bl1 ldfw pbl tzsw) and radio-<device>-*.img (modem), travelling under')
        rep.note('partition names rather than filenames. deblob_release.sh removes the file form')
        rep.note('from the factory zip and does not touch this package. Regenerate the payload')
        rep.note('with --partial naming only the partitions RistOS builds -- see')
        rep.note('--help for the exact invocation.')
    else:
        rep.ok('no Google firmware partitions among the %d in the payload' % len(parts))

    extra = sorted(p for p in parts if p not in keep_set and p not in GOOGLE_FIRMWARE_PARTITIONS)
    if extra:
        rep.fail('partitions outside the keep-set are in this payload:')
        for p in extra:
            rep.note(p)
        rep.note('keep-set: %s' % ' '.join(sorted(keep_set)))
    elif fw:
        rep.ok('apart from the firmware named above, every partition is inside the keep-set')
    else:
        rep.ok('every partition in the payload is inside the keep-set')

    looks_partial = not fw
    if looks_partial and manifest.partial_update is not True:
        rep.fail('this payload omits partitions but is NOT marked partial_update.')
        rep.note('update_engine gates the whole copy-forward path on manifest_.partial_update()')
        rep.note('(delta_performer.cc). Without the flag, GenerateOperationsForPartitionsNotInPayload')
        rep.note('never runs, the omitted partitions are never SOURCE_COPYd to the target slot,')
        rep.note('and that slot boots with whatever the update before last left there.')
        rep.note('Regenerate with --partial (which sets the flag) rather than by deleting images.')
    elif manifest.partial_update is True:
        rep.ok('manifest is marked partial_update=true, so update_engine will SOURCE_COPY every')
        rep.note('partition in the device\'s own ro.product.ab_ota_partitions that this payload')
        rep.note('does not carry, forward from the running slot to the target slot')
    else:
        rep.ok('full payload (every partition present), partial_update not required')

    for name, script in sorted(manifest.postinstall.items()):
        if name not in parts:
            rep.fail('postinstall is configured for %s, which is not in the payload' % name)
        else:
            rep.ok('postinstall on %s -> %s (its partition IS shipped)' % (name, script))
    orphan_post = [n for n in manifest.postinstall if n not in parts]
    if not manifest.postinstall:
        rep.note('no postinstall hooks in this payload')
        rep.note('  NOTE: a full Rist package runs system/bin/otapreopt_script on `system`.')
        rep.note('  If `system` is in the payload and this line says there are no hooks, the')
        rep.note('  POSTINSTALL_CONFIG was dropped -- GetTargetFilesZipForPartialUpdates deletes')
        rep.note('  the file outright when no surviving line matches the partial list.')
    del orphan_post

    check_vbmeta(z, path, parts, vbmeta_path, target_files_arg, rep)

    try:
        meta = parse_metadata(z.read(METADATA_ENTRY).decode('utf-8', 'replace'))
    except Exception as e:
        rep.unknown('could not read %s: %s' % (METADATA_ENTRY, e))
        meta = {}
    check_otacert(z, names, expect_otacert, rep)
    check_spl_and_timestamps(meta, manifest, min_security_patch, rep)
    check_target_files_retained(path, target_files_arg, rep)


def check_vbmeta(z, path, parts, vbmeta_path, target_files_arg, rep):
    avb_in_payload = 'vbmeta' in parts
    data = None
    where = None
    if vbmeta_path:
        if not os.path.isfile(vbmeta_path):
            rep.unknown('--vbmeta %s is not a file' % vbmeta_path)
            return
        data = open(vbmeta_path, 'rb').read()
        where = vbmeta_path
    else:
        tf, why = (None, None)
        try:
            tf, why = find_target_files(path, target_files_arg)
        except ValueError as e:
            rep.unknown(str(e))
            return
        if tf:
            try:
                with zipfile.ZipFile(tf) as t:
                    for cand in ('IMAGES/vbmeta.img', 'PREBUILT_IMAGES/vbmeta.img'):
                        if cand in t.namelist():
                            data = t.read(cand)
                            where = '%s!%s (%s)' % (tf, cand, why)
                            break
            except Exception as e:
                rep.unknown('could not read a vbmeta.img out of %s: %s' % (tf, e))
                return

    if data is None:
        if avb_in_payload:
            rep.unknown('this payload ships `vbmeta` and no copy of that vbmeta.img was supplied.')
            rep.note('The image is inside payload.bin as compressed operations, so the package')
            rep.note('alone cannot say which partitions its descriptors cover -- and shipping a')
            rep.note('vbmeta whose descriptors name a partition the payload does not carry is')
            rep.note('the failure that stops a phone booting. Supply it:')
            rep.note('')
            rep.note('    --vbmeta <release-dir>/vbmeta.img')
            rep.note('    --target-files <device>-target_files.zip')
            rep.note('')
            rep.note('This is not a pass.')
        else:
            rep.unknown('no vbmeta.img supplied, and this payload does not ship `vbmeta` either.')
            rep.note('That combination needs checking by hand: if ANY AVB-covered partition is')
            rep.note('in the payload while vbmeta is not, the target slot keeps a vbmeta that')
            rep.note('describes the OLD images and the slot will be rejected at boot.')
        return

    try:
        vb = VbMeta(data)
    except Exception as e:
        rep.unknown('could not parse %s as a vbmeta image: %s' % (where, e))
        return

    if vb.chains:
        rep.unknown('this vbmeta carries %d chain_partition descriptor(s): %s'
                    % (len(vb.chains), ' '.join(sorted(vb.chains))))
        rep.note('A chained arrangement changes the whole analysis -- the chained vbmeta is a')
        rep.note('separate signed image with its own rollback index, and whether the top-level')
        rep.note('one may be shipped without it is a different question from the flat case this')
        rep.note('gate reasons about. Rist builds a FLAT vbmeta today (12 descriptors, zero')
        rep.note('chains), so seeing chains here means the build configuration changed and')
        rep.note('the partial-OTA assumptions need revisiting before anything is published.')
        return

    covered = vb.covered
    rep.note('vbmeta: %d descriptors over %s' % (len(covered), ' '.join(sorted(covered))))
    rep.note('  read from %s' % where)

    if not avb_in_payload:
        also = sorted(covered & set(parts))
        if also:
            rep.fail('this payload updates AVB-covered partitions but does NOT ship `vbmeta`:')
            for p in also:
                rep.note(p)
            rep.note('')
            rep.note('The target slot would keep the vbmeta it already has, whose descriptors')
            rep.note('name the OLD images. Verified boot compares them against the new ones and')
            rep.note('rejects the slot. `vbmeta` must be in the --partial list.')
        else:
            rep.ok('payload ships no AVB-covered partition and no vbmeta -- consistent')
        return

    orphans = sorted(covered - set(parts))
    if orphans:
        rep.fail('THIS PAYLOAD WILL NOT BOOT. It ships a new `vbmeta` whose descriptors cover')
        rep.note('partitions the payload does not carry:')
        for p in orphans:
            rep.note('  %s' % p)
        rep.note('')
        rep.note('update_engine SOURCE_COPYs each of those forward from the running slot, so the')
        rep.note('target slot ends up with the OLD image and the NEW vbmeta digest for it. The')
        rep.note('AVB salt is seeded from the build fingerprint, so those digests change on every')
        rep.note('build even when the file content does not: between Rist 2026082802 and')
        rep.note('2026082904 the salt went f76f0c58... -> 6e32bc7b... and every root digest with')
        rep.note('it. A HASH mismatch (boot, dtbo, init_boot, pvmfw, vendor_boot,')
        rep.note('vendor_kernel_boot) is rejected by the bootloader before the kernel runs; a')
        rep.note('HASHTREE mismatch (product, system, system_dlkm, system_ext, vendor,')
        rep.note('vendor_dlkm) fails dm-verity on first read.')
        rep.note('')
        rep.note('Either add these to the --partial list, or do not ship vbmeta -- and read')
        rep.note('carefully before choosing, because the second option has its own')
        rep.note('failure and it is the one above.')
    else:
        rep.ok('vbmeta is self-consistent: every partition it verifies is in the payload')


def check_otacert(z, names, expect_otacert, rep):
    if OTACERT_ENTRY not in names:
        rep.fail('no %s in the package -- recovery has nothing to verify a sideload against'
                 % OTACERT_ENTRY)
        return
    try:
        fp = cert_fingerprint(z.read(OTACERT_ENTRY))
    except Exception as e:
        rep.unknown('could not read the otacert: %s' % e)
        return
    pretty = ':'.join(fp[i:i + 2] for i in range(0, len(fp), 2)).upper()
    if not expect_otacert:
        rep.unknown('otacert sha256 is %s' % pretty)
        rep.note('Nothing was compared against it. Pin it, so that a package signed with the')
        rep.note('wrong key is caught here rather than by handsets in the field that stop updating:')
        rep.note('')
        rep.note('    --expect-otacert %s' % fp)
        rep.note('    --expect-otacert <path to the .x509.pem>')
        rep.note('')
        rep.note('The store side -- whether the reserve cert is in otacerts.zip -- is a separate')
        rep.note('question and belongs to tools/check_otacerts.py. Run that too.')
        return
    want = expect_otacert.strip().lower().replace(':', '')
    if os.path.isfile(expect_otacert):
        try:
            want = cert_fingerprint(open(expect_otacert, 'rb').read())
        except Exception as e:
            rep.unknown('could not read --expect-otacert %s: %s' % (expect_otacert, e))
            return
    if not re.fullmatch(r'[0-9a-f]{64}', want):
        rep.unknown('--expect-otacert is neither a readable certificate nor a sha256 hex digest')
        return
    if want == fp:
        rep.ok('otacert matches the pinned certificate (%s)' % pretty)
    else:
        rep.fail('otacert does NOT match the pinned certificate.')
        rep.note('in the package : %s' % fp)
        rep.note('expected       : %s' % want)
        rep.note('update_engine verifies against otacerts.zip in the RUNNING image, so a package')
        rep.note('signed with a key the handsets in the field do not carry is one no device will take.')


def check_spl_and_timestamps(meta, manifest, min_security_patch, rep):
    spl = meta.get('post-security-patch-level')
    ts = meta.get('post-timestamp')
    build = meta.get('post-build')
    if build:
        rep.note('post-build: %s' % build)

    if manifest.max_timestamp is None:
        rep.fail('the manifest carries no max_timestamp.')
        rep.note('That field is the signed, unfakeable anti-rollback backstop update_engine')
        rep.note('checks in delta_performer.cc. A payload without it can be replayed onto a')
        rep.note('newer device by anyone who can serve it.')
    elif ts and str(manifest.max_timestamp) != str(ts):
        rep.fail('max_timestamp in the payload (%s) disagrees with post-timestamp in the '
                 'metadata (%s).' % (manifest.max_timestamp, ts))
        rep.note('The two are written by different build steps and only agree if the package is')
        rep.note('intact. The device checks the payload one; the server and OtaPolicy read the')
        rep.note('metadata one. Handsets in the field that disagree about which build is newer are')
        rep.note('handsets with no working rollback defence.')
    else:
        rep.ok('max_timestamp %s agrees with post-timestamp' % manifest.max_timestamp)

    if not spl:
        rep.unknown('no post-security-patch-level in the metadata')
        return
    rep.note('security patch level: %s' % spl)
    rep.note('  BOARD_AVB_ROLLBACK_INDEX tracks the SPL MONTH, not the build')
    rep.note('  (PLATFORM_SECURITY_PATCH_TIMESTAMP), so every build in one month shares a')
    rep.note('  rollback index and is interchangeable with its siblings as far as the')
    rep.note('  bootloader is concerned. max_timestamp above is the per-build backstop.')
    if not min_security_patch:
        return
    if not re.fullmatch(r'\d{4}-\d{2}(-\d{2})?', min_security_patch):
        rep.unknown('--min-security-patch %s is not YYYY-MM or YYYY-MM-DD' % min_security_patch)
        return
    if spl[:len(min_security_patch)] < min_security_patch:
        rep.fail('security patch level %s is older than the floor %s.' % (spl, min_security_patch))
        rep.note('Publishing it moves handsets in the field BACKWARDS on the rollback index if the month')
        rep.note('differs, and every device that takes it loses the intervening patches.')
    else:
        rep.ok('security patch level %s is at or above the floor %s' % (spl, min_security_patch))


def check_target_files_retained(path, target_files_arg, rep):
    try:
        tf, why = find_target_files(path, target_files_arg)
    except ValueError as e:
        rep.unknown(str(e))
        return
    if not tf:
        rep.unknown('no *target_files*.zip found beside this package.')
        rep.note('Keep it with the release. Without it: check_no_blobs.sh can never prove this')
        rep.note('build shipped no pre-authorised adb key, no incremental OTA can ever be built')
        rep.note('against this build, and an app-only respin costs another cold build.')
        rep.note('sign_public.sh stages it; pull it back before publishing.')
        return
    rep.ok('target_files retained: %s (%s)' % (os.path.basename(tf), why))


def _tag(fn, wt):
    return _enc_varint((fn << 3) | wt)


def _enc_varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _len_delim(fn, payload):
    return _tag(fn, 2) + _enc_varint(len(payload)) + payload


def _partition_update(name, run_postinstall=False, post_path=None):
    b = _len_delim(1, name.encode())
    if run_postinstall:
        b += _tag(2, 0) + _enc_varint(1)
        if post_path:
            b += _len_delim(3, post_path.encode())
    return b


def make_manifest(partitions, partial=None, max_timestamp=1787957064,
                  postinstall=None):
    b = _tag(3, 0) + _enc_varint(4096)          # block_size
    b += _tag(12, 0) + _enc_varint(0)           # minor_version
    for p in partitions:
        post = (postinstall or {}).get(p)
        b += _len_delim(13, _partition_update(p, post is not None, post))
    b += _tag(14, 0) + _enc_varint(max_timestamp)
    if partial is not None:
        b += _tag(16, 0) + _enc_varint(1 if partial else 0)
    return b


def make_payload(partitions, **kw):
    manifest = make_manifest(partitions, **kw)
    head = b'CrAU' + struct.pack('>Q', 2) + struct.pack('>Q', len(manifest)) \
        + struct.pack('>I', 523)
    return head + manifest + b'\0' * 523 + b'fake payload data'


def make_vbmeta(hashed, hashtrees, chains=()):
    descs = bytearray()
    for name in hashed:
        body = bytearray(116)
        struct.pack_into('>Q', body, 0, 4096)                 # image_size
        body[8:8 + 6] = b'sha256'
        struct.pack_into('>LLLL', body, 40, len(name), 4, 32, 0)
        body += name.encode() + b'\0' * 4 + bytes(32)
        descs += struct.pack('>QQ', AVB_TAG_HASH, len(body)) + bytes(body)
    for name in hashtrees:
        body = bytearray(164)
        struct.pack_into('>L', body, 0, 1)                    # dm_verity_version
        struct.pack_into('>Q', body, 4, 4096)                 # image_size
        body[56:56 + 6] = b'sha256'
        struct.pack_into('>LLLL', body, 88, len(name), 4, 32, 0)
        body += name.encode() + b'\0' * 4 + bytes(32)
        descs += struct.pack('>QQ', AVB_TAG_HASHTREE, len(body)) + bytes(body)
    for name in chains:
        body = bytearray(76)
        struct.pack_into('>LLL', body, 0, 1, len(name), 8)
        body += name.encode() + bytes(8)
        descs += struct.pack('>QQ', AVB_TAG_CHAIN_PARTITION, len(body)) + bytes(body)
    header = struct.pack(AVB_HEADER_FMT, AVB_MAGIC, 1, 0,
                         0, len(descs), 2,
                         0, 0, 0, 0,
                         0, 0, 0, 0,
                         0, len(descs),
                         1785888000, 0, 0,
                         b'avbtool 1.4.0', b'')
    return header + bytes(descs)


DEFAULT_METADATA = (
    'ota-type=AB\n'
    'post-build=google/stallion/stallion:17/CP2A.260805.005/rist.2026082902:user/release-keys\n'
    'post-build-incremental=rist.2026082902\n'
    'post-security-patch-level=2026-08-05\n'
    'post-timestamp=1787957064\n'
    'pre-device=stallion\n'
)

FIXTURE_CERT = (
    '-----BEGIN CERTIFICATE-----\n'
    'MAoGCCqGSM49BAMCMAA=\n'
    '-----END CERTIFICATE-----\n'
)


def make_ota(path, partitions, partial=None, secondary=False, metadata=None,
             otacert=FIXTURE_CERT, max_timestamp=1787957064, postinstall=None):
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr(METADATA_ENTRY, metadata if metadata is not None else DEFAULT_METADATA)
        z.writestr(PAYLOAD_ENTRY, make_payload(partitions, partial=partial,
                                               max_timestamp=max_timestamp,
                                               postinstall=postinstall))
        z.writestr(PAYLOAD_PROPERTIES_ENTRY, 'FILE_SIZE=1\nFILE_HASH=x\n')
        if otacert is not None:
            z.writestr(OTACERT_ENTRY, otacert)
        if secondary:
            z.writestr(SECONDARY_PAYLOAD_BIN, make_payload(partitions))
            z.writestr(SECONDARY_PAYLOAD_PROPERTIES, 'FILE_SIZE=1\n')
    return path


CLEAN_SET = sorted(DEFAULT_KEEP_SET)
FULL_SET = sorted(DEFAULT_KEEP_SET | GOOGLE_FIRMWARE_PARTITIONS - {'bootloader', 'radio'})


def selftest():
    import contextlib
    failures = []
    tmp = tempfile.mkdtemp(prefix='partial-ota-selftest-')
    vb_ok = os.path.join(tmp, 'vbmeta-ok.img')
    with open(vb_ok, 'wb') as f:
        f.write(make_vbmeta(['boot', 'dtbo', 'init_boot', 'pvmfw', 'vendor_boot',
                             'vendor_kernel_boot'],
                            ['product', 'system', 'system_dlkm', 'system_ext', 'vendor',
                             'vendor_dlkm']))
    vb_chain = os.path.join(tmp, 'vbmeta-chained.img')
    with open(vb_chain, 'wb') as f:
        f.write(make_vbmeta(['dtbo'], ['vendor_dlkm'], chains=['vbmeta_system', 'boot']))

    def run(name, want_rc, want_txt, argv):
        buf = io.StringIO()
        try:
            with contextlib.redirect_stdout(buf):
                rc = main(argv)
        except SystemExit as e:
            rc = e.code if isinstance(e.code, int) else 2
        out = buf.getvalue()
        if rc != want_rc:
            failures.append((name, 'exit %s, expected %s' % (rc, want_rc), out))
            print('SELFTEST FAIL  %-52s exit %s, expected %s' % (name, rc, want_rc))
            return
        if want_txt not in out:
            failures.append((name, 'never said %r' % want_txt, out))
            print('SELFTEST FAIL  %-52s exit %s ok, but never said "%s"'
                  % (name, rc, want_txt))
            return
        print('SELFTEST ok    %-52s exit %s, said "%s"' % (name, rc, want_txt))

    p = os.path.join

    clean = make_ota(p(tmp, 'clean.zip'), CLEAN_SET, partial=True)
    run('a correct partial passes', 0, 'OTA OK',
        [clean, '--vbmeta', vb_ok, '--expect-otacert', cert_fingerprint(FIXTURE_CERT.encode()),
         '--target-files', _fixture_tf(tmp)])

    full = make_ota(p(tmp, 'full.zip'), FULL_SET)
    run('Google firmware partitions fire', 1, 'Google firmware partitions are inside',
        [full, '--vbmeta', vb_ok, '--target-files', _fixture_tf(tmp)])

    brick = make_ota(p(tmp, 'brick.zip'), ['system', 'system_ext', 'product', 'vbmeta'],
                     partial=True)
    run('vbmeta covering an unshipped partition fires', 1, 'THIS PAYLOAD WILL NOT BOOT',
        [brick, '--vbmeta', vb_ok, '--target-files', _fixture_tf(tmp)])

    novb = make_ota(p(tmp, 'novb.zip'), ['system', 'system_ext', 'product'], partial=True)
    run('AVB partitions without vbmeta fires', 1, 'does NOT ship `vbmeta`',
        [novb, '--vbmeta', vb_ok, '--target-files', _fixture_tf(tmp)])

    noflag = make_ota(p(tmp, 'noflag.zip'), CLEAN_SET)
    run('partial without partial_update fires', 1, 'NOT marked partial_update',
        [noflag, '--vbmeta', vb_ok, '--target-files', _fixture_tf(tmp)])

    sec = make_ota(p(tmp, 'sec.zip'), CLEAN_SET, partial=True, secondary=True)
    run('secondary payload fires', 1, 'carries a SECONDARY payload',
        [sec, '--vbmeta', vb_ok, '--target-files', _fixture_tf(tmp)])

    outside = make_ota(p(tmp, 'outside.zip'), CLEAN_SET + ['persist'], partial=True)
    run('a partition outside the keep-set fires', 1, 'outside the keep-set',
        [outside, '--vbmeta', vb_ok, '--target-files', _fixture_tf(tmp)])

    badcert = make_ota(p(tmp, 'badcert.zip'), CLEAN_SET, partial=True)
    run('a mismatched otacert fires', 1, 'does NOT match the pinned certificate',
        [badcert, '--vbmeta', vb_ok, '--target-files', _fixture_tf(tmp),
         '--expect-otacert', '00' * 32])

    oldspl = make_ota(p(tmp, 'oldspl.zip'), CLEAN_SET, partial=True,
                      metadata=DEFAULT_METADATA.replace('2026-08-05', '2026-05-05'))
    run('an SPL below the floor fires', 1, 'older than the floor',
        [oldspl, '--vbmeta', vb_ok, '--target-files', _fixture_tf(tmp), '--min-security-patch',
         '2026-08'])

    skew = make_ota(p(tmp, 'skew.zip'), CLEAN_SET, partial=True, max_timestamp=1)
    run('max_timestamp disagreeing with the metadata fires', 1, 'disagrees with post-timestamp',
        [skew, '--vbmeta', vb_ok, '--target-files', _fixture_tf(tmp)])

    run('no vbmeta supplied: refuses, does not pass', 2, 'no copy of that vbmeta.img was supplied',
        [clean, '--target-files', _fixture_tf(tmp)])

    run('a chained vbmeta: refuses, does not pass', 2, 'chain_partition descriptor',
        [clean, '--vbmeta', vb_chain, '--target-files', _fixture_tf(tmp)])

    lonely_root = tempfile.mkdtemp(prefix='partial-ota-lonely-')
    lonely = make_ota(p(lonely_root, 'x.zip'), CLEAN_SET, partial=True)
    run('no target_files retained: refuses, does not pass', 2, 'no *target_files*.zip found',
        [lonely, '--vbmeta', vb_ok,
         '--expect-otacert', cert_fingerprint(FIXTURE_CERT.encode())])
    shutil.rmtree(lonely_root, ignore_errors=True)

    empty = p(tmp, 'empty.zip')
    with zipfile.ZipFile(empty, 'w') as z:
        z.writestr('README', 'nothing here')
    run('a non-OTA zip: refuses, does not pass', 2, 'does not look like an A/B OTA package',
        [empty, '--vbmeta', vb_ok])

    broken = p(tmp, 'broken.zip')
    with zipfile.ZipFile(broken, 'w') as z:
        z.writestr(METADATA_ENTRY, DEFAULT_METADATA)
        z.writestr(PAYLOAD_ENTRY, b'CrAU' + struct.pack('>Q', 2) + struct.pack('>Q', 4)
                   + struct.pack('>I', 0) + b'\0\0\0\0')
        z.writestr(PAYLOAD_PROPERTIES_ENTRY, 'FILE_SIZE=1\n')
        z.writestr(OTACERT_ENTRY, FIXTURE_CERT)
    run('a manifest naming zero partitions: refuses', 2, 'named ZERO partitions',
        [broken, '--vbmeta', vb_ok, '--target-files', _fixture_tf(tmp)])

    shutil.rmtree(tmp, ignore_errors=True)
    print('')
    if failures:
        print('SELFTEST FAILED -- the checks in this file are NOT proved. Do not rely on them.',
              file=sys.stderr)
        for name, why, out in failures:
            print('  %s: %s' % (name, why), file=sys.stderr)
            for line in out.splitlines():
                print('    | %s' % line, file=sys.stderr)
        return 1
    print('SELFTEST PASS -- every check fired on a payload carrying its defect, the clean')
    print('                partial passed, and every "cannot tell" case exited 2 rather than 0.')
    return 0


def _fixture_tf(tmp):
    path = os.path.join(tmp, 'stallion-target_files.zip')
    if not os.path.exists(path):
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('SYSTEM/build.prop', 'ro.build.version.incremental=rist.2026082902\n')
    return path


def main(argv=None):
    ap = argparse.ArgumentParser(
        prog='check_partial_ota.py',
        description='Refuse to publish an OTA package carrying Google firmware, or one whose '
                    'vbmeta does not match the partitions it ships.',
        epilog='Exit 0 clean, 1 findings, 2 could not tell. Both non-zero codes mean do not '
               'publish.')
    ap.add_argument('package', nargs='?', help='the *-ota_update-*.zip about to be served')
    ap.add_argument('--keep-set', metavar='"a b c"',
                    help='partitions this payload is allowed to carry. Space or comma separated. '
                         'Default: the full AVB-covered set (%s).'
                         % ' '.join(sorted(DEFAULT_KEEP_SET)))
    ap.add_argument('--vbmeta', metavar='PATH',
                    help='the vbmeta.img this release ships, for the consistency check')
    ap.add_argument('--target-files', metavar='ZIP',
                    help='the target_files package this OTA was built from. Used to find a '
                         'vbmeta.img when --vbmeta is not given, and for the retention check.')
    ap.add_argument('--expect-otacert', metavar='SHA256|PEM',
                    help='pin the package otacert: a sha256 hex digest of the DER, or a path to '
                         'the certificate')
    ap.add_argument('--min-security-patch', metavar='YYYY-MM[-DD]',
                    help='refuse a package whose security patch level is older than this')
    ap.add_argument('--selftest', action='store_true',
                    help='build fixtures, run every check against a payload carrying its defect, '
                         'and assert both the exit code and the sentence')
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()
    if not args.package:
        ap.print_usage(sys.stderr)
        print('check_partial_ota.py: a package is required (or --selftest)', file=sys.stderr)
        return 2

    keep = frozenset(split_partitions(args.keep_set)) if args.keep_set else DEFAULT_KEEP_SET
    if not keep:
        print('--keep-set parsed to nothing. An empty keep-set would fail every partition and '
              'tell you nothing.', file=sys.stderr)
        return 2

    rep = Report()
    print('=== check_partial_ota: %s' % args.package)
    check(args.package, keep, args.vbmeta, args.target_files, args.expect_otacert,
          args.min_security_patch, rep)
    return rep.verdict(args.package)


if __name__ == '__main__':
    sys.exit(main())
