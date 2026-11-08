package com.aetherport.aether.worldgen;

import com.aetherport.aether.content.Content;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Generates ores and springs inside Aether chunks with exact carrier-block matching.
 * The datapack cannot express this (custom replaceable tags), so the densities,
 * sizes and heights come straight from the mod data (see tools/ore_populator.json).
 */
public final class AetherPopulator implements Listener {

    private record Vein(BlockData ore, Set<String> replace, int size, int count,
                        int minY, int maxY, boolean trapezoid) {
    }

    private final Plugin plugin;
    private final Content content;
    private final Logger logger;
    private final List<Vein> veins = new ArrayList<>();
    private final List<String> springValid = new ArrayList<>();
    private int springAttempts;

    public AetherPopulator(Plugin plugin, Content content) {
        this.plugin = plugin;
        this.content = content;
        this.logger = plugin.getLogger();
    }

    public void load() {
        try (InputStream in = getClass().getResourceAsStream("/content/ores.json")) {
            if (in == null) {
                logger.warning("Missing content resource /content/ores.json");
                return;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("ores");
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                String oreId = o.get("ore").getAsString();
                var def = content.block(oreId);
                if (def == null) {
                    continue;
                }
                BlockData ore;
                try {
                    ore = Bukkit.createBlockData(def.carrier());
                } catch (Exception e) {
                    continue;
                }
                Set<String> replace = new HashSet<>();
                for (JsonElement r : o.getAsJsonArray("replace")) {
                    var target = content.block(r.getAsString());
                    if (target != null) {
                        replace.add(target.carrier());
                    }
                }
                veins.add(new Vein(ore, replace, o.get("size").getAsInt(), o.get("count").getAsInt(),
                        o.get("minY").getAsInt(), o.get("maxY").getAsInt(),
                        o.get("trapezoid").getAsBoolean()));
            }
            JsonObject springs = root.getAsJsonObject("springs");
            springAttempts = springs.get("rock_count").getAsInt();
            for (JsonElement v : springs.getAsJsonArray("valid")) {
                var target = content.block(v.getAsString());
                if (target != null) {
                    springValid.add(target.carrier());
                }
            }
            logger.info("Ore populator loaded: " + veins.size() + " veins, "
                    + springAttempts + " spring tries.");
        } catch (Exception e) {
            logger.warning("Failed to load ores.json: " + e);
        }
    }

    public static String aetherKey(Plugin plugin) {
        return plugin.getConfig().getString("world.dimension", "aether:the_aether");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) {
            return;
        }
        World world = event.getWorld();
        if (!world.getKey().toString().equals(aetherKey(plugin))) {
            return;
        }
        populate(world, event.getChunk().getX(), event.getChunk().getZ());
        if (plugin instanceof com.aetherport.aether.AetherPlugin aether
                && aether.dungeons() != null) {
            int cx=event.getChunk().getX(), cz=event.getChunk().getZ();
            Bukkit.getScheduler().runTask(plugin,()->aether.dungeons().maybeGenerate(world,cx,cz));
        }
    }

    private void populate(World world, int chunkX, int chunkZ) {
        Random rng = new Random(world.getSeed() ^ (chunkX * 341873128712L + chunkZ * 132897987541L));
        int worldMin = world.getMinHeight();
        int worldMax = world.getMaxHeight() - 1;
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        for (Vein vein : veins) {
            int minY = Math.max(worldMin, vein.minY());
            int maxY = Math.min(worldMax, vein.maxY());
            if (minY > maxY) {
                continue;
            }
            for (int a = 0; a < vein.count(); a++) {
                int x = baseX + rng.nextInt(16);
                int z = baseZ + rng.nextInt(16);
                int y = vein.trapezoid()
                        ? minY + (int) ((maxY - minY) * (rng.nextDouble() + rng.nextDouble()) / 2.0)
                        : minY + rng.nextInt(maxY - minY + 1);
                int px = x;
                int py = y;
                int pz = z;
                for (int i = 0; i < vein.size(); i++) {
                    if (px < baseX || px >= baseX + 16 || pz < baseZ || pz >= baseZ + 16
                            || py < worldMin || py > worldMax) {
                        break;
                    }
                    Block block = world.getBlockAt(px, py, pz);
                    if (vein.replace().contains(block.getBlockData().getAsString())) {
                        block.setBlockData(vein.ore(), false);
                    }
                    px += rng.nextInt(3) - 1;
                    py += rng.nextInt(3) - 1;
                    pz += rng.nextInt(3) - 1;
                }
            }
        }
        for (int a = 0; a < springAttempts; a++) {
            int x = baseX + rng.nextInt(16);
            int z = baseZ + rng.nextInt(16);
            for (int y = Math.min(worldMax, 200); y > worldMin; y--) {
                Block block = world.getBlockAt(x, y, z);
                if (block.isEmpty()) {
                    Block below = world.getBlockAt(x, y - 1, z);
                    if (springValid.contains(below.getBlockData().getAsString())) {
                        block.setType(Material.WATER, true);
                        break;
                    }
                } else if (!block.getType().isAir() && !block.isLiquid()
                        && !block.getType().name().contains("LEAVES")
                        && !block.getType().name().contains("LOG")) {
                    break;
                }
            }
        }
        surfaceTrees(world,rng,baseX,baseZ);
        plantPatches(world, rng, baseX, baseZ);
    }

    private void surfaceTrees(World world, Random rng, int baseX, int baseZ) {
        String biome=world.getBiome(baseX+8,80,baseZ+8).getKey().getKey();
        int tries=biome.contains("forest") ? 6 : biome.contains("woodland") ? 4 : biome.contains("grove") ? 3 : 1;
        for (int attempt=0;attempt<tries;attempt++) {
            int x=baseX+3+rng.nextInt(10), z=baseZ+3+rng.nextInt(10), ground=-1;
            for (int y=Math.min(220,world.getHighestBlockYAt(x,z));y>world.getMinHeight();y--) {
                Block b=world.getBlockAt(x,y,z);
                String id=content.blockIdForCarrier(b.getBlockData().getAsString());
                if ("aether_grass_block".equals(id) || "enchanted_aether_grass_block".equals(id)) {ground=y; break;}
                if (!b.isPassable()) break;
            }
            if (ground<0) continue;
            boolean golden=rng.nextInt(12)==0;
            int height=golden ? 8+rng.nextInt(3) : 4+rng.nextInt(3);
            if (ground+height+3>=world.getMaxHeight()) continue;
            boolean clear=true;
            for (int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) for(int y=1;y<=height+2;y++)
                if (!world.getBlockAt(x+dx,ground+y,z+dz).isPassable()) clear=false;
            if (!clear) continue;
            BlockData log=Bukkit.createBlockData(content.block(golden?"golden_oak_log":"skyroot_log").carrier());
            BlockData leaves=Bukkit.createBlockData(content.block(golden?"golden_oak_leaves":"skyroot_leaves").carrier());
            for (int y=1;y<=height;y++) world.getBlockAt(x,ground+y,z).setBlockData(log,false);
            for (int dy=-2;dy<=1;dy++) for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
                if (Math.abs(dx)+Math.abs(dz)> (dy==1?2:3)) continue;
                Block b=world.getBlockAt(x+dx,ground+height+dy,z+dz);
                if (b.isPassable()) b.setBlockData(leaves,false);
            }
        }
    }

    /** Surface flowers, grasses and berry bushes with strict ground checks. */
    private void plantPatches(World world, Random rng, int baseX, int baseZ) {
        var grassDef = content.block("aether_grass_block");
        if (grassDef == null) {
            return;
        }
        String grass = grassDef.carrier();
        plantPatch(world, rng, baseX, baseZ, grass, Material.SHORT_GRASS, null, 3, 1);
        plantPatch(world, rng, baseX, baseZ, grass, Material.TALL_GRASS, null, 2, 1);
        if (rng.nextInt(2) == 0) {
            plantPatch(world, rng, baseX, baseZ, grass, null, "white_flower", 2, 1);
        }
        if (rng.nextInt(2) == 0) {
            plantPatch(world, rng, baseX, baseZ, grass, null, "purple_flower", 2, 1);
        }
        if (rng.nextInt(4) == 0) {
            plantPatch(world, rng, baseX, baseZ, grass, null, "berry_bush", 2, 1);
        }
    }

    private void plantPatch(World world, Random rng, int baseX, int baseZ, String grass,
                            Material vanilla, String carrierId, int tries, int unused) {
        BlockData carrier = null;
        if (carrierId != null) {
            var def = content.block(carrierId);
            if (def == null) {
                return;
            }
            try {
                carrier = Bukkit.createBlockData(def.carrier());
            } catch (Exception e) {
                return;
            }
        }
        for (int i = 0; i < tries; i++) {
            int x = baseX + rng.nextInt(16);
            int z = baseZ + rng.nextInt(16);
            int y = world.getHighestBlockYAt(x, z) + 1;
            if (y <= world.getMinHeight() + 1 || y > 200) {
                continue;
            }
            Block below = world.getBlockAt(x, y - 1, z);
            Block at = world.getBlockAt(x, y, z);
            if (!below.getBlockData().getAsString().equals(grass) || !at.getType().isAir()) {
                continue;
            }
            if (carrier != null) {
                at.setBlockData(carrier, false);
            } else if (vanilla != null) {
                if (vanilla == Material.TALL_GRASS) {
                    if (!at.getRelative(0, 1, 0).getType().isAir()) { continue; }
                    var lower = (org.bukkit.block.data.Bisected) Bukkit.createBlockData(Material.TALL_GRASS);
                    lower.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);
                    at.setBlockData(lower, false);
                    var upper = (org.bukkit.block.data.Bisected) lower.clone();
                    upper.setHalf(org.bukkit.block.data.Bisected.Half.TOP);
                    at.getRelative(0, 1, 0).setBlockData(upper, false);
                } else {
                    at.setType(vanilla, false);
                }
            }
        }
    }
}
