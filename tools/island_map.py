#!/usr/bin/env python3
"""Top-down ASCII map of Aether islands from region files. Each char = 4x4 columns.
'#': island top >= y60, '+': low rock, '.': void/air."""
import io
import os
import struct
import sys
import tempfile
import zlib

import nbtlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGION = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    ROOT, "server", "world", "dimensions", "aether", "the_aether", "region")


def scan_chunk(data):
    with tempfile.NamedTemporaryFile(delete=False, suffix=".nbt") as tmp:
        tmp.write(data)
        path = tmp.name
    try:
        root = nbtlib.load(path, gzipped=False)
    finally:
        os.unlink(path)
    tops = {}
    try:
        sections = root["sections"]
    except KeyError:
        return tops
    for section in sections:
        bs = section.get("block_states")
        if bs is None:
            continue
        palette = bs.get("palette", [])
        data_arr = bs.get("data")
        if not palette:
            continue
        sec_y = int(section.get("Y", 0)) * 16
        if data_arr is None:
            name = str(palette[0].get("Name", ""))
            if name != "minecraft:air" and "cave_air" not in name:
                for lx in range(16):
                    for lz in range(16):
                        tops[(lx, lz)] = max(tops.get((lx, lz), -1), sec_y + 15)
            continue
        bpe = max(4, (len(palette) - 1).bit_length())
        mask = (1 << bpe) - 1
        idx = 0
        for word in list(data_arr):
            for shift in range(0, 64, bpe):
                if idx >= 4096:
                    break
                pi = (word >> shift) & mask
                if pi < len(palette):
                    name = str(palette[pi].get("Name", ""))
                    if name != "minecraft:air" and "cave_air" not in name:
                        lx, lz = idx % 16, (idx // 16) % 16
                        y = sec_y + idx // 256
                        if y > tops.get((lx, lz), -1):
                            tops[(lx, lz)] = y
                idx += 1
    return tops


def main():
    grid = {}
    for fn in sorted(os.listdir(REGION)):
        if not fn.endswith(".mca"):
            continue
        rx, rz = (int(x) for x in fn[2:-4].split("."))
        with open(os.path.join(REGION, fn), "rb") as f:
            raw = f.read()
        for i in range(1024):
            off = struct.unpack(">I", raw[i * 4:i * 4 + 4])[0]
            if off == 0:
                continue
            sector = (off >> 8) * 4096
            length = struct.unpack(">I", raw[sector:sector + 4])[0]
            ctype = raw[sector + 4]
            payload = raw[sector + 5:sector + 4 + length]
            if ctype == 1:
                import gzip
                payload = gzip.decompress(payload)
            elif ctype == 2:
                payload = zlib.decompress(payload)
            else:
                continue
            try:
                tops = scan_chunk(payload)
            except Exception:
                continue
            cx, cz = rx * 32 + (i % 32), rz * 32 + (i // 32)
            for (lx, lz), y in tops.items():
                grid[(cx * 16 + lx, cz * 16 + lz)] = y
    if not grid:
        print("no terrain")
        return
    xs = [k[0] for k in grid]
    zs = [k[1] for k in grid]
    x0, x1, z0, z1 = min(xs), max(xs), min(zs), max(zs)
    print(f"x {x0}..{x1}  z {z0}..{z1}")
    for bz in range(z0 // 4 * 4, z1 + 1, 4):
        row = ""
        for bx in range(x0 // 4 * 4, x1 + 1, 4):
            best = -1
            for dx in range(4):
                for dz in range(4):
                    best = max(best, grid.get((bx + dx, bz + dz), -1))
            row += "#" if best >= 60 else ("+" if best >= 0 else ".")
        print(row)


if __name__ == "__main__":
    main()
