#!/usr/bin/env python3
"""Build app/src/main/assets/tz/zones.bin: an offline latitude/longitude -> IANA time zone index.

Source: timezone-boundary-builder, `timezones-with-oceans.geojson.zip`
(https://github.com/evansiroky/timezone-boundary-builder/releases). The data is derived from
OpenStreetMap and licensed ODbL 1.0; the file this script writes is a derived database under
the same licence (see NOTICE).

    python3 tools/build_tz_index.py combined-with-oceans.json --release 2026c

Needs shapely >= 2.1 (coverage_simplify) and numpy.

FORMAT (big-endian), read by TimeZoneIndex.kt:
    "RTZ1"                  magic
    u16  scale              coordinate units per degree
    u16  cellDeg            grid cell size in whole degrees
    u16  zoneCount
    zoneCount x (u8 len, utf-8 name)
    u16  gridW, u16 gridH   cells; row 0 starts at latitude -90, column 0 at longitude -180
    gridW*gridH x i32       >= 0 the one zone covering the cell; -1 no zone; <= -2 list -(v+2)
    u32  listCount
    listCount x (u16 n, n x u16 zone)
    zoneCount x u32         byte offset of the zone's rings within the geometry blob
    u32  blobLength, blob   per zone: varint rings; per ring varint points, then zig-zag varint
                            deltas of (lon, lat) in units, the first relative to (0, 0)

Containment is even-odd over every ring of a zone, so holes need no separate marking.
Simplification is done as a coverage, so neighbouring zones keep a shared border and the
simplified map has no gaps or overlaps for a point to fall into.
"""
import argparse
import json
import math
import random
import struct
import sys

import numpy as np
import shapely
from shapely.geometry import Point, box, shape
from shapely.strtree import STRtree


def varint(out: bytearray, v: int) -> None:
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def zigzag(v: int) -> int:
    return (v << 1) ^ (v >> 63)


def rings_of(geom):
    polys = [geom] if geom.geom_type == "Polygon" else list(geom.geoms)
    for p in polys:
        if p.is_empty:
            continue
        yield p.exterior.coords
        for hole in p.interiors:
            yield hole.coords


def encode_zone(geom, scale: int) -> bytes:
    rings = []
    for coords in rings_of(geom):
        pts = []
        for x, y in coords:
            q = (int(round(x * scale)), int(round(y * scale)))
            if not pts or pts[-1] != q:
                pts.append(q)
        if len(pts) > 1 and pts[0] == pts[-1]:
            pts.pop()  # the reader closes every ring itself
        if len(pts) >= 3:
            rings.append(pts)
    out = bytearray()
    varint(out, len(rings))
    for pts in rings:
        varint(out, len(pts))
        px = py = 0
        for x, y in pts:
            varint(out, zigzag(x - px))
            varint(out, zigzag(y - py))
            px, py = x, y
    return bytes(out)


# ---- a reader that mirrors TimeZoneIndex.kt, used to validate what was written ----

class Reader:
    def __init__(self, data: bytes):
        self.d = data
        assert data[:4] == b"RTZ1"
        p = 4
        self.scale, self.cell, n = struct.unpack_from(">HHH", data, p); p += 6
        self.names = []
        for _ in range(n):
            ln = data[p]; p += 1
            self.names.append(data[p:p + ln].decode()); p += ln
        self.w, self.h = struct.unpack_from(">HH", data, p); p += 4
        self.grid = np.frombuffer(data, dtype=">i4", count=self.w * self.h, offset=p); p += 4 * self.w * self.h
        (lc,) = struct.unpack_from(">I", data, p); p += 4
        self.lists = []
        for _ in range(lc):
            (k,) = struct.unpack_from(">H", data, p); p += 2
            self.lists.append(struct.unpack_from(">" + "H" * k, data, p)); p += 2 * k
        self.offsets = struct.unpack_from(">" + "I" * n, data, p); p += 4 * n
        (bl,) = struct.unpack_from(">I", data, p); p += 4
        self.blob = data[p:p + bl]
        self.cache = {}

    def rings(self, z):
        if z in self.cache:
            return self.cache[z]
        b, p = self.blob, self.offsets[z]

        def rv():
            nonlocal p
            v = s = 0
            while True:
                c = b[p]; p += 1
                v |= (c & 0x7F) << s; s += 7
                if c < 0x80:
                    return v

        def zz(v):
            return (v >> 1) ^ -(v & 1)

        out = []
        for _ in range(rv()):
            k = rv(); xs = []; ys = []; x = y = 0
            for _ in range(k):
                x += zz(rv()); y += zz(rv()); xs.append(x); ys.append(y)
            out.append((np.array(xs, dtype=np.float64), np.array(ys, dtype=np.float64)))
        self.cache[z] = out
        return out

    def contains(self, z, x, y):
        # Same arithmetic, in the same order, as TimeZoneIndex.kt, so both round identically.
        inside = False
        for xs, ys in self.rings(z):
            xj, yj = np.roll(xs, 1), np.roll(ys, 1)
            straddles = (ys > y) != (yj > y)
            if not straddles.any():
                continue
            xi_, yi_, xj_, yj_ = xs[straddles], ys[straddles], xj[straddles], yj[straddles]
            cross = xi_ + (y - yi_) * (xj_ - xi_) / (yj_ - yi_)
            if int(np.count_nonzero(x < cross)) % 2 == 1:
                inside = not inside
        return inside

    def dist2(self, z, x, y):
        best = math.inf
        for xs, ys in self.rings(z):
            ax, ay = np.roll(xs, 1), np.roll(ys, 1)
            dx, dy = xs - ax, ys - ay
            L = dx * dx + dy * dy
            with np.errstate(invalid="ignore", divide="ignore"):
                t = np.where(L == 0, 0.0, np.clip(((x - ax) * dx + (y - ay) * dy) / L, 0.0, 1.0))
            ex, ey = ax + t * dx - x, ay + t * dy - y
            best = min(best, float((ex * ex + ey * ey).min()))
        return best

    def lookup(self, lat, lon):
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            return None
        col = min(int((lon + 180) // self.cell), self.w - 1)
        row = min(int((lat + 90) // self.cell), self.h - 1)
        v = int(self.grid[row * self.w + col])
        if v == -1:
            return None
        if v >= 0:
            return self.names[v]
        cands = self.lists[-(v + 2)]
        x, y = lon * self.scale, lat * self.scale
        for z in cands:
            if self.contains(z, x, y):
                return self.names[z]
        # Only reachable exactly on a quantised border: the nearest candidate is right.
        return self.names[min(cands, key=lambda z: self.dist2(z, x, y))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("geojson")
    ap.add_argument("--release", required=True, help="timezone-boundary-builder release tag")
    ap.add_argument("--tolerance", type=float, default=0.005, help="simplification, degrees")
    ap.add_argument("--scale", type=int, default=1000, help="units per degree")
    ap.add_argument("--cell", type=int, default=1, help="grid cell, whole degrees")
    ap.add_argument("--out", default="app/src/main/assets/tz/zones.bin")
    ap.add_argument("--fixture", default="app/src/test/resources/tz/lookup_fixture.tsv")
    ap.add_argument("--samples", type=int, default=40_000, help="accuracy check against the source")
    args = ap.parse_args()

    with open(args.geojson) as f:
        feats = json.load(f)["features"]
    feats.sort(key=lambda ft: ft["properties"]["tzid"])
    names = [ft["properties"]["tzid"] for ft in feats]
    assert len(names) < 0xFFFF and len(set(names)) == len(names)
    original = [shapely.make_valid(shape(ft["geometry"])) for ft in feats]
    print(f"{len(names)} zones; simplifying at {args.tolerance} deg", file=sys.stderr)

    simplified = list(shapely.coverage_simplify(np.array(original, dtype=object), args.tolerance))
    simplified = [shapely.make_valid(g) for g in simplified]

    tree = STRtree(simplified)
    w, h = 360 // args.cell, 180 // args.cell
    grid = np.full(w * h, -1, dtype=">i4")
    lists, list_ix = [], {}
    for row in range(h):
        lat0 = -90 + row * args.cell
        for col in range(w):
            lon0 = -180 + col * args.cell
            cell = box(lon0, lat0, lon0 + args.cell, lat0 + args.cell)
            hits = sorted(int(i) for i in tree.query(cell, predicate="intersects")
                          if shapely.area(shapely.intersection(simplified[i], cell)) > 0)
            if not hits:
                continue
            if len(hits) == 1:
                grid[row * w + col] = hits[0]
                continue
            key = tuple(hits)
            if key not in list_ix:
                list_ix[key] = len(lists)
                lists.append(key)
            grid[row * w + col] = -(list_ix[key] + 2)

    blobs = [encode_zone(g, args.scale) for g in simplified]
    offsets, pos = [], 0
    for b in blobs:
        offsets.append(pos)
        pos += len(b)

    out = bytearray(b"RTZ1")
    out += struct.pack(">HHH", args.scale, args.cell, len(names))
    for n in names:
        e = n.encode()
        out += struct.pack(">B", len(e)) + e
    out += struct.pack(">HH", w, h)
    out += grid.tobytes()
    out += struct.pack(">I", len(lists))
    for key in lists:
        out += struct.pack(">H", len(key)) + struct.pack(">" + "H" * len(key), *key)
    out += struct.pack(">" + "I" * len(offsets), *offsets)
    out += struct.pack(">I", pos)
    for b in blobs:
        out += b

    import os
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(out)
    print(f"wrote {args.out}: {len(out):,} bytes, {len(lists)} mixed-cell lists, release {args.release}",
          file=sys.stderr)

    # ---- validate the written file against the unsimplified source ----
    r = Reader(bytes(out))
    otree = STRtree(original)
    rnd = random.Random(20260912)
    wrong = []
    for _ in range(args.samples):
        # Area-uniform over the sphere, so the check is not dominated by the poles.
        lat = math.degrees(math.asin(rnd.uniform(-1, 1)))
        lon = rnd.uniform(-180, 180)
        p = Point(lon, lat)
        truth = [names[int(i)] for i in otree.query(p, predicate="covered_by")]
        if not truth:
            continue
        got = r.lookup(lat, lon)
        if got not in truth:
            d = min(shapely.distance(original[names.index(t)].boundary, p) for t in truth)
            wrong.append((lat, lon, truth[0], got, d))
    far = [x for x in wrong if x[4] > args.tolerance * 3]
    print(f"accuracy: {args.samples - len(wrong)}/{args.samples} agree with the source; "
          f"{len(wrong)} disagree, all within {max((x[4] for x in wrong), default=0):.4f} deg of a border; "
          f"{len(far)} further than {args.tolerance * 3} deg", file=sys.stderr)
    for x in far[:10]:
        print("  FAR:", x, file=sys.stderr)

    # ---- fixture: what this reader says, so the Kotlin reader can be held to the same answers ----
    os.makedirs(os.path.dirname(args.fixture), exist_ok=True)
    rnd = random.Random(7)
    with open(args.fixture, "w") as f:
        f.write(f"# lat\tlon\tzone  (generated by tools/build_tz_index.py from release {args.release})\n")
        for i in range(3000):
            if i % 3 == 0:  # a third of the points land in mixed cells, where the work is
                row, col = divmod(rnd.choice([k for k in range(w * h) if grid[k] <= -2]), w)
                lat = -90 + (row + rnd.random()) * args.cell
                lon = -180 + (col + rnd.random()) * args.cell
            else:
                lat = math.degrees(math.asin(rnd.uniform(-1, 1)))
                lon = rnd.uniform(-180, 180)
            f.write(f"{lat:.6f}\t{lon:.6f}\t{r.lookup(lat, lon) or '-'}\n")
    print(f"wrote {args.fixture}", file=sys.stderr)
    return 1 if far else 0


if __name__ == "__main__":
    sys.exit(main())
