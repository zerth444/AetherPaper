"""Checks invariants behind the reported flower, entity UV and carrier sound regressions."""
import itertools
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def matches(rule, state):
    if 'AND' in rule: return all(matches(r, state) for r in rule['AND'])
    if 'OR' in rule: return any(matches(r, state) for r in rule['OR'])
    return all(state[key] in value.split('|') for key, value in rule.items())


with zipfile.ZipFile(ROOT / 'dist/aether-resourcepack.zip') as pack:
    def read(name): return json.loads(pack.read(name))
    wire = read('assets/minecraft/blockstates/tripwire.json')
    for values in itertools.product(('false', 'true'), repeat=7):
        state = dict(zip(('attached','disarmed','east','north','powered','south','west'), values))
        selected = [part['apply'] for part in wire['multipart'] if matches(part['when'], state)]
        if state['disarmed'] == 'true':
            flower = 'purple_flower' if state['powered'] == 'true' else 'white_flower'
            if state['attached']=='true': flower='berry_bush_stem' if state['powered']=='true' else 'berry_bush'
            elif all(state[key]=='true' for key in ('east','north','south','west')): flower='golden_oak_sapling' if state['powered']=='true' else 'skyroot_sapling'
            assert selected == [{'model': 'aether:block/' + flower}], (state, selected)
        else:
            assert len(selected) == 1 and selected[0]['model'].startswith('minecraft:'), state
    muted = read('assets/minecraft/sounds.json')
    private = read('assets/aether/sounds.json')
    for key in ('block.portal.ambient','block.portal.trigger','block.portal.travel'):
        assert muted[key] == {'replace': True, 'sounds': []}
    assert read('assets/minecraft/particles/portal.json')['textures']==['aether:empty']
    import io
    from PIL import Image
    assert Image.open(io.BytesIO(pack.read('assets/aether/textures/particle/empty.png'))).getpixel((0,0))[3]==0
    for action in ('break','place','hit','step','fall'):
        key = 'block.wood.' + action
        assert muted[key] == {'replace': True, 'sounds': []}
        assert private['carrier_original.' + key]['sounds']
        assert all(name.startswith('minecraft:') for name in private['carrier_original.' + key]['sounds'])
        assert muted['block.stone.'+action] == {'replace': True, 'sounds': []}
    for mob, scale in [('flying_cow',1),('phyg',1),('aerbunny',1),('moa',1.8),('cockatrice',1.8),('aerwhale',2)]:
        model = read('assets/aether/models/entity/' + mob + '.json')
        assert model['aether_renderer_scale'] == scale
        for element in model['elements']:
            for face in element['faces'].values():
                assert all(-.001 <= coordinate <= 16.001 for coordinate in face['uv']), (mob,face)
        if mob in ('phyg','flying_cow'):
            assert 'wings' in model['textures']
            assert any(face['texture'] == '#wings' for element in model['elements'] for face in element['faces'].values())
        if mob == 'aerwhale':
            length = (max(e['to'][2] for e in model['elements']) - min(e['from'][2] for e in model['elements'])) / 16 * model['aether_display_scale'] * scale
            assert length > 8, length
            print(f'Aerwhale model length: {length:.2f} blocks')
    blocks = json.loads((ROOT/'plugin/src/main/resources/content/blocks.json').read_text(encoding='utf-8'))
    for mob in ('white_flower','purple_flower'):
        assert blocks[mob]['sound'] == 'grass'
        assert blocks[mob]['legacy_carrier'].startswith('minecraft:note_block[')
    notes=read('assets/minecraft/blockstates/note_block.json')['variants']
    for key,model in notes.items():
        if 'note=24,' in key: assert model['model']=='aether:block/aether_grass_block'
        if 'note=23,' in key: assert model['model']=='aether:block/enchanted_aether_grass_block'
    for mob,count in [('flying_cow',4),('sheepuff',8)]:
        model=read('assets/aether/models/entity/'+mob+'.json')
        assert len(model['aether_limbs'])==count,(mob,model['aether_limbs'])
        for limb in model['aether_limbs']:
            assert read('assets/aether/items/'+limb['model'].split(':')[1]+'.json')['model']['type']=='minecraft:model'
    sheep=read('assets/aether/models/entity/sheepuff.json')
    # Original sheep torso is 8x16x6 before its 90-degree X rotation:
    # physical north is the old 8x6 top face, not the old 8x16 front.
    torso=sheep['elements'][1]
    uv=torso['faces']['north']['uv']
    assert abs(abs(uv[3]-uv[1])-3)<.0001, uv
print('PASS: 128 tripwire states, flower sound inheritance, mob UV/renderer scales, wood sound routing')
