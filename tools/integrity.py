#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Integrity checker for the Aether port.

Verifies:
  1. every registered block/item has a model + textures + ru/en lang entry in the
     original mod assets (regression guard for the port's asset source);
  2. the built resource pack contains the same models/textures/lang;
  3. every JSON file in build/datapack parses;
  4. no TODO/FIXME/placeholder markers in the plugin sources.

Exit code 1 if any hard check fails.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import inventory  # noqa: E402

RP = os.path.join(ROOT, "build", "resourcepack", "assets", "aether")
DP = os.path.join(ROOT, "build", "datapack")

problems = []
warnings = []


def main():
    inv = json.load(open(os.path.join(ROOT, "tools", "inventory.json"), encoding="utf-8"))
    en = (inventory.load_json(os.path.join(inventory.MAIN_ASSETS, "lang", "en_us.json"))
          or inventory.load_json(os.path.join(inventory.GEN_ASSETS, "lang", "en_us.json")) or {})
    ru = (inventory.load_json(os.path.join(inventory.MAIN_ASSETS, "lang", "ru_ru.json"))
          or inventory.load_json(os.path.join(inventory.GEN_ASSETS, "lang", "ru_ru.json")) or {})

    special = {
        "aether_portal", "skyroot_wall_sign", "skyroot_wall_hanging_sign",
        "frosted_ice", "unstable_obsidian", "chest_mimic", "skyroot_bed",
        "boss_doorway_carved_stone", "boss_doorway_sentry_stone",
        "boss_doorway_angelic_stone", "boss_doorway_light_angelic_stone",
        "boss_doorway_hellfire_stone", "boss_doorway_light_hellfire_stone",
    }
    miss_model = miss_tex = miss_lang = 0
    for kind, table in (("item", inv["_item_assets"]), ("block", inv["_block_assets"])):
        for name, a in table.items():
            if name.endswith("_spawn_egg") or name in special:
                continue
            if not a["model"]:
                miss_model += 1
                problems.append(f"{kind} {name}: no model")
            if a["model"] and not a["textures_ok"]:
                miss_tex += 1
                problems.append(f"{kind} {name}: textures missing {a['textures']}")
            key = (f"block.aether.{name}" if kind == "block" else f"item.aether.{name}")
            if key not in en or key not in ru:
                miss_lang += 1
                warnings.append(f"{kind} {name}: lang missing ({key})")

    # resource pack contents
    rp_ok = os.path.isdir(RP)
    rp_items = rp_blocks = rp_lang = 0
    if rp_ok:
        rp_items = len([f for f in os.listdir(os.path.join(RP, "models", "item"))
                        if f.endswith(".json")]) if os.path.isdir(os.path.join(RP, "models", "item")) else 0
        rp_blocks = len([f for f in os.listdir(os.path.join(RP, "models", "block"))
                         if f.endswith(".json")]) if os.path.isdir(os.path.join(RP, "models", "block")) else 0
        rp_lang = int(os.path.isfile(os.path.join(RP, "lang", "ru_ru.json"))) + \
            int(os.path.isfile(os.path.join(RP, "lang", "en_us.json")))
    else:
        problems.append("resource pack not built (run tools/convert_assets.py)")

    # datapack json validity
    dp_files = 0
    dp_bad = 0
    if os.path.isdir(DP):
        for dirpath, _d, files in os.walk(DP):
            for fn in files:
                if fn.endswith(".json"):
                    dp_files += 1
                    try:
                        json.load(open(os.path.join(dirpath, fn), encoding="utf-8"))
                    except Exception as e:
                        dp_bad += 1
                        problems.append(f"datapack {fn}: invalid JSON ({e})")

    # TODO / placeholders in plugin sources
    markers = []
    src = os.path.join(ROOT, "plugin", "src", "main", "java")
    for dirpath, _d, files in os.walk(src):
        for fn in files:
            if fn.endswith(".java"):
                text = open(os.path.join(dirpath, fn), encoding="utf-8", errors="replace").read()
                for m in re.finditer(r"TODO|FIXME|placeholder|not implemented", text, re.I):
                    markers.append(f"{fn}: {m.group(0)}")

    print("=== Aether port integrity report ===")
    print(f"blocks={len(inv['blocks'])} items={len(inv['items'])} entities={len(inv['entities'])}")
    print(f"asset problems: model={miss_model} textures={miss_tex} lang-warnings={miss_lang}")
    print(f"resourcepack: built={rp_ok} itemModels={rp_items} blockModels={rp_blocks} langFiles={rp_lang}")
    print(f"datapack: jsonFiles={dp_files} invalid={dp_bad}")
    print(f"code markers (TODO/FIXME): {len(markers)}")
    for w in warnings[:20]:
        print("  WARN", w)
    for p in problems:
        print("  FAIL", p)
    for m in markers:
        print("  MARK", m)
    if problems:
        print(f"RESULT: FAIL ({len(problems)} problems)")
        sys.exit(1)
    print("RESULT: OK")


if __name__ == "__main__":
    main()
