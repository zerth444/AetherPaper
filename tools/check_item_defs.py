import json
import os
import zipfile
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
z = zipfile.ZipFile(os.path.join(ROOT, "..", "dist", "aether-resourcepack.zip"))
znames = set(z.namelist())
from minecraft_paths import client_jar
c = zipfile.ZipFile(client_jar())

content = os.path.join(ROOT, "..", "plugin", "src", "main", "resources", "content")
missing = []
total = 0
for fn in ("blocks.json", "items.json"):
    data = json.load(open(os.path.join(content, fn), encoding="utf-8"))
    for k, v in data.items():
        model = v.get("model")
        if not isinstance(model, str):
            continue
        total += 1
        ns, _, path = model.partition(":")
        if ns == "aether":
            p = f"assets/aether/items/{path}.json"
            if p not in znames:
                missing.append((fn, k, model, p))
        elif ns == "minecraft":
            p = f"assets/minecraft/items/{path}.json"
            if p not in znames and p not in c.namelist():
                missing.append((fn, k, model, p))
print(f"checked={total} missing={len(missing)}")
for m in missing[:25]:
    print("MISS", m)
sys.exit(1 if missing else 0)
