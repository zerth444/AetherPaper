#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mob spec generator: extracts per-mob attributes, drops and spawn rules from the
Aether mod sources into plugin/src/main/resources/content/mobs.json.

Honest approximations are flagged in the report; exact numbers come from the mod
(entity classes, loot tables, biome spawner lists).
"""
import json
import os
import re
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "aether-mod", "src", "main", "java", "com", "aetherteam", "aether")
GEN_DATA = os.path.join(ROOT, "aether-mod", "src", "generated", "resources", "data", "aether")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "content", "mobs.json")

REGISTER = re.compile(
    r'ENTITY_TYPES\.register\(\s*"(?P<id>[a-z0-9_]+)"\s*,\s*\(\)\s*->\s*'
    r'EntityType\.Builder\.of\(\s*(?P<cls>[A-Za-z0-9_]+)::new')
ATTR = re.compile(r"\.add\(\s*Attributes\.([A-Z_]+)\s*,\s*([0-9.]+)\s*\)")

# mob id -> vanilla carrier + notes. Documented approximations (see LIMITATIONS).
CARRIER = {
    "phyg": "PIG", "flying_cow": "COW", "sheepuff": "SHEEP", "moa": "HORSE",
    "aerbunny": "RABBIT", "blue_swet": "SLIME", "golden_swet": "MAGMA_CUBE",
    "whirlwind": "ALLAY", "evil_whirlwind": "VEX", "aechor_plant": "SHULKER",
    "cockatrice": "HUSK", "zephyr": "GHAST", "aerwhale": "GHAST",
    "mimic": "SILVERFISH", "sentry": "BLAZE", "slider": "SLIME",
    "valkyrie": "VEX", "valkyrie_queen": "VEX", "fire_minion": "BLAZE",
    "sun_spirit": "BLAZE", "cloud_minion": "ALLAY",
}
BOSSES = {"slider", "valkyrie_queen", "sun_spirit"}
CATEGORY_MAP = {
    "aether_aerwhale": "ambient", "aether_darkness_monster": "monster",
    "aether_sky_monster": "monster", "aether_surface_monster": "monster",
    "ambient": "ambient", "creature": "creature", "monster": "monster",
}

SKIP_IDS = {"skyroot_boat", "skyroot_chest_boat", "cold_parachute", "golden_parachute",
            "floating_block", "tnt_present", "zephyr_snowball", "cloud_crystal",
            "fire_crystal", "ice_crystal", "thunder_crystal", "golden_dart",
            "poison_dart", "enchanted_dart", "poison_needle", "lightning_knife",
            "hammer_projectile"}


def find_class_file(cls):
    for dirpath, _d, files in os.walk(os.path.join(JAVA, "entity")):
        if cls + ".java" in files:
            return os.path.join(dirpath, cls + ".java")
    return None


def parse_attrs(path):
    attrs = {}
    approx = []
    if not path:
        return attrs, ["no-source"]
    text = open(path, "r", encoding="utf-8", errors="replace").read()
    m = re.search(r"createMobAttributes\(\)\s*\{(.*?)\n    \}", text, re.S)
    body = m.group(1) if m else text
    for a, v in ATTR.findall(body):
        attrs[a] = float(v)
    base = re.search(r"return\s+([A-Za-z0-9_]+)\.createMobAttributes\(\)", body)
    if base:
        approx.append("inherits-" + base.group(1))
    return attrs, approx


def num_range(v, default=(1, 1)):
    if isinstance(v, (int, float)):
        return int(v), int(v)
    if isinstance(v, dict):
        t = v.get("type", "")
        if t == "minecraft:uniform":
            return int(v.get("min", 1)), int(v.get("max", 1))
        if t == "minecraft:constant":
            return int(v.get("value", 1)), int(v.get("value", 1))
        if t == "minecraft:binomial":
            n, p = int(v.get("n", 1)), float(v.get("p", 1.0))
            return max(0, int(n * p)), n
    return default


def parse_drops(mid):
    p = os.path.join(GEN_DATA, "loot_table", "entities", mid + ".json")
    pools = []
    if not os.path.isfile(p):
        return pools
    data = json.load(open(p, encoding="utf-8"))
    for pool in data.get("pools", []):
        rolls_min, rolls_max = num_range(pool.get("rolls", 1))
        entries = []
        for entry in pool.get("entries", []):
            name = entry.get("name", "")
            if not isinstance(name, str):
                continue
            lo, hi = 1, 1
            weight = int(entry.get("weight", 1))
            for f in entry.get("functions", []):
                if not isinstance(f, dict):
                    continue
                if f.get("function") == "minecraft:set_count":
                    lo, hi = num_range(f.get("count", 1))
            entries.append({"item": name, "min": lo, "max": hi, "weight": weight})
        if entries:
            pools.append({"rolls_min": rolls_min, "rolls_max": rolls_max,
                          "entries": entries})
    return pools


def parse_spawns():
    spawns = {}
    bdir = os.path.join(GEN_DATA, "worldgen", "biome")
    for fn in sorted(os.listdir(bdir)):
        if not fn.endswith(".json"):
            continue
        biome = fn[:-5]
        data = json.load(open(os.path.join(bdir, fn), encoding="utf-8"))
        for cat, entries in data.get("spawners", {}).items():
            vcat = CATEGORY_MAP.get(cat.split(":", 1)[-1])
            if not vcat:
                continue
            for e in entries:
                mid = e["type"].split(":", 1)[1] if ":" in e["type"] else e["type"]
                spawns.setdefault(mid, []).append({
                    "biome": biome, "category": vcat, "weight": e.get("weight", 1),
                    "min": e.get("minCount", 1), "max": e.get("maxCount", 1)})
    return spawns


def main():
    text = open(os.path.join(JAVA, "entity", "AetherEntityTypes.java"),
                encoding="utf-8", errors="replace").read()
    en = json.load(open(os.path.join(
        ROOT, "aether-mod", "src", "generated", "resources", "assets", "aether",
        "lang", "en_us.json"), encoding="utf-8"))
    ru = json.load(open(os.path.join(
        ROOT, "aether-mod", "src", "main", "resources", "assets", "aether",
        "lang", "ru_ru.json"), encoding="utf-8"))
    spawns = parse_spawns()
    mobs = OrderedDict()
    for m in REGISTER.finditer(text):
        mid, cls = m.group("id"), m.group("cls")
        if mid in SKIP_IDS:
            continue
        attrs, approx = parse_attrs(find_class_file(cls))
        mobs[mid] = {
            "class": cls,
            "carrier": CARRIER.get(mid, "PIG"),
            "carrier_approx": mid in CARRIER,
            "boss": mid in BOSSES,
            "attributes": attrs,
            "attributes_note": approx,
            "drops": parse_drops(mid),
            "spawns": spawns.get(mid, []),
            "lang_en": en.get("entity.aether." + mid, ""),
            "lang_ru": ru.get("entity.aether." + mid, ""),
        }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(mobs, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    report = {
        "mobs": len(mobs),
        "bosses": sum(1 for m in mobs.values() if m["boss"]),
        "with_drops": sum(1 for m in mobs.values() if m["drops"]),
        "with_spawns": sum(1 for m in mobs.values() if m["spawns"]),
        "with_health": sum(1 for m in mobs.values() if "MAX_HEALTH" in m["attributes"]),
    }
    json.dump(report, open(os.path.join(ROOT, "tools", "mobs_report.json"), "w",
                           encoding="utf-8"), ensure_ascii=False, indent=1)
    print(json.dumps(report, ensure_ascii=False, indent=1))
    print("unmapped carriers:", [k for k in mobs if k not in CARRIER])


if __name__ == "__main__":
    main()
