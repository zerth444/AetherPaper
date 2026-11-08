#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Inventory generator for the Aether port.
Parses the original Aether mod source (aether-mod/) and produces:
  - INVENTORY.md   (human readable, checkboxes)
  - tools/inventory.json (machine readable, used by integrity checker)
"""
import json
import os
import re
import sys
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD = os.path.join(ROOT, "aether-mod")
JAVA = os.path.join(MOD, "src", "main", "java", "com", "aetherteam", "aether")
GEN_ASSETS = os.path.join(MOD, "src", "generated", "resources", "assets", "aether")
MAIN_ASSETS = os.path.join(MOD, "src", "main", "resources", "assets", "aether")
GEN_DATA = os.path.join(MOD, "src", "generated", "resources", "data", "aether")
MAIN_DATA = os.path.join(MOD, "src", "main", "resources", "data", "aether")

REGISTER_RE = re.compile(r'\bregister\(\s*"([a-z0-9_./]+)"')

# java registry file (relative to JAVA) -> (category, data key)
REGISTRIES = OrderedDict([
    ("block/AetherBlocks.java", "blocks"),
    ("item/AetherItems.java", "items"),
    ("entity/AetherEntityTypes.java", "entities"),
    ("blockentity/AetherBlockEntityTypes.java", "block_entities"),
    ("effect/AetherEffects.java", "mob_effects"),
    ("client/particle/AetherParticleTypes.java", "particles"),
    ("client/AetherSoundEvents.java", "sounds"),
    ("inventory/menu/AetherMenuTypes.java", "menus"),
    ("recipe/AetherRecipeTypes.java", "recipe_types"),
    ("recipe/AetherRecipeSerializers.java", "recipe_serializers"),
    ("item/components/AetherDataComponents.java", "data_components"),
    ("entity/ai/attribute/AetherAttributes.java", "attributes"),
    ("AetherGameEvents.java", "game_events"),
    ("world/structure/AetherStructureTypes.java", "structure_types"),
    ("world/feature/AetherFeatures.java", "features"),
    ("world/AetherPoi.java", "poi"),
    ("world/placementmodifier/AetherPlacementModifiers.java", "placement_modifiers"),
    ("world/trunkplacer/AetherTrunkPlacerTypes.java", "trunk_placers"),
    ("world/foliageplacer/AetherFoliagePlacerTypes.java", "foliage_placers"),
    ("world/treedecorator/AetherTreeDecoratorTypes.java", "tree_decorators"),
    ("world/processor/AetherStructureProcessors.java", "structure_processors"),
    ("world/processor/AetherPosRuleTests.java", "pos_rule_tests"),
    ("loot/modifiers/AetherLootModifiers.java", "loot_modifiers"),
    ("loot/functions/AetherLootFunctions.java", "loot_functions"),
    ("loot/conditions/AetherLootConditions.java", "loot_conditions"),
    ("advancement/AetherAdvancementTriggers.java", "advancement_triggers"),
])


def read_java_ids(rel):
    path = os.path.join(JAVA, rel)
    if not os.path.isfile(path):
        return []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        txt = f.read()
    seen = []
    for m in REGISTER_RE.finditer(txt):
        name = m.group(1)
        if name not in seen:
            seen.append(name)
    return seen


def json_files(base):
    out = []
    if not os.path.isdir(base):
        return out
    for dirpath, _dirs, files in os.walk(base):
        for fn in files:
            if fn.endswith(".json"):
                out.append(os.path.relpath(os.path.join(dirpath, fn), base).replace("\\", "/"))
    return sorted(out)


def files_with_ext(base, ext):
    out = []
    if not os.path.isdir(base):
        return out
    for dirpath, _dirs, files in os.walk(base):
        for fn in files:
            if fn.lower().endswith(ext):
                out.append(os.path.relpath(os.path.join(dirpath, fn), base).replace("\\", "/"))
    return sorted(out)


def load_json(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return json.load(f)
    except Exception:
        return None


def model_textures(model_rel):
    """Collect aether: texture refs from a model json (following aether parents)."""
    refs = set()
    visited = set()
    if model_rel and model_rel.startswith("models/"):
        model_rel = model_rel[len("models/"):]
    cur = model_rel
    while cur and cur not in visited:
        visited.add(cur)
        path = os.path.join(GEN_ASSETS, "models", cur + ".json")
        if not os.path.isfile(path):
            path = os.path.join(MAIN_ASSETS, "models", cur + ".json")
        if not os.path.isfile(path):
            break
        data = load_json(path)
        if not isinstance(data, dict):
            break
        tex = data.get("textures")
        if isinstance(tex, dict):
            for v in tex.values():
                if isinstance(v, str) and v.startswith("aether:"):
                    refs.add(v.split(":", 1)[1])
        parent = data.get("parent")
        if isinstance(parent, str) and parent.startswith("aether:"):
            cur = parent.split(":", 1)[1]
        else:
            cur = None
    return refs


def texture_exists(rel):
    for base in (MAIN_ASSETS, GEN_ASSETS):
        p = os.path.join(base, "textures", rel + ".png")
        if os.path.isfile(p):
            return True
    return False


def model_exists(kind, name):
    for base in (GEN_ASSETS, MAIN_ASSETS):
        for sub in ("models/" + kind, "models/block", "models/item"):
            if os.path.isfile(os.path.join(base, sub, name + ".json")):
                return sub + "/" + name
    return None


def main():
    inv = OrderedDict()
    for rel, key in REGISTRIES.items():
        inv[key] = read_java_ids(rel)

    # ---- item / block asset integrity ----
    item_assets = {}
    for name in inv.get("items", []):
        m = model_exists("item", name)
        texs = sorted(model_textures(m)) if m else []
        ok_tex = all(texture_exists(t) for t in texs) if texs else False
        item_assets[name] = {
            "model": m,
            "textures": texs,
            "textures_ok": ok_tex,
        }

    block_assets = {}
    for name in inv.get("blocks", []):
        m = model_exists("block", name)
        texs = sorted(model_textures(m)) if m else []
        ok_tex = all(texture_exists(t) for t in texs) if texs else False
        block_assets[name] = {"model": m, "textures": texs, "textures_ok": ok_tex}

    # ---- lang ----
    en = (load_json(os.path.join(MAIN_ASSETS, "lang", "en_us.json"))
          or load_json(os.path.join(GEN_ASSETS, "lang", "en_us.json")) or {})
    ru = (load_json(os.path.join(MAIN_ASSETS, "lang", "ru_ru.json"))
          or load_json(os.path.join(GEN_ASSETS, "lang", "ru_ru.json")) or {})

    def lang_name(kind, name):
        for k in ("block.aether." + name, "item.aether." + name,
                  "entity.aether." + name, "effect.aether." + name):
            if k in en or k in ru:
                return en.get(k), ru.get(k)
        return None, None

    # ---- data ----
    data = OrderedDict()
    for sub in ("recipe", "loot_table", "advancement", "dimension",
                "dimension_type", "damage_type", "jukebox_song", "trim_material"):
        files = json_files(os.path.join(GEN_DATA, sub))
        if not files:
            files = json_files(os.path.join(MAIN_DATA, sub))
        data[sub] = files
    wg = OrderedDict()
    wgbase = os.path.join(GEN_DATA, "worldgen")
    if os.path.isdir(wgbase):
        for d in sorted(os.listdir(wgbase)):
            if os.path.isdir(os.path.join(wgbase, d)):
                wg[d] = json_files(os.path.join(wgbase, d))
    data["worldgen"] = wg

    # ---- assets summary ----
    assets = OrderedDict()
    for d in ("textures/item", "textures/block", "textures/entity", "textures/gui",
              "textures/mob_effect", "textures/particle", "sounds", "particles",
              "font", "shaders", "models/item", "models/block", "blockstates"):
        main = files_with_ext(os.path.join(MAIN_ASSETS, d), ".")
        gen = files_with_ext(os.path.join(GEN_ASSETS, d), ".")
        assets[d] = len(main) + len(gen)

    inv["_assets"] = assets
    inv["_data"] = data
    inv["_item_assets"] = item_assets
    inv["_block_assets"] = block_assets
    inv["_lang_en"] = len(en)
    inv["_lang_ru"] = len(ru)

    os.makedirs(os.path.join(ROOT, "tools"), exist_ok=True)
    with open(os.path.join(ROOT, "tools", "inventory.json"), "w", encoding="utf-8") as f:
        json.dump(inv, f, ensure_ascii=False, indent=1)

    # ---- write INVENTORY.md ----
    def cb(ok):
        return "[x]" if ok else "[ ]"

    L = []
    L.append("# INVENTORY.md — Полная инвентаризация контента The Aether")
    L.append("")
    L.append("Источник: `aether-mod/` (The-Aether-Team/The-Aether, ветка `1.21.1-develop`, "
             "mod_version 1.5.10, MC 1.21.1).")
    L.append("Файл сгенерирован автоматически `tools/inventory.py` из исходников и ассетов мода.")
    L.append("Чекбокс = наличие оригинального ассета/данных в исходниках мода (не статус порта; "
             "статус порта ведётся в PROGRESS.md).")
    L.append("")

    def section(title, key, kind=None):
        names = inv.get(key, [])
        L.append(f"## {title} — {len(names)}")
        L.append("")
        if not names:
            L.append("_нет_")
            L.append("")
            return
        L.append("| # | id | модель | текстуры | en | ru |")
        L.append("|---|----|--------|----------|----|----|")
        for i, n in enumerate(names, 1):
            if kind:
                a = (item_assets if kind == "item" else block_assets).get(n, {})
                m = a.get("model") or "-"
                t = "OK" if a.get("textures_ok") else ("нет" if not a.get("textures") else "частично")
                en_n, ru_n = lang_name(kind, n)
            else:
                m = t = "-"
                en_n, ru_n = lang_name("entity", n)
            L.append("| {} | `{}` | {} | {} | {} | {} |".format(
                i, n, m, t,
                (en_n or "—").replace("|", "/"),
                (ru_n or "—").replace("|", "/")))
        L.append("")

    section("Блоки", "blocks", "block")
    section("Предметы", "items", "item")
    section("Сущности (мобы/боссы/снаряды)", "entities", "entity")

    for key, title in (
        ("block_entities", "Блок-энтити"), ("mob_effects", "Эффекты"),
        ("particles", "Частицы"), ("sounds", "Звуки"), ("menus", "Меню/GUI"),
        ("recipe_types", "Типы рецептов"), ("recipe_serializers", "Сериализаторы рецептов"),
        ("data_components", "Компоненты предметов"), ("attributes", "Атрибуты"),
        ("game_events", "Game events"), ("structure_types", "Типы структур"),
        ("features", "Фичи (features)"), ("poi", "POI"), ("placement_modifiers", "Placement modifiers"),
        ("trunk_placers", "Trunk placers"), ("foliage_placers", "Foliage placers"),
        ("tree_decorators", "Tree decorators"), ("structure_processors", "Обработчики структур"),
        ("pos_rule_tests", "Pos rule tests"), ("loot_modifiers", "Loot modifiers"),
        ("loot_functions", "Loot functions"), ("loot_conditions", "Loot conditions"),
        ("advancement_triggers", "Триггеры достижений"),
    ):
        names = inv.get(key, [])
        L.append(f"## {title} — {len(names)}")
        L.append("")
        if names:
            L.append(", ".join("`" + n + "`" for n in names))
        else:
            L.append("_нет_")
        L.append("")

    L.append("## Данные (data-driven)")
    L.append("")
    for sub in ("recipe", "loot_table", "advancement", "dimension", "dimension_type",
                "damage_type", "jukebox_song", "trim_material"):
        files = data.get(sub, [])
        L.append(f"### {sub} — {len(files)}")
        L.append("")
        if files:
            L.append(", ".join("`" + f[:-5] + "`" for f in files))
        else:
            L.append("_нет_")
        L.append("")
    L.append("### worldgen")
    L.append("")
    for d, files in wg.items():
        L.append(f"- **{d}** — {len(files)}: " + (", ".join("`" + f[:-5] + "`" for f in files) if files else "_нет_"))
    L.append("")

    L.append("## Ассеты (файлы)")
    L.append("")
    L.append("| каталог | файлов |")
    L.append("|---------|--------|")
    for k, v in assets.items():
        L.append(f"| {k} | {v} |")
    L.append("")
    L.append(f"Локализация: en_us ключей = {len(en)}, ru_ru ключей = {len(ru)}")
    L.append("")

    # asset gaps
    L.append("## Отсутствующие ассеты (по проверке моделей/текстур)")
    L.append("")
    SPECIAL_RENDER = {
        "aether_portal", "skyroot_wall_sign", "skyroot_wall_hanging_sign",
        "frosted_ice", "unstable_obsidian", "chest_mimic", "skyroot_bed",
        "boss_doorway_carved_stone", "boss_doorway_sentry_stone",
        "boss_doorway_angelic_stone", "boss_doorway_light_angelic_stone",
        "boss_doorway_hellfire_stone", "boss_doorway_light_hellfire_stone",
    }
    gaps, expected = [], []
    for kind, table in (("item", item_assets), ("block", block_assets)):
        for n, a in table.items():
            is_egg = kind == "item" and n.endswith("_spawn_egg")
            is_special = kind == "block" and n in SPECIAL_RENDER
            if is_egg or is_special:
                expected.append(f"- {kind}: `{n}` — шаблон/специальный рендер (модель на клиенте/ванильный темплейт)")
                continue
            if not a["model"]:
                gaps.append(f"- {kind}: `{n}` — модель отсутствует")
            elif not a["textures"]:
                gaps.append(f"- {kind}: `{n}` — модель без aether-текстур")
            elif not a["textures_ok"]:
                gaps.append(f"- {kind}: `{n}` — часть текстур не найдена: {a['textures']}")
    L.extend(gaps if gaps else ["**Нет.** Все зарегистрированные блоки/предметы имеют модель и текстуры."])
    L.append("")
    L.append("### Ожидаемые исключения (не дефекты)")
    L.append("")
    L.extend(expected if expected else ["_нет_"])
    L.append("")

    with open(os.path.join(ROOT, "INVENTORY.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(L))

    print("INVENTORY.md written.")
    print("blocks=%d items=%d entities=%d" % (
        len(inv.get("blocks", [])), len(inv.get("items", [])), len(inv.get("entities", []))))
    print("recipes=%d loot=%d advancements=%d" % (
        len(data.get("recipe", [])), len(data.get("loot_table", [])), len(data.get("advancement", []))))
    print("asset gaps=%d" % len(gaps))


if __name__ == "__main__":
    main()
