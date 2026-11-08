package com.aetherport.aether.world;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks all lit Aether portals so they can survive restarts, be protected against
 * vanilla frame validation and used as teleport targets.
 */
public final class PortalManager {

    private final Map<String, PortalRect> rects = new LinkedHashMap<>();

    public synchronized void register(PortalRect rect) {
        rects.put(rect.key(), rect);
    }

    public synchronized void remove(PortalRect rect) {
        rects.remove(rect.key());
    }

    public synchronized PortalRect at(String worldKey, int x, int y, int z) {
        for (PortalRect r : rects.values()) {
            if (r.world().equals(worldKey) && r.contains(x, y, z)) {
                return r;
            }
        }
        return null;
    }

    public synchronized List<PortalRect> inWorld(String worldKey) {
        List<PortalRect> out = new ArrayList<>();
        for (PortalRect r : rects.values()) {
            if (r.world().equals(worldKey)) {
                out.add(r);
            }
        }
        return out;
    }

    public synchronized int count() {
        return rects.size();
    }

    /** Drops entries whose portal plane no longer exists (edited or reset worlds). */
    public synchronized int prune(Plugin plugin) {
        int removed = 0;
        for (PortalRect r : new ArrayList<>(rects.values())) {
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(r.world());
            org.bukkit.World world = key == null ? null : org.bukkit.Bukkit.getWorld(key);
            if (world == null) {
                continue;
            }
            int cx = r.axis() == 'x' ? r.x0() + r.w() / 2 : r.x0();
            int cz = r.axis() == 'z' ? r.z0() + r.w() / 2 : r.z0();
            org.bukkit.block.Block center =
                    world.getBlockAt(cx, r.y0() + r.h() / 2, cz);
            if (center.getType() != org.bukkit.Material.NETHER_PORTAL) {
                rects.remove(r.key());
                removed++;
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Pruned " + removed + " stale Aether portals.");
            saveAsync(plugin);
        }
        return removed;
    }

    public synchronized PortalRect nearest(String worldKey, double x, double y, double z, double maxDist) {
        PortalRect best = null;
        double bestD = maxDist * maxDist;
        for (PortalRect r : rects.values()) {
            if (!r.world().equals(worldKey)) {
                continue;
            }
            double cx = r.axis() == 'x' ? r.x0() + r.w() / 2.0 : r.x0() + 0.5;
            double cz = r.axis() == 'z' ? r.z0() + r.w() / 2.0 : r.z0() + 0.5;
            double cy = r.y0() + r.h() / 2.0;
            double d = (cx - x) * (cx - x) + (cy - y) * (cy - y) + (cz - z) * (cz - z);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    public synchronized void load(Plugin plugin) {
        File file = file(plugin);
        if (!file.isFile()) {
            return;
        }
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JsonArray arr = new Gson().fromJson(text, JsonArray.class);
            if (arr == null) {
                throw new IllegalStateException("empty portals file");
            }
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                PortalRect rect = new PortalRect(
                        o.get("world").getAsString(),
                        o.get("axis").getAsString().charAt(0),
                        o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                        o.get("w").getAsInt(), o.get("h").getAsInt());
                rects.put(rect.key(), rect);
            }
            plugin.getLogger().info("Loaded " + rects.size() + " Aether portals.");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load portals.json: " + e);
        }
    }

    public synchronized void saveAsync(Plugin plugin) {
        List<PortalRect> snapshot = new ArrayList<>(rects.values());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            JsonArray arr = new JsonArray();
            for (PortalRect r : snapshot) {
                JsonObject o = new JsonObject();
                o.addProperty("world", r.world());
                o.addProperty("axis", String.valueOf(r.axis()));
                o.addProperty("x", r.x0());
                o.addProperty("y", r.y0());
                o.addProperty("z", r.z0());
                o.addProperty("w", r.w());
                o.addProperty("h", r.h());
                arr.add(o);
            }
            try {
                File file = file(plugin);
                Files.createDirectories(file.toPath().getParent());
                File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                Files.writeString(tmp.toPath(), new Gson().toJson(arr), StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save portals.json: " + e);
            }
        });
    }

    private static File file(Plugin plugin) {
        return new File(plugin.getDataFolder(), "portals.json");
    }
}
