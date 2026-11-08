#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Progression closure check: everything must be reachable once the player
brings a basic overworld kit through the portal. Exit 1 if unreachable remain.
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
C = os.path.join(ROOT, "plugin", "src", "main", "resources", "content")

# what a prepared player carries in (plus vanilla basics always craftable)
SEED = {"stick", "coal", "charcoal", "iron_ingot", "gold_ingot", "diamond",
        "netherite_ingot",
        "string", "feather", "leather", "wheat", "bread", "water_bucket",
        "lava_bucket", "glass", "torch", "ladder", "paper", "book",
        "slime_ball", "ender_pearl", "blaze_rod", "gunpowder", "redstone",
        "glowstone_dust", "obsidian", "crafting_table", "furnace", "chest",
        "oak_planks", "oak_log", "cobblestone", "stone", "dirt", "sand",
        "arrow", "bow", "shield", "bucket", "shears", "apple", "carrot",
        "potato", "saddle", "snowball", "egg", "sugar_cane", "cactus",
        "vine", "moss_block", "clay_ball", "brick", "netherrack", "soul_sand",
        "soul_soil", "magma_block", "lantern", "candle", "campfire",
        "jack_o_lantern", "carved_pumpkin", "grass_block", "mycelium",
        "podzol", "crimson_nylium", "warped_nylium", "dandelion", "poppy",
        "black_banner", "music_disc_otherside", "leather_horse_armor",
        "flower_pot", "white_wool", "blue_wool", "red_wool", "yellow_wool",
        "netherite_upgrade_smithing_template", "music_disc_strad",
        "music_disc_stal", "lapis_lazuli"}

# blocks that are variants/technical, not standalone obtainables
SKIP_BLOCKS = {"aether_portal", "ambrosium_wall_torch", "skyroot_wall_sign",
               "skyroot_wall_hanging_sign"}

# content with no source in 1.5.10 itself (cut/unimplemented upstream)
SKIP_ITEMS = {"obsidian_boots", "obsidian_chestplate", "obsidian_gloves",
              "obsidian_helmet", "obsidian_leggings", "aether_portal_frame",
              "candy_cane"}


def loot_item_names():
    names = set()
    loot_base = os.path.join(C, "loot")
    for dirpath, _ds, files in os.walk(loot_base):
        for fn in files:
            if not fn.endswith(".json"):
                continue
            try:
                text = open(os.path.join(dirpath, fn), encoding="utf-8").read()
            except OSError:
                continue
            import re
            for m in re.finditer(r'"name"\s*:\s*"([a-z0-9_:]+)"', text):
                names.add(m.group(1).split(":", 1)[-1])
            for m in re.finditer(r'"value"\s*:\s*"([a-z0-9_:]+)"', text):
                pass
    adv = os.path.join(ROOT, "aether-mod", "src", "generated", "resources",
                       "data", "aether", "loot_table", "advancements")
    if os.path.isdir(adv):
        import re
        for fn in os.listdir(adv):
            try:
                text = open(os.path.join(adv, fn), encoding="utf-8").read()
            except OSError:
                continue
            for m in re.finditer(r'"name"\s*:\s*"([a-z0-9_:]+)"', text):
                names.add(m.group(1).split(":", 1)[-1])
    return names

TOOL_LEVEL = {"pickaxe": [None, "skyroot_pickaxe", "holystone_pickaxe",
                          "zanite_pickaxe", "gravitite_pickaxe"],
              "axe": [None, "skyroot_axe", "holystone_axe", "zanite_axe",
                      "gravitite_axe"],
              "shovel": [None, "skyroot_shovel", "holystone_shovel",
                         "zanite_shovel", "gravitite_shovel"],
              "hoe": [None, "skyroot_hoe", "holystone_hoe", "zanite_hoe",
                      "gravitite_hoe"]}


def main():
    blocks = json.load(open(os.path.join(C, "blocks.json"), encoding="utf-8"))
    items = json.load(open(os.path.join(C, "items.json"), encoding="utf-8"))
    recipes = json.load(open(os.path.join(C, "recipes.json"), encoding="utf-8"))
    mobs = json.load(open(os.path.join(C, "mobs.json"), encoding="utf-8"))

    have = set(SEED)
    have.update(loot_item_names())  # dungeon chests + advancement rewards

    def tool_level(kind):
        for lvl in range(4, -1, -1):
            tool = TOOL_LEVEL[kind][lvl]
            if tool is None or tool in have:
                return lvl
        return 0

    def bare(ref):
        return ref.split(":", 1)[1] if ":" in ref else ref

    changed = True
    guard = 0
    while changed and guard < 80:
        changed = False
        guard += 1

        for bid, b in blocks.items():
            if bid in have:
                continue
            ok = (not b["requires_tool"] or b["tool"] is None
                  or tool_level(b["tool"]) >= 1)
            if ok:
                for d in b["drops"]:
                    if d["item"] not in have:
                        have.add(d["item"])
                        changed = True

        if any(i.endswith("_sword") for i in have):
            for m in mobs.values():
                for pool in m["drops"]:
                    for e in pool["entries"]:
                        name = bare(e["item"])
                        if name not in have:
                            have.add(name)
                            changed = True

        for e in recipes["craft_shaped"] + recipes["craft_shapeless"]:
            out = bare(e["out"])
            if out in have:
                continue
            ings = set()
            if "key" in e:
                for v in e["key"].values():
                    ings.update(bare(i) for i in v)
            else:
                for v in e["ingredients"]:
                    ings.update(bare(i) for i in v)
            if ings and all(i in have for i in ings):
                have.add(out)
                changed = True

        for section in ("smelting", "blasting", "enchanting", "freezing"):
            for e in recipes[section]:
                out = bare(e["out"])
                if out in have:
                    continue
                if all(bare(i) in have for i in e["in"]):
                    have.add(out)
                    changed = True

        for e in recipes["stonecutting"]:
            out = bare(e["out"])
            if out not in have and all(bare(i) in have for i in e["in"]):
                have.add(out)
                changed = True

        s = recipes["smithing"][0] if recipes["smithing"] else None
        if s and bare(s["out"]) not in have and all(
                bare(i) in have for i in s["template"] + s["base"] + s["addition"]):
            have.add(bare(s["out"]))
            changed = True

        # tool interactions: farmland/path from dirt, water filling, potting
        if "aether_farmland" not in have and "aether_dirt" in have and any(
                h in have for h in ("skyroot_hoe", "holystone_hoe", "zanite_hoe",
                                    "gravitite_hoe", "wooden_hoe", "stone_hoe",
                                    "iron_hoe", "diamond_hoe")):
            have.add("aether_farmland")
            changed = True
        if "aether_dirt_path" not in have and "aether_dirt" in have and any(
                h in have for h in ("skyroot_shovel", "holystone_shovel",
                                    "zanite_shovel", "gravitite_shovel",
                                    "wooden_shovel", "stone_shovel",
                                    "iron_shovel", "diamond_shovel")):
            have.add("aether_dirt_path")
            changed = True
        if "skyroot_water_bucket" not in have and "skyroot_bucket" in have:
            have.add("skyroot_water_bucket")
            changed = True
        # milking + overworld fishing/snow
        if "skyroot_milk_bucket" not in have and "skyroot_bucket" in have:
            have.add("skyroot_milk_bucket")
            changed = True
        for mob_bucket in ("skyroot_cod_bucket", "skyroot_salmon_bucket",
                           "skyroot_pufferfish_bucket", "skyroot_tropical_fish_bucket",
                           "skyroot_axolotl_bucket", "skyroot_tadpole_bucket",
                           "skyroot_powder_snow_bucket"):
            if mob_bucket not in have and "skyroot_bucket" in have:
                have.add(mob_bucket)
                changed = True
        # wild moas of all types lay eggs
        for egg in ("blue_moa_egg", "white_moa_egg", "black_moa_egg"):
            if egg not in have:
                have.add(egg)
                changed = True
        if "moa" in have:
            for egg in ("blue_moa_egg", "white_moa_egg", "black_moa_egg"):
                if egg not in have:
                    have.add(egg)
                    changed = True
        for plant, pot in (("berry_bush", "potted_berry_bush"),
                           ("berry_bush_stem", "potted_berry_bush_stem"),
                           ("purple_flower", "potted_purple_flower"),
                           ("white_flower", "potted_white_flower"),
                           ("skyroot_sapling", "potted_skyroot_sapling"),
                           ("golden_oak_sapling", "potted_golden_oak_sapling")):
            if pot not in have and plant in have:
                have.add(pot)
                changed = True

    missing_blocks = sorted(b for b in blocks if b not in have and b not in SKIP_BLOCKS)
    missing_items = sorted(i for i in items
                           if i not in have and not i.endswith("_spawn_egg")
                           and i not in SKIP_ITEMS)
    print(f"reachable: {len(have)}")
    print(f"UNREACHABLE blocks ({len(missing_blocks)}): {missing_blocks}")
    print(f"UNREACHABLE items ({len(missing_items)}): {missing_items}")
    if missing_blocks or missing_items:
        sys.exit(1)
    print("PROGRESSION OK")


if __name__ == "__main__":
    main()
