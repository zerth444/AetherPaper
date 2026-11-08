#!/usr/bin/env python3
"""Exact block-state census of the Aether dimension region files."""
import io
import os
import struct
import sys
import zlib
from collections import Counter

import nbtlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGION = os.path.join(ROOT, "server", "world", "dimensions", "aether",
                      "the_aether", "region")

CARRIER = {}
try:
    import json as _json
    _b = _json.load(open(os.path.join(ROOT, "plugin", "src", "main",
                                      "resources", "content", "blocks.json"),
                         encoding="utf-8"))
    for _k, _v in _b.items():
        CARRIER.setdefault(_v["carrier"], []).append(_k)
except Exception as _e:
    print("carrier map failed", _e)


def state_name(state):
    name = str(state["Name"])
    props = state.get("Properties", {})
    if props:
        name += "[" + ",".join(f"{k}={props[k]}" for k in sorted(props)) + "]"
    ids = CARRIER.get(name)
    return ids[0] if ids else name


def raw_name(state):
    name = str(state["Name"])
    props = state.get("Properties", {})
    if props:
        name += "[" + ",".join(f"{k}={props[k]}" for k in sorted(props)) + "]"
    return name


RAW = Counter()


WATCH = {"altar", "holystone_brick_slab", "boss_doorway_hellfire_stone",
           "potted_golden_oak_sapling", "minecraft:dirt"}
FOUND = {}
COLUMNS = {(-147, -160): [], (-154, -157): []}


def note(chunk_cx, chunk_cz, lx, y, lz, name):
    if name in WATCH and len(FOUND.setdefault(name, [])) < 6:
        FOUND[name].append((chunk_cx * 16 + lx, y, chunk_cz * 16 + lz))


def scan_chunk(data, chunk_cx, chunk_cz):
    import tempfile
    with tempfile.NamedTemporaryFile(delete=False, suffix=".nbt") as tmp:
        tmp.write(data)
        path = tmp.name
    try:
        root = nbtlib.load(path, gzipped=False)
    finally:
        os.unlink(path)
    cnt = Counter()
    odd = Counter()
    try:
        sections = root["sections"]
    except KeyError:
        return cnt, odd
    for section in sections:
        bs = section.get("block_states")
        if bs is None:
            continue
        palette = bs.get("palette", [])
        data_arr = bs.get("data")
        if not palette:
            continue
        if data_arr is None:
            cnt[state_name(palette[0])] += 4096
            continue
        bpe = max(4, (len(palette) - 1).bit_length())
        mask = (1 << bpe) - 1
        idx = 0
        sec_y = section.get("Y", 0) * 16
        vals = list(data_arr)
        for word in vals:
            for shift in range(0, 64, bpe):
                if idx >= 4096:
                    break
                pi = (word >> shift) & mask
                if pi < len(palette):
                    nm = state_name(palette[pi])
                    cnt[nm] += 1
                    rn = raw_name(palette[pi])
                    if rn.startswith("minecraft:note_block"):
                        RAW[rn] += 1
                    lx2 = idx % 16
                    ly2 = sec_y + (idx // 256)
                    lz2 = (idx // 16) % 16
                    note(chunk_cx, chunk_cz, lx2, ly2, lz2, nm)
                    wx = chunk_cx * 16 + lx2
                    wz = chunk_cz * 16 + lz2
                    if (wx, wz) in COLUMNS and 70 <= ly2 <= 100:
                        COLUMNS[(wx, wz)].append((ly2, nm))
                idx += 1
    return cnt, odd


def main():
    total = Counter()
    chunks = 0
    for fn in sorted(os.listdir(REGION)):
        if not fn.endswith(".mca"):
            continue
        rx, rz = fn[2:-4].split(".")[0], fn[2:-4].split(".")[1]
        rx, rz = int(rx), int(rz)
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
                cx = rx * 32 + (i % 32)
                cz = rz * 32 + (i // 32)
                c, _ = scan_chunk(payload, cx, cz)
            except Exception as e:
                print("chunk parse fail", fn, i, e)
                continue
            total.update(c)
            chunks += 1
    print("chunks:", chunks)
    for k, v in total.most_common(40):
        print(f"{v:8d}  {k}")
    print("--- raw note_block states ---")
    for k in sorted(RAW):
        print(f"{RAW[k]:8d}  {k}  ->  {CARRIER.get(k, ['?'])}")
    for k, v in FOUND.items():
        print(k, v)
    for k, v in COLUMNS.items():
        print("COLUMN", k, sorted(v))


if __name__ == "__main__":
    main()
