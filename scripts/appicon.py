#!/usr/bin/env python3
"""
Renders iosApp's app icon from the mark's geometry.

The mark is three pills, and its source of record is `brand/mark.svg` in the
workspace repo. Android takes it as a vector drawable and needs nothing
generated; an iOS asset catalogue takes a PNG and only a PNG, so this draws
one -- in the standard library, because a build dependency on a raster
toolchain for one 1024px square is a worse trade than ninety lines of
arithmetic.

Shapes are filled by signed distance rather than sampled, which is exact at
the edge and is what keeps the ends of the pills smooth at any size.

    ./scripts/appicon.py            # writes the icon the catalogue expects
"""

import math
import pathlib
import struct
import sys
import zlib

SIZE = 1024
TOP = (0x5A, 0xA0, 0xEC)
BOTTOM = (0x2C, 0x6F, 0xB5)
INK = (0xFF, 0xFF, 0xFF)

# The 64-unit grid the mark is drawn on, placed as brand/app-icon.svg places it:
# scaled 14x and offset so the mark is 616px wide and clears Android's 66% safe
# circle, which is tighter than anything iOS asks for.
SCALE, DX, DY = 14, 64, 71
PILLS = [(26, 14, 28, 9), (10, 27, 44, 9), (10, 40, 28, 9)]


def coverage(px, py, box):
    """How much of this pixel the rounded box covers, 0 to 1."""
    cx, cy, hx, hy, r = box
    qx = abs(px - cx) - (hx - r)
    qy = abs(py - cy) - (hy - r)
    outside = math.hypot(max(qx, 0.0), max(qy, 0.0))
    d = outside + min(max(qx, qy), 0.0) - r
    return min(max(0.5 - d, 0.0), 1.0)


def render():
    boxes = []
    for x, y, w, h in PILLS:
        left, top = x * SCALE + DX, y * SCALE + DY
        width, height = w * SCALE, h * SCALE
        boxes.append((left + width / 2, top + height / 2, width / 2, height / 2, height / 2))

    rows = []
    for y in range(SIZE):
        t = (y + 0.5) / SIZE
        base = bytes(round(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
        row = bytearray(base * SIZE)
        py = y + 0.5
        for cx, cy, hx, hy, r in boxes:
            if not (cy - hy - 1 <= py <= cy + hy + 1):
                continue
            for x in range(max(0, int(cx - hx - 2)), min(SIZE, int(cx + hx + 3))):
                a = coverage(x + 0.5, py, (cx, cy, hx, hy, r))
                if a <= 0:
                    continue
                at = x * 3
                for i in range(3):
                    row[at + i] = round(row[at + i] + (INK[i] - row[at + i]) * a)
        rows.append(row)
    return rows


def png(rows):
    raw = b"".join(b"\0" + bytes(r) for r in rows)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    # Colour type 2 is RGB with no alpha, which is what an iOS app icon has to
    # be: the system rejects one that carries a channel it would have to flatten.
    head = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", head)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    out = pathlib.Path(__file__).resolve().parent.parent / "iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(png(render()))
    print(f"wrote {out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    sys.exit(main())
