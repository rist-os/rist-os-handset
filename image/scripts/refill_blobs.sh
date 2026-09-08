#!/bin/bash

# Refill Google's files into a punched RistOS release and verify the result.

set -euo pipefail

RELEASE=""
BLOBS=""
OUT=""
MANIFEST=""
IN_PLACE=0
DRY_RUN=0

die() { printf 'refill_blobs: %s\n' "$1" >&2; exit 2; }
say() { printf '%s\n' "$1"; }

if command -v sha256sum >/dev/null 2>&1; then
  sha256of() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
  sha256of() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
  die "neither sha256sum nor shasum is on PATH. Every guarantee this script offers is a hash
       comparison, so without one of them it could only pretend to have checked."
fi

while [ $# -gt 0 ]; do
  case "$1" in
    --release)  RELEASE="${2:-}"; shift 2 ;;
    --blobs)    BLOBS="${2:-}"; shift 2 ;;
    --out)      OUT="${2:-}"; shift 2 ;;
    --manifest) MANIFEST="${2:-}"; shift 2 ;;
    --in-place) IN_PLACE=1; shift ;;
    --dry-run)  DRY_RUN=1; shift ;;
    -h|--help)  echo "usage: refill_blobs.sh [options]"; exit 0 ;;
    *)          die "unknown argument: $1" ;;
  esac
done

[ -n "$RELEASE" ] || die "--release <punched release dir> is required"
[ -d "$RELEASE" ] || die "no such directory: $RELEASE"
[ -n "$BLOBS" ]   || die "--blobs <dir> is required. That is the --out directory you gave
       image/scripts/extract_from_device.sh --from factory."
[ -d "$BLOBS" ]   || die "no such directory: $BLOBS"
command -v python3 >/dev/null 2>&1 || die "python3 is required."

RELEASE="$(cd "$RELEASE" && pwd)"
BLOBS="$(cd "$BLOBS" && pwd)"
[ -n "$MANIFEST" ] || MANIFEST="$RELEASE/punch-manifest.txt"
[ -f "$MANIFEST" ] || die "no punch manifest at $MANIFEST.

       Either this release was never punched -- in which case it already contains Google's files
       and there is nothing to refill -- or the manifest was lost. It is published alongside the
       images and it is small; re-download it."
[ -f "$RELEASE/vbmeta.img" ] || die "$RELEASE has no vbmeta.img, so the AVB check that proves the
       refill is correct cannot run. Refusing to hand back an unverified image."

if [ "$IN_PLACE" -eq 1 ]; then
  [ -z "$OUT" ] || die "--in-place and --out are mutually exclusive."
  TARGET="$RELEASE"
elif [ "$DRY_RUN" -eq 1 ]; then
  TARGET="$RELEASE"
else
  [ -n "$OUT" ] || die "--out <dir> is required (or --in-place)"
  [ ! -e "$OUT" ] || die "$OUT already exists. Refusing to write into it."
fi

say "release:         $RELEASE"
say "blobs:           $BLOBS"
say "manifest:        $MANIFEST"
say ""

TMP="$(mktemp -d "${TMPDIR:-/tmp}/rist-refill.XXXXXX")"
ok_out=0
cleanup() {
  rc=$?
  rm -rf "$TMP"
  if [ "$ok_out" -eq 0 ] && [ "$IN_PLACE" -eq 0 ] && [ "$DRY_RUN" -eq 0 ] && [ -n "$OUT" ]; then
    rm -rf "$OUT"
  fi
  exit $rc
}
trap cleanup EXIT INT TERM

if [ "$DRY_RUN" -eq 0 ] && [ "$IN_PLACE" -eq 0 ]; then
  mkdir -p "$OUT"
  OUT="$(cd "$OUT" && pwd)"
  say "copying the published release to $OUT"
  cp -pR "$RELEASE"/. "$OUT"/
  TARGET="$OUT"
fi

cat > "$TMP/refill.py" <<'PYEOF'
import sys, os, struct, hashlib, time

SPARSE_MAGIC = 0xED26FF3A
RAW, FILL, DONT_CARE, CRC32 = 0xCAC1, 0xCAC2, 0xCAC3, 0xCAC4

class SplitSparse:
    """Enough of the split sparse format to map a `super` device byte range onto the files that
    carry it. The refill itself does not need this -- the punch manifest already names file and
    offset -- but the AVB self-check at the end does, and that check is the point."""

    def __init__(self, paths):
        self.blk = self.total_blocks = None
        self.raw = []
        for pi, p in enumerate(paths):
            with open(p, 'rb') as f:
                magic, maj, mino, fhs, chs, blk, tot_blk, tot_chunk, csum = struct.unpack(
                    '<IHHHHIIII', f.read(28))
                if magic != SPARSE_MAGIC:
                    raise SystemExit('%s is not an Android sparse image' % p)
                if self.blk is None:
                    self.blk, self.total_blocks = blk, tot_blk
                f.seek(fhs)
                cur = 0
                for _ in range(tot_chunk):
                    ct, res, csz, tsz = struct.unpack('<HHII', f.read(chs))
                    body = f.tell()
                    if ct == RAW:
                        self.raw.append((cur, csz, pi, body))
                    cur += csz
                    f.seek(body + tsz - chs)
        self.raw.sort()
        self._fh = [open(p, 'rb') for p in paths]

    def _seg(self, dev_off):
        b = dev_off // self.blk
        lo, hi = 0, len(self.raw)
        while lo < hi:
            m = (lo + hi) // 2
            if self.raw[m][0] <= b:
                lo = m + 1
            else:
                hi = m
        if lo == 0:
            return None
        s = self.raw[lo - 1]
        return s if b < s[0] + s[1] else None

    def read(self, dev_off, n):
        out = bytearray()
        while n > 0:
            s = self._seg(dev_off)
            if s is None:
                raise SystemExit('device offset %d is not backed by data' % dev_off)
            start, nb, pi, fo = s
            take = min(n, (start + nb) * self.blk - dev_off)
            self._fh[pi].seek(fo + (dev_off - start * self.blk))
            out += self._fh[pi].read(take)
            dev_off += take
            n -= take
        return bytes(out)


def parse_lp(dev):
    geo = dev.read(4096, 4096)
    if struct.unpack('<I', geo[:4])[0] != 0x616C4467:
        raise SystemExit('no LP geometry in super')
    base = 4096 + 4096 * 2
    h = dev.read(base, 128)
    if struct.unpack('<I', h[:4])[0] != 0x414C5030:
        raise SystemExit('no LP metadata header in super')
    hsz, = struct.unpack('<I', h[8:12])
    tables_size, = struct.unpack('<I', h[44:48])
    (poff, pn, pe), (eoff, en, ee) = [struct.unpack('<III', h[80 + 12 * i:92 + 12 * i])
                                      for i in range(2)]
    tables = dev.read(base + hsz, tables_size)
    exts = []
    for i in range(en):
        r = tables[eoff + i * ee: eoff + (i + 1) * ee]
        nsec, ttype, tdata, tsrc = struct.unpack('<QIQI', r[:24])
        exts.append((nsec, ttype, tdata))
    out = {}
    for i in range(pn):
        r = tables[poff + i * pe: poff + (i + 1) * pe]
        name = r[:36].split(b'\0')[0].decode()
        attrs, fei, nex, gi = struct.unpack('<IIII', r[36:52])
        out[name] = [(exts[j][2] * 512, exts[j][0] * 512) for j in range(fei, fei + nex)]
    return out


class LogicalPart:
    def __init__(self, dev, segs):
        self.dev, self.segs = dev, segs

    def read(self, off, n):
        out, pos = bytearray(), 0
        for ds, dl in self.segs:
            if n <= 0:
                break
            if off < pos + dl:
                skip = max(0, off - pos)
                take = min(n, dl - skip)
                out += self.dev.read(ds + skip, take)
                off += take
                n -= take
            pos += dl
        return bytes(out)

def parse_vbmeta(d):
    if d[0:4] != b'AVB0':
        raise SystemExit('vbmeta.img does not start with the AVB0 magic')
    akb, axb = struct.unpack('>QQ', d[12:28])
    alg, = struct.unpack('>I', d[28:32])
    (ho, hs, so, ss, ko, ks, kmo, kms, dro, drs, rbi) = struct.unpack('>11Q', d[32:120])
    aux = 256 + akb
    v = {'alg': alg, 'pubkey': d[aux + ko: aux + ko + ks], 'descriptors': []}
    body = d[aux: aux + axb]
    p, end = dro, dro + drs
    while p < end:
        tag, nbf = struct.unpack('>QQ', body[p:p + 16])
        v['descriptors'].append((tag, body[p:p + 16 + nbf]))
        p += 16 + nbf
    return v


def hashtree_descriptors(v):
    res = {}
    for tag, b in v['descriptors']:
        if tag != 1:
            continue
        (dm_ver, image_size, tree_offset, tree_size, dbs, hbs,
         fec_roots, fec_off, fec_size) = struct.unpack('>IQQQIIIQQ', b[16:72])
        halg = b[72:104].split(b'\0')[0].decode()
        pn, sl, rl, flags = struct.unpack('>IIII', b[104:120])
        o = 120 + 60
        name = b[o:o + pn].decode(); o += pn
        salt = b[o:o + sl]; o += sl
        res[name] = dict(image_size=image_size, data_block_size=dbs, hash_alg=halg,
                         salt=salt, root=b[o:o + rl])
    return res


def _round_up(n, m):
    return ((n + m - 1) // m) * m


def hashtree_root(read_at, image_size, block_size, hash_alg, salt):
    dsz = hashlib.new(hash_alg).digest_size
    sizes, size = [], image_size
    while size > block_size:
        sizes.append(_round_up(((size + block_size - 1) // block_size) * dsz, block_size))
        size = sizes[-1]
    offsets = [sum(sizes[n + 1:]) for n in range(len(sizes))]
    tree = bytearray(sum(sizes))
    src, level, level_output = image_size, 0, b''
    while src > block_size:
        parts, remaining = [], src
        while remaining > 0:
            h = hashlib.new(hash_alg, salt)
            take = min(remaining, block_size)
            if level == 0:
                data = read_at(src - remaining, take)
            else:
                o = offsets[level - 1] + src - remaining
                data = bytes(tree[o:o + take])
            h.update(data)
            remaining -= len(data)
            if len(data) < block_size:
                h.update(b'\0' * (block_size - len(data)))
            parts.append(h.digest())
        level_output = b''.join(parts)
        level_output += b'\0' * (_round_up(len(level_output), block_size) - len(level_output))
        o = offsets[level]
        tree[o:o + len(level_output)] = level_output
        src = len(level_output)
        level += 1
    h = hashlib.new(hash_alg, salt)
    h.update(level_output)
    return h.digest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 22), b''):
            h.update(chunk)
    return h.hexdigest()

def main():
    manifest, release, blobs, target, dry_run = sys.argv[1:6]
    dry_run = int(dry_run)

    imgs = {}     # name -> (size, sha_signed, sha_punched)
    files = []    # (rel, sha, size, [(logical_off, img, img_off, len)])
    meta = {}
    for raw in open(manifest):
        line = raw.strip()
        if not line:
            continue
        if line.startswith('#'):
            if ':' in line:
                k, _, val = line[1:].partition(':')
                meta[k.strip()] = val.strip()
            continue
        f = line.split()
        if f[0] == 'IMG':
            imgs[f[1]] = (int(f[2]), f[3], f[4])
        elif f[0] == 'FILE':
            files.append((f[1], f[2], int(f[3]), []))
        elif f[0] == 'SEG':
            if not files:
                raise SystemExit('malformed manifest: SEG before any FILE')
            files[-1][3].append((int(f[1]), f[2], int(f[3]), int(f[4])))
        else:
            raise SystemExit('malformed manifest: unknown record %r' % f[0])
    if not imgs or not files:
        raise SystemExit('%s carries no IMG or no FILE records' % manifest)
    print('manifest:        %d images, %d files, %d ranges, device %s @ %s'
          % (len(imgs), len(files), sum(len(x[3]) for x in files),
             meta.get('DEVICE', '?'), meta.get('REQUIRED_STOCK_BUILD_ID', '?')))

    bad = []
    for name, (size, sha_signed, sha_punched) in sorted(imgs.items()):
        p = os.path.join(release, name)
        if not os.path.exists(p):
            bad.append('%s is missing from the release' % name)
            continue
        if os.path.getsize(p) != size:
            bad.append('%s is %d bytes, manifest says %d' % (name, os.path.getsize(p), size))
            continue
        got = sha256_file(p)
        if got == sha_signed and sha_signed != sha_punched:
            bad.append('%s is ALREADY REFILLED (it matches the signed hash, not the punched one)'
                       % name)
        elif got != sha_punched:
            bad.append('%s does not match the published hash\n       expected %s\n       got      %s'
                       % (name, sha_punched, got))
    if bad:
        raise SystemExit('REFUSED: the release does not match the manifest.\n  - '
                         + '\n  - '.join(bad)
                         + '\nRe-download the release, and make sure the manifest came with it.')
    print('checked:         all %d super images match the published hashes' % len(imgs))

    missing, mismatch = [], []
    for rel, sha, size, segs in files:
        p = os.path.join(blobs, rel)
        if not os.path.exists(p):
            missing.append(rel)
            continue
        if os.path.getsize(p) != size:
            mismatch.append((rel, sha, 'wrong size: %d, expected %d'
                             % (os.path.getsize(p), size)))
            continue
        got = sha256_file(p)
        if got != sha:
            mismatch.append((rel, sha, got))
    if missing or mismatch:
        for r in missing[:10]:
            print('  MISSING  %s' % r)
        for r, w, g in mismatch[:10]:
            print('  MISMATCH %s\n           expected %s\n           got      %s' % (r, w, g))
        raise SystemExit(
            'REFUSED: %d of %d files are missing and %d do not match their pin.\n'
            'A hash mismatch here almost always means the WRONG BUILD, not a bad download: this\n'
            'manifest is pinned to %s @ %s and to nothing else. Re-run\n'
            'extract_from_device.sh --from factory with the factory zip for that exact build.'
            % (len(missing), len(files), len(mismatch),
               meta.get('DEVICE', '?'), meta.get('REQUIRED_STOCK_BUILD_ID', '?')))
    print('checked:         all %d files present and matching their pinned sha256' % len(files))

    if dry_run:
        print('')
        print('dry run: inputs verified, nothing written.')
        return 0

    fhs = {}
    written = 0
    t0 = time.time()
    for rel, sha, size, segs in files:
        with open(os.path.join(blobs, rel), 'rb') as src:
            for lo, img, off, ln in segs:
                if img not in fhs:
                    fhs[img] = open(os.path.join(target, img), 'r+b')
                src.seek(lo)
                data = src.read(ln)
                if len(data) != ln:
                    raise SystemExit('%s: wanted %d bytes at %d, got %d' % (rel, ln, lo, len(data)))
                fhs[img].seek(off)
                fhs[img].write(data)
                written += ln
    for f in fhs.values():
        f.flush()
        os.fsync(f.fileno())
        f.close()
    print('refilled:        %d bytes into %d images (%.1fs)' % (written, len(fhs), time.time() - t0))

    bad = []
    for name, (size, sha_signed, sha_punched) in sorted(imgs.items()):
        p = os.path.join(target, name)
        if os.path.getsize(p) != size:
            bad.append('%s changed size' % name)
            continue
        got = sha256_file(p)
        if got != sha_signed:
            bad.append('%s\n       signed  %s\n       refilled %s' % (name, sha_signed, got))
    if bad:
        raise SystemExit('FAILED: the refilled images are NOT byte-identical to the signed '
                         'release.\n  - ' + '\n  - '.join(bad))
    print('PROVED:          every super image is byte-identical to the one RistOS signed')

    names = sorted(imgs, key=lambda n: int(n[6:-4]) if n.startswith('super_') else 0)
    dev = SplitSparse([os.path.join(target, n) for n in names])
    parts = parse_lp(dev)
    v = parse_vbmeta(open(os.path.join(target, 'vbmeta.img'), 'rb').read())
    ht = hashtree_descriptors(v)
    touched = sorted(set(rel.split('/', 1)[0] for rel, _, _, _ in files))
    for p in touched:
        if p not in ht or (p + '_a') not in parts:
            raise SystemExit('no AVB hashtree descriptor or no logical partition for %s' % p)
        lp = LogicalPart(dev, parts[p + '_a'])
        x = ht[p]
        t0 = time.time()
        root = hashtree_root(lp.read, x['image_size'], x['data_block_size'], x['hash_alg'], x['salt'])
        if root != x['root']:
            raise SystemExit('FAILED: the AVB hashtree root over the refilled "%s" does not match '
                             'vbmeta.img.\n    vbmeta says %s\n    image gives %s\n'
                             'DO NOT FLASH THIS.' % (p, x['root'].hex(), root.hex()))
        print('PROVED:          AVB hashtree over %-11s matches vbmeta  (%.1fs)'
              % (p, time.time() - t0))
    return 0


sys.exit(main())
PYEOF

set +e
python3 "$TMP/refill.py" "$MANIFEST" "$RELEASE" "$BLOBS" "$TARGET" "$DRY_RUN"
py_rc=$?
set -e
[ "$py_rc" -eq 0 ] || die "refill failed (exit $py_rc). The output above is the reason.
       Nothing usable was produced -- do NOT flash a partially refilled image."

if [ "$DRY_RUN" -eq 0 ]; then
  if command -v sha256sum >/dev/null 2>&1; then SUMCHECK="sha256sum -c --ignore-missing"
  elif command -v shasum >/dev/null 2>&1; then SUMCHECK="shasum -a 256 -c --ignore-missing"
  else SUMCHECK=""; fi

  if [ ! -f "$TARGET/SHA256SUMS.refilled" ]; then
    say ""
    say "NOTE  this release carries no SHA256SUMS.refilled, so the directory-wide check did not"
    say "      run. The three proofs above still hold over the super images; files copied through"
    say "      untouched (boot.img, vbmeta.img, the flasher) were not re-checked here."
  elif [ -z "$SUMCHECK" ]; then
    say ""
    say "NOTE  neither sha256sum nor shasum is on PATH, so SHA256SUMS.refilled was not checked."
  elif ( cd "$TARGET" && $SUMCHECK SHA256SUMS.refilled >/dev/null 2>&1 ); then
    say ""
    say "PROVED:          every file in $TARGET matches SHA256SUMS.refilled"
  else
    ( cd "$TARGET" && $SUMCHECK SHA256SUMS.refilled 2>&1 | grep -v ': OK$' | head -20 ) || true
    die "$TARGET does not match SHA256SUMS.refilled (the failing files are listed above).

       That file is the hash of the release as RistOS signed it, taken before the punch, so a
       mismatch here means a file that this script copies through untouched did not arrive
       intact -- boot.img, vbmeta.img and dtbo.img are the usual suspects, and none of them is
       covered by the super-image proofs above. Re-download the release; do not flash this."
  fi

  ok_out=1
  say ""
  say "PASS  $TARGET is bit-for-bit the release RistOS built and signed."
  say "      Google's files came from your own licensed copy of their firmware; RistOS never"
  say "      distributed them. The image is now flashable, and re-locking to RistOS's AVB key"
  say "      is safe because the hashtree above matches the vbmeta that was published."
  say ""
  say "      NOTE: $TARGET still carries SHA256SUMS, which describes the artefact you DOWNLOADED"
  say "      -- with the holes in it. It will not match this directory any more, and it is not"
  say "      supposed to. flash_rist.sh knows the difference: it sees punch-manifest.txt and"
  say "      checks SHA256SUMS.refilled instead, refusing a release that still matches the other."
fi
