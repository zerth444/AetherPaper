package com.aetherport.aether.world;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.Optional;

/**
 * Nether-style portal shape detection adapted to the Aether: the frame is
 * {@code glowstone} and the interior may be air, water or an existing portal plane.
 * Ported from the original {@code AetherPortalShape} (which itself mirrors the
 * vanilla {@code PortalShape} algorithm).
 */
public final class PortalShape {

    public static final int MIN_WIDTH = 2;
    public static final int MAX_WIDTH = 21;
    public static final int MIN_HEIGHT = 3;
    public static final int MAX_HEIGHT = 23;

    private PortalShape() {
    }

    public static boolean isReplaceable(Material m) {
        return m.isAir() || m == Material.WATER || m == Material.NETHER_PORTAL;
    }

    public static boolean isFrame(Material m) {
        return m == Material.GLOWSTONE;
    }

    public static Optional<PortalRect> find(World world, int x, int y, int z) {
        Optional<PortalRect> alongX = findAxis(world, x, y, z, 'x');
        if (alongX.isPresent()) {
            return alongX;
        }
        return findAxis(world, x, y, z, 'z');
    }

    private static Optional<PortalRect> findAxis(World world, int x, int y, int z, char axis) {
        int minY = world.getMinHeight();
        int by = y;
        while (by > minY && isReplaceable(world.getBlockAt(x, by - 1, z).getType())) {
            by--;
        }
        int neg = expand(world, x, by, z, axis, -1);
        int pos = expand(world, x, by, z, axis, 1);
        int width = neg + pos + 1;
        if (width < MIN_WIDTH || width > MAX_WIDTH) {
            return Optional.empty();
        }
        int x0 = axis == 'x' ? x - neg : x;
        int z0 = axis == 'z' ? z - neg : z;
        if (!frameAt(world, x0, by, z0, z0, width, axis, true, 0)
                || !frameAt(world, x0, by, z0, z0, width, axis, false, 0)) {
            return Optional.empty();
        }
        int h = 0;
        while (h < MAX_HEIGHT && rowReplaceable(world, x0, by + h, z0, width, axis)) {
            h++;
        }
        if (h < MIN_HEIGHT || h > MAX_HEIGHT) {
            return Optional.empty();
        }
        for (int level = 0; level < h; level++) {
            if (!frameAt(world, x0, by + level, z0, z0, width, axis, true, 0)
                    || !frameAt(world, x0, by + level, z0, z0, width, axis, false, 0)) {
                return Optional.empty();
            }
        }
        for (int i = -1; i <= width; i++) {
            if (!isFrame(topCell(world, x0, by + h, z0, i, axis))) {
                return Optional.empty();
            }
        }
        return Optional.of(new PortalRect(world.getKey().toString(), axis, x0, by, z0, width, h));
    }

    private static int expand(World world, int x, int y, int z, char axis, int dir) {
        int n = 0;
        while (n < MAX_WIDTH) {
            int nx = axis == 'x' ? x + dir * (n + 1) : x;
            int nz = axis == 'z' ? z + dir * (n + 1) : z;
            if (!isReplaceable(world.getBlockAt(nx, y, nz).getType())) {
                break;
            }
            if (!isFrame(world.getBlockAt(nx, y - 1, nz).getType())) {
                break;
            }
            n++;
        }
        return n;
    }

    private static boolean rowReplaceable(World world, int x0, int y, int z0, int width, char axis) {
        for (int i = 0; i < width; i++) {
            int cx = axis == 'x' ? x0 + i : x0;
            int cz = axis == 'z' ? z0 + i : z0;
            if (!isReplaceable(world.getBlockAt(cx, y, cz).getType())) {
                return false;
            }
        }
        return true;
    }

    private static boolean frameAt(World world, int x0, int y, int z0, int unused, int width, char axis,
                                   boolean left, int level) {
        int cx = axis == 'x' ? (left ? x0 - 1 : x0 + width) : x0;
        int cz = axis == 'z' ? (left ? z0 - 1 : z0 + width) : z0;
        return isFrame(world.getBlockAt(cx, y, cz).getType());
    }

    private static Material topCell(World world, int x0, int y, int z0, int i, char axis) {
        int cx = axis == 'x' ? x0 + i : x0;
        int cz = axis == 'z' ? z0 + i : z0;
        return world.getBlockAt(cx, y, cz).getType();
    }

    /** Full-frame validation of an existing rectangle (used when the frame breaks). */
    public static boolean validate(World world, PortalRect r) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        if (r.y0() < minY || r.y0() + r.h() > maxY || r.w() < MIN_WIDTH || r.w() > MAX_WIDTH
                || r.h() < MIN_HEIGHT || r.h() > MAX_HEIGHT) {
            return false;
        }
        for (int level = -1; level <= r.h(); level++) {
            for (int i = -1; i <= r.w(); i++) {
                int cx = r.axis() == 'x' ? r.x0() + i : r.x0();
                int cz = r.axis() == 'z' ? r.z0() + i : r.z0();
                Material m = world.getBlockAt(cx, r.y0() + level, cz).getType();
                boolean edge = level == -1 || level == r.h() || i == -1 || i == r.w();
                // bottom corners are optional, like the original frame
                if (level == -1 && (i == -1 || i == r.w())) {
                    continue;
                }
                if (edge) {
                    if (!isFrame(m)) {
                        return false;
                    }
                } else if (!isReplaceable(m)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void clearVolume(World world, PortalRect r) {
        for (int level = -1; level <= r.h(); level++) {
            for (int i = 0; i < r.w(); i++) {
                int cx = r.axis() == 'x' ? r.x0() + i : r.x0();
                int cz = r.axis() == 'z' ? r.z0() + i : r.z0();
                world.getBlockAt(cx, r.y0() + level, cz).setType(Material.AIR, false);
            }
        }
    }

    /** Builds a glowstone ring and fills the interior with the given type. */
    public static boolean buildRect(World world, int x0, int y0, int z0, char axis, int w, int h,
                                    Material interior) {
        BlockData portalData = null;
        if (interior == Material.NETHER_PORTAL) {
            try {
                portalData = org.bukkit.Bukkit.createBlockData(
                        "minecraft:nether_portal[axis=" + axis + "]");
            } catch (Exception e) {
                return false;
            }
        }
        for (int level = -1; level <= h; level++) {
            for (int i = -1; i <= w; i++) {
                int cx = axis == 'x' ? x0 + i : x0;
                int cz = axis == 'z' ? z0 + i : z0;
                Block block = world.getBlockAt(cx, y0 + level, cz);
                boolean edge = level == -1 || level == h || i == -1 || i == w;
                if (edge) {
                    if (isReplaceable(block.getType()) || block.getType() == Material.GLOWSTONE
                            || level==-1 && block.getType().isSolid() && block.getType()!=Material.BEDROCK
                            && !(block.getState() instanceof org.bukkit.block.TileState)) {
                        block.setType(Material.GLOWSTONE, false);
                    } else {
                        return false;
                    }
                } else if (portalData != null) {
                    block.setBlockData(portalData, false);
                } else if (isReplaceable(block.getType())) {
                    block.setType(interior, false);
                } else {
                    return false;
                }
            }
        }
        return true;
    }
}
