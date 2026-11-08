package com.aetherport.aether.worldgen;

import com.aetherport.aether.content.Content;
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

import java.util.Random;
import java.util.logging.Logger;

/**
 * Aether sky features that cannot live in the datapack (custom feature types),
 * ported 1:1 from the original feature classes: AercloudFeature, ShelfFeature,
 * AetherLakeFeature (simplified crater), CrystalIslandFeature.
 */
public final class CloudPopulator implements Listener {

    private final Plugin plugin;
    private final Content content;
    private final Logger logger;

    public CloudPopulator(Plugin plugin, Content content) {
        this.plugin = plugin;
        this.content = content;
        this.logger = plugin.getLogger();
    }

    private BlockData carrier(String id) {
        var def = content.block(id);
        if (def == null) {
            return null;
        }
        try {
            return Bukkit.createBlockData(def.carrier());
        } catch (Exception e) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) {
            return;
        }
        World world = event.getWorld();
        if (!world.getKey().toString().equals(
                plugin.getConfig().getString("world.dimension", "aether:the_aether"))) {
            return;
        }
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        Random rng = new Random(world.getSeed() ^ (cx * 341873128712L + cz * 132897987541L) ^ 0x5EEDL);
        int baseX = cx * 16;
        int baseZ = cz * 16;
        // cold aerclouds: y 32-96, 1/7
        if (rng.nextInt(28) == 0) {
            aercloud(world, rng, baseX + rng.nextInt(16), 32 + rng.nextInt(65),
                    baseZ + rng.nextInt(16), "cold_aercloud");
        }
        // blue aerclouds: y 32-96, 1/24
        if (rng.nextInt(96) == 0) {
            aercloud(world, rng, baseX + rng.nextInt(16), 32 + rng.nextInt(65),
                    baseZ + rng.nextInt(16), "blue_aercloud");
        }
        // Golden clouds disabled at the user's request; old generated ones migrate.
        // crystal islands: y 32-96, 1/50
        if (rng.nextInt(300) == 0) {
            crystalIsland(world, rng, baseX + rng.nextInt(16), 32 + rng.nextInt(65),
                    baseZ + rng.nextInt(16));
        }
        // lakes: 1/15 on the surface
        if (rng.nextInt(15) == 0) {
            lake(world, rng, baseX + rng.nextInt(16), baseZ + rng.nextInt(16));
        }
        // presents under holiday trees: 1/50 chunks, like the tree decorator
        if (rng.nextInt(50) == 0) {
            presents(world, rng, baseX, baseZ);
        }
        // quicksoil shelves: 1/5 on the surface
        if (rng.nextInt(5) == 0) {
            shelf(world, rng, baseX, baseZ);
        }
    }

    /** Direct port of AercloudFeature.place (bounds 16, manhattan blobs, air only). */
    private void aercloud(World world, Random rng, int x, int y, int z, String id) {
        BlockData state = carrier(id);
        if (state == null) {
            return;
        }
        // Large, shallow cloud banks. Validate the whole bank and a three-block
        // air margin first, so it cannot be clipped by an island or sit on grass.
        int rx=10+rng.nextInt(8), rz=7+rng.nextInt(7);
        int thickness=2+rng.nextInt(2);
        for(int dx=-rx-3;dx<=rx+3;dx++)for(int dz=-rz-3;dz<=rz+3;dz++)
            for(int dy=-3;dy<=thickness+3;dy++)
                if(!world.getBlockAt(x+dx,y+dy,z+dz).getType().isAir())return;
        int lobes=4+rng.nextInt(5);
        for(int n=0;n<lobes;n++) {
            int ox=rng.nextInt(rx+1)-rx/2,oz=rng.nextInt(rz+1)-rz/2;
            int ax=3+rng.nextInt(6),az=3+rng.nextInt(5),h=1+rng.nextInt(3);
            for(int dx=-ax;dx<=ax;dx++)for(int dz=-az;dz<=az;dz++) {
                if(dx*dx/(double)(ax*ax)+dz*dz/(double)(az*az)>1)continue;
                for(int dy=0;dy<h;dy++) {
                    Block at=world.getBlockAt(x+ox+dx,y+dy,z+oz+dz);
                    if(at.getType().isAir())at.setBlockData(state,false);
                }
            }
        }
    }

    /** Direct port of ShelfFeature: quicksoil disks under island edges. */
    private void shelf(World world, Random rng, int baseX, int baseZ) {
        BlockData quicksoil = carrier("quicksoil");
        String grass = content.block("aether_grass_block") == null ? null
                : content.block("aether_grass_block").carrier();
        if (quicksoil == null || grass == null) {
            return;
        }
        for (int x = baseX; x < baseX + 16; x++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                for (int y = 0; y < 48; y++) {
                    Block at = world.getBlockAt(x, y, z);
                    if (!at.getType().isAir()) {
                        continue;
                    }
                    Block above = world.getBlockAt(x, y + 1, z);
                    Block above2 = world.getBlockAt(x, y + 2, z);
                    if (!above2.getType().isAir()
                            || !above.getBlockData().getAsString().equals(grass)) {
                        continue;
                    }
                    disk(world, x, y, z, 3.46, quicksoil, true);
                }
            }
        }
    }

    private void disk(World world, int cx, int cy, int cz, double radius, BlockData state,
                      boolean airOnly) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                Block block = world.getBlockAt(cx + dx, cy, cz + dz);
                if (!airOnly || block.getType().isAir()) {
                    block.setBlockData(state, false);
                }
            }
        }
    }

    /** Simplified lake: shallow water bowl with a grass rim. */
    private void lake(World world, Random rng, int x, int z) {
        int ground = world.getHighestBlockYAt(x, z);
        if (ground <= world.getMinHeight() + 3 || ground > 200) {
            return;
        }
        BlockData grass = carrier("aether_grass_block");
        BlockData dirt = carrier("aether_dirt");
        if (grass == null || dirt == null) {
            return;
        }
        if (!world.getBlockAt(x, ground, z).getBlockData().matches(grass)) {
            return;
        }
        int r = 3 + rng.nextInt(3);
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dz = -r - 1; dz <= r + 1; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                Block top = world.getBlockAt(x + dx, ground, z + dz);
                if (d <= r && top.getBlockData().matches(grass)
                        && top.getRelative(0, 1, 0).getType().isAir()
                        && !top.getRelative(0, -1, 0).getType().isAir()) {
                    top.getRelative(0, -1, 0).setBlockData(dirt, false);
                    top.setType(Material.WATER, true);
                }
            }
        }
    }

    /** Presents appear under holiday trees, mirroring the tree decorator. */
    private void presents(World world, Random rng, int baseX, int baseZ) {
        var leavesDef = content.block("holiday_leaves");
        var presentDef = content.block("present");
        if (leavesDef == null || presentDef == null) {
            return;
        }
        String leaves = leavesDef.carrier();
        BlockData present;
        try {
            present = Bukkit.createBlockData(presentDef.carrier());
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < 6; i++) {
            int x = baseX + rng.nextInt(16);
            int z = baseZ + rng.nextInt(16);
            int top = world.getHighestBlockYAt(x, z);
            for (int y = Math.max(world.getMinHeight(), top - 30); y <= top; y++) {
                if (world.getBlockAt(x, y, z).getBlockData().getAsString().equals(leaves)) {
                    int px = x + rng.nextInt(5) - 2;
                    int pz = z + rng.nextInt(5) - 2;
                    int py = world.getHighestBlockYAt(px, pz) + 1;
                    if (world.getBlockAt(px, py, pz).getType().isAir()
                            && py < world.getMaxHeight() - 1) {
                        world.getBlockAt(px, py, pz).setBlockData(present, false);
                    }
                    return;
                }
            }
        }
    }

    /** Crystal island: crystal tree plus a small holystone/dirt island around it. */
    private void crystalIsland(World world, Random rng, int x, int y, int z) {
        BlockData trunk = carrier("skyroot_log");
        BlockData leaves = carrier("crystal_leaves");
        BlockData fruit = carrier("crystal_fruit_leaves");
        BlockData holystone = carrier("holystone");
        BlockData dirt = carrier("aether_dirt");
        if (trunk == null || leaves == null || holystone == null || dirt == null) {
            return;
        }
        int h = 7;
        for (int i = 0; i < h; i++) {
            world.getBlockAt(x, y + i, z).setBlockData(trunk, false);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 4) {
                        continue;
                    }
                    Block block = world.getBlockAt(x + dx, y + h - 2 + dy, z + dz);
                    if (block.getType().isAir()) {
                        // weighted foliage 4:1 like the original tree
                        block.setBlockData(
                                fruit != null && rng.nextInt(5) == 0 ? fruit : leaves, false);
                    }
                }
            }
        }
        for (int i = 1; i <= 3; i++) {
            disk(world, x, y - i, z, 4.5 - i, i == 1 ? dirt : holystone, false);
        }
    }
}
