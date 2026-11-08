#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Normalizes the mod's 343 advancements into content/advancements.json."""
import json
import os
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADIR = os.path.join(ROOT, "aether-mod", "src", "generated", "resources", "data",
                    "aether", "advancement")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "content",
                   "advancements.json")


def collect(node, key, acc):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == key and isinstance(v, str):
                acc.append(v)
            else:
                collect(v, key, acc)
    elif isinstance(node, list):
        for v in node:
            collect(v, key, acc)
    return acc


def main():
    advs = OrderedDict()
    for dirpath, _ds, files in os.walk(ADIR):
        for fn in sorted(files):
            if not fn.endswith(".json"):
                continue
            o = json.load(open(os.path.join(dirpath, fn), encoding="utf-8"))
            rel = os.path.relpath(os.path.join(dirpath, fn), ADIR)[:-5].replace("\\", "/")
            aid = "aether:" + rel
            disp = o.get("display", {})
            icon = disp.get("icon", {})
            criteria = []
            for name, c in o.get("criteria", {}).items():
                cond = c.get("conditions", {})
                vehicles = []
                for p in cond.get("player", []):
                    v = (((p.get("predicate", {}) or {}).get("vehicle", {}) or {})
                         .get("type"))
                    if isinstance(v, str):
                        vehicles.append(v)
                criteria.append({
                    "name": name,
                    "trigger": c.get("trigger", ""),
                    "items": collect(cond.get("items", []), "items", []),
                    "entities": collect(cond.get("entity", []), "type", []),
                    "vehicle": vehicles,
                    "to": cond.get("to", "") if isinstance(cond.get("to"), str) else "",
                    "from_": cond.get("from", "") if isinstance(cond.get("from"), str) else "",
                    "blocks": collect(cond, "block", []) if False else [],
                    "recipes": [cond["recipe"]] if isinstance(cond.get("recipe"), str) else [],
                })
            rewards = o.get("rewards", {})
            advs[aid] = {
                "parent": o.get("parent", ""),
                "title": disp.get("title", {}),
                "description": disp.get("description", {}),
                "icon": {"id": icon.get("id", "minecraft:stone"),
                         "count": icon.get("count", 1)},
                "frame": disp.get("frame", "task"),
                "background": disp.get("background", ""),
                "criteria": criteria,
                "requirements": o.get("requirements", []),
                "loot": rewards.get("loot", []),
                "reward_recipes": rewards.get("recipes", []),
            }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(advs, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    display = sum(1 for a in advs.values() if a["title"])
    print(f"advancements={len(advs)} with_display={display}")
    # copy advancement reward loot tables
    import shutil
    src = os.path.join(ROOT, "aether-mod", "src", "generated", "resources", "data",
                       "aether", "loot_table", "advancements")
    dst = os.path.join(ROOT, "plugin", "src", "main", "resources", "content",
                       "loot", "advancements")
    if os.path.isdir(src):
        for fn in os.listdir(src):
            if fn.endswith(".json"):
                os.makedirs(dst, exist_ok=True)
                shutil.copy2(os.path.join(src, fn), os.path.join(dst, fn))
        print("advancement loot copied")


if __name__ == "__main__":
    main()
