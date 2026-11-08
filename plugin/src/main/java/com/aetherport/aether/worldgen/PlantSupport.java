package com.aetherport.aether.worldgen;

import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.content.Content;
import com.aetherport.aether.item.AetherItems;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

public final class PlantSupport implements Listener {
    private static final Set<String> PLANTS = Set.of("berry_bush", "berry_bush_stem",
            "white_flower", "purple_flower", "skyroot_sapling", "golden_oak_sapling");
    private static final Set<String> SOIL = Set.of("aether_grass_block",
            "enchanted_aether_grass_block", "aether_dirt");
    private final AetherPlugin plugin;
    private final Content content;
    private final AetherItems items;
    private final NamespacedKey repaired;
    private final ArrayDeque<Scan> scans = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();

    private static final class Scan {
        final Chunk chunk;
        final String key;
        int column;
        Scan(Chunk chunk, String key) { this.chunk = chunk; this.key = key; }
    }

    public PlantSupport(AetherPlugin plugin, Content content, AetherItems items) {
        this.plugin = plugin;
        this.content = content;
        this.items = items;
        repaired = new NamespacedKey(plugin, "plant_support_v14");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getWorlds().forEach(world -> {
            for (Chunk chunk : world.getLoadedChunks()) { enqueue(chunk); }
        }));
    }

    public static boolean isPlant(String id) { return PLANTS.contains(id); }

    private String id(Block block) {
        String sapling=plugin.saplings().idAt(block);if(sapling!=null)return sapling;
        return (block.getType() == Material.NOTE_BLOCK || block.getType() == Material.TRIPWIRE || block.getType()==Material.FROSTED_ICE || block.getType().name().endsWith("STAINED_GLASS"))
                ? content.blockIdForCarrier(block.getBlockData().getAsString()) : null;
    }

    public boolean supported(Block block) {
        Block below = block.getRelative(0, -1, 0);
        String id = id(below);
        if (id != null) { return SOIL.contains(id); }
        return switch (below.getType()) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, PODZOL, ROOTED_DIRT, MYCELIUM, FARMLAND,
                    MOSS_BLOCK, PALE_MOSS_BLOCK, MUD, MUDDY_MANGROVE_ROOTS -> true;
            default -> false;
        };
    }

    private void check(Block block, boolean drop) {
        String id = id(block);
        if("golden_aercloud".equals(id)) {block.setBlockData(Bukkit.createBlockData(content.block("cold_aercloud").carrier()),false);return;}
        if(id!=null && id.startsWith("boss_doorway_") && plugin.dungeons()!=null && plugin.dungeons().at(block.getLocation())!=null) {
            block.setType(Material.STRUCTURE_VOID,false);return;
        }
        if ("aether_grass_block".equals(id) || "enchanted_aether_grass_block".equals(id) || "quicksoil".equals(id)) {
            String carrier = content.block(id).carrier();
            if (!block.getBlockData().getAsString().equals(carrier)) block.setBlockData(Bukkit.createBlockData(carrier), false);
            return;
        }
        if (id!=null && (id.endsWith("_leaves") || id.endsWith("_aercloud"))) {
            block.setBlockData(Bukkit.createBlockData(content.block(id).carrier()),false);return;
        }
        if (id == null || !isPlant(id)) { return; }
        if (supported(block)) {
            String carrier = content.block(id).carrier();
            if (carrier.startsWith("minecraft:tripwire[") && !block.getBlockData().getAsString().equals(carrier)) {
                block.setBlockData(Bukkit.createBlockData(carrier), false);
            }
            if(id.endsWith("_sapling"))plugin.saplings().track(block);
            return;
        }
        block.setType(Material.AIR, false);
        if (drop) {
            var item = items.create(id, 1);
            if (item != null) { block.getWorld().dropItemNaturally(block.getLocation(), item); }
        }
    }

    private void enqueue(Chunk chunk) {
        if (!chunk.getWorld().getKey().toString().equals(plugin.aetherDimensionKey())
                || chunk.getPersistentDataContainer().has(repaired, PersistentDataType.BYTE)) { return; }
        String key = chunk.getWorld().getUID() + ":" + chunk.getChunkKey();
        if (queued.add(key)) { scans.add(new Scan(chunk, key)); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent event) { enqueue(event.getChunk()); }

    private void tick() {
        int budget = 32;
        while (budget > 0 && !scans.isEmpty()) {
            Scan scan = scans.peek();
            if (!scan.chunk.isLoaded()) {
                queued.remove(scan.key); scans.remove(); continue;
            }
            int column = scan.column++;
            int x = column & 15;
            int z = column >> 4;
            for (int y = scan.chunk.getWorld().getMinHeight() + 1;
                    y < scan.chunk.getWorld().getMaxHeight(); y++) {
                check(scan.chunk.getBlock(x, y, z), false);
            }
            budget--;
            if (scan.column == 256) {
                scan.chunk.getPersistentDataContainer().set(repaired, PersistentDataType.BYTE, (byte) 1);
                queued.remove(scan.key); scans.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        String id = id(event.getBlock());
        if (id != null && isPlant(id) && !supported(event.getBlock())) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        Block above = event.getBlock().getRelative(0, 1, 0);
        String id = id(above);
        if (id!=null && (id.endsWith("_leaves") || id.endsWith("_aercloud"))) {
            above.setBlockData(Bukkit.createBlockData(content.block(id).carrier()),false);return;
        }
        if (id == null || !isPlant(id)) { return; }
        Bukkit.getScheduler().runTask(plugin, () -> check(above, true));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.NOTE_BLOCK && block.getType() != Material.TRIPWIRE) { return; }
        String id = id(block);
        if (id != null && isPlant(id)) {
            Bukkit.getScheduler().runTask(plugin, () -> check(block, true));
        }
    }
}
