package com.aetherport.aether.world;

/**
 * A registered Aether portal rectangle. {@code x0,y0,z0} is the minimal interior corner,
 * {@code w} the interior width along the plane axis, {@code h} the interior height.
 */
public record PortalRect(String world, char axis, int x0, int y0, int z0, int w, int h) {

    public static String keyOf(String world, char axis, int x0, int y0, int z0) {
        return world + "|" + axis + "|" + x0 + "," + y0 + "," + z0;
    }

    public String key() {
        return keyOf(world, axis, x0, y0, z0);
    }

    public boolean contains(int x, int y, int z) {
        if (axis == 'x') {
            return z == z0 && x >= x0 && x < x0 + w && y >= y0 && y < y0 + h;
        }
        return x == x0 && z >= z0 && z < z0 + w && y >= y0 && y < y0 + h;
    }
}
