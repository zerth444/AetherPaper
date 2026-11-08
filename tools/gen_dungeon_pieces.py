#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Converts dungeon NBT templates into runtime JSON:
plugin/src/main/resources/content/dungeons/<dungeon>.json
plus copies the dungeon chest loot tables into content/loot/.

Block translation: aether: ids -> carrier BlockData strings (via block_map);
vanilla air kept (carve); structure blocks become markers.
"""
import json
import os
import shutil
from collections import OrderedDict

import nbtlib

import block_map

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STRUCT = os.path.join(ROOT, "aether-mod", "src", "main", "resources", "data",
                      "aether", "structure")
GEN_LOOT = os.path.join(ROOT, "aether-mod", "src", "generated", "resources",
                        "data", "aether", "loot_table")
OUTDIR = os.path.join(ROOT, "plugin", "src", "main", "resources", "content",
                      "dungeons")
LOOTOUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "content",
                       "loot")


def translate(name, bmap):
    if name == "minecraft:air":
        return "minecraft:air"
    if name.startswith("aether:"):
        bid = name.split(":", 1)[1]
        return bmap.get(bid, "minecraft:barrier")
    return name


def main():
    inv = json.load(open(os.path.join(ROOT, "tools", "inventory.json"), encoding="utf-8"))
    bmap = block_map.build_map(inv["blocks"])
    dungeons = OrderedDict()
    for dungeon in sorted(os.listdir(STRUCT)):
        d = os.path.join(STRUCT, dungeon)
        if not os.path.isdir(d):
            continue
        pieces = OrderedDict()
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".nbt"):
                continue
            n = nbtlib.load(os.path.join(d, fn))
            size = [int(n["size"][0]), int(n["size"][1]), int(n["size"][2])]
            pal = n["palette"]
            blocks, markers = [], []
            for b in n["blocks"]:
                x, y, z = int(b["pos"][0]), int(b["pos"][1]), int(b["pos"][2])
                state = pal[int(b["state"])]
                name = str(state["Name"])
                if name == "minecraft:structure_block":
                    meta = str(b.get("nbt", {}).get("metadata", ""))
                    if meta:
                        markers.append({"x": x, "y": y, "z": z, "kind": meta})
                    continue
                if "treasure_chest" in name:
                    extra = b.get("nbt", {})
                    markers.append({"x": x, "y": y, "z": z, "kind": "TreasureChest",
                                    "locked": bool(extra.get("Locked", 0)),
                                    "dungeon_kind": str(extra.get("Kind", ""))})
                translated=translate(name,bmap)
                if 'boss_doorway_' in name and str(state.get('Properties',{}).get('invisible','false'))=='true':
                    translated='minecraft:structure_void'
                if not name.startswith('aether:') and state.get('Properties'):
                    translated+='['+','.join(str(k)+'='+str(v) for k,v in sorted(state['Properties'].items()))+']'
                blocks.append({"x": x, "y": y, "z": z, "block": translated})
            pieces[fn[:-4]] = {"size": size, "blocks": blocks, "markers": markers}
        dungeons[dungeon] = pieces
    os.makedirs(OUTDIR, exist_ok=True)
    for dungeon, pieces in dungeons.items():
        with open(os.path.join(OUTDIR, dungeon + ".json"), "w", encoding="utf-8") as f:
            json.dump(pieces, f, ensure_ascii=False)
    # copy dungeon chest loot tables verbatim
    for dirpath, _ds, files in os.walk(os.path.join(GEN_LOOT, "chests")):
        for fn in files:
            if not fn.endswith(".json"):
                continue
            src = os.path.join(dirpath, fn)
            rel = os.path.relpath(src, GEN_LOOT)
            dst = os.path.join(LOOTOUT, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
    print("dungeons:", {k: list(v) for k, v in dungeons.items()})
    print("loot copied to", os.path.relpath(LOOTOUT, ROOT))


if __name__ == "__main__":
    main()
