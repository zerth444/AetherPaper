#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Headless renderer for every model in the resource pack: resolves parent
chains (pack + vanilla client jar), rasterizes a front orthographic view with
PIL, and reports missing textures (magenta) per model. Exit 1 on failures."""
import io
import json
import os
import sys
import zipfile
from collections import OrderedDict

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK = os.path.join(ROOT, "dist", "aether-resourcepack.zip")
CLIENT = "C:/Users/LEMANS~1/AppData/Local/Temp/opencode/client-26.2.jar"
OUTDIR = os.path.join(ROOT, "build", "renders")

MAGENTA = (255, 0, 255, 255)


class Repo:
    def __init__(self):
        self.pack = zipfile.ZipFile(PACK)
        self.pnames = set(self.pack.namelist())
        self.client = zipfile.ZipFile(CLIENT) if os.path.isfile(CLIENT) else None
        self.cn = set(self.client.namelist()) if self.client else set()
        self.texcache = {}
        self.modelcache = {}

    def model(self, ref):
        """ref like 'aether:block/x' or 'minecraft:item/handheld' -> dict or None."""
        if ref in self.modelcache:
            return self.modelcache[ref]
        ns, _, path = ref.partition(":")
        data = None
        if ns == "aether":
            p = f"assets/aether/models/{path}.json"
            if p in self.pnames:
                data = json.loads(self.pack.read(p).decode())
        elif ns == "minecraft" and self.client:
            for cand in (f"assets/minecraft/models/{path}.json",):
                if cand in self.cn:
                    data = json.loads(self.client.read(cand).decode())
                    break
        self.modelcache[ref] = data
        return data

    def texture(self, ref):
        """ref like 'aether:item/x' (no extension) -> PIL image or None."""
        if ref in self.texcache:
            return self.texcache[ref]
        ns, _, path = ref.partition(":")
        img = None
        for base, arch, names in (("aether", self.pack, self.pnames),
                                  ("minecraft", self.client, self.cn)):
            if ns != base or arch is None:
                continue
            p = f"assets/{base}/textures/{path}.png"
            if p in names:
                try:
                    img = Image.open(io.BytesIO(arch.read(p))).convert("RGBA")
                except Exception:
                    img = None
                break
        self.texcache[ref] = img
        return img


def resolve_model(repo, ref, depth=0):
    """Follow parents; return (textures dict, elements list or None, display?)."""
    if depth > 8:
        return {}, None
    data = repo.model(ref)
    if data is None:
        return None, None
    textures = dict(data.get("textures", {}))
    elements = data.get("elements")
    parent = data.get("parent")
    if parent and parent != "builtin/generated" and not parent.startswith("builtin/"):
        if ":" not in parent:
            parent = ref.split(":")[0] + ":" + parent
        ptex, pele = resolve_model(repo, parent, depth + 1)
        if ptex is None and pele is None:
            return None, None
        merged = dict(ptex)
        for k, v in textures.items():
            if isinstance(v, str) and v.startswith("#"):
                target = v[1:]
                merged[k] = merged.get(target, v)
            else:
                merged[k] = v
        if elements is None:
            elements = pele
        textures = merged
    # resolve texture variables (#x)
    final = {}
    for k, v in textures.items():
        if isinstance(v, str) and v.startswith("#"):
            final[k] = textures.get(v[1:], v)
        else:
            final[k] = v
    return final, elements


def draw_sprite(img, size=128):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    if img is None:
        canvas.paste(MAGENTA[:3] + (255,), (0, 0, size, size))
        return canvas, 1.0
    w, h = img.size
    scale = min(size / max(1, w), size / max(1, h))
    nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
    small = img.resize((nw, nh), Image.NEAREST)
    canvas.paste(small, ((size - nw) // 2, (size - nh) // 2), small)
    return canvas, 0.0


def render_model(repo, ref, size=128):
    textures, elements = resolve_model(repo, ref)
    if textures is None:
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        canvas.paste((255, 0, 255, 255), (0, 0, size, size))
        return canvas, 1.0, "missing-model"
    if not elements:
        # flat sprite: layer0..layer4 stacked
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        magenta = 0
        total = 0
        for layer in ("layer0", "layer1", "layer2", "layer3", "layer4"):
            tref = textures.get(layer)
            if not isinstance(tref, str) or tref.startswith("#"):
                continue
            img = repo.texture(tref)
            if img is None:
                magenta += 1
                continue
            w, h = img.size
            scale = min(size / max(1, w), size / max(1, h))
            nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
            small = img.resize((nw, nh), Image.NEAREST)
            tmp = Image.new("RGBA", (size, size), (0, 0, 0, 0))
            tmp.paste(small, ((size - nw) // 2, (size - nh) // 2), small)
            canvas = Image.alpha_composite(canvas, tmp)
            total += 1
        if total == 0 and magenta > 0:
            canvas.paste((255, 0, 255, 255), (0, 0, size, size))
            return canvas, 1.0, "missing-texture"
        return canvas, 0.0, "ok-sprite"
    # cuboid front view: draw elements back-to-front by z0
    els = sorted(elements, key=lambda e: e.get("from", [0, 0, 0])[2])
    # bounds
    xs = [c for e in els for c in (e["from"][0], e["to"][0])]
    ys = [c for e in els for c in (e["from"][1], e["to"][1])]
    x0, x1 = min(xs + [0]), max(xs + [16])
    y0, y1 = min(ys + [0]), max(ys + [16])
    span = max(x1 - x0, y1 - y0, 1)
    scale = (size - 8) / span
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    miss_px = 0
    tot_px = 0
    for e in els:
        fx0, fy0, fz0 = e["from"]
        fx1, fy1, fz1 = e["to"]
        w = max(1, int(round((fx1 - fx0) * scale)))
        h = max(1, int(round((fy1 - fy0) * scale)))
        px = int(round(4 + (fx0 - x0) * scale))
        py = int(round(4 + (y1 - fy1) * scale))
        face = e.get("faces", {}).get("north") or e.get("faces", {}).get("south")
        if not face:
            continue
        uv = face.get("uv", [0, 0, 16, 16])
        tref = (face.get("texture", "#0") or "#0")
        if tref.startswith("#"):
            tref = textures.get(tref[1:], tref)
        img = repo.texture(tref) if isinstance(tref, str) and ":" in tref else None
        if img is None:
            miss_px += w * h
            tot_px += w * h
            patch = Image.new("RGBA", (w, h), (255, 0, 255, 255))
            canvas.paste(patch, (px, py))
            continue
        tw, th = img.size
        # uv are in texture pixels (0..tw); our faces store absolute px
        x_a = int(max(0, min(uv[0], uv[2])))
        y_a = int(max(0, min(uv[1], uv[3])))
        x_b = int(min(tw, max(uv[0], uv[2])))
        y_b = int(min(th, max(uv[1], uv[3])))
        crop = img.crop((x_a, y_a, x_b, y_b))
        if crop.size[0] <= 0 or crop.size[1] <= 0:
            miss_px += w * h
            tot_px += w * h
            patch = Image.new("RGBA", (w, h), (255, 0, 255, 255))
            canvas.paste(patch, (px, py))
            continue
        patch = crop.resize((w, h), Image.NEAREST)
        canvas.paste(patch, (px, py))
        tot_px += w * h
    ratio = (miss_px / tot_px) if tot_px else 0.0
    return canvas, ratio, "ok-cuboid"


def main():
    os.makedirs(OUTDIR, exist_ok=True)
    repo = Repo()
    print("DBG pack entries:", len(repo.pnames), "client:", repo.client is not None)
    report = OrderedDict()
    # real refs come from content/*.json, which carry the model actually used
    model_of = {}
    cb = os.path.join(ROOT, "plugin", "src", "main", "resources", "content", "blocks.json")
    if os.path.exists(cb):
        for k, v in json.load(open(cb, encoding="utf-8")).items():
            if isinstance(v, dict) and isinstance(v.get("model"), str):
                model_of["block:" + k] = v["model"]
    ci = os.path.join(ROOT, "plugin", "src", "main", "resources", "content", "items.json")
    if os.path.exists(ci):
        for k, v in json.load(open(ci, encoding="utf-8")).items():
            if isinstance(v, dict) and isinstance(v.get("model"), str):
                model_of.setdefault("item:" + k, v["model"])
    inv = json.load(open(os.path.join(ROOT, "tools", "inventory.json"), encoding="utf-8"))
    ids = []
    for bid in inv["blocks"]:
        ids.append("block:" + bid)
    for iid in inv["items"]:
        ids.append("item:" + iid)
    ent_dir = "assets/aether/models/entity/"
    for n in repo.pnames:
        if n.startswith(ent_dir) and n.endswith(".json"):
            ids.append("entity/" + n[len(ent_dir):-5])
    fails = []
    for short in sorted(set(ids)):
        ref = model_of.get(short) or ("aether:" + short.replace(":", "/"))
        canvas, ratio, status = render_model(repo, ref)
        if status.startswith("missing") or ratio > 0.05:
            fails.append((short, status, round(ratio, 3)))
        out = os.path.join(OUTDIR, short.replace("/", "_") + ".png")
        canvas.save(out)
        report[short] = {"status": status, "magenta": round(ratio, 3)}
    with open(os.path.join(ROOT, "tools", "renders_report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    print(f"rendered={len(report)} fails={len(fails)} out=build/renders")
    for short, status, ratio in fails[:40]:
        print("FAIL", short, status, ratio)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
