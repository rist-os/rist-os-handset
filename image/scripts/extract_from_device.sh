#!/bin/bash
# Usage: extract_from_device.sh --from factory|device --zip <factory.zip> --out <dir> [--manifest <path>] [--jobs <n>] [--keep-going] [--dry-run]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_DIR="$(cd "$HERE/.." && pwd)"

FROM=""
ZIP=""
OUT=""
MANIFEST="$IMAGE_DIR/proprietary-files.txt"
JOBS=1
KEEP_GOING=0
DRY_RUN=0

ADB="${ADB:-adb}"

die() { printf 'extract_from_device: %s\n' "$1" >&2; exit 2; }
say() { printf '%s\n' "$1"; }
warn() { printf 'WARN  %s\n' "$1" >&2; }

if command -v sha256sum >/dev/null 2>&1; then
  sha256of() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
  sha256of() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
  die "neither sha256sum nor shasum is on PATH. Every file this script extracts is verified
       against a pinned hash, so without one of them it could only pretend to have checked."
fi

while [ $# -gt 0 ]; do
  case "$1" in
    --from)      FROM="${2:-}"; shift 2 ;;
    --zip)       ZIP="${2:-}"; shift 2 ;;
    --out)       OUT="${2:-}"; shift 2 ;;
    --manifest)  MANIFEST="${2:-}"; shift 2 ;;
    --jobs)      JOBS="${2:-}"; shift 2 ;;
    --keep-going) KEEP_GOING=1; shift ;;
    --dry-run)   DRY_RUN=1; shift ;;
    -h|--help)   echo "usage: extract_from_device.sh [options]"; exit 0 ;;
    *)           die "unknown argument: $1" ;;
  esac
done

[ -n "$FROM" ] || die "--from is required (factory | device | ota)"
[ -n "$OUT" ]  || die "--out <dir> is required"
[ -f "$MANIFEST" ] || die "manifest not found: $MANIFEST"

case "$JOBS" in
  ''|*[!0-9]*) die "--jobs must be a positive integer, got '$JOBS'" ;;
  0)           die "--jobs must be at least 1" ;;
esac

# Manifest format: <partition>/<path>|<sha256>; comments start with #.
TMP="$(mktemp -d "${TMPDIR:-/tmp}/rist-extract.XXXXXX")"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

grep -vE '^[[:space:]]*(#|$)' "$MANIFEST" > "$TMP/entries" || true
N_ENTRIES="$(wc -l < "$TMP/entries" | tr -d ' ')"
[ "$N_ENTRIES" -gt 0 ] || die "$MANIFEST lists no files. Nothing to extract."

unpinned="$(grep -cvE '\|[0-9a-f]{64}$' "$TMP/entries" || true)"
if [ "$unpinned" -gt 0 ]; then
  die "$unpinned of $N_ENTRIES manifest entries carry no sha256 pin. Every file this script
       hands to a build must be verifiable; an unpinned entry is an unverifiable one. Regenerate
       the manifest with image/scripts/list_google_files.sh."
fi

EXPECT_DEVICE="$(sed -n 's/^# DEVICE:[[:space:]]*//p' "$MANIFEST" | head -1)"
EXPECT_BUILD="$(sed -n 's/^# REQUIRED_STOCK_BUILD_ID:[[:space:]]*//p' "$MANIFEST" | head -1)"
[ -n "$EXPECT_DEVICE" ] || die "$MANIFEST carries no '# DEVICE:' line. Refusing to guess which
       device these files belong to -- extracting a Pixel 9's blobs into a Pixel 10a build
       produces an image that flashes and then does not boot."
[ -n "$EXPECT_BUILD" ] || die "$MANIFEST carries no '# REQUIRED_STOCK_BUILD_ID:' line. Without it
       there is nothing to check a source against, and every hash mismatch below would be
       reported as a corrupt download rather than as the wrong build."

say "manifest:        $MANIFEST"
say "entries:         $N_ENTRIES files, all sha256-pinned"
say "expects:         $EXPECT_DEVICE @ $EXPECT_BUILD"
say ""

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

cut -d'|' -f1 "$TMP/entries" | cut -d'/' -f1 | sort -u > "$TMP/partitions"

OK=0
BAD=0
MISSING=0
verify_one() {
  local rel="$1" want="$2" path="$3"
  local got
  if [ ! -f "$path" ]; then
    MISSING=$((MISSING + 1))
    warn "MISSING  $rel"
    return 1
  fi
  got="$(sha256of "$path")"
  if [ "$got" != "$want" ]; then
    BAD=$((BAD + 1))
    warn "MISMATCH $rel"
    warn "         expected $want"
    warn "         got      $got"
    rm -f "$path"
    return 1
  fi
  OK=$((OK + 1))
  return 0
}

if [ "$FROM" = "ota" ]; then
  cat >&2 <<'EOF'
REFUSED: --from ota

payload.bin is a delta stream, not a filesystem; files cannot be pulled from it.

Use:  --from factory --zip <the factory zip for the build named in the manifest>
EOF
  exit 2
fi

if [ "$FROM" = "device" ]; then
  command -v "$ADB" >/dev/null 2>&1 || die "adb is not on PATH. Set ADB=/path/to/adb, or use
       --from factory, which needs no Android tooling at all."

  "$ADB" start-server >/dev/null 2>&1 || true
  n_dev="$("$ADB" devices | awk 'NR>1 && NF>=2 && $2=="device" {c++} END{print c+0}')"
  n_unauth="$("$ADB" devices | awk 'NR>1 && NF>=2 && $2!="device" {c++} END{print c+0}')"

  if [ "$n_dev" -eq 0 ] && [ "$n_unauth" -gt 0 ]; then
    die "a device is attached but is not authorised for adb ($("$ADB" devices | awk 'NR>1 && NF>=2 {print $2}' | tr '\n' ' ')).
       Unlock the phone and accept the 'Allow USB debugging?' prompt, then run this again."
  fi
  [ "$n_dev" -ne 0 ] || die "no device is attached. Enable Developer options -> USB debugging on
       the phone, plug it in, accept the authorisation prompt, and check with 'adb devices'.
       If the phone is not available, use --from factory instead -- it is the better source
       anyway and it is the only one that keeps working after RistOS is installed."
  [ "$n_dev" -eq 1 ] || die "$n_dev devices are attached. Detach all but one, or set
       ANDROID_SERIAL to the one you mean, so that files cannot be pulled from the wrong phone."

  dev="$("$ADB" shell getprop ro.product.device 2>/dev/null | tr -d '\r')"
  bid="$("$ADB" shell getprop ro.build.id 2>/dev/null | tr -d '\r')"
  fp="$("$ADB" shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r')"

  say "device:          $dev"
  say "build id:        $bid"
  say "fingerprint:     $fp"
  say ""

  [ "$dev" = "$EXPECT_DEVICE" ] || die "this is a '$dev', and the manifest is for '$EXPECT_DEVICE'.
       Extracting one Pixel's proprietary files into another Pixel's build produces an image that
       flashes cleanly and then does not boot, which is a far worse outcome than this refusal."

  # Fingerprint, not build id: RistOS keeps Google's build id and changes only the incremental.
  case "$fp" in
    *"/rist."*|*"/graphene"*|*"GrapheneOS"*)
      die "this phone is already running a RistOS/GrapheneOS build:
           $fp
       Its stock Google files were overwritten when that was installed, so there is nothing here
       to extract -- anything pulled now would be OUR files, and they would fail the hash check
       below. This is the fundamental limit of --from device: it works once, before you flash.
       Use --from factory --zip <factory zip for $EXPECT_BUILD>." ;;
  esac

  if [ "$bid" != "$EXPECT_BUILD" ]; then
    die "this phone is on build '$bid' and the manifest was measured against '$EXPECT_BUILD'.
       Every one of the $N_ENTRIES hash pins below is specific to that build, so continuing would
       produce $N_ENTRIES mismatches and no useful diagnosis. Either flash Google's stock
       $EXPECT_BUILD first, or use --from factory with the zip for $EXPECT_BUILD."
  fi

  if grep -q '^vendor$' "$TMP/partitions"; then
    if "$ADB" shell 'cat /vendor/build.prop >/dev/null 2>&1 && echo READABLE' 2>/dev/null | grep -q READABLE; then
      warn "/vendor is readable on this device -- unexpected, but good. Proceeding."
    else
      die "the manifest lists files in /vendor, and /vendor is not readable over adb on this
       device. This is not a misconfiguration and 'adb root' will not fix it on a user build:
       the platform SELinux policy grants the shell domain only

           (allow domain vendor_file (dir (getattr search)))

       -- traverse a vendor directory, and nothing else. No file read, not even a listing. So
       /vendor cannot come from a running stock phone at all, by design. Use --from factory."
    fi
  fi

  probe="$(cut -d'|' -f1 "$TMP/entries" | head -1)"
  if ! "$ADB" shell "test -r /$probe && echo OK" 2>/dev/null | grep -q OK; then
    die "cannot read /$probe on this device, so the pull loop would fail $N_ENTRIES times in a
       row. Check that the phone is unlocked and that this is really $EXPECT_BUILD."
  fi

  [ "$DRY_RUN" -eq 0 ] || { say "dry run: source checks passed, extracted nothing."; exit 0; }

  say "pulling $N_ENTRIES files from the device (read-only; nothing is written to the phone)"
  while IFS= read -r line; do
    rel="${line%%|*}"
    want="${line##*|}"
    dst="$OUT/$rel"
    mkdir -p "$(dirname "$dst")"
    if ! "$ADB" pull "/$rel" "$dst" >/dev/null 2>"$TMP/pullerr"; then
      MISSING=$((MISSING + 1))
      warn "PULL FAILED $rel: $(tr -d '\r' < "$TMP/pullerr" | tail -1)"
      [ "$KEEP_GOING" -eq 1 ] || die "stopped at the first failure. Re-run with --keep-going for
       the full list, but a single failure here usually means the wrong build, not a bad file."
      continue
    fi
    if ! verify_one "$rel" "$want" "$dst"; then
      [ "$KEEP_GOING" -eq 1 ] || die "stopped at the first verification failure."
    fi
  done < "$TMP/entries"

elif [ "$FROM" = "factory" ]; then
  [ -n "$ZIP" ] || die "--from factory needs --zip <google factory zip>. Get it from
       https://developers.google.com/android/images -- pick $EXPECT_DEVICE, build $EXPECT_BUILD,
       and accept Google's terms. That download is between you and Google; this script only
       reads what you already have."
  [ -f "$ZIP" ] || die "no such file: $ZIP"
  command -v python3 >/dev/null 2>&1 || die "python3 is required to read the ext4 images inside
       the factory zip. It ships with macOS and every mainstream Linux; install it and re-run."

  base="$(basename "$ZIP")"
  case "$base" in
    *ota*)  die "'$base' looks like an OTA package, not a factory image. See --from ota." ;;
  esac
  lower_build="$(printf '%s' "$EXPECT_BUILD" | tr 'A-Z' 'a-z')"
  case "$(printf '%s' "$base" | tr 'A-Z' 'a-z')" in
    *"$EXPECT_DEVICE"*"$lower_build"*) : ;;
    *) warn "'$base' does not look like the $EXPECT_DEVICE factory zip for $EXPECT_BUILD."
       warn "Continuing anyway -- the filename is a hint, the hashes are the check." ;;
  esac

  cat > "$TMP/read_factory.py" <<'PYEOF'
import sys, os, struct, zipfile, hashlib, shutil

# Byte-range view of a file, so the stored (uncompressed) nested zip can be opened in place.
class Slice:
    def __init__(self, path, start, length):
        self.f = open(path, 'rb'); self.start = start; self.length = length; self.pos = 0
    def seek(self, o, whence=0):
        self.pos = o if whence == 0 else (self.pos + o if whence == 1 else self.length + o)
        return self.pos
    def tell(self): return self.pos
    def seekable(self): return True
    def read(self, n=-1):
        if n < 0: n = self.length - self.pos
        n = max(0, min(n, self.length - self.pos))
        self.f.seek(self.start + self.pos); b = self.f.read(n); self.pos += len(b); return b

class Raw:
    def __init__(self, f): self.f = f
    def read(self, off, n):
        if n <= 0: return b''
        self.f.seek(off); return self.f.read(n)

# Minimal ext4 reader.
class Ext4:
    def __init__(self, dev, base=0):
        self.dev = dev; self.base = base
        sb = dev.read(base + 1024, 1024)
        if struct.unpack('<H', sb[56:58])[0] != 0xEF53:
            raise SystemExit("not an ext4 filesystem (bad magic)")
        self.inodes_per_group, = struct.unpack('<I', sb[40:44])
        self.bs = 1024 << struct.unpack('<I', sb[24:28])[0]
        self.inode_size, = struct.unpack('<H', sb[88:90])
        feat_inc, = struct.unpack('<I', sb[96:100])
        self.desc_size, = struct.unpack('<H', sb[254:256])
        if not (feat_inc & 0x80) or self.desc_size == 0: self.desc_size = 32
        self.gd_block = 1 if self.bs > 1024 else 2
        self._gd = {}
    def blk(self, n, cnt=1): return self.dev.read(self.base + n * self.bs, cnt * self.bs)
    def itable(self, g):
        if g not in self._gd:
            d = self.dev.read(self.base + self.gd_block * self.bs + g * self.desc_size, self.desc_size)
            lo, = struct.unpack('<I', d[8:12])
            hi, = struct.unpack('<I', d[40:44]) if self.desc_size >= 40 else (0,)
            self._gd[g] = lo | (hi << 32)
        return self._gd[g]
    def inode(self, ino):
        g, i = (ino - 1) // self.inodes_per_group, (ino - 1) % self.inodes_per_group
        return self.dev.read(self.base + self.itable(g) * self.bs + i * self.inode_size, self.inode_size)
    def extents(self, raw):
        out = []
        def walk(buf):
            magic, ent, mx, depth, gen = struct.unpack('<HHHHI', buf[:12])
            if magic != 0xF30A: return
            for k in range(ent):
                e = buf[12 + k * 12:24 + k * 12]
                if depth == 0:
                    lb, ln, shi, slo = struct.unpack('<IHHI', e)
                    if ln > 32768: ln -= 32768
                    out.append((lb, (shi << 32) | slo, ln))
                else:
                    lb, llo, lhi, _ = struct.unpack('<IIHH', e)
                    walk(self.blk((lhi << 32) | llo))
        walk(raw[40:100]); out.sort(); return out
    def size(self, raw):
        lo, = struct.unpack('<I', raw[4:8]); hi, = struct.unpack('<I', raw[108:112])
        return lo | (hi << 32)
    def read_file(self, raw):
        sz = self.size(raw); data = bytearray(sz)
        for lb, pb, ln in self.extents(raw):
            s = lb * self.bs
            if s >= sz: continue
            n = min(ln * self.bs, sz - s); data[s:s + n] = self.blk(pb, ln)[:n]
        return bytes(data)
    def listdir(self, raw):
        out = {}; data = self.read_file(raw); p = 0
        while p < len(data) - 8:
            ino, rec, nl, ft = struct.unpack('<IHBB', data[p:p + 8])
            if rec < 8: break
            if ino and nl: out[data[p + 8:p + 8 + nl].decode('utf-8', 'replace')] = ino
            p += rec
        return out
    def resolve(self, path):
        raw = self.inode(2)
        for part in [x for x in path.split('/') if x]:
            ents = self.listdir(raw)
            if part not in ents: return None
            raw = self.inode(ents[part])
        return raw

factory, entries_file, outdir, tmpdir = sys.argv[1:5]

zf = zipfile.ZipFile(factory)
inner = [n for n in zf.namelist() if n.endswith('.zip') and '/image-' in n]
if not inner:
    raise SystemExit("no nested image-*.zip in %s -- is this really a factory image?" % factory)
zi = zf.getinfo(inner[0])
f = open(factory, 'rb'); f.seek(zi.header_offset)
sig, ver, flg, meth, t, d, crc, csz, usz, nl, el = struct.unpack('<IHHHHHIIIHH', f.read(30))
if sig != 0x04034b50:
    raise SystemExit("corrupt zip: bad local header for %s" % inner[0])
inner_zf = zipfile.ZipFile(Slice(factory, zi.header_offset + 30 + nl + el, zi.file_size)
                           if zi.compress_type == zipfile.ZIP_STORED else zf.open(inner[0]))

wanted = {}
for line in open(entries_file):
    line = line.strip()
    if not line: continue
    rel, want = line.split('|', 1)
    part, _, path = rel.partition('/')
    wanted.setdefault(part, []).append((rel, path, want))

rc = 0
for part in sorted(wanted):
    member = part + '.img'
    if member not in inner_zf.namelist():
        print("MISSING-PARTITION %s" % member); rc = 1; continue
    # The partition images are DEFLATE inside the nested zip, so they must be materialised
    # before they can be seeked. Done one at a time and deleted immediately, so peak scratch is
    # one partition (product.img, ~4.9 GB) and not all of them at once.
    tmp_img = os.path.join(tmpdir, member)
    sys.stderr.write("  decompressing %s ...\n" % member); sys.stderr.flush()
    with inner_zf.open(member) as src, open(tmp_img, 'wb') as dst:
        shutil.copyfileobj(src, dst, 1 << 22)
    try:
        fs = Ext4(Raw(open(tmp_img, 'rb')))
        for rel, path, want in wanted[part]:
            raw = fs.resolve(path)
            if raw is None:
                print("MISSING %s" % rel); rc = 1; continue
            data = fs.read_file(raw)
            got = hashlib.sha256(data).hexdigest()
            if got != want:
                print("MISMATCH %s %s %s" % (rel, want, got)); rc = 1; continue
            dstp = os.path.join(outdir, rel)
            os.makedirs(os.path.dirname(dstp), exist_ok=True)
            with open(dstp, 'wb') as o: o.write(data)
            print("OK %s" % rel)
    finally:
        os.unlink(tmp_img)
sys.exit(rc)
PYEOF

  say "reading $ZIP"
  say "(the partition images inside are compressed, so each is decompressed to scratch space one"
  say " at a time and deleted immediately -- peak scratch is one partition, about 5 GB.)"
  say ""

  [ "$DRY_RUN" -eq 0 ] || { say "dry run: source present and manifest valid, extracted nothing."; exit 0; }

  set +e
  python3 "$TMP/read_factory.py" "$ZIP" "$TMP/entries" "$OUT" "$TMP" > "$TMP/result" 2>"$TMP/pyerr"
  py_rc=$?
  set -e
  cat "$TMP/pyerr" >&2 || true

  OK="$(grep -c '^OK ' "$TMP/result" || true)"
  BAD="$(grep -c '^MISMATCH ' "$TMP/result" || true)"
  MISSING="$(grep -c '^MISSING' "$TMP/result" || true)"
  # || true: under pipefail a no-match grep would take the pipeline, and the script, down.
  { grep '^MISMATCH ' "$TMP/result" || true; } | while read -r _ rel want got; do
    warn "MISMATCH $rel"
    warn "         expected $want"
    warn "         got      $got"
  done
  { grep '^MISSING' "$TMP/result" || true; } | while read -r _ rel; do warn "MISSING  $rel"; done

  if [ "$py_rc" -gt 1 ]; then
    die "the factory-image reader failed outright (exit $py_rc). The output above is the reason."
  fi

else
  die "--from must be one of: factory, device, ota (got '$FROM')"
fi

say ""
say "extracted:  $OK / $N_ENTRIES"
[ "$BAD" -eq 0 ]     || say "mismatched: $BAD"
[ "$MISSING" -eq 0 ] || say "missing:    $MISSING"
say "output:     $OUT"

if [ "$OK" -eq "$N_ENTRIES" ]; then
  say ""
  say "PASS  every inventoried file was extracted and matches its pinned hash."
  say "      These bytes came from your licensed copy of Google's firmware, not from RistOS."
  exit 0
fi

say ""
say "FAIL  $((N_ENTRIES - OK)) of $N_ENTRIES files were not recovered."
if [ "${BAD:-0}" -gt 0 ]; then
  say ""
  say "      Hash mismatches almost always mean the WRONG BUILD rather than a bad download."
  say "      This manifest is pinned to $EXPECT_DEVICE @ $EXPECT_BUILD and to nothing else."
fi
exit 1
