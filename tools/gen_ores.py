#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Derives plugin/src/main/resources/content/ores.json from tools/ore_populator.json."""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "content", "ores.json")
MIN_Y, MAX_Y, HEIGHT = 0, 255, 256


def clamp(v):
    return max(MIN_Y, min(MAX_Y, v))


def y_of(spec):
    if isinstance(spec, dict):
        return clamp(MIN_Y + int(spec.get("above_bottom", 0)))
    return clamp(int(spec))


def main():
    src = json.load(open(os.path.join(ROOT, "tools", "ore_populator.json"), encoding="utf-8"))
    ores = []
    springs = {"rock_count": 4, "valid": ["holystone", "aether_dirt"]}
    for stem, data in src.items():
        if stem == "water_spring":
            springs = {"rock_count": int(data.get("config", {}).get("rock_count", 4)),
                       "valid": [b.split(":", 1)[1]
                                 for b in data.get("config", {}).get("valid_blocks", [])]}
            continue
        cfg = data.get("config", {})
        plc = data.get("placement", []) or []
        count = 1
        min_y, max_y, trap = MIN_Y, MAX_Y, False
        for m in plc:
            if not isinstance(m, dict):
                continue
            if m.get("type") == "minecraft:count":
                count = int(m.get("count", 1))
            if m.get("type") == "minecraft:height_range":
                h = m.get("height", {})
                if h.get("type") == "minecraft:trapezoid":
                    trap = True
                min_y = y_of(h.get("min_inclusive", MIN_Y))
                max_y = y_of(h.get("max_inclusive", MAX_Y))
                if min_y > max_y:
                    min_y, max_y = max_y, min_y
        for t in cfg.get("targets", []):
            ore_id = t["state"]["Name"].split(":", 1)[1]
            ores.append({"ore": ore_id, "replace": ["holystone"],
                         "size": int(cfg.get("size", 8)), "count": count,
                         "minY": min_y, "maxY": max_y, "trapezoid": trap})
    out = {"dimension_min_y": MIN_Y, "ores": ores, "springs": springs}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"ores={len(ores)} springs={springs} -> {os.path.relpath(OUT, ROOT)}")


if __name__ == "__main__":
    main()
