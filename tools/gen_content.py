#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Content generator: turns the Aether mod Java registries + data into runtime JSON
consumed by the plugin.

Outputs:
  plugin/src/main/resources/content/blocks.json
  plugin/src/main/resources/content/items.json
  tools/content_report.json
"""
import json
import os
import re
from collections import OrderedDict

import block_map
import inventory

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "content")
GEN_DATA = os.path.join(ROOT, "aether-mod", "src", "generated", "resources", "data")

BLOCK_LINE = re.compile(
    r'register\(\s*"([a-z0-9_]+)"\s*,\s*\(\)\s*->\s*new\s+([A-Za-z0-9_]+)\((.*?)\)\s*\)\s*;',
    re.S)
ITEM_LINE = re.compile(
    r'register\(\s*"([a-z0-9_]+)"\s*,\s*(?:\(\)\s*->\s*new\s+)?([A-Za-z0-9_]+?)(?:::new|\()')
STRENGTH = re.compile(r"\.strength\(\s*(-?[0-9.]+)F?\s*(?:,\s*(-?[0-9.]+)F?\s*)?\)")
SOUND = re.compile(r"\.sound\(SoundType\.([A-Z_]+)\)")
REQUIRES_TOOL = re.compile(r"requiresCorrectToolForDrops\(\)")


def load(p):
    with open(p, "r", encoding="utf-8", errors="replace") as f:
        return json.load(f)


def write(p, o):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(o, f, ensure_ascii=False, indent=1)


def collect_tool_map():
    tool_map = {}
    base = os.path.join(GEN_DATA, "minecraft", "tags", "block", "mineable")
    for tool in ("pickaxe", "axe", "shovel", "hoe"):
        p = os.path.join(base, tool + ".json")
        if not os.path.isfile(p):
            continue
        for v in load(p).get("values", []):
            if isinstance(v, str) and v.startswith("aether:"):
                tool_map[v.split(":", 1)[1]] = tool
    return tool_map


def tool_cond(cond):
    """Returns ('shear'|'silk'|None, inverted:bool) for tool conditions."""
    if not isinstance(cond, dict):
        return None, False
    t = cond.get("condition", "")
    if t == "minecraft:match_tool":
        pred = cond.get("predicate", {})
        items = pred.get("items", "")
        if isinstance(items, str) and "shear" in items:
            return "shears", False
        if items == "minecraft:shears":
            return "shears", False
        ench = pred.get("predicates", {}).get("minecraft:enchantments", [])
        if any(e.get("enchantments") == "minecraft:silk_touch" for e in ench):
            return "silk", False
        return None, False
    if t == "minecraft:any_of":
        kinds={tool_cond(term)[0] for term in cond.get("terms", [])}-{None}
        if kinds=={'shears','silk'}:return 'shears_or_silk',False
        if len(kinds)==1:return kinds.pop(),False
        return None, False
    if t == "minecraft:inverted":
        kind, _inv = tool_cond(cond.get("term", {}))
        return kind, True
    if t in ("minecraft:survives_explosion",):
        return None, False
    return None, False


def entry_drops(entry):
    """Flatten one loot entry into drop dicts."""
    out = []
    etype = entry.get("type", "")
    if etype == "minecraft:alternatives":
        fallback_excludes_tool=False
        for child in entry.get("children", []):
            drops=entry_drops(child)
            for drop in drops:
                if fallback_excludes_tool and drop['require'] is None:drop['exclude']=True
            out.extend(drops)
            fallback_excludes_tool |= any(d['require'] is not None for d in drops)
        return out
    if etype != "minecraft:item":
        return out
    name = entry.get("name", "")
    if not isinstance(name, str):
        return out
    lo = hi = 1
    chance = 1.0
    fortune = None
    require = None
    exclude = False
    bonus = None
    for cond in entry.get("conditions", []):
        if not isinstance(cond, dict):
            continue
        if cond.get("condition") == "minecraft:table_bonus":
            fortune = cond.get("chances", [1.0])
            chance = 0.0
        else:
            kind, inv = tool_cond(cond)
            if kind and not inv:
                require = kind
            elif kind and inv:
                exclude = True
    for f in entry.get("functions", []):
        if not isinstance(f, dict):
            continue
        if f.get("function") == "minecraft:set_count":
            c = f.get("count")
            if isinstance(c, (int, float)):
                lo = hi = int(c)
            elif isinstance(c, dict) and c.get("type") == "minecraft:uniform":
                lo, hi = int(c.get("min", 1)), int(c.get("max", 1))
        if f.get('function')=='minecraft:apply_bonus' and f.get('enchantment')=='minecraft:fortune':
            bonus=f.get('formula')
    out.append({"item": name, "min": lo, "max": hi, "chance": chance,
                "fortune": fortune, "require": require, "exclude": exclude, "bonus":bonus})
    return out


def loot_drop(block_id):
    """Returns (drops, double_drops) from the block loot table."""
    p = os.path.join(GEN_DATA, "aether", "loot_table", "blocks", block_id + ".json")
    if not os.path.isfile(p):
        return [{"item": block_id, "min": 1, "max": 1, "chance": 1.0,
                 "fortune": None, "require": None, "exclude": False}], False
    data = load(p)
    double = any(isinstance(f, dict) and f.get("function") == "aether:double_drops"
                 for f in data.get("functions", []))
    drops = []
    for pool in data.get("pools", []):
        pool_exclude = False
        pool_require = None
        explosion_only = False
        for cond in pool.get("conditions", []):
            _k, inv = tool_cond(cond)
            if _k and inv:
                pool_exclude = True
            elif _k:pool_require=_k
        for entry in pool.get('entries',[]):
            if any(c.get('condition')=='minecraft:inverted' and c.get('term',{}).get('condition')=='minecraft:entity_properties' for c in entry.get('conditions',[])):explosion_only=True
        for entry in pool.get("entries", []):
            for d in entry_drops(entry):
                if pool_require and d['require'] is None:d=dict(d,require=pool_require)
                if explosion_only:d=dict(d,require='explosion')
                if pool_exclude and d["require"] is None:
                    d = dict(d, exclude=True)
                # pool-level inverted shear/silk handled via exclude flag
                drops.append(d)
    # User-facing harvesting rule: shears collect the whole ripe bush, as Silk
    # Touch does, instead of dropping berries + an unrelated loose stem.
    if block_id=='berry_bush':
        for d in drops:
            if d['item']=='aether:berry_bush':d['require']='shears_or_silk'
    if not drops:
        drops = [{"item": block_id, "min": 1, "max": 1, "chance": 1.0,
                  "fortune": None, "require": None, "exclude": False}]
    return drops, double


def resolve_model(item_id, is_block):
    for base in (inventory.GEN_ASSETS, inventory.MAIN_ASSETS):
        if os.path.isfile(os.path.join(base, "models", "item", item_id + ".json")):
            return "aether:item/" + item_id
    if is_block:
        for base in (inventory.GEN_ASSETS, inventory.MAIN_ASSETS):
            if os.path.isfile(os.path.join(base, "models", "block", item_id + ".json")):
                return "aether:block/" + item_id
    return None


def main():
    blocks_in = load(os.path.join(ROOT, "tools", "inventory.json"))
    block_ids = blocks_in["blocks"]
    item_ids = blocks_in["items"]
    bmap = block_map.build_map(block_ids)
    tool_map = collect_tool_map()

    en = (inventory.load_json(os.path.join(inventory.MAIN_ASSETS, "lang", "en_us.json"))
          or inventory.load_json(os.path.join(inventory.GEN_ASSETS, "lang", "en_us.json")) or {})
    ru = (inventory.load_json(os.path.join(inventory.MAIN_ASSETS, "lang", "ru_ru.json"))
          or inventory.load_json(os.path.join(inventory.GEN_ASSETS, "lang", "ru_ru.json")) or {})

    # ---- parse block Java lines ----
    src = os.path.join(ROOT, "aether-mod", "src", "main", "java", "com", "aetherteam",
                       "aether", "block", "AetherBlocks.java")
    text = open(src, "r", encoding="utf-8", errors="replace").read()
    props = {}
    copied = {}
    explicit_strength = set()
    explicit_sound = set()
    for m in BLOCK_LINE.finditer(text):
        bid, cls, args = m.group(1), m.group(2), m.group(3)
        st = STRENGTH.search(args)
        if st: explicit_strength.add(bid)
        snd = SOUND.search(args)
        if snd: explicit_sound.add(bid)
        copy = re.search(r'ofFullCopy\(\s*(AetherBlocks|Blocks)\.([A-Z_]+)', args)
        if copy: copied[bid] = copy.groups()
        props[bid] = {
            "class": cls,
            "hardness": float(st.group(1)) if st else 0.0,
            "resistance": float(st.group(2)) if st and st.group(2) else (float(st.group(1)) if st else 0.0),
            "sound": (snd.group(1).lower() if snd else "stone"),
            "requires_tool": bool(REQUIRES_TOOL.search(args)),
        }

    def inherited_sound(bid, seen=None):
        seen = set() if seen is None else seen
        if bid in seen or bid in explicit_sound or bid not in copied: return props.get(bid, {}).get('sound', 'stone')
        seen.add(bid)
        namespace, source = copied[bid]
        if namespace == 'AetherBlocks': return inherited_sound(source.lower(), seen)
        if any(token in source for token in ('LOG', 'WOOD', 'PLANKS', 'FENCE', 'GATE', 'CHEST', 'TORCH')): return 'wood'
        if source in ('DANDELION', 'OAK_SAPLING', 'GRASS_BLOCK', 'SHORT_GRASS'): return 'grass'
        if 'GLASS' in source: return 'glass'
        if source == 'DIRT': return 'gravel'
        if 'WOOL' in source: return 'wool'
        return 'stone'
    for bid in props: props[bid]['sound'] = inherited_sound(bid)

    # ofFullCopy inherits strength too: zero made logs/planks instant to mine.
    vanilla_strength = {'BOOKSHELF': (1.5,1.5), 'CHEST': (2.5,2.5), 'CYAN_BED': (.2,.2),
                        'OAK_DOOR': (3,3), 'OAK_TRAPDOOR': (3,3),
                        'FLOWER_POT': (0,0), 'DANDELION': (0,0), 'OAK_SAPLING': (0,0),
                        'TORCH': (0,0), 'WALL_TORCH': (0,0), 'STONE_BUTTON': (.5,.5),
                        'OAK_BUTTON': (.5,.5), 'OAK_PRESSURE_PLATE': (.5,.5)}
    def strength(bid, seen=None):
        seen=set() if seen is None else seen
        p=props.get(bid,{})
        if bid in seen or bid in explicit_strength or bid not in copied:
            return p.get('hardness',0),p.get('resistance',0)
        seen.add(bid); namespace,source=copied[bid]
        if namespace=='AetherBlocks': return strength(source.lower(),seen)
        return vanilla_strength.get(source,(2,3))
    for bid,p in props.items(): p['hardness'],p['resistance']=strength(bid)
    required_tiers={}
    for level,name in ((1,'stone'),(2,'iron'),(3,'diamond')):
        for ref in load(os.path.join(GEN_DATA,'minecraft','tags','block',f'needs_{name}_tool.json'))['values']:
            if isinstance(ref,str) and ref.startswith('aether:'):required_tiers[ref[7:]]=level

    blocks = OrderedDict()
    for bid in block_ids:
        p = props.get(bid, {})
        drops, double = loot_drop(bid)
        tool = tool_map.get(bid)
        # strip namespaces for runtime (aether: registered ids, minecraft: vanilla)
        clean = []
        for d in drops:
            name = d["item"].split(":", 1)[1] if ":" in d["item"] else d["item"]
            clean.append({"item": name, "vanilla": d["item"].startswith("minecraft:"),
                          "min": d["min"], "max": d["max"], "chance": d["chance"],
                          "fortune": d["fortune"], "require": d["require"],
                          "exclude": d["exclude"]})
            clean[-1]['bonus']=d.get('bonus')
        blocks[bid] = {
            "carrier": bmap.get(bid, "minecraft:barrier"),
            "legacy_carrier": block_map.build_map(block_ids, legacy=True).get(bid),
            "hardness": p.get("hardness", 0.0),
            "resistance": p.get("resistance", 0.0),
            "sound": p.get("sound", "stone"),
            "requires_tool": p.get("requires_tool", False),
            "required_tier": required_tiers.get(bid,0),
            "tool": tool,
            "drops": [] if bid in block_map.SPECIAL_RENDER and bid == "aether_portal" else clean,
            "double_drops": double,
            "model": resolve_model(bid, True),
            "lang_en": en.get("block.aether." + bid, ""),
            "lang_ru": ru.get("block.aether." + bid, ""),
        }

    # ---- items ----
    isrc = os.path.join(ROOT, "aether-mod", "src", "main", "java", "com", "aetherteam",
                        "aether", "item", "AetherItems.java")
    itext = open(isrc, "r", encoding="utf-8", errors="replace").read()
    iclass = {}
    istack = {}
    idura = {}
    for part in itext.split("register(")[1:]:
        m0 = re.match(r'\s*"([a-z0-9_]+)"\s*,', part)
        if not m0:
            continue
        depth = 0
        end = None
        for i, ch in enumerate(part):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0 and part[i:i + 2] == ");":
                    end = i
                    break
        chunk = part[:end] if end else part[:800]
        iid = m0.group(1)
        m1 = re.search(r"(?:\(\)\s*->\s*new\s+)?([A-Za-z0-9_]+?)(?:::new|\()", chunk)
        if m1:
            iclass[iid] = m1.group(1)
        m2 = re.search(r"\.stacksTo\((\d+)\)", chunk)
        if m2:
            istack[iid] = int(m2.group(1))
        m3 = re.search(r"\.durability\((\d+)\)", chunk)
        if m3:
            idura[iid] = int(m3.group(1))

    def item_kind(iid, cls):
        c = (cls or "").lower()
        if "spawnegg" in c or iid.endswith("_spawn_egg"):
            return "spawn_egg"
        if iid.endswith("_helmet") or iid.endswith("_chestplate") or \
                iid.endswith("_leggings") or iid.endswith("_boots"):
            return "armor"
        if "sword" in c or "blade" in c or "slayer" in c or "hammer" in c:
            return "sword"
        if "pickaxe" in c:
            return "pickaxe"
        if "axe" in c:
            return "axe"
        if "shovel" in c:
            return "shovel"
        if "hoe" in c:
            return "hoe"
        if "shooter" in c or "dart_shooter" in iid:
            return "shooter"
        if "ring" in iid or "pendant" in iid or "gloves" in iid or "cape" in iid \
                or iid in ("golden_feather", "iron_bubble", "regeneration_stone",
                           "shield_of_repulsion", "invisibility_cloak"):
            return "accessory"
        return "item"

    # verified tier durability (AetherItemTiers.getUses)
    TIER_USES = {"skyroot": 59, "holystone": 131, "zanite": 250, "gravitite": 1561,
                 "valkyrie": 1561, "lightning": 502, "holy": 502, "vampire": 2031,
                 "pig_slayer": 200, "hammer_of_kingbdogz": 250, "candy_cane": 32}
    NON_STACKABLE = ("_bucket", "boat", "parachute", "moa_egg", "cloud_staff",
                     "hammer_of_kingbdogz", "dart_shooter")

    items = OrderedDict()
    for iid in item_ids:
        cls = iclass.get(iid, "")
        kind = item_kind(iid, cls)
        dura = idura.get(iid)
        if dura is None and kind in ("sword", "pickaxe", "axe", "shovel", "hoe"):
            for prefix, uses in TIER_USES.items():
                if iid == prefix or iid.startswith(prefix + "_"):
                    dura = uses
                    break
        stack = istack.get(iid,
                           1 if kind in ("sword", "pickaxe", "axe", "shovel", "hoe",
                                         "armor", "shooter", "accessory")
                           or any(k in iid for k in NON_STACKABLE) else 64)
        if dura is not None: stack=1  # Item.Properties.durability also disables stacking.
        items[iid] = {
            "kind": kind,
            "class": cls,
            "model": resolve_model(iid, False),
            "durability": dura,
            "max_stack": stack,
            "lang_en": en.get("item.aether." + iid, ""),
            "lang_ru": ru.get("item.aether." + iid, ""),
        }

    write(os.path.join(OUT, "blocks.json"), blocks)
    write(os.path.join(OUT, "items.json"), items)

    report = {
        "blocks": len(blocks),
        "items": len(items),
        "blocks_with_tool": sum(1 for b in blocks.values() if b["tool"]),
        "blocks_double_drops": sum(1 for b in blocks.values() if b["double_drops"]),
        "blocks_with_lang_ru": sum(1 for b in blocks.values() if b["lang_ru"]),
        "items_with_lang_ru": sum(1 for i in items.values() if i["lang_ru"]),
    }
    write(os.path.join(ROOT, "tools", "content_report.json"), report)
    print(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
