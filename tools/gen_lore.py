#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Bakes Book of Lore entries from the mod lang files into content/lore.json."""
import json
import os
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "content", "lore.json")


def load_lang(name):
    for base in ("src/main/resources/assets/aether/lang",
                 "src/generated/resources/assets/aether/lang"):
        p = os.path.join(ROOT, "aether-mod", base, name)
        if os.path.isfile(p):
            lang=json.load(open(p, encoding="utf-8"))
            if name=='ru_ru.json':lang.update(json.load(open(os.path.join(ROOT,'tools','lore_titles_ru.json'),encoding='utf-8')))
            return lang
    return {}


def main():
    en = load_lang("en_us.json")
    ru = load_lang("ru_ru.json")
    overrides=json.load(open(os.path.join(ROOT,"tools","lore_ru.json"),encoding="utf-8"))
    lore = OrderedDict()
    for key in sorted(en):
        if key.startswith("lore.") and isinstance(en[key], str):
            lore[key] = {"en": en[key], "ru": overrides.get(key,ru.get(key, ""))}
            if not lore[key]['ru'].strip():raise ValueError("Missing Russian lore: "+key)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(lore, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"lore entries={len(lore)}")


if __name__ == "__main__":
    main()
