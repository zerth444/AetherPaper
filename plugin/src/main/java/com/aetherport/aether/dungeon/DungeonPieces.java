package com.aetherport.aether.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * NBT dungeon templates converted to JSON at build time (see tools/gen_dungeon_pieces.py).
 * Places translated blocks and reports markers (chests, doors).
 */
public final class DungeonPieces {

    public record Marker(int x, int y, int z, String kind, boolean locked, String dungeonKind) {
    }

    public record Place(String dungeon, String piece, int ox, int oy, int oz,
                        List<Marker> markers) {
    }

    private final Map<String, Map<String, JsonObject>> dungeons = new HashMap<>();

    public void load(Logger logger) {
        for (String dungeon : new String[]{"bronze_dungeon", "silver_dungeon", "gold_dungeon"}) {
            try (InputStream in = getClass().getResourceAsStream(
                    "/content/dungeons/" + dungeon + ".json")) {
                if (in == null) {
                    logger.warning("Missing dungeon pieces for " + dungeon);
                    continue;
                }
                JsonObject root = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, JsonObject> pieces = new HashMap<>();
                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    pieces.put(e.getKey(), e.getValue().getAsJsonObject());
                }
                dungeons.put(dungeon, pieces);
            } catch (Exception e) {
                logger.warning("Failed to load dungeon " + dungeon + ": " + e);
            }
        }
        logger.info("Dungeon pieces loaded: " + dungeons.keySet());
    }

    public JsonObject piece(String dungeon, String name) {
        Map<String, JsonObject> pieces = dungeons.get(dungeon);
        return pieces == null ? null : pieces.get(name);
    }

    public int[] sizeOf(String dungeon, String name) {
        JsonObject piece = piece(dungeon, name);
        if (piece == null) {
            return new int[]{0, 0, 0};
        }
        JsonArray size = piece.getAsJsonArray("size");
        return new int[]{size.get(0).getAsInt(), size.get(1).getAsInt(), size.get(2).getAsInt()};
    }

    /**
     * Places a piece with optional 90-degree rotations. Returns absolute markers.
     * Air is carved; vanilla blocks pass through; unknown states are skipped.
     */
    public Place place(World world, String dungeon, String piece, int ox, int oy, int oz,
                       int rotation) {
        JsonObject data = piece(dungeon, piece);
        List<Marker> markers = new ArrayList<>();
        if (data == null) {
            return new Place(dungeon, piece, ox, oy, oz, markers);
        }
        JsonArray size = data.getAsJsonArray("size");
        int sx = size.get(0).getAsInt();
        int sz = size.get(2).getAsInt();
        for (JsonElement e : data.getAsJsonArray("blocks")) {
            JsonObject b = e.getAsJsonObject();
            int x = b.get("x").getAsInt();
            int y = b.get("y").getAsInt();
            int z = b.get("z").getAsInt();
            int[] r = rotate(x, z, sx, sz, rotation);
            String block = b.get("block").getAsString();
            try {
                BlockData bd = Bukkit.createBlockData(block);
                bd.rotate(switch(Math.floorMod(rotation,4)) {
                    case 1 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_90;
                    case 2 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_180;
                    case 3 -> org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90;
                    default -> org.bukkit.block.structure.StructureRotation.NONE;
                });
                world.getBlockAt(ox+r[0],oy+y,oz+r[1]).setBlockData(bd,false);
            } catch (Exception error) {
                throw new IllegalStateException("Cannot place "+dungeon+"/"+piece+" block "+block+" at "+x+","+y+","+z,error);
            }
        }
        if (data.has("markers")) {
            for (JsonElement e : data.getAsJsonArray("markers")) {
                JsonObject m = e.getAsJsonObject();
                int[] r = rotate(m.get("x").getAsInt(), m.get("z").getAsInt(), sx, sz, rotation);
                markers.add(new Marker(ox + r[0], oy + m.get("y").getAsInt(), oz + r[1],
                        m.get("kind").getAsString(),
                        m.has("locked") && m.get("locked").getAsBoolean(),
                        m.has("dungeon_kind") ? m.get("dungeon_kind").getAsString() : ""));
            }
        }
        return new Place(dungeon, piece, ox, oy, oz, markers);
    }

    /** Rotate (x,z) inside an sx*sz footprint by 0/90/180/270 degrees. */
    static int[] rotate(int x, int z, int sx, int sz, int rotation) {
        return switch (((rotation % 4) + 4) % 4) {
            case 1 -> new int[]{sz - 1 - z, x};
            case 2 -> new int[]{sx - 1 - x, sz - 1 - z};
            case 3 -> new int[]{z, sx - 1 - x};
            default -> new int[]{x, z};
        };
    }

    public static Location markerLoc(World world, Marker marker) {
        return new Location(world, marker.x() + 0.5, marker.y(), marker.z() + 0.5);
    }
}
