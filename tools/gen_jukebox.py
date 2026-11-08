#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Bakes content/jukebox.json: disc item -> sound event + length in seconds."""
import json
import os
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDIR = os.path.join(ROOT, "aether-mod", "src", "generated", "resources", "data",
                    "aether", "jukebox_song")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "content", "jukebox.json")
INV = os.path.join(ROOT, "tools", "inventory.json")


def main():
    inv = json.load(open(INV, encoding="utf-8"))
    item_ids = set(inv["items"])
    songs = OrderedDict()
    for fn in sorted(os.listdir(SDIR)):
        if not fn.endswith(".json"):
            continue
        o = json.load(open(os.path.join(SDIR, fn), encoding="utf-8"))
        disc = "music_disc_" + fn[:-5]
        if disc in item_ids:
            songs[disc] = {"sound": "aether:" + o["sound_event"],
                           "length": float(o["length_in_seconds"])}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(songs, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"songs={len(songs)} -> {os.path.relpath(OUT, ROOT)}")


if __name__ == "__main__":
    main()
