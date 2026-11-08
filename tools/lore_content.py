"""Book availability follows current world sources and supported crafting chains.
Original mod definitions alone do not make an item available in this port.
Dungeon/holiday/admin-only content is not a survival source.
"""
from check_survival_progression import natural,run,SEED

def available_entries(lore):
    available=set(run(SEED,include_silk=True)['reachable_custom'])
    available.update('aether:'+name for name in natural)
    # The harvested bush's rooted stem is a runtime state, not a biome feature.
    available.add('aether:berry_bush_stem')
    return {key:data for key,data in lore.items() if ':'.join(key.split('.')[-2:]) in available}
