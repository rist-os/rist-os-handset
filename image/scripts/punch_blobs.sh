#!/bin/bash
# Usage: punch_blobs.sh --release <signed release dir> --out <dir> [--manifest <path>] [--measure] [--no-verify] [--dry-run]
# Punch strictly AFTER AVB signing and BEFORE the checksums are signed; both orderings are enforced below.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_DIR="$(cd "$HERE/.." && pwd)"

RELEASE=""
OUT=""
MANIFEST="$IMAGE_DIR/proprietary-files.txt"
MEASURE=0
VERIFY=1
DRY_RUN=0

die() { printf 'punch_blobs: %s\n' "$1" >&2; exit 2; }
say() { printf '%s\n' "$1"; }

if command -v sha256sum >/dev/null 2>&1; then
  sha256of() { sha256sum "$1" | cut -d' ' -f1; }
  SHA256_LINE="sha256sum"
elif command -v shasum >/dev/null 2>&1; then
  sha256of() { shasum -a 256 "$1" | cut -d' ' -f1; }
  SHA256_LINE="shasum -a 256"
else
  die "neither sha256sum nor shasum is on PATH. Everything this script asserts is a hash
       comparison, so without one of them it could only pretend to have checked."
fi

# write_sums <dir> <output file> [second name to exclude]
write_sums() {
  sums_dir="$1"; sums_out="$2"; sums_skip="${3:-}"; sums_ok=1
  sums_self="$(basename "$sums_out")"
  ( cd "$sums_dir" && find . -maxdepth 1 -type f ! -name "$sums_self" ! -name '*.minisig' -print \
      | LC_ALL=C sort \
      | while IFS= read -r f; do
          # An `&& ... && continue` one-liner here aborts the subshell under set -e.
          if [ -n "$sums_skip" ] && [ "$f" = "./$sums_skip" ]; then continue; fi
          $SHA256_LINE "$f" || exit 1
        done ) > "$sums_out" || sums_ok=0
  sums_n="$(wc -l < "$sums_out" | tr -d ' ')"
  if [ "$sums_ok" -ne 1 ] || [ "$sums_n" -eq 0 ]; then
    rm -f "$sums_out"
    die "failed to write $sums_self over $sums_dir ($sums_n lines). The errors above are the
       reason. Do not publish this directory: a release whose checksum file is empty is a release
       nobody downloading it can verify, and the empty file looks exactly like a good one."
  fi
  printf '%s' "$sums_n"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --release)   RELEASE="${2:-}"; shift 2 ;;
    --out)       OUT="${2:-}"; shift 2 ;;
    --manifest)  MANIFEST="${2:-}"; shift 2 ;;
    --measure)   MEASURE=1; shift ;;
    --no-verify) VERIFY=0; shift ;;
    --dry-run)   DRY_RUN=1; shift ;;
    -h|--help)   echo "usage: punch_blobs.sh [options]"; exit 0 ;;
    *)           die "unknown argument: $1" ;;
  esac
done

[ -n "$RELEASE" ] || die "--release <signed release dir> is required"
[ -d "$RELEASE" ] || die "no such directory: $RELEASE"
[ -n "$OUT" ] || [ "$DRY_RUN" -eq 1 ] || die "--out <dir> is required"
[ -f "$MANIFEST" ] || die "manifest not found: $MANIFEST"
command -v python3 >/dev/null 2>&1 || die "python3 is required to read the sparse/LP/ext4 layers."

RELEASE="$(cd "$RELEASE" && pwd)"

[ -f "$RELEASE/vbmeta.img" ] || die "$RELEASE has no vbmeta.img. This is not a RistOS release
       directory, and without vbmeta.img there is nothing to prove the artefact was signed --
       which is the one thing this script must not guess about."
ls "$RELEASE"/super_*.img >/dev/null 2>&1 || die "$RELEASE has no super_*.img. Expected the split
       sparse images produced by the release build."

for sig in "$RELEASE"/SHA256SUMS.minisig "$RELEASE"/SHA256SUMS.refilled.minisig; do
  [ -e "$sig" ] || continue
  die "$(basename "$sig") is already in $RELEASE, so the checksums were signed BEFORE the punch.

       That signature covers the unpunched bytes. The punch is about to change 190,178,315 of
       them, and this script cannot re-sign anything -- the release secret key is deliberately
       not on the build machine.

       Order: deblob -> PUNCH -> verify -> sign. Punch the unsigned artefact, pull the punched
       output back to the machine that holds the key, and sign the SHA256SUMS this script writes."
done

[ ! -e "$RELEASE/punch-manifest.txt" ] || die "$RELEASE already contains punch-manifest.txt, so it
       has been punched once already. Punching a punched artefact would zero bytes that are
       already zero and write a manifest whose 'as signed' hashes are the punched ones, which
       would make an unrefillable image look refillable. Start from the signed release."

TMP="$(mktemp -d "${TMPDIR:-/tmp}/rist-punch.XXXXXX")"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

# grep returns 1 on no match, which pipefail would turn into an exit.
{ grep -vE '^[[:space:]]*(#|$)' "$MANIFEST" || true; } > "$TMP/entries"
N_ENTRIES="$(wc -l < "$TMP/entries" | tr -d ' ')"
[ "$N_ENTRIES" -gt 0 ] || die "$MANIFEST lists no files. Nothing to punch."

unpinned="$({ grep -cvE '\|[0-9a-f]{64}$' "$TMP/entries" || true; })"
if [ "$unpinned" -gt 0 ]; then
  die "$unpinned of $N_ENTRIES manifest entries carry no sha256 pin. A hole is only refillable if
       the refill can prove it put the right bytes back, and an unpinned entry cannot."
fi

EXPECT_DEVICE="$(sed -n 's/^# DEVICE:[[:space:]]*//p' "$MANIFEST" | head -1)"
EXPECT_BUILD="$(sed -n 's/^# REQUIRED_STOCK_BUILD_ID:[[:space:]]*//p' "$MANIFEST" | head -1)"
[ -n "$EXPECT_DEVICE" ] || die "$MANIFEST carries no '# DEVICE:' line."
[ -n "$EXPECT_BUILD" ]  || die "$MANIFEST carries no '# REQUIRED_STOCK_BUILD_ID:' line."

MANIFEST_SHA="$(sha256of "$MANIFEST")"

say "release:         $RELEASE"
say "manifest:        $MANIFEST"
say "entries:         $N_ENTRIES files, all sha256-pinned"
say "expects:         $EXPECT_DEVICE @ $EXPECT_BUILD"
say ""

if [ "$DRY_RUN" -eq 0 ]; then
  [ ! -e "$OUT" ] || die "$OUT already exists. Refusing to punch into an existing directory --
       a half-overwritten release that still looks complete is exactly the artefact nobody
       should be able to publish by accident."
  mkdir -p "$OUT"
  OUT="$(cd "$OUT" && pwd)"
  say "copying release to $OUT"
  cp -pR "$RELEASE"/. "$OUT"/
  ok_out=0
  trap 'rc=$?; if [ "$ok_out" -eq 0 ] && [ -n "$OUT" ]; then rm -rf "$OUT"; fi; rm -rf "$TMP"; exit $rc' EXIT INT TERM
  TARGET="$OUT"

  # Taken before any byte is zeroed; SHA256SUMS is excluded because it is rewritten after the punch.
  say "hashing the signed release (before the punch) -> SHA256SUMS.refilled"
  N_REFILLED="$(write_sums "$TARGET" "$TARGET/SHA256SUMS.refilled" SHA256SUMS)"

  if [ -f "$TARGET/SHA256SUMS" ]; then
    if diff -u "$TARGET/SHA256SUMS" "$TARGET/SHA256SUMS.refilled" > "$TMP/sums.diff" 2>&1; then
      say "                 matches the SHA256SUMS deblob_release.sh wrote ($N_REFILLED files)"
    else
      head -40 "$TMP/sums.diff" >&2
      die "the SHA256SUMS in $RELEASE does not describe the files in $RELEASE (diff above).

       Something changed the release after deblob_release.sh wrote its checksums. Fix that first:
       every downstream guarantee -- the refill proof, the download check, the signature -- is
       computed from these bytes, so punching now would carry the discrepancy into all of them."
    fi
    rm -f "$TARGET/SHA256SUMS"
  else
    say "                 $N_REFILLED files (the input carried no SHA256SUMS of its own)"
  fi
  say ""
else
  TARGET="$RELEASE"
fi

cat > "$TMP/punch.py" <<'PYEOF'
import sys, os, struct, hashlib, zlib, time

SPARSE_MAGIC = 0xED26FF3A
RAW, FILL, DONT_CARE, CRC32 = 0xCAC1, 0xCAC2, 0xCAC3, 0xCAC4


class SplitSparse:

    def __init__(self, paths, writable=False):
        self.paths = paths
        self.blk = self.total_blocks = None
        self.raw = []          # (dev_block_start, nblocks, path_index, file_offset)
        self.chunk_counts = []
        for pi, p in enumerate(paths):
            with open(p, 'rb') as f:
                magic, maj, mino, fhs, chs, blk, tot_blk, tot_chunk, csum = struct.unpack(
                    '<IHHHHIIII', f.read(28))
                if magic != SPARSE_MAGIC:
                    raise SystemExit('%s is not an Android sparse image (magic %08x)' % (p, magic))
                if (maj, mino) != (1, 0):
                    raise SystemExit('%s is sparse v%d.%d; this tool understands v1.0' % (p, maj, mino))
                if self.blk is None:
                    self.blk, self.total_blocks = blk, tot_blk
                elif (blk, tot_blk) != (self.blk, self.total_blocks):
                    raise SystemExit('%s disagrees with the other splits on geometry' % p)
                self.chunk_counts.append(tot_chunk)
                f.seek(fhs)
                cur = 0
                for _ in range(tot_chunk):
                    ct, res, csz, tsz = struct.unpack('<HHII', f.read(chs))
                    body = f.tell()
                    if ct == RAW:
                        self.raw.append((cur, csz, pi, body))
                    elif ct == CRC32:
                        # A CRC32 chunk covers the preceding data; zeroing would invalidate it.
                        raise SystemExit('%s carries a CRC32 sparse chunk. Punching would '
                                         'invalidate it; this tool will not do that.' % p)
                    elif ct not in (FILL, DONT_CARE):
                        raise SystemExit('%s: unknown sparse chunk type 0x%x' % (p, ct))
                    cur += csz
                    f.seek(body + tsz - chs)
                if cur != tot_blk:
                    raise SystemExit('%s: chunks cover %d blocks, header says %d' % (p, cur, tot_blk))
        self.raw.sort()
        self._fh = [open(p, 'r+b' if writable else 'rb') for p in paths]

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

    def map_range(self, dev_off, n):
        """Device byte range -> [(path_index, file_offset, length)]."""
        out = []
        while n > 0:
            s = self._seg(dev_off)
            if s is None:
                raise SystemExit('device offset %d is inside a DONT_CARE hole; the image does not '
                                 'carry those bytes at all' % dev_off)
            start, nb, pi, fo = s
            take = min(n, (start + nb) * self.blk - dev_off)
            out.append((pi, fo + (dev_off - start * self.blk), take))
            dev_off += take
            n -= take
        return out

    def read(self, dev_off, n):
        out = bytearray()
        for pi, fo, ln in self.map_range(dev_off, n):
            self._fh[pi].seek(fo)
            out += self._fh[pi].read(ln)
        return bytes(out)


def parse_lp(dev):
    geo = dev.read(4096, 4096)
    magic, = struct.unpack('<I', geo[:4])
    if magic != 0x616C4467:
        raise SystemExit('no LP geometry at super offset 4096 (magic %08x). This does not look '
                         'like a dynamic-partition super image.' % magic)
    base = 4096 + 4096 * 2
    h = dev.read(base, 128)
    hmagic, maj, mino, hsz = struct.unpack('<IHHI', h[:12])
    if hmagic != 0x414C5030:
        raise SystemExit('no LP metadata header at super offset %d (magic %08x)' % (base, hmagic))
    tables_size, = struct.unpack('<I', h[44:48])
    descs = [struct.unpack('<III', h[80 + 12 * i:92 + 12 * i]) for i in range(4)]
    tables = dev.read(base + hsz, tables_size)
    (poff, pn, pe), (eoff, en, ee), _, _ = descs
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
        segs = []
        for j in range(fei, fei + nex):
            nsec, ttype, tdata = exts[j]
            if ttype != 0:
                raise SystemExit('partition %s has a non-LINEAR extent; unsupported' % name)
            segs.append((tdata * 512, nsec * 512))
        out[name] = segs
    return out


class LogicalPart:
    def __init__(self, dev, segs):
        self.dev, self.segs = dev, segs
        self.size = sum(l for _, l in segs)

    def _to_dev(self, off, n):
        out, pos = [], 0
        for ds, dl in self.segs:
            if n <= 0:
                break
            if off < pos + dl:
                skip = max(0, off - pos)
                take = min(n, dl - skip)
                out.append((ds + skip, take))
                off += take
                n -= take
            pos += dl
        if n > 0:
            raise SystemExit('read past the end of a logical partition')
        return out

    def read(self, off, n):
        return b''.join(self.dev.read(a, b) for a, b in self._to_dev(off, n))

    def map_range(self, off, n):
        out = []
        for a, b in self._to_dev(off, n):
            out += self.dev.map_range(a, b)
        return out


class Ext4:
    def __init__(self, dev):
        self.dev = dev
        sb = dev.read(1024, 1024)
        if struct.unpack('<H', sb[56:58])[0] != 0xEF53:
            raise SystemExit('not an ext4 filesystem (bad magic)')
        self.inodes_per_group, = struct.unpack('<I', sb[40:44])
        self.bs = 1024 << struct.unpack('<I', sb[24:28])[0]
        self.inode_size, = struct.unpack('<H', sb[88:90])
        feat_inc, = struct.unpack('<I', sb[96:100])
        self.desc_size, = struct.unpack('<H', sb[254:256])
        if not (feat_inc & 0x80) or self.desc_size == 0:
            self.desc_size = 32
        blocks_lo, = struct.unpack('<I', sb[4:8])
        blocks_hi, = struct.unpack('<I', sb[0x150:0x154])
        self.fs_bytes = ((blocks_hi << 32) | blocks_lo) * self.bs
        self.gd_block = 1 if self.bs > 1024 else 2
        self._gd = {}

    def blk(self, n, cnt=1):
        return self.dev.read(n * self.bs, cnt * self.bs)

    def itable(self, g):
        if g not in self._gd:
            d = self.dev.read(self.gd_block * self.bs + g * self.desc_size, self.desc_size)
            lo, = struct.unpack('<I', d[8:12])
            hi, = struct.unpack('<I', d[40:44]) if self.desc_size >= 40 else (0,)
            self._gd[g] = lo | (hi << 32)
        return self._gd[g]

    def inode(self, ino):
        g, i = (ino - 1) // self.inodes_per_group, (ino - 1) % self.inodes_per_group
        return self.dev.read(self.itable(g) * self.bs + i * self.inode_size, self.inode_size)

    @staticmethod
    def mode(raw):
        return struct.unpack('<H', raw[0:2])[0]

    @staticmethod
    def iflags(raw):
        return struct.unpack('<I', raw[32:36])[0]

    @staticmethod
    def size(raw):
        lo, = struct.unpack('<I', raw[4:8])
        hi, = struct.unpack('<I', raw[108:112])
        return lo | (hi << 32)

    def extents(self, raw):
        out = []

        def walk(buf):
            magic, ent, mx, depth, gen = struct.unpack('<HHHHI', buf[:12])
            if magic != 0xF30A:
                raise SystemExit('bad ext4 extent header')
            for k in range(ent):
                e = buf[12 + k * 12:24 + k * 12]
                if depth == 0:
                    lb, ln, shi, slo = struct.unpack('<IHHI', e)
                    if ln > 32768:
                        raise SystemExit('uninitialised extent; unsupported')
                    out.append((lb, (shi << 32) | slo, ln))
                else:
                    lb, llo, lhi, _ = struct.unpack('<IIHH', e)
                    walk(self.blk((lhi << 32) | llo))
        walk(raw[40:100])
        out.sort()
        return out

    def read_file(self, raw):
        sz = self.size(raw)
        data = bytearray(sz)
        for lb, pb, ln in self.extents(raw):
            s = lb * self.bs
            if s >= sz:
                continue
            n = min(ln * self.bs, sz - s)
            data[s:s + n] = self.blk(pb, ln)[:n]
        return bytes(data)

    def listdir(self, raw):
        out, data, p = {}, self.read_file(raw), 0
        while p < len(data) - 8:
            ino, rec, nl, ft = struct.unpack('<IHBB', data[p:p + 8])
            if rec < 8:
                break
            if ino and nl:
                out[data[p + 8:p + 8 + nl].decode('utf-8', 'replace')] = ino
            p += rec
        return out

    def resolve(self, path):
        raw, ino = self.inode(2), 2
        for part in [x for x in path.split('/') if x]:
            ents = self.listdir(raw)
            if part not in ents:
                return None, None
            ino = ents[part]
            raw = self.inode(ino)
        return ino, raw

    def data_ranges(self, raw):
        sz = self.size(raw)
        out = []
        for lb, pb, ln in self.extents(raw):
            s = lb * self.bs
            if s >= sz:
                continue
            n = min(ln * self.bs, sz - s)
            out.append((s, pb * self.bs, n))
        return out, sz

    def walk_files(self):
        seen = set()
        stack = [(self.inode(2), '')]
        while stack:
            raw, prefix = stack.pop()
            for name, ino in self.listdir(raw).items():
                if name in ('.', '..') or ino in seen:
                    continue
                r = self.inode(ino)
                m = self.mode(r) & 0xF000
                if m == 0x4000:
                    seen.add(ino)
                    stack.append((r, prefix + name + '/'))
                elif m == 0x8000:
                    yield prefix + name, self.size(r)


def parse_vbmeta(d):
    if d[0:4] != b'AVB0':
        raise SystemExit('vbmeta.img does not start with the AVB0 magic')
    akb, axb = struct.unpack('>QQ', d[12:28])
    alg, = struct.unpack('>I', d[28:32])
    (ho, hs, so, ss, ko, ks, kmo, kms, dro, drs, rbi) = struct.unpack('>11Q', d[32:120])
    aux = 256 + akb
    v = {'alg': alg, 'pubkey': d[aux + ko: aux + ko + ks],
         'release': d[128:176].split(b'\0')[0].decode(), 'descriptors': []}
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
        root = b[o:o + rl]
        res[name] = dict(image_size=image_size, tree_offset=tree_offset, tree_size=tree_size,
                         data_block_size=dbs, hash_alg=halg, salt=salt, root=root)
    return res


def _round_up(n, m):
    return ((n + m - 1) // m) * m


def hashtree_root(read_at, image_size, block_size, hash_alg, salt):
    """avbtool's generate_hash_tree, enough to recompute the root; digest padding is zero for sha256."""
    dsz = hashlib.new(hash_alg).digest_size
    if dsz & (dsz - 1):
        raise SystemExit('hash algorithm %s needs digest padding this tool does not implement'
                         % hash_alg)
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
    (release, target, entries_file, manifest_path, manifest_sha, device, build,
     dry_run, do_measure, do_verify) = sys.argv[1:11]
    dry_run, do_measure, do_verify = int(dry_run), int(do_measure), int(do_verify)

    imgs = sorted([n for n in os.listdir(release) if n.startswith('super_') and n.endswith('.img')],
                  key=lambda n: int(n[6:-4]))
    src_paths = [os.path.join(release, n) for n in imgs]
    dev = SplitSparse(src_paths)
    parts = parse_lp(dev)

    v = parse_vbmeta(open(os.path.join(release, 'vbmeta.img'), 'rb').read())
    if v['alg'] == 0:
        raise SystemExit(
            'REFUSED: vbmeta.img has algorithm_type 0 -- it is UNSIGNED (avbtool --algorithm NONE).\n'
            'Punching must happen strictly AFTER signing. Punching first makes the signature cover\n'
            'the zeros, and a correctly refilled image then fails verified boot on the user\'s\n'
            'phone, after re-lock, unrecoverably. Sign first, then punch the signed output.')
    pkmd = os.path.join(release, 'avb_pkmd.bin')
    if os.path.exists(pkmd):
        if open(pkmd, 'rb').read() != v['pubkey']:
            raise SystemExit('REFUSED: the public key inside vbmeta.img is not the one in '
                             'avb_pkmd.bin. This release was signed with a different key than it '
                             'ships, and the punch would be pinned to the wrong signature.')
    ht = hashtree_descriptors(v)
    print('vbmeta: %s, algorithm_type=%d, %d hashtree descriptors'
          % (v['release'], v['alg'], len(ht)))

    wanted = {}
    for line in open(entries_file):
        line = line.strip()
        if not line:
            continue
        rel, want = line.split('|', 1)
        p, _, path = rel.partition('/')
        wanted.setdefault(p, []).append((rel, path, want))

    # THE GATE: recompute the AVB hashtree root over every partition about to be touched.
    # An unequal root means unsigned, already punched, or a vbmeta from a different build.
    for p in sorted(wanted):
        lpname = p + '_a'
        if lpname not in parts:
            raise SystemExit('super carries no logical partition %s' % lpname)
        if p not in ht:
            raise SystemExit(
                'REFUSED: vbmeta.img carries no hashtree descriptor for "%s", so there is nothing\n'
                'to prove this artefact was signed over that partition. Punch only signed releases.' % p)
        lp = LogicalPart(dev, parts[lpname])
        x = ht[p]
        t0 = time.time()
        root = hashtree_root(lp.read, x['image_size'], x['data_block_size'], x['hash_alg'], x['salt'])
        if root != x['root']:
            raise SystemExit(
                'REFUSED: the AVB hashtree root recomputed over "%s" does not match the root in\n'
                'vbmeta.img.\n'
                '    vbmeta says  %s\n'
                '    image gives  %s\n'
                'Exactly three things cause this, and all three mean "do not punch":\n'
                '  1. the artefact is NOT SIGNED, or was signed before something changed it;\n'
                '  2. it has ALREADY BEEN PUNCHED -- punch once, from the signed original;\n'
                '  3. this vbmeta.img belongs to a DIFFERENT BUILD than these super images.\n'
                % (p, x['root'].hex(), root.hex()))
        print('  AVB hashtree over %-11s matches vbmeta  (%s, %.1fs)'
              % (p, root.hex()[:32] + '...', time.time() - t0))

    holes = []        # (rel, want, size, [(logical_off, img_index, img_off, len)])
    punch_bytes = hole_bytes = 0
    modes = {}
    for p in sorted(wanted):
        lp = LogicalPart(dev, parts[p + '_a'])
        fs = Ext4(lp)
        if fs.fs_bytes != ht[p]['image_size']:
            raise SystemExit('%s: ext4 says the filesystem is %d bytes, AVB signed %d. Refusing '
                             'to compute offsets against a layout that does not agree with itself.'
                             % (p, fs.fs_bytes, ht[p]['image_size']))
        for rel, path, want in sorted(wanted[p]):
            ino, raw = fs.resolve(path)
            if raw is None:
                raise SystemExit('REFUSED: %s is in the manifest but not in the image. The two '
                                 'lists have drifted; fix the manifest, do not punch.' % rel)
            if (fs.mode(raw) & 0xF000) != 0x8000:
                raise SystemExit('REFUSED: %s is not a regular file.' % rel)
            fl = fs.iflags(raw)
            if fl & 0x10000000:
                raise SystemExit('REFUSED: %s uses ext4 inline_data -- its content lives INSIDE '
                                 'the inode, so zeroing it would destroy filesystem metadata.' % rel)
            if not (fl & 0x80000):
                raise SystemExit('REFUSED: %s does not use extents (old block-map inode).' % rel)
            data = fs.read_file(raw)
            got = hashlib.sha256(data).hexdigest()
            if got != want:
                raise SystemExit(
                    'REFUSED: %s does not match its pin.\n    manifest %s\n    image    %s\n'
                    'Punching a hole the user cannot refill produces a device that does not boot, '
                    'so a mismatch here stops the release rather than shrinking it.' % (rel, want, got))
            modes[oct(fs.mode(raw) & 0o7777)] = modes.get(oct(fs.mode(raw) & 0o7777), 0) + 1
            rngs, sz = fs.data_ranges(raw)
            segs, covered = [], 0
            for logoff, poff, ln in rngs:
                # Every hole must land inside the AVB-signed area, never in the hashtree or FEC after it.
                if poff + ln > ht[p]['image_size']:
                    raise SystemExit('REFUSED: %s has a data block at partition offset %d, past the '
                                     'AVB image_size %d. That is hashtree territory.'
                                     % (rel, poff, ht[p]['image_size']))
                lo = logoff
                for pi, fo, tl in lp.map_range(poff, ln):
                    segs.append((lo, pi, fo, tl))
                    lo += tl
                covered += ln
            holes.append((rel, want, sz, segs))
            punch_bytes += covered
            hole_bytes += sz - covered

    n_listed = sum(len(x) for x in wanted.values())
    print('')
    print('resolved:        %d / %d files, every sha256 matching its pin' % (len(holes), n_listed))
    print('to be zeroed:    %d bytes across %d ranges' % (punch_bytes, sum(len(h[3]) for h in holes)))
    print('already zero:    %d bytes in ext4 holes (unallocated, nothing to punch)' % hole_bytes)
    print('modes:           %s' % ', '.join('%s x%d' % (k, n) for k, n in sorted(modes.items())))

    residue = []
    listed = set(h[0] for h in holes)
    for p in sorted(wanted):
        fs = Ext4(LogicalPart(dev, parts[p + '_a']))
        for full, sz in fs.walk_files():
            comp = full.split('/')
            if len(comp) < 3 or comp[-3] != 'oat':
                continue
            leaf = comp[-1]
            if leaf.endswith('.fsv_meta'):
                leaf = leaf[:-len('.fsv_meta')]
            ext = leaf.rsplit('.', 1)[-1]
            if ext not in ('odex', 'vdex', 'art'):
                continue
            stem = leaf[:-(len(ext) + 1)]
            base = '/'.join(comp[:-3])
            src = p + '/' + (base + '/' if base else '') + stem
            if not (src + '.apk' in listed or src + '.jar' in listed):
                continue
            if p + '/' + full in listed:
                continue
            residue.append((p + '/' + full, sz))
    if residue:
        print('')
        print('AOT RESIDUE -- NOT punched, and not punchable: %d files, %d bytes.'
              % (len(residue), sum(s for _, s in residue)))
        print('  These are .odex/.vdex our own dex2oat compiled from the Google bytecode above.')
        print('  They are absent from Google\'s factory image, so nothing can refill them; the only')
        print('  way to get them out of the artefact is to turn dexpreopt off for those packages.')
        for f, s in sorted(residue, key=lambda x: -x[1])[:4]:
            print('    %-72s %d' % (f, s))

    if dry_run:
        print('')
        print('dry run: every check passed, nothing was written.')
        return 0

    touched = sorted(set(pi for _, _, _, segs in holes for _, pi, _, _ in segs))
    before = {}
    for pi in touched:
        before[pi] = sha256_file(src_paths[pi])
    tgt_paths = [os.path.join(target, n) for n in imgs]
    print('')
    print('punching %d of %d super images' % (len(touched), len(imgs)))
    fhs = {pi: open(tgt_paths[pi], 'r+b') for pi in touched}
    zeros = b'\0' * (1 << 20)
    for rel, want, sz, segs in holes:
        for lo, pi, fo, ln in segs:
            f = fhs[pi]
            f.seek(fo)
            n = ln
            while n > 0:
                t = min(n, len(zeros))
                f.write(zeros[:t])
                n -= t
    for f in fhs.values():
        f.flush()
        os.fsync(f.fileno())
        f.close()

    after = {pi: sha256_file(tgt_paths[pi]) for pi in touched}
    for pi in range(len(imgs)):
        s, t = os.path.getsize(src_paths[pi]), os.path.getsize(tgt_paths[pi])
        if s != t:
            raise SystemExit('%s changed size (%d -> %d). Punching must never do that.'
                             % (imgs[pi], s, t))

    if do_verify:
        want_zero = {}
        for rel, w, sz, segs in holes:
            for lo, pi, fo, ln in segs:
                want_zero.setdefault(pi, []).append((fo, ln))
        for pi in touched:
            rs = sorted(want_zero[pi])
            merged = []
            for a, b in rs:
                if merged and a <= merged[-1][0] + merged[-1][1]:
                    e = max(merged[-1][0] + merged[-1][1], a + b)
                    merged[-1] = (merged[-1][0], e - merged[-1][0])
                else:
                    merged.append((a, b))
            fa, fb = open(src_paths[pi], 'rb'), open(tgt_paths[pi], 'rb')
            pos, mi = 0, 0
            size = os.path.getsize(src_paths[pi])
            while pos < size:
                nxt = merged[mi][0] if mi < len(merged) else size
                while pos < nxt:
                    n = min(1 << 22, nxt - pos)
                    if fa.read(n) != fb.read(n):
                        raise SystemExit('%s differs at offset %d, outside every recorded hole. '
                                         'The punch touched something it should not have.'
                                         % (imgs[pi], pos))
                    pos += n
                if mi < len(merged):
                    a, b = merged[mi]
                    fa.seek(a + b); fb.seek(a)
                    n = b
                    while n > 0:
                        t = min(1 << 22, n)
                        if fb.read(t) != b'\0' * t:
                            raise SystemExit('%s: hole at %d is not zero after punching'
                                             % (imgs[pi], a))
                        n -= t
                    pos = a + b
                    mi += 1
            fa.close(); fb.close()
        print('verified:        the only bytes that changed are the %d recorded holes, and every '
              'one of them is now zero' % sum(len(h[3]) for h in holes))

    measured = None
    if do_measure:
        # Level 9 is what the published install zip uses.
        def deflated(path):
            c = zlib.compressobj(9, zlib.DEFLATED, -15)
            n = 0
            with open(path, 'rb') as f:
                for chunk in iter(lambda: f.read(1 << 22), b''):
                    n += len(c.compress(chunk))
            return n + len(c.flush())
        b_tot = a_tot = 0
        for pi in touched:
            b_tot += deflated(src_paths[pi])
            a_tot += deflated(tgt_paths[pi])
        measured = (b_tot, a_tot)
        print('')
        print('download saving, measured (deflate -9 over the %d touched images):' % len(touched))
        print('  before punch   %d bytes' % b_tot)
        print('  after punch    %d bytes' % a_tot)
        print('  saved          %d bytes' % (b_tot - a_tot))

    mpath = os.path.join(target, 'punch-manifest.txt')
    with open(mpath, 'w') as m:
        m.write('# RistOS punch manifest v1 -- read by image/scripts/refill_blobs.sh\n')
        m.write('#\n')
        m.write('# Every FILE below had its data blocks zeroed in the images named by its SEG\n')
        m.write('# lines. Nothing here is Google content: it is offsets, lengths and hashes.\n')
        m.write('#\n')
        m.write('# DEVICE: %s\n' % device)
        m.write('# REQUIRED_STOCK_BUILD_ID: %s\n' % build)
        m.write('# SOURCE_MANIFEST: %s\n' % os.path.basename(manifest_path))
        m.write('# SOURCE_MANIFEST_SHA256: %s\n' % manifest_sha)
        m.write('# AVB_RELEASE_STRING: %s\n' % v['release'])
        m.write('# FILES: %d\n' % len(holes))
        m.write('# PUNCHED_BYTES: %d\n' % punch_bytes)
        m.write('# RANGES: %d\n' % sum(len(h[3]) for h in holes))
        if measured:
            m.write('# DEFLATE_BEFORE: %d\n# DEFLATE_AFTER: %d\n' % measured)
        m.write('#\n')
        m.write('# IMG  <name> <bytes> <sha256 as signed> <sha256 as published>\n')
        m.write('# FILE <path> <sha256> <size>\n')
        m.write('# SEG  <offset in file> <image> <offset in image> <length>\n')
        for pi in range(len(imgs)):
            if pi in before:
                m.write('IMG %s %d %s %s\n'
                        % (imgs[pi], os.path.getsize(tgt_paths[pi]), before[pi], after[pi]))
            else:
                h = sha256_file(tgt_paths[pi])
                m.write('IMG %s %d %s %s\n' % (imgs[pi], os.path.getsize(tgt_paths[pi]), h, h))
        for rel, want, sz, segs in holes:
            m.write('FILE %s %s %d\n' % (rel, want, sz))
            for lo, pi, fo, ln in segs:
                m.write('SEG %d %s %d %d\n' % (lo, imgs[pi], fo, ln))
    print('')
    print('manifest:        %s (%d bytes)' % (mpath, os.path.getsize(mpath)))
    return 0


sys.exit(main())
PYEOF

set +e
python3 "$TMP/punch.py" "$RELEASE" "$TARGET" "$TMP/entries" "$MANIFEST" "$MANIFEST_SHA" \
        "$EXPECT_DEVICE" "$EXPECT_BUILD" "$DRY_RUN" "$MEASURE" "$VERIFY"
py_rc=$?
set -e
[ "$py_rc" -eq 0 ] || die "punch failed (exit $py_rc). The output above is the reason, and
       nothing was published."

if [ "$DRY_RUN" -eq 0 ]; then
  # Must run after the punch AND after punch-manifest.txt is written, so every published file is covered.
  say ""
  say "hashing the punched release -> SHA256SUMS"
  N_PUBLISHED="$(write_sums "$OUT" "$OUT/SHA256SUMS")"

  ok_out=1
  say ""
  say "PASS  punched release written to $OUT"
  say "      Its super images are the same size and the same shape as the signed originals;"
  say "      only the data blocks of the $N_ENTRIES inventoried files are now zero."
  say ""
  say "      SHA256SUMS           $N_PUBLISHED files, over the PUNCHED artefact."
  say "                           This is what a downloader checks (image/INSTALL.md step 2) and"
  say "                           it is the file to minisign. It was written AFTER the punch."
  say "      SHA256SUMS.refilled  $N_REFILLED files, over the SIGNED artefact as it arrived here."
  say "                           This is what a correct refill reproduces, so it is the proof the"
  say "                           refill worked. flash_rist.sh checks a refilled release against"
  say "                           it, and refuses a release that still matches SHA256SUMS."
  say ""
  say "      NEXT: the user runs image/scripts/extract_from_device.sh --from factory to recover"
  say "      those files from their own copy of Google's firmware, then refill_blobs.sh to put"
  say "      them back. The result is byte-identical to the release you just signed."
  say ""
  say "      BEFORE PUBLISHING, in order:"
  say "        1. bash image/scripts/verify_public_artefact.sh $OUT"
  say "        2. sign the checksums on the machine that holds the key, NOT here:"
  say "              minisign -Sm $OUT/SHA256SUMS -s <your-secret-key>"
  say "           Sign SHA256SUMS.refilled too if you want the refill proof to be authenticated"
  say "           rather than merely present -- it is served from the same place, so on its own it"
  say "           tells a user only that their refill was self-consistent."
fi
