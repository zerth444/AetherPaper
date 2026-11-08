"""Check source-complete lore pagination, font glyph references and menu geometry."""
import json, re, zipfile
from pathlib import Path
from lore_content import available_entries

ROOT=Path(__file__).resolve().parent.parent
read=lambda path:json.loads((ROOT/path).read_text(encoding='utf8'))
lore=read('plugin/src/main/resources/content/lore.json')
layout=read('plugin/src/main/resources/content/lore_gui.json')
pages=read('build/lore_gui_pages.json')
available=available_entries(lore)
assert set(available)=={key for key in layout['entries'] if not key.startswith('__')}
assert 0<len(available)<len(lore)
for key,data in lore.items():
    assert data.get('ru') and re.search('[А-Яа-яЁё]',data['ru']),(key,'missing Russian description')
for excluded in ('lore.item.aether.gold_dungeon_key','lore.item.aether.victory_medal','lore.item.aether.valkyrie_pickaxe','lore.block.aether.treasure_chest'):
    assert excluded not in layout['entries'],excluded
for included in ('lore.block.aether.aether_grass_block','lore.block.aether.zanite_ore','lore.block.aether.golden_oak_log','lore.block.aether.golden_oak_wood','lore.item.aether.gravitite_pickaxe'):
    assert included in layout['entries'],included
for key,data in available.items():
    for locale in ('en','ru'):
        selected=[page for page in pages if page['key']==key and page['locale']==locale]
        expected=data[locale]
        if locale=='ru':assert re.search('[А-Яа-яЁё]',layout['titles'][key][locale]),(key,'Russian title')
        actual=' '.join(line for page in selected for line in page['lines'])
        # Hard-wrapped long words gain a visual line break, not a missing letter.
        assert re.sub(r'\s+','',actual)==re.sub(r'\s+','',expected),(key,locale)
        assert re.sub(r'\s+','',''.join(selected[0]['title_lines']))==re.sub(r'\s+','',selected[0]['title']),(key,locale,'title')
        assert all(page['body_y']+max(0,len(page['lines'])-1)*10+8<=80 for page in selected),(key,locale,'text bounds')
        assert [page['glyph'] for page in selected]==layout['entries'][key][locale]
        assert all(len(page['lines'])<=(6 if page['page']==0 else 8) for page in selected)
with zipfile.ZipFile(ROOT/'dist/aether-resourcepack.zip') as pack:
    russian=json.loads(pack.read('assets/aether/lang/ru_ru.json'))
    for key,data in lore.items():assert russian[key]==data['ru'],(key,'pack Russian text')
    font=json.loads(pack.read('assets/aether/font/lore_gui.json'));glyphs=set()
    for provider in font['providers']:
        if provider['type']=='space':continue
        glyphs.update(''.join(provider['chars']))
        ns,path=provider['file'].split(':');assert f'assets/{ns}/textures/{path}' in pack.namelist()
        assert provider['ascent']<=provider['height']
    assert all(page['glyph'] in glyphs for page in pages)
    assert all(char in glyphs for char in layout['backgrounds'].values())
    invisible=json.loads(pack.read('assets/aether/items/gui/lore_invisible.json'))
    assert invisible['model']['model']=='aether:gui/lore_invisible'
    assert 'assets/aether/models/gui/lore_invisible.json' in pack.namelist()
    model=json.loads(pack.read('assets/aether/models/gui/lore_invisible.json'))
    assert model['textures']['layer0']=='aether:item/gui_empty'
    assert 'assets/aether/textures/item/gui_empty.png' in pack.namelist()
    for material in ('white','light_blue','yellow'):
        assert f'assets/minecraft/blockstates/{material}_stained_glass.json' not in pack.namelist()
assert layout['input_slot']==29 and layout['previous_slot']==45 and layout['next_slot']==53
print(f'PASS: {len(available)} available lore entries, {len(lore)-len(available)} unavailable excluded; all {len(lore)} Russian descriptions present; {len(pages)} pages; glyphs and text bounds resolve')
