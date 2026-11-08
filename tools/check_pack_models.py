"""Validate the models actually used by the plugin, including entity textures and atlases."""
import json
import math
import os
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PACK = zipfile.ZipFile(ROOT / 'dist/aether-resourcepack.zip')
from minecraft_paths import client_jar
CLIENT = zipfile.ZipFile(client_jar())
ERRORS = set()
CACHE = {}


def read(path):
    for archive in (PACK, CLIENT):
        if path in archive.namelist():
            return json.loads(archive.read(path))
    ERRORS.add('Missing ' + path)
    return {}


def location(ref, folder):
    ns, path = ref.split(':', 1) if ':' in ref else ('minecraft', ref)
    return f'assets/{ns}/{folder}/{path}.json'


def resolved(ref, chain=()):
    if ref in CACHE:
        return CACHE[ref]
    if ref in chain:
        ERRORS.add('Model parent cycle: ' + ref)
        return {}
    data = read(location(ref, 'models'))
    parent = data.get('parent', '')
    merged = resolved(parent, chain + (ref,)) if parent and 'builtin/' not in parent else {}
    result = {**merged, **data}
    result['textures'] = {**merged.get('textures', {}), **data.get('textures', {})}
    CACHE[ref] = result
    return result


def texture(ref, textures):
    seen = set()
    while ref.startswith('#'):
        if ref in seen:
            return None
        seen.add(ref)
        ref = textures.get(ref[1:], '')
    return ref or None


def validate_model(ref):
    data = resolved(ref)
    textures = data.get('textures', {})
    used = [v for k, v in textures.items() if k.startswith('layer')]
    for element in data.get('elements', []):
        for corner in ('from', 'to'):
            coords = element.get(corner, [])
            if len(coords) != 3 or any(not math.isfinite(x) or not -16 <= x <= 32 for x in coords):
                ERRORS.add('Invalid element bounds: ' + ref)
        used.extend(face.get('texture', '') for face in element.get('faces', {}).values())
    for use in used:
        tex = texture(use, textures)
        if not tex:
            ERRORS.add('Unresolved texture in ' + ref + ': ' + use)
            continue
        ns, path = tex.split(':', 1) if ':' in tex else ('minecraft', tex)
        filename = f'assets/{ns}/textures/{path}.png'
        if filename not in PACK.namelist() and filename not in CLIENT.namelist():
            ERRORS.add('Missing texture: ' + filename)
        if ref.startswith('aether:entity/') and tex not in ATLAS:
            ERRORS.add('Entity texture absent from block atlas: ' + tex)


def definition(node):
    transform = node.get('transformation')
    if isinstance(transform, dict) and not all(k in transform for k in
                                             ('translation', 'scale', 'left_rotation', 'right_rotation')):
        ERRORS.add('Incomplete item transformation: ' + str(transform))
    if node.get('type') == 'minecraft:model':
        validate_model(node['model'])
    for child in node.get('models', []):
        definition(child)


ATLAS = {s.get('sprite', s.get('resource'))
         for s in read('assets/minecraft/atlases/blocks.json').get('sources', [])}
roots = set()
for file in ('blocks.json', 'items.json'):
    for item in json.loads((ROOT / 'plugin/src/main/resources/content' / file).read_text(encoding='utf-8')).values():
        if isinstance(item.get('model'), str):
            roots.add(item['model'])
for path in (ROOT / 'plugin/src/main/resources/assets/aether/models/entity').glob('*.json'):
    roots.add('aether:entity/' + path.stem)
for ref in sorted(roots):
    definition(read(location(ref, 'items')).get('model', {}))
states = read('assets/minecraft/blockstates/note_block.json')
for node in states.get('variants', {}).values():
    for model in node if isinstance(node, list) else [node]:
        validate_model(model['model'])
for error in sorted(ERRORS):
    print(error)
print(f'Validated {len(roots)} item definitions, {len(CACHE)} models; errors={len(ERRORS)}')
raise SystemExit(1 if ERRORS else 0)
