#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Resource-pack converter: assembles a Paper 26.2 resource pack from the original
Aether mod assets (main + generated resources). No textures are drawn by hand.

- merges src/main/resources/assets/aether and src/generated/resources/assets/aether
- writes pack.mcmeta (resource format 88)
- generates minecraft:blockstates/note_block.json overrides so carrier note_block
  states render the original Aether block models (see tools/block_map.py)
- copies sounds.json, particles, shaders, font, GUI, lang (ru+en preserved)
- zips to dist/aether-resourcepack.zip
- writes tools/resourcepack_report.json (integrity summary)
"""
import json
import os
import shutil
import zipfile
from collections import OrderedDict

import block_map

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "aether-mod", "src", "main", "resources", "assets", "aether")
GEN = os.path.join(ROOT, "aether-mod", "src", "generated", "resources", "assets", "aether")
BUILD = os.path.join(ROOT, "build", "resourcepack")
DIST = os.path.join(ROOT, "dist")
RES_FORMAT = 88
INSTRUMENTS = block_map.INSTRUMENTS


def load(p):
    with open(p, "r", encoding="utf-8", errors="replace") as f:
        return json.load(f)


def dump(p, o):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(o, f, ensure_ascii=False, indent=2)


def find(path_parts):
    for base in (GEN, MAIN):
        p = os.path.join(base, *path_parts)
        if os.path.exists(p):
            return p
    return None


FALLBACK_MODEL = {
    "skyroot_wall_sign": "aether:block/skyroot_sign",
    "skyroot_wall_hanging_sign": "aether:block/skyroot_hanging_sign",
    "frosted_ice": "minecraft:block/frosted_ice_0",
    "unstable_obsidian": "minecraft:block/obsidian",
}


def block_model(block_id):
    """Resolve the primary aether model for a block id, or None."""
    if block_id.startswith('boss_doorway_'): return 'aether:block/'+block_id[len('boss_doorway_'):]
    if block_id in FALLBACK_MODEL:
        return FALLBACK_MODEL[block_id]
    p = find(("models", "block", block_id + ".json"))
    if p:
        return "aether:block/" + block_id
    bs = find(("blockstates", block_id + ".json"))
    if bs:
        try:
            data = load(bs)
        except Exception:
            return None
        for holder in ("variants", "multipart"):
            node = data.get(holder)
            models = []
            if isinstance(node, dict):
                models = list(node.values())
            elif isinstance(node, list):
                models = node
            for v in models:
                if isinstance(v, dict) and "model" in v:
                    return v["model"]
                if isinstance(v, dict) and isinstance(v.get("apply"), dict):
                    return v["apply"].get("model")
                if isinstance(v, dict) and isinstance(v.get("apply"), list):
                    for e in v["apply"]:
                        if isinstance(e, dict) and "model" in e:
                            return e["model"]
        return None
    item = find(("models", "item", block_id + ".json"))
    if item:
        try:
            d = load(item)
            if isinstance(d.get("parent"), str):
                return d["parent"]
        except Exception:
            pass
    return None


def generate_note_block_override(bmap, report):
    with zipfile.ZipFile(CLIENT_JAR) as client:
        native_notes=json.loads(client.read('assets/minecraft/blockstates/note_block.json'))['variants']
    variants = OrderedDict(native_notes)
    legacy = block_map.build_map(bmap.keys(), legacy=True)
    mapped = 0
    for aid, state in legacy.items():
        if not state.startswith("minecraft:note_block["):
            continue  # barrier carrier (special-render block), handled by the plugin
        model = block_model(aid)
        if model is None:
            report["blocks_without_model"].append(aid)
            continue
        key = state.split("minecraft:note_block[", 1)[1].rstrip("]")
        variants[key] = {"model": model}
        mapped += 1
    # fill the rest with the vanilla note block so ordinary note blocks still work
    for instr in INSTRUMENTS:
        for note in range(25):
            for powered in ("true", "false"):
                key = f"instrument={instr},note={note},powered={powered}"
                variants.setdefault(key, {"model": "minecraft:block/note_block"})
    native_instruments=set(INSTRUMENTS)
    for state in native_notes:
        native_instruments.update(value.split('=',1)[1] for value in state.split(',') if value.startswith('instrument='))
    for instr in sorted(native_instruments):
        for powered in ('true','false'):
            variants[f'instrument={instr},note=22,powered={powered}']={'model':'minecraft:block/note_block'}
    for instr in INSTRUMENTS:
        for note, aid in ((24, 'aether_grass_block'), (23, 'enchanted_aether_grass_block')):
            for powered in ('true','false'):
                variants[f'instrument={instr},note={note},powered={powered}'] = {'model': block_model(aid)}
    dump(os.path.join(BUILD, "assets", "minecraft", "blockstates", "note_block.json"),
         {"variants": variants})
    report["note_block_states_mapped"] = mapped
    report["note_block_variants_total"] = len(variants)
    with zipfile.ZipFile(CLIENT_JAR) as client:
        wire = json.loads(client.read('assets/minecraft/blockstates/tripwire.json'))
    if 'variants' in wire:
        wire = {'multipart': [{'when': dict(v.split('=') for v in key.split(',')), 'apply': value}
                              for key, value in wire['variants'].items()]}
    ordinary = {'disarmed': 'false'}
    for part in wire['multipart']:
        part['when'] = {'AND': [part['when'], ordinary]} if 'when' in part else ordinary
    for aid, state in bmap.items():
        if state.startswith('minecraft:tripwire['):
            props = dict(v.split('=') for v in state.split('[', 1)[1][:-1].split(','))
            if aid.endswith('_sapling'):
                when=props
            else:
                base={key: props[key] for key in ('attached','disarmed','powered')}
                # A sapling must select exactly one model, never the flower too.
                when={'AND':[base,{'OR':[{key:'false'} for key in ('east','north','south','west')]}]} if props['attached']=='false' else base
            wire['multipart'].append({'when': when, 'apply': {'model': block_model(aid)}})
    dump(os.path.join(BUILD, 'assets/minecraft/blockstates/tripwire.json'), wire)

    # The portal carrier is vanilla nether_portal (passable plane with an axis);
    # remap it to the original Aether portal models verbatim.
    dump(os.path.join(BUILD, "assets", "minecraft", "blockstates", "nether_portal.json"), {
        "variants": {
            "axis=x": {"model": "aether:block/aether_portal_ns"},
            "axis=z": {"model": "aether:block/aether_portal_ew"},
        }
    })
    report["nether_portal_overridden"] = True
    dump(os.path.join(BUILD,'assets','minecraft','blockstates','frosted_ice.json'),{'variants':{
        f'age={age}':{'model':'aether:block/quicksoil' if age==3 else f'minecraft:block/frosted_ice_{age}'} for age in range(4)}})

    emit_module_templates(report)
    relax_module_parents(report)
    tint_spawn_eggs(report)

    # 26.x item rendering resolves through assets/<ns>/items/*.json definitions:
    # emit one per model so every item_model id exists in the baked maps
    defs = 0
    composites = 0
    for dirpath, _d, files in os.walk(os.path.join(BUILD, "assets", "aether", "models")):
        for fn in files:
            if not fn.endswith(".json"):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, os.path.join(BUILD, "assets", "aether", "models"))
            model_id = "aether:" + rel[:-5].replace("\\", "/").replace(os.sep, "/")
            out = os.path.join(BUILD, "assets", "aether", "items", rel)
            parent = ""
            try:
                parent = json.loads(open(full, "rb").read().decode("utf-8", "replace")).get("parent", "")
            except Exception:
                parent = ""
            # beds ship as a head/foot pair since 1.21; the item def composites them
            if parent == "minecraft:item/template_bed" and model_id.startswith("aether:item/"):
                base = model_id.split("/")[-1]
                dump(out, {
                    "model": {
                        "type": "minecraft:composite",
                        "models": [
                            {"type": "minecraft:model", "model": "aether:block/" + base + "_head"},
                            {"type": "minecraft:model", "model": "aether:block/" + base + "_foot",
                             "transformation": {"translation": [0.0, 0.0, 1.0],
                                                "left_rotation": [0, 0, 0, 1],
                                                "scale": [1, 1, 1],
                                                "right_rotation": [0, 0, 0, 1]}},
                        ],
                    }
                })
                composites += 1
                defs += 1
                continue
            os.makedirs(os.path.dirname(out), exist_ok=True)
            definition = {"type": "minecraft:model", "model": model_id}
            if model_id.startswith('aether:entity/'):
                entity_model = load(full)
                for element in entity_model.get('elements',[]):
                    for face in element.get('faces',{}).values(): face['tintindex']=0
                dump(full,entity_model)
                definition['tints']=[{'type':'minecraft:custom_model_data','index':0,'default':16777215}]
                # Entity origin/normalisation is applied once by MobModels,
                # including the moving joint's rotated item-centre offset.
            dump(out, {"model": definition})
            defs += 1
    report["item_definitions"] = defs
    report["bed_composites"] = composites
    entity_textures = set()
    entity_dir = os.path.join(BUILD, 'assets', 'aether', 'models', 'entity')
    for fn in os.listdir(entity_dir):
        if fn.endswith('.json'):
            entity_textures.update(t for t in load(os.path.join(entity_dir, fn)).get('textures', {}).values()
                                   if isinstance(t, str) and not t.startswith('#'))
    # 26.2 rejects cross-atlas duplicate sprites in a future version. Both block
    # and item models can resolve explicitly listed sprites from the blocks atlas.
    for atlas in ('blocks',):
        dump(os.path.join(BUILD, 'assets', 'minecraft', 'atlases', atlas + '.json'), {
            'sources': [{'type': 'minecraft:single', 'resource': texture, 'sprite': texture}
                        for texture in sorted(entity_textures)]})
    report['entity_textures_in_atlases'] = len(entity_textures)


from minecraft_paths import client_jar
CLIENT_JAR = client_jar()

# Forge-only library the 1.21.1 assets parent to; the vanilla client cannot
# resolve them, so rebuild the geometry we need inside our own namespace.
SPAWN_EGG_COLORS = {
    "aechor_plant_spawn_egg": (0x076178, 0x4BC69E),
    "aerbunny_spawn_egg": (0xE2FCFF, 0xFFDFF9),
    "aerwhale_spawn_egg": (0xC0E7FD, 0x879EAA),
    "cockatrice_spawn_egg": (0x6CB15C, 0x6C579D),
    "fire_minion_spawn_egg": (0xFF6D01, 0xFEF500),
    "flying_cow_spawn_egg": (0xD8D8D8, 0xFFD939),
    "mimic_spawn_egg": (0xB18132, 0x605A4E),
    "moa_spawn_egg": (0x87BFEF, 0x7A7A7A),
    "phyg_spawn_egg": (0xFFC1D0, 0xFFD939),
    "sentry_spawn_egg": (0x808080, 0x3A8AEC),
    "sheepuff_spawn_egg": (0xE2FCFF, 0xCB9090),
    "blue_swet_spawn_egg": (0x4FB1DA, 0xCDDA4F),
    "golden_swet_spawn_egg": (0xCDDA4F, 0x4FB1DA),
    "whirlwind_spawn_egg": (0x9FC3F7, 0xFFFFFF),
    "evil_whirlwind_spawn_egg": (0x9FC3F7, 0x111111),
    "valkyrie_spawn_egg": (0xF9F5E3, 0xF2D200),
    "valkyrie_queen_spawn_egg": (0xF2D200, 0xF9F5E3),
    "slider_spawn_egg": (0xA7A7A7, 0x5C9FF2),
    "sun_spirit_spawn_egg": (0xFEF500, 0xFF6D01),
    "zephyr_spawn_egg": (0xDFDFDF, 0x99CFE8),
}

EGG_REFERENCE = "assets/minecraft/textures/item/cow_spawn_egg.png"
MODULE_MODELS = {
    "stairs_translucent": "minecraft:block/stairs",
    "inner_stairs_translucent": "minecraft:block/inner_stairs",
    "outer_stairs_translucent": "minecraft:block/outer_stairs",
}

POST = (4, 0, 4, 12, 16, 12)
LOW_ARM = (5, 0, 0, 11, 14, 8)
TALL_ARM = (5, 0, 0, 11, 16, 8)

# arm layout per variant: (post, list of (arm, rotation))
WALL_LAYOUT = {
    "template_translucent_wall_post": (True, []),
    "template_translucent_wall_post_nosides": (True, []),
    "template_translucent_wall_post_side": (True, [(LOW_ARM, 0)]),
    "template_translucent_wall_side": (False, [(LOW_ARM, 0)]),
    "template_translucent_wall_side_with_post": (True, [(LOW_ARM, 0)]),
    "template_translucent_wall_side_tall": (False, [(TALL_ARM, 0)]),
    "template_translucent_wall_side_tall_with_post": (True, [(TALL_ARM, 0)]),
    "template_translucent_wall_corner": (False, [(LOW_ARM, 0), (LOW_ARM, 1)]),
    "template_translucent_wall_corner_tall": (False, [(TALL_ARM, 0), (TALL_ARM, 1)]),
    "template_translucent_wall_t": (True, [(LOW_ARM, 0), (LOW_ARM, 1), (LOW_ARM, 3)]),
    "template_translucent_wall_t_tall": (True, [(TALL_ARM, 0), (TALL_ARM, 1), (TALL_ARM, 3)]),
    "template_translucent_wall_plus": (True, [(LOW_ARM, 0), (LOW_ARM, 1),
                                              (LOW_ARM, 2), (LOW_ARM, 3)]),
    "template_translucent_wall_plus_tall": (True, [(TALL_ARM, 0), (TALL_ARM, 1),
                                                   (TALL_ARM, 2), (TALL_ARM, 3)]),
}


def rot_y(box, times):
    """Rotate a 6-tuple box around the block centre; texture must be uniform."""
    x0, y0, z0, x1, y1, z1 = box
    for _ in range(times % 4):
        x0, z0, x1, z1 = 16 - z1, x0, 16 - z0, x1
    return x0, y0, z0, x1, y1, z1


def wall_element(box):
    """Vanilla wall cuboid: same per-face uv derivation as a plain box."""
    x0, y0, z0, x1, y1, z1 = box
    if x1 <= x0 or y1 <= y0 or z1 <= z0:
        return None
    faces = {
        "down": {"uv": [x0, z0, x1, z1], "texture": "#wall", "cullface": "down"},
        "up": {"uv": [x0, 16 - z1, x1, 16 - z0], "texture": "#wall"},
        "north": {"uv": [16 - x1, 16 - y1, 16 - x0, 16 - y0], "texture": "#wall"},
        "south": {"uv": [x0, 16 - y1, x1, 16 - y0], "texture": "#wall"},
        "west": {"uv": [z0, 16 - y1, z1, 16 - y0], "texture": "#wall"},
        "east": {"uv": [16 - z1, 16 - y1, 16 - z0, 16 - y0], "texture": "#wall"},
    }
    return {"from": [x0, y0, z0], "to": [x1, y1, z1], "faces": faces,
            "__comment": "generated wall piece"}


def emit_module_templates(report):
    """Write aether-namespaced copies of the Forge library's templates."""
    if not os.path.isfile(CLIENT_JAR):
        report["module_templates"] = 0
        return
    zf = zipfile.ZipFile(CLIENT_JAR)
    written = 0
    for name, vanilla in MODULE_MODELS.items():
        path = "assets/minecraft/models/" + vanilla.split(":", 1)[1] + ".json"
        data = json.loads(zf.read(path).decode())
        parent = data.get("parent")
        if parent and ":" not in parent:
            data["parent"] = "minecraft:block/" + parent.split("block/")[-1]
        dump(os.path.join(BUILD, "assets", "aether", "models", "block", name + ".json"), data)
        written += 1
    for name, (with_post, arms) in WALL_LAYOUT.items():
        elements = []
        if with_post:
            e = wall_element(POST)
            if e:
                elements.append(e)
        for arm, r in arms:
            e = wall_element(rot_y(arm, r))
            if e:
                elements.append(e)
        model = {"parent": "minecraft:block/block",
                 "textures": {"particle": "#wall", "wall": "#wall"},
                 "elements": elements}
        dump(os.path.join(BUILD, "assets", "aether", "models", "block", name + ".json"), model)
        written += 1
    dump(os.path.join(BUILD, "assets", "aether", "models", "block",
                      "template_translucent_wall_inventory.json"),
         {"parent": "minecraft:block/wall_inventory", "textures": {"wall": "#wall"}})
    written += 1
    report["module_templates"] = written


def relax_module_parents(report):
    fixed = 0
    for dirpath, _d, files in os.walk(os.path.join(BUILD, "assets", "aether")):
        for fn in files:
            if not fn.endswith(".json"):
                continue
            full = os.path.join(dirpath, fn)
            raw = open(full, "rb").read().decode("utf-8", "replace")
            if "nitrogen_internals:" not in raw:
                continue
            data = json.loads(raw)
            parent = data.get("parent")
            if isinstance(parent, str) and parent.startswith("nitrogen_internals:block/"):
                data["parent"] = "aether:block/" + parent.split("/", 1)[1]
                dump(full, data)
                fixed += 1
    report["module_parents_rewritten"] = fixed


def tint_spawn_eggs(report):
    """Bake Forge's runtime egg tint into textures for the vanilla client."""
    if not os.path.isfile(CLIENT_JAR):
        return
    zf = zipfile.ZipFile(CLIENT_JAR)
    from PIL import Image
    import io
    src = Image.open(io.BytesIO(zf.read(EGG_REFERENCE))).convert("RGBA")
    opaque = [p for p in src.getdata() if p[3] > 0]
    lums = [0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2] for p in opaque]
    lo, hi = min(lums), max(lums)
    span = (hi - lo) or 1.0
    made = 0
    for egg, (bg, hl) in SPAWN_EGG_COLORS.items():
        model_path = os.path.join(BUILD, "assets", "aether", "models", "item", egg + ".json")
        if not os.path.exists(model_path):
            continue
        dump(model_path, {"parent": "minecraft:item/generated",
                          "textures": {"layer0": "aether:item/" + egg}})
        out = os.path.join(BUILD, "assets", "aether", "textures", "item", egg + ".png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        img = Image.new("RGBA", src.size)
        data = []
        for p in src.getdata():
            if p[3] == 0:
                data.append((0, 0, 0, 0))
                continue
            t = ((0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]) - lo) / span
            r = int(round(((bg >> 16) & 255) + (((hl >> 16) & 255) - ((bg >> 16) & 255)) * t))
            g = int(round(((bg >> 8) & 255) + (((hl >> 8) & 255) - ((bg >> 8) & 255)) * t))
            b = int(round((bg & 255) + ((hl & 255) - (bg & 255)) * t))
            data.append((r, g, b, p[3]))
        img.putdata(data)
        img.save(out)
        made += 1
    report["spawn_eggs_tinted"] = made


def copytree_overlay():
    copied = 0
    for base in (MAIN, GEN):
        if not os.path.isdir(base):
            continue
        for dirpath, _d, files in os.walk(base):
            for fn in files:
                src = os.path.join(dirpath, fn)
                rel = os.path.relpath(src, base)
                # glove trim overrides reference per-material textures that only
                # exist via the mod's runtime tinting; without them the trimmed
                # item would render missing, so fall back to the base model
                if rel.startswith("models" + os.sep + "item") and fn.endswith("_trim.json"):
                    continue
                dst = os.path.join(BUILD, "assets", "aether", rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                # generated assets win over main for identical paths
                if base == MAIN and os.path.exists(os.path.join(GEN, rel)):
                    continue
                shutil.copy2(src, dst)
                copied += 1
    # converted entity display models live in the plugin resources
    extra = os.path.join(ROOT, "plugin", "src", "main", "resources", "assets",
                         "aether", "models", "entity")
    if os.path.isdir(extra):
        os.makedirs(os.path.join(BUILD, "assets", "aether", "models", "entity"),
                    exist_ok=True)
        for fn in os.listdir(extra):
            if fn.endswith(".json"):
                shutil.copy2(os.path.join(extra, fn),
                             os.path.join(BUILD, "assets", "aether", "models", "entity", fn))
                copied += 1
    return copied


def main():
    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    os.makedirs(BUILD, exist_ok=True)
    report = OrderedDict()
    report["blocks_without_model"] = []

    copied = copytree_overlay()
    # Bitmap illustrations use existing Aether item/block textures; no external
    # images or client mod is needed for the native written-book screen.
    lore=load(os.path.join(ROOT,'plugin','src','main','resources','content','lore.json'))
    texture_root=os.path.join(BUILD,'assets','aether','textures')
    candidates={}
    for directory,_,files in os.walk(texture_root):
        for filename in files:
            if not filename.endswith('.png') or os.path.exists(os.path.join(directory,filename+'.mcmeta')): continue
            rel=os.path.relpath(os.path.join(directory,filename),texture_root).replace('\\','/')
            if rel.startswith(('item/','block/')): candidates.setdefault(filename[:-4],[]).append(rel)
    providers=[];glyphs={}
    for index,key in enumerate(lore):
        choices=candidates.get(key.split('.')[-1],[])
        if not choices:continue
        texture=sorted(choices,key=lambda p:(not p.startswith('item/'),p))[0]
        glyph=chr(0xe400+index);glyphs[key]=glyph
        providers.append({'type':'bitmap','file':'aether:'+texture,'height':24,'ascent':20,'chars':[glyph]})
    dump(os.path.join(BUILD,'assets','aether','font','lore_icons.json'),{'providers':providers})
    dump(os.path.join(ROOT,'plugin','src','main','resources','content','lore_icons.json'),glyphs)
    # Client predicts the carrier's wood sounds before server events can cancel
    # them. Mute those five vanilla events and retain their original samples
    # under private names; CarrierSounds restores real wood and custom materials.
    wood = load(os.path.join(ROOT, 'tools', 'vanilla_wood_sounds.json'))
    for action in ('break','place','hit','step','fall'):
        wood['block.snow.'+action]={'sounds':[f'minecraft:step/snow{i}' for i in range(1,5)]}
    private = load(os.path.join(BUILD, 'assets', 'aether', 'sounds.json'))
    muted = {}
    for key, original in wood.items():
        alias = 'carrier_original.' + key
        for i, sound in enumerate(original['sounds']):
            if isinstance(sound, str):
                original['sounds'][i] = sound if ':' in sound else 'minecraft:' + sound
            elif ':' not in sound['name']:
                sound['name'] = 'minecraft:' + sound['name']
        private[alias] = original
        muted[key] = {'replace': True, 'sounds': []}
    dump(os.path.join(BUILD, 'assets', 'aether', 'sounds.json'), private)
    dump(os.path.join(ROOT, 'plugin', 'src', 'main', 'resources', 'content', 'sound_events.json'), list(private))
    dump(os.path.join(BUILD, 'assets', 'minecraft', 'sounds.json'), muted)
    # PortalBlock emits client-side purple particles. Give only that particle
    # an empty sprite; ordinary Nether portals are rendered server-side using
    # reverse_portal, whose original sprite remains untouched.
    import struct
    import zlib
    empty = os.path.join(BUILD, 'assets', 'aether', 'textures', 'particle', 'empty.png')
    os.makedirs(os.path.dirname(empty), exist_ok=True)
    def png_chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    with open(empty, 'wb') as out:
        out.write(b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 1,1,8,6,0,0,0))
                  + png_chunk(b'IDAT', zlib.compress(bytes(5))) + png_chunk(b'IEND', b''))
    dump(os.path.join(BUILD, 'assets', 'minecraft', 'particles', 'portal.json'), {'textures':['aether:empty']})

    inv = load(os.path.join(ROOT, "tools", "inventory.json"))
    bmap = block_map.build_map(inv["blocks"])
    generate_note_block_override(bmap, report)
    for aid,state in bmap.items():
        if state.endswith('_stained_glass'):
            dump(os.path.join(BUILD,'assets','minecraft','blockstates',state.split(':')[1]+'.json'),{'variants':{'':{'model':block_model(aid)}}})


    with zipfile.ZipFile(CLIENT_JAR) as client:
        snow=json.loads(client.read('assets/minecraft/blockstates/snow.json'))
    for cloud,layers in [('cold_aercloud',6),('blue_aercloud',5),('golden_aercloud',7)]:
        snow['variants'][f'layers={layers}']={'model':block_model(cloud)}
    dump(os.path.join(BUILD,'assets','minecraft','blockstates','snow.json'),snow)

    # The lower Overworld sky disc is hardcoded black below Y63. A packet flat
    # flag only hides it, leaving the background exposed on some renderers.
    # Colour the disc itself, exclusively in the marked Aether atmosphere.
    # Other dimensions execute the exact original shader body.
    with zipfile.ZipFile(CLIENT_JAR) as client:
        sky = client.read('assets/minecraft/shaders/core/sky.fsh').decode('utf-8')
    sky=sky.replace('void main() {', '''void main() {
    if (abs(FogEnvironmentalEnd - 8191.25) < 0.0625) {
        vec4 sky = ColorModulator;
        if (max(sky.r, max(sky.g, sky.b)) < 0.001) {
            sky = vec4(0.71764706, 0.85098039, 1.0, 1.0);
        }
        fragColor = sky;
        return;
    }''')
    shader=os.path.join(BUILD,'assets','minecraft','shaders','core','sky.fsh')
    os.makedirs(os.path.dirname(shader),exist_ok=True)
    with open(shader,'w',encoding='utf-8') as out: out.write(sky)
    # A mathematically defined zero-light result, even with Fullbright's gamma
    # outside the vanilla range. Non-zero light follows the original expression.
    with zipfile.ZipFile(CLIENT_JAR) as client:
        light=client.read('assets/minecraft/shaders/core/lightmap.fsh').decode('utf-8')
    needle='float maxComponent = max(max(color.x, color.y), color.z);'
    assert needle in light, 'Pinned lightmap shader changed'
    light=light.replace(needle,needle+'\n    if (maxComponent <= 0.0) return vec3(0.0);')
    with open(os.path.join(os.path.dirname(shader),'lightmap.fsh'),'w',encoding='utf-8') as out:out.write(light)

    dump(os.path.join(BUILD, "pack.mcmeta"), {
        "pack": {
            # legacy field and the 26.x min/max pair: whichever the client
            # validates must be present (unknown fields are ignored)
            "description": "The Aether - private fan port resource pack",
            "pack_format": RES_FORMAT,
            "max_format": RES_FORMAT,
            "min_format": [RES_FORMAT, 0],
        }
    })
    import gen_lore_gui
    gen_lore_gui.build()
    import gen_slab_visuals
    gen_slab_visuals.build()

    os.makedirs(DIST, exist_ok=True)
    zpath = os.path.join(DIST, "aether-resourcepack.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for dirpath, _d, files in os.walk(BUILD):
            for fn in files:
                full = os.path.join(dirpath, fn)
                z.write(full, os.path.relpath(full, BUILD))

    report["files_copied"] = copied
    report["zip"] = os.path.relpath(zpath, ROOT)
    report["zip_bytes"] = os.path.getsize(zpath)
    with open(os.path.join(ROOT, "tools", "resourcepack_report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)

    print(f"copied={copied} note_block_states={report['note_block_states_mapped']} "
          f"blocks_without_model={len(report['blocks_without_model'])}")
    print("zip:", os.path.relpath(zpath, ROOT), os.path.getsize(zpath), "bytes")


if __name__ == "__main__":
    main()
