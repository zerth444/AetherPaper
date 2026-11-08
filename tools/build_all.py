#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""One-command build: data converters + integrity check.
Usage: python tools/build_all.py   (then build the plugin with Gradle)
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STEPS = [
    "tools/prepare_sources.py",
    "tools/inventory.py",
    "tools/port_data.py",
    "tools/gen_content.py",
    "tools/gen_mobs.py",
    "tools/gen_recipes.py",
    "tools/gen_advancements.py",
    "tools/gen_lore.py",
    "tools/gen_ores.py",
    "tools/gen_dungeon_pieces.py",
    "tools/gen_mob_models.py",
    "tools/convert_assets.py",
    "tools/check_survival_progression.py",
    "tools/integrity.py",
    "tools/check_item_defs.py",
    "tools/check_pack_models.py",
    "tools/check_regression_assets.py",
    "tools/check_lore_gui.py",
    "tools/check_slabs.py",
]


def main():
    for step in STEPS:
        print("=== " + step + " ===")
        r = subprocess.run([sys.executable, os.path.join(ROOT, step)],
                           cwd=ROOT, capture_output=True, text=True, encoding='utf-8',
                           env={**os.environ, 'PYTHONIOENCODING': 'utf-8'})
        print(r.stdout[-1500:] if len(r.stdout) > 1500 else r.stdout)
        if r.returncode != 0:
            print(r.stderr[-2000:])
            print("FAILED: " + step)
            sys.exit(1)
    print("BUILD_ALL OK")


if __name__ == "__main__":
    main()
