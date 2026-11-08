#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Recipe spec generator: parses the mod's recipe + tag data into
plugin/src/main/resources/content/recipes.json for the plugin engines
(stations, crafting, furnace, conversions, bans, repairs)."""
import json
import os
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RDIR = os.path.join(ROOT, "aether-mod", "src", "generated", "resources", "data",
                    "aether", "recipe")
TDIR = os.path.join(ROOT, "aether-mod", "src", "generated", "resources", "data",
                    "aether", "tags")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "content", "recipes.json")


def load(p):
    return json.load(open(p, encoding="utf-8"))


COMMON_TAGS = {    "c:buckets/lava": ["minecraft:lava_bucket"],
    "c:buckets/water": ["minecraft:water_bucket"],
    "c:chests/wooden": ["minecraft:chest"],
    "c:dusts/glowstone": ["minecraft:glowstone_dust"],
    "c:dusts/redstone": ["minecraft:redstone"],
    "c:feathers": ["minecraft:feather"],
    "c:gems/diamond": ["minecraft:diamond"],
    "c:ingots/gold": ["minecraft:gold_ingot"],
    "c:ingots/iron": ["minecraft:iron_ingot"],
    "c:leathers": ["minecraft:leather"],
    "c:rods/wooden": ["minecraft:stick"],
    "c:strings": ["minecraft:string"],
}


def vanilla_tag(kind, name, seen=None):
    """Expand a vanilla tag using the reference 26.2 jar."""
    import zipfile
    seen = seen or set()
    key = kind + ":" + name
    if key in seen:
        return []
    seen.add(key)
    try:
        from port_data import VANILLA_JAR
    except ImportError:
        VANILLA_JAR = None
    if not VANILLA_JAR:
        import os as _os
        VANILLA_JAR = _os.path.join(ROOT, "server", "versions", "26.2", "paper-26.2.jar")
    out = []
    try:
        with zipfile.ZipFile(VANILLA_JAR) as z:
            data = z.read(f"data/minecraft/tags/{kind}/{name}.json")
    except Exception:
        return out
    for v in json.loads(data).get("values", []):
        if isinstance(v, dict):
            v = v.get("id", "")
        if not isinstance(v, str):
            continue
        if v.startswith("#"):
            ns, _, rest = v[1:].partition(":")
            if ns == "minecraft":
                out.extend(vanilla_tag(kind, rest, seen))
            else:
                out.append(v)
        else:
            out.append(v)
    return out


def resolve_tag(ns_path, kind="item", seen=None):
    """Resolve a tag id like 'aether:x' to member ids (recursive, namespace kept)."""
    seen = seen or set()
    if ns_path in seen:
        return []
    seen.add(ns_path)
    if ns_path in COMMON_TAGS:
        return list(COMMON_TAGS[ns_path])
    ns, _, name = ns_path.partition(":")
    if ns == "minecraft":
        return vanilla_tag(kind, name, seen)
    base = os.path.join(TDIR if ns == "aether" else None or "", "")
    out = []
    if ns == "aether":
        p = os.path.join(TDIR, kind, name + ".json")
        if not os.path.isfile(p):
            return out
        for v in load(p).get("values", []):
            if isinstance(v, dict):
                v = v.get("id", "")
            if not isinstance(v, str):
                continue
            if v.startswith("#"):
                out.extend(resolve_tag(v[1:], kind, seen))
            else:
                out.append(v)
    else:
        out.append(ns_path)
    return out


def ingredient_ids(ing):
    if isinstance(ing, list):
        out = []
        for e in ing:
            out.extend(ingredient_ids(e))
        return out
    if not isinstance(ing, dict):
        return []
    if "item" in ing:
        return [ing["item"]]
    if "tag" in ing:
        kind = "block" if ing.get("block") else "item"
        # tags in recipes are item tags unless the recipe is block-based
        return resolve_tag(ing["tag"], "block" if "block" in ing else "item")
    if "block" in ing:
        return [ing["block"]]
    return []


def result_id(res):
    if isinstance(res, dict):
        return res.get("id") or res.get("item") or res.get("block") or ""
    return res if isinstance(res, str) else ""


def result_count(res):
    if isinstance(res, dict):
        return int(res.get("count", 1))
    return 1


def load_lang():
    for p in (os.path.join(ROOT, "aether-mod", "src", "main", "resources",
                           "assets", "aether", "lang", "en_us.json"),
              os.path.join(ROOT, "aether-mod", "src", "generated", "resources",
                           "assets", "aether", "lang", "en_us.json")):
        if os.path.isfile(p):
            return load(p)
    return {}


LANG_EN = load_lang()


MATERIAL_ALIASES = {
    "minecraft:chain": "minecraft:iron_chain",
}


def fix_alias(o):
    if isinstance(o, dict):
        for k, v in o.items():
            if isinstance(v, str) and v in MATERIAL_ALIASES:
                o[k] = MATERIAL_ALIASES[v]
            else:
                fix_alias(v)
    elif isinstance(o, list):
        for i, v in enumerate(o):
            if isinstance(v, str) and v in MATERIAL_ALIASES:
                o[i] = MATERIAL_ALIASES[v]
            else:
                fix_alias(v)


def result_extra(res):
    if not isinstance(res, dict) or "components" not in res:
        return None
    comp = res["components"]
    extra = {}
    if "minecraft:banner_patterns" in comp:
        extra["banner"] = comp["minecraft:banner_patterns"]
    name = comp.get("minecraft:item_name")
    if isinstance(name, str):
        try:
            payload = json.loads(name)
            key = payload.get("translate", "")
            extra["name_key"] = key
            extra["name"] = LANG_EN.get(key) or LANG_EN.get(key.replace("aether.", "", 1), "")
            extra["color"] = payload.get("color", "")
        except Exception:
            pass
    return extra or None


def main():
    spec = OrderedDict([
        ("enchanting", []), ("freezing", []), ("repairing", []), ("incubation", []),
        ("ambrosium", []), ("swet", []), ("freezable", []), ("placement", []),
        ("bans_block", []), ("bans_item", []), ("craft_shaped", []),
        ("craft_shapeless", []), ("smelting", []), ("blasting", []),
        ("stonecutting", []), ("smithing", []),
    ])
    counts = {"skipped_other": 0}
    for fn in sorted(os.listdir(RDIR)):
        if not fn.endswith(".json"):
            continue
        o = load(os.path.join(RDIR, fn))
        t = o.get("type", "")
        if t == "aether:enchanting":
            spec["enchanting"].append({
                "in": ingredient_ids(o.get("ingredient", {})),
                "out": result_id(o.get("result", {})), "count": result_count(o.get("result", {})),
                "time": int(o.get("cookingtime", 200))})
        elif t == "aether:freezing":
            spec["freezing"].append({
                "in": ingredient_ids(o.get("ingredient", {})),
                "out": result_id(o.get("result", {})), "count": result_count(o.get("result", {})),
                "time": int(o.get("cookingtime", 200))})
        elif t == "aether:repairing":
            spec["repairing"].append({
                "in": ingredient_ids(o.get("ingredient", {})),
                "time": int(o.get("repairTime", 600))})
        elif t == "aether:incubation":
            tag = o.get("tag", {})
            spec["incubation"].append({
                "egg": ingredient_ids(o.get("ingredient", {})),
                "time": int(o.get("incubationtime", 1000)),
                "moa": tag.get("MoaType", "aether:blue")})
        elif t == "aether:ambrosium_enchanting":
            spec["ambrosium"].append({
                "in": (o.get("ingredient", {}).get("block", ""), o.get("ingredient", {}).get("properties", {})),
                "out": (o.get("result", {}).get("block", ""), o.get("result", {}).get("properties", {}))})
        elif t == "aether:swet_ball_conversion":
            spec["swet"].append({"in": o.get("ingredient", {}).get("block", ""),
                                 "out": o.get("result", {}).get("block", "")})
        elif t in ("aether:accessory_freezable", "aether:icestone_freezable"):
            spec["freezable"].append({"in": o.get("ingredient", {}).get("block", ""),
                                      "out": o.get("result", {}).get("block", "")})
        elif t == "aether:placement_conversion":
            spec["placement"].append({
                "in": o.get("ingredient", {}).get("block", ""),
                "in_props": o.get("ingredient", {}).get("properties", {}),
                "out": o.get("result", {}).get("block", ""),
                "out_props": o.get("result", {}).get("properties", {})})
        elif t == "aether:block_placement_ban":
            spec["bans_block"].append({"block": o.get("ingredient", {}).get("block", "")})
        elif t == "aether:item_placement_ban":
            spec["bans_item"].append({"item": o.get("ingredient", {}).get("item", "")})
        elif t == "minecraft:crafting_shaped":
            key = {}
            for c, ing in o.get("key", {}).items():
                key[c] = ingredient_ids(ing)
            entry = {"pattern": o.get("pattern", []), "key": key,
                     "out": result_id(o.get("result", {})),
                     "count": result_count(o.get("result", {}))}
            extra = result_extra(o.get("result", {}))
            if extra:
                entry["extra"] = extra
            entry["rkey"] = fn[:-5]
            spec["craft_shaped"].append(entry)
        elif t == "minecraft:crafting_shapeless":
            entry = {
                "ingredients": [ingredient_ids(i) for i in o.get("ingredients", [])],
                "out": result_id(o.get("result", {})),
                "count": result_count(o.get("result", {}))}
            extra = result_extra(o.get("result", {}))
            if extra:
                entry["extra"] = extra
            entry["rkey"] = fn[:-5]
            spec["craft_shapeless"].append(entry)
        elif t in ("minecraft:smelting", "minecraft:blasting"):
            bucket = spec["smelting"] if t == "minecraft:smelting" else spec["blasting"]
            bucket.append({"rkey": fn[:-5],
                           "in": ingredient_ids(o.get("ingredient", {})),
                           "out": result_id(o.get("result", {})),
                           "count": result_count(o.get("result", {})),
                           "time": int(o.get("cookingtime", 200)),
                           "xp": float(o.get("experience", 0.1))})
        elif t == "minecraft:stonecutting":
            spec["stonecutting"].append({"rkey": fn[:-5],
                                         "in": ingredient_ids(o.get("ingredient", {})),
                                         "out": result_id(o.get("result", {})),
                                         "count": result_count(o.get("result", {}))})
        elif t == "minecraft:smithing_transform":
            spec["smithing"].append({"rkey": fn[:-5],
                                     "template": ingredient_ids(o.get("template", {})),
                                     "base": ingredient_ids(o.get("base", {})),
                                     "addition": ingredient_ids(o.get("addition", {})),
                                     "out": result_id(o.get("result", {})),
                                     "count": result_count(o.get("result", {}))})
        else:
            counts["skipped_other"] += 1
    # vanilla ids renamed since 1.21.1 (Bukkit Material names)
    for entries in list(spec.values()):
        if not isinstance(entries, list):
            continue
        for e in entries:
            if isinstance(e, dict):
                fix_alias(e)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    # anvil repair materials per tool tier (from *_repairing item tags)
    tiers = {}
    for fn in sorted(os.listdir(os.path.join(TDIR, "item"))):
        if fn.endswith("_repairing.json"):
            tier = fn[:-len("_repairing.json")]
            tiers[tier] = resolve_tag("aether:" + tier + "_repairing", "item")
    spec["anvil_repair"] = tiers
    json.dump(spec, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    summary = {k: len(v) for k, v in spec.items()}
    summary.update(counts)
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
