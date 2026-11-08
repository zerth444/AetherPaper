"""Reachability from actually generated blocks/natural mobs; no dungeon-loot seed.
Reports optional imports separately rather than declaring all upstream content obtainable.
"""
import json,re,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from check_progression import SEED

ROOT=Path(__file__).resolve().parent.parent;CONTENT=ROOT/'plugin/src/main/resources/content'
load=lambda p:json.loads(p.read_text(encoding='utf8'))
blocks=load(CONTENT/'blocks.json');items=load(CONTENT/'items.json');recipes=load(CONTENT/'recipes.json');mobs=load(CONTENT/'mobs.json')
def state_key(value):
    name,_,props=value.partition('[')
    return name,tuple(sorted(tuple(p.split('=')) for p in props.rstrip(']').split(',') if '=' in p))
carriers={state_key(b[k]):id for id,b in blocks.items() for k in ('carrier','legacy_carrier') if b.get(k)}
natural=set()
def walk(value):
    if isinstance(value,dict):
        if 'Name' in value:
            key=(value['Name'],tuple(sorted(value.get('Properties',{}).items())))
            if key in carriers:natural.add(carriers[key])
        for child in value.values():walk(child)
    elif isinstance(value,list):
        for child in value:walk(child)
worldgen=ROOT/'build/datapack/data/aether/worldgen'
for path in (worldgen/'noise_settings').rglob('*.json'):walk(load(path))
# A defined configured feature is not necessarily enabled by a biome.
pending=[];visited=set()
for path in (worldgen/'biome').glob('*.json'):
    for step in load(path).get('features',[]):pending.extend(step)
while pending:
    ref=pending.pop()
    if not isinstance(ref,str) or not ref.startswith('aether:') or ref in visited:continue
    visited.add(ref)
    for kind in ('placed_feature','configured_feature'):
        path=worldgen/kind/(ref[7:]+'.json')
        if path.exists():
            value=load(path);walk(value)
            pending.extend(re.findall(r'aether:[a-z0-9_/]+',json.dumps(value)))
natural.update(e['ore'] for e in load(CONTENT/'ores.json')['ores'])
natural.update({'holystone','aether_grass_block','aether_dirt','quicksoil','skyroot_log','skyroot_leaves','golden_oak_log','golden_oak_leaves','crystal_leaves','crystal_fruit_leaves','cold_aercloud','blue_aercloud','berry_bush','white_flower','purple_flower'})
sections=('craft_shaped','craft_shapeless','smelting','blasting','stonecutting','smithing','enchanting','freezing')
def groups(section,r):
    if section=='craft_shaped':return list(r['key'].values())
    if section=='craft_shapeless':return r['ingredients']
    if section=='smithing':return [r['template'],r['base'],r['addition']]
    return [r['in']]
def run(imports,include_silk=False):
    have={'minecraft:'+id for id in imports}|{'minecraft:wooden_pickaxe','minecraft:shears','minecraft:crafting_table','minecraft:furnace'}
    acquired={};done=set()
    def add(ref,reason):
        if ref in have:return False
        have.add(ref);acquired[ref]=reason;return True
    def tier():
        levels={**{f'aether:{kind}_pickaxe':level for kind,level in [('skyroot',0),('holystone',1),('zanite',2),('gravitite',3)]},**{f'minecraft:{kind}_pickaxe':level for kind,level in [('wooden',0),('golden',0),('stone',1),('iron',2),('diamond',3),('netherite',4)]}}
        return max((level for ref,level in levels.items() if ref in have),default=-1)
    changed=True
    while changed:
        changed=False
        for id in natural:
            b=blocks[id]
            if b['hardness']<0 or b['requires_tool'] and tier()<b['required_tier']:continue
            for drop in b['drops']:
                if drop['require'] not in ((None,'shears','shears_or_silk','silk') if include_silk else (None,'shears','shears_or_silk')) or drop['max']<=0:continue
                chance=(drop['fortune'] or [drop['chance']])[0]
                if chance<=0:continue
                changed|=add(('minecraft:' if drop['vanilla'] else 'aether:')+drop['item'],'mine '+id)
        if 'berry_bush' in natural:changed|=add('aether:blue_berry','harvest berry bush')
        for id,mob in mobs.items():
            if not mob.get('spawns') or mob.get('boss') or id in {'slider','valkyrie','valkyrie_queen','sun_spirit','fire_minion','sentry','mimic'}:continue
            for pool in mob['drops']:
                for drop in pool['entries']:
                    if drop['max']>0:changed|=add(drop['item'],'natural mob '+id)
        if mobs.get('moa',{}).get('spawns'):
            for color in ('blue','white','black'):changed|=add(f'aether:{color}_moa_egg','natural moa eggs')
        for section in sections:
            for index,r in enumerate(recipes[section]):
                if section=='enchanting' and not {'aether:altar','aether:ambrosium_shard'}<=have:continue
                if section=='freezing' and not {'aether:freezer','aether:icestone'}<=have:continue
                if all(any(ref in have for ref in group) for group in groups(section,r)):
                    done.add((section,index));changed|=add(r['out'],section+' '+r.get('rkey',str(index)))
        for ref in tuple(have):
            if ref.startswith('aether:') and ref.endswith('_log') and any(t.endswith('_axe') for t in have):
                target='aether:stripped_'+ref[7:]
                if target[7:] in blocks:changed|=add(target,'strip log')
        if 'aether:skyroot_bucket' in have:
            for liquid in ('water','milk'):changed|=add(f'aether:skyroot_{liquid}_bucket','bucket interaction')
        if 'aether:aether_dirt' in have:
            if any(ref.endswith('_hoe') for ref in have):changed|=add('aether:aether_farmland','hoe dirt')
            if any(ref.endswith('_shovel') for ref in have):changed|=add('aether:aether_dirt_path','shovel dirt')
    blocked=[{'section':section,'key':r.get('rkey',r['out']),'missing_groups':[g for g in groups(section,r) if not any(ref in have for ref in g)]} for section in sections for n,r in enumerate(recipes[section]) if (section,n) not in done]
    return {'reachable_custom':sorted(ref for ref in have if ref.startswith('aether:')),'recipes_enabled':len(done),'blocked_recipes':blocked,'sources':acquired}
def audit():
    basic=run(set());imported=run(SEED)
    critical={f'aether:{tier}_{tool}' for tier in ('skyroot','holystone','zanite','gravitite') for tool in ('pickaxe','axe','shovel','hoe','sword')}|{'aether:altar','aether:freezer','aether:incubator','aether:ambrosium_shard','aether:zanite_gemstone','aether:gravitite_ore','aether:enchanted_gravitite'}
    missing=sorted(critical-set(basic['reachable_custom']))
    report={'natural_blocks':sorted(natural),'no_dungeon_loot_assumed':True,'minimal_imports':['wooden_pickaxe','shears','crafting_table','furnace'],'aether_progression':basic,'with_overworld_resources':imported,'missing_core':missing}
    out=ROOT/'build/survival-progression.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
    print(f'Natural blocks={len(natural)}; Aether-only custom={len(basic["reachable_custom"])}; with Overworld resources={len(imported["reachable_custom"])}; missing core={missing}')
    assert not missing,missing

if __name__=='__main__':audit()
