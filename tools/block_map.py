# -*- coding: utf-8 -*-
"""
Deterministic mapping of Aether block ids -> vanilla carrier block states.

Paper cannot register new BlockTypes, so every custom block is represented by a
vanilla block with a unique block-state. Resource-pack blockstate overrides make
the carrier render as the original Aether model. The plugin treats the carrier
state as the Aether block (place/break/drop/interact).

Carrier: minecraft:note_block  -> instrument(16) x note(0..24) x powered(2) = 800 states.
Reserved for special-render blocks: minecraft:barrier (invisible, plugin handles display).
"""

INSTRUMENTS = [
    "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar",
    "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit",
    "banjo", "pling",
]

# Explicit assignment for blocks that appear in worldgen / fluid / common use.
EXPLICIT = {
    "aether_grass_block": "harp",
    "enchanted_aether_grass_block": "harp",
    "aether_dirt": "basedrum",
    "aether_dirt_path": "basedrum",
    "quicksoil": "snare",
    "holystone": "hat",
    "mossy_holystone": "hat",
    "icestone": "bass",
    "cold_aercloud": "flute",
    "blue_aercloud": "flute",
    "golden_aercloud": "bell",
    "skyroot_log": "guitar",
    "golden_oak_log": "guitar",
    "skyroot_leaves": "chime",
    "golden_oak_leaves": "chime",
    "crystal_leaves": "chime",
    "holiday_leaves": "chime",
    "ambrosium_ore": "xylophone",
    "zanite_ore": "xylophone",
    "gravitite_ore": "xylophone",
    "aether_farmland": "basedrum",
}

# Blocks that must be an invisible passable carrier: portal uses `barrier` (which the
# resource pack overrides to render the portal plane); other special-render blocks use
# `structure_void` so they don't visually clash with the portal.
SPECIAL_RENDER = {
    "aether_portal", "skyroot_wall_sign", "skyroot_sign", "skyroot_wall_hanging_sign",
    "skyroot_hanging_sign", "frosted_ice", "unstable_obsidian", "chest_mimic",
    "skyroot_bed", "boss_doorway_carved_stone", "boss_doorway_sentry_stone",
    "boss_doorway_angelic_stone", "boss_doorway_light_angelic_stone",
    "boss_doorway_hellfire_stone", "boss_doorway_light_hellfire_stone",
    "treasure_chest", "sun_altar", "altar", "freezer", "incubator",
}
PORTAL_BLOCK = "aether_portal"


def build_map(block_ids, legacy=False):
    """Return {aether_id: 'minecraft:note_block[...]'} with unique states."""
    mapping = {}
    used = set()

    def take(instrument):
        for note in range(25):
            state = _state(instrument, note, False)
            if state not in used:
                used.add(state)
                return state
        return None

    # special render -> passable invisible carriers.
    # Only the portal uses a dedicated passable carrier (vanilla nether_portal,
    # a passable plane with axis property); the resource pack overrides its
    # blockstate to the original Aether models. Every other block gets a unique
    # note_block state so it stays placeable, clickable and breakable; finer
    # special rendering arrives via display entities in a later stage.
    for bid in sorted(block_ids):
        if bid == PORTAL_BLOCK:
            mapping[bid] = "minecraft:nether_portal"

    # explicit blocks first, grouped by preferred instrument
    for bid in sorted(block_ids):
        if bid in mapping:
            continue
        instr = EXPLICIT.get(bid)
        if instr:
            state = take(instr)
            if state is not None:
                mapping[bid] = state

    # remaining blocks round-robin over all instruments
    idx = 0
    for bid in sorted(block_ids):
        if bid in mapping:
            continue
        placed = None
        while placed is None and idx < len(INSTRUMENTS) * 25:
            instr = INSTRUMENTS[idx % len(INSTRUMENTS)]
            idx += 1
            placed = take(instr)
        if placed is not None:
            mapping[bid] = placed
    if not legacy:
        # Non-solid, non-occluding plant carriers. These disarmed wire states
        # were already reserved for Aether plants; four connections mark saplings.
        for bid,powered in (("skyroot_sapling","false"),("golden_oak_sapling","true")):
            if bid in mapping:
                mapping[bid]="minecraft:tripwire[attached=false,disarmed=true,east=true,north=true,powered="+powered+",south=true,west=true]"
        transparent={'cold_aercloud':'white','blue_aercloud':'light_blue','golden_aercloud':'yellow','skyroot_leaves':'green','golden_oak_leaves':'orange','crystal_leaves':'cyan','crystal_fruit_leaves':'blue','holiday_leaves':'red','decorated_holiday_leaves':'pink'}
        for bid,colour in transparent.items():
            if bid in mapping: mapping[bid]=f'minecraft:{colour}_stained_glass'
        for cloud,layers in [('cold_aercloud',6),('blue_aercloud',5),('golden_aercloud',7)]:
            if cloud in mapping: mapping[cloud]=f'minecraft:snow[layers={layers}]'
        if 'quicksoil' in mapping: mapping['quicksoil']='minecraft:frosted_ice[age=3]'
        # Reserve unused note values across instruments to prevent the client's
        # local instrument update from flashing another block after flower break.
        for bid, note in (("aether_grass_block",24),("enchanted_aether_grass_block",23)):
            if bid in mapping: mapping[bid] = _state('harp', note, False)
        for bid, powered in (("white_flower", "false"), ("purple_flower", "true")):
            if bid in mapping:
                mapping[bid] = ("minecraft:tripwire[attached=false,disarmed=true,east=false"
                                + ",north=false,powered=" + powered + ",south=false,west=false]")
        for bid,powered in (("berry_bush","false"),("berry_bush_stem","true")):
            if bid in mapping: mapping[bid]="minecraft:tripwire[attached=true,disarmed=true,east=false,north=false,powered="+powered+",south=false,west=false]"
    return mapping


def _state(instrument, note, powered):
    p = "true" if powered else "false"
    return f"minecraft:note_block[instrument={instrument},note={note},powered={p}]"
