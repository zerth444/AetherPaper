package com.aetherport.aether.station;

import com.aetherport.aether.content.Content;
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
import java.util.logging.Logger;

/**
 * Runtime recipe data parsed from the mod (see tools/gen_recipes.py).
 * Station recipes, conversions, bans and repair materials.
 */
public final class Recipes {

    public record Cook(String out, int count, int time, List<String> in) {
    }

    public record Repair(List<String> in, int time) {
    }

    public record Hatch(List<String> egg, int time, String moa) {
    }

    private final Content content;
    private final AetherItems items;
    private final List<Cook> enchanting = new ArrayList<>();
    private final List<Cook> freezing = new ArrayList<>();
    private final List<Repair> repairing = new ArrayList<>();
    private final List<Hatch> incubation = new ArrayList<>();
    private final Map<String, int[]> fuels = new HashMap<>();
    private final Map<String, List<String>> anvilRepair = new HashMap<>();

    /** Fuel burn times in ticks, from data/aether/data_maps (verified). */
    public static final Map<String, Integer> ALTAR_FUEL = Map.of(
            "ambrosium_shard", 250, "ambrosium_block", 2500);
    public static final Map<String, Integer> FREEZER_FUEL = Map.of(
            "icestone", 400, "icestone_slab", 200, "icestone_stairs", 400,
            "icestone_wall", 400);
    public static final Map<String, Integer> INCUBATOR_FUEL = Map.of(
            "ambrosium_torch", 500);

    public Recipes(Content content, AetherItems items) {
        this.content = content;
        this.items = items;
    }

    public void load(Logger logger) {
        try (InputStream in = getClass().getResourceAsStream("/content/recipes.json")) {
            if (in == null) {
                logger.warning("Missing content resource /content/recipes.json");
                return;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("enchanting")) {
                JsonObject o = e.getAsJsonObject();
                enchanting.add(new Cook(str(o, "out"), o.get("count").getAsInt(),
                        o.get("time").getAsInt(), strings(o.getAsJsonArray("in"))));
            }
            for (JsonElement e : root.getAsJsonArray("freezing")) {
                JsonObject o = e.getAsJsonObject();
                freezing.add(new Cook(str(o, "out"), o.get("count").getAsInt(),
                        o.get("time").getAsInt(), strings(o.getAsJsonArray("in"))));
            }
            for (JsonElement e : root.getAsJsonArray("repairing")) {
                JsonObject o = e.getAsJsonObject();
                repairing.add(new Repair(strings(o.getAsJsonArray("in")), o.get("time").getAsInt()));
            }
            for (JsonElement e : root.getAsJsonArray("incubation")) {
                JsonObject o = e.getAsJsonObject();
                incubation.add(new Hatch(strings(o.getAsJsonArray("egg")),
                        o.get("time").getAsInt(), str(o, "moa")));
            }
            JsonObject anvil = root.getAsJsonObject("anvil_repair");
            if (anvil != null) {
                for (Map.Entry<String, JsonElement> e : anvil.entrySet()) {
                    anvilRepair.put(e.getKey(), strings(e.getValue().getAsJsonArray()));
                }
            }
            logger.info("Recipes loaded: enchanting=" + enchanting.size()
                    + " freezing=" + freezing.size() + " repairing=" + repairing.size()
                    + " incubation=" + incubation.size());
        } catch (Exception e) {
            logger.warning("Failed to load recipes.json: " + e);
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static List<String> strings(JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (JsonElement e : arr) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    private static String first(JsonArray arr) {
        return arr != null && arr.size() > 0 ? arr.get(0).getAsString() : "";
    }

    /** Matches an ItemStack against an "aether:id" or "minecraft:name" reference. */
    public boolean matches(String ref, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (ref.startsWith("aether:")) {
            return ref.substring(7).equals(items.idOf(stack));
        }
        Material mat = Material.matchMaterial(ref.substring(10).toUpperCase());
        return mat != null && stack.getType() == mat && items.idOf(stack) == null;
    }

    public Cook findCook(List<Cook> list, ItemStack input) {
        for (Cook c : list) {
            for (String ref : c.in()) {
                if (matches(ref, input)) {
                    return c;
                }
            }
        }
        return null;
    }

    public List<Cook> enchanting() {
        return enchanting;
    }

    public List<Cook> freezing() {
        return freezing;
    }

    public List<Repair> repairing() {
        return repairing;
    }

    public List<Hatch> incubation() {
        return incubation;
    }

    public Map<String, List<String>> anvilRepair() {
        return anvilRepair;
    }

    /** Tier tag for anvil repairs, derived from the item id prefix. */
    public String repairTier(String toolId) {
        for (String tier : anvilRepair.keySet()) {
            if (toolId.equals(tier) || toolId.startsWith(tier + "_")) {
                return tier;
            }
        }
        return null;
    }
}
