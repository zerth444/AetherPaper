package com.aetherport.aether.dungeon;

import com.aetherport.aether.item.AetherItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Rolls the bundled dungeon chest loot tables (nested references, weights,
 * uniform counts), mirroring vanilla loot semantics for these tables.
 */
public final class ChestLoot {

    private final AetherItems items;
    private final Map<String, JsonObject> cache = new HashMap<>();

    public ChestLoot(AetherItems items) {
        this.items = items;
    }

    public List<ItemStack> roll(String table, Random rng) {
        List<ItemStack> out = new ArrayList<>();
        rollInto(table, rng, out, 0);
        return out;
    }

    private void rollInto(String table, Random rng, List<ItemStack> out, int depth) {
        if (depth > 4) {
            return;
        }
        JsonObject root = load(table);
        if (root == null || !root.has("pools")) {
            return;
        }
        for (JsonElement poolEl : root.getAsJsonArray("pools")) {
            JsonObject pool = poolEl.getAsJsonObject();
            int rolls = numRange(pool.get("rolls"), rng);
            for (int r = 0; r < rolls; r++) {
                JsonArray entries = pool.getAsJsonArray("entries");
                if (entries == null || entries.size() == 0) {
                    continue;
                }
                int total = 0;
                for (JsonElement e : entries) {
                    total += e.getAsJsonObject().has("weight")
                            ? e.getAsJsonObject().get("weight").getAsInt() : 1;
                }
                int pick = total > 0 ? rng.nextInt(total) : 0;
                JsonObject chosen = entries.get(0).getAsJsonObject();
                for (JsonElement e : entries) {
                    JsonObject o = e.getAsJsonObject();
                    pick -= o.has("weight") ? o.get("weight").getAsInt() : 1;
                    if (pick < 0) {
                        chosen = o;
                        break;
                    }
                }
                String type = chosen.has("type") ? chosen.get("type").getAsString() : "";
                if (type.equals("minecraft:loot_table")) {
                    rollInto(chosen.get("value").getAsString(), rng, out, depth + 1);
                } else if (type.equals("minecraft:item")) {
                    String name = chosen.get("name").getAsString();
                    int count = 1;
                    if (chosen.has("functions")) {
                        for (JsonElement f : chosen.getAsJsonArray("functions")) {
                            JsonObject fo = f.getAsJsonObject();
                            if (fo.get("function").getAsString().equals("minecraft:set_count")) {
                                count = numRange(fo.get("count"), rng);
                            }
                        }
                    }
                    ItemStack stack = build(name, count);
                    if (stack != null) {
                        out.add(stack);
                    }
                }
            }
        }
    }

    private ItemStack build(String name, int count) {
        if (name.startsWith("aether:")) {
            return items.create(name.substring(7), Math.max(1, count));
        }
        if (name.startsWith("minecraft:")) {
            Material mat = Material.matchMaterial(name.substring(10).toUpperCase());
            return mat == null ? null : new ItemStack(mat, Math.max(1, count));
        }
        return null;
    }

    private static int numRange(JsonElement el, Random rng) {
        if (el == null || el.isJsonNull()) {
            return 1;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsInt();
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            if (type.equals("minecraft:uniform")) {
                int min = o.has("min") ? (int) o.get("min").getAsDouble() : 1;
                int max = o.has("max") ? (int) o.get("max").getAsDouble() : 1;
                return min + (max > min ? rng.nextInt(max - min + 1) : 0);
            }
            if (type.equals("minecraft:constant")) {
                return (int) o.get("value").getAsDouble();
            }
            if (type.equals("minecraft:binomial")) {
                int n = o.has("n") ? o.get("n").getAsInt() : 1;
                float p = o.has("p") ? o.get("p").getAsFloat() : 1.0f;
                int c = 0;
                for (int i = 0; i < n; i++) {
                    if (rng.nextFloat() < p) {
                        c++;
                    }
                }
                return c;
            }
        }
        return 1;
    }

    private JsonObject load(String table) {
        String id = table.startsWith("aether:") ? table.substring(7) : table;
        if (cache.containsKey(id)) {
            return cache.get(id);
        }
        try (InputStream in = getClass().getResourceAsStream("/content/loot/" + id + ".json")) {
            if (in == null) {
                return null;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            cache.put(id, root);
            return root;
        } catch (Exception e) {
            return null;
        }
    }
}
