package com.aetherport.aether.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads the generated content definitions (blocks.json / items.json) and exposes
 * fast lookups for the runtime.
 */
public final class Content {

    private final Map<String, BlockDef> blocks = new LinkedHashMap<>();
    private final Map<String, ItemDef> items = new LinkedHashMap<>();
    private final Map<String, String> carrierToBlock = new HashMap<>();

    public void load(Logger logger) {
        JsonObject bo = readJson("/content/blocks.json", logger);
        if (bo != null) {
            for (Map.Entry<String, JsonElement> e : bo.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                List<BlockDef.Drop> drops = new ArrayList<>();
                if (o.has("drops")) {
                    for (JsonElement d : o.getAsJsonArray("drops")) {
                        JsonObject dob = d.getAsJsonObject();
                        List<Double> fortune = new ArrayList<>();
                        if (dob.has("fortune") && dob.get("fortune").isJsonArray()) {
                            for (JsonElement f : dob.getAsJsonArray("fortune")) {
                                fortune.add(f.getAsDouble());
                            }
                        }
                        drops.add(new BlockDef.Drop(dob.get("item").getAsString(),
                                dob.has("vanilla") && dob.get("vanilla").getAsBoolean(),
                                dob.get("min").getAsInt(), dob.get("max").getAsInt(),
                                dob.has("chance") ? dob.get("chance").getAsDouble() : 1.0,
                                fortune,
                                dob.has("require") && !dob.get("require").isJsonNull()
                                        ? dob.get("require").getAsString() : null,
                                dob.has("exclude") && dob.get("exclude").getAsBoolean(), str(dob,"bonus")));
                    }
                }
                BlockDef def = new BlockDef(
                        e.getKey(),
                        str(o, "carrier"),
                        o.get("hardness").getAsDouble(),
                        o.get("resistance").getAsDouble(),
                        str(o, "sound"),
                        o.get("requires_tool").getAsBoolean(),
                        o.has("tool") && !o.get("tool").isJsonNull() ? o.get("tool").getAsString() : null,
                        o.has("required_tier") ? o.get("required_tier").getAsInt() : 0,
                        drops,
                        o.get("double_drops").getAsBoolean(),
                        o.has("model") && !o.get("model").isJsonNull() ? o.get("model").getAsString() : null,
                        str(o, "lang_en"),
                        str(o, "lang_ru"));
                blocks.put(def.id(), def);
                String carrier = def.carrier();
                if (carrier != null && (carrier.startsWith("minecraft:note_block")
                        || carrier.startsWith("minecraft:tripwire[") || carrier.startsWith("minecraft:frosted_ice[") || carrier.endsWith("_stained_glass") || carrier.startsWith("minecraft:snow["))) {
                    carrierToBlock.put(carrier, def.id());
                }
                String legacy = str(o, "legacy_carrier");
                if (legacy.startsWith("minecraft:note_block")) carrierToBlock.put(legacy, def.id());
            }
        }
        JsonObject io = readJson("/content/items.json", logger);
        if (io != null) {
            for (Map.Entry<String, JsonElement> e : io.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                items.put(e.getKey(), new ItemDef(
                        e.getKey(),
                        str(o, "kind"),
                        o.has("model") && !o.get("model").isJsonNull() ? o.get("model").getAsString() : null,
                        o.has("durability") && !o.get("durability").isJsonNull()
                                ? o.get("durability").getAsInt() : null,
                        o.has("max_stack") ? o.get("max_stack").getAsInt() : 64,
                        str(o, "lang_en"),
                        str(o, "lang_ru")));
            }
        }
        logger.info("Content loaded: " + blocks.size() + " blocks, " + items.size() + " items, "
                + carrierToBlock.size() + " carrier states.");
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static JsonObject readJson(String resource, Logger logger) {
        try (InputStream in = Content.class.getResourceAsStream(resource)) {
            if (in == null) {
                logger.warning("Missing content resource " + resource);
                return null;
            }
            JsonElement el = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return el.getAsJsonObject();
        } catch (Exception e) {
            logger.warning("Failed to read " + resource + ": " + e);
            return null;
        }
    }

    public Map<String, BlockDef> blocks() {
        return blocks;
    }

    public Map<String, ItemDef> items() {
        return items;
    }

    public BlockDef block(String id) {
        return blocks.get(id);
    }

    public ItemDef item(String id) {
        return items.get(id);
    }

    public String blockIdForCarrier(String carrierState) {
        if (carrierState.startsWith("minecraft:note_block[") && carrierState.contains("note=22,")) return null;
        if (carrierState.startsWith("minecraft:note_block[")) {
            if (carrierState.contains("note=24,")) return "aether_grass_block";
            if (carrierState.contains("note=23,")) return "enchanted_aether_grass_block";
        }
        // Wire connections change when neighbours update; they are not the
        // flower identity. Contact is cancelled before pressure can change it.
        if (carrierState.startsWith("minecraft:tripwire[") && carrierState.contains("disarmed=true")) {
            if(carrierState.contains("attached=false") && carrierState.contains("east=true") && carrierState.contains("north=true") && carrierState.contains("south=true") && carrierState.contains("west=true"))
                return carrierState.contains("powered=true")?"golden_oak_sapling":"skyroot_sapling";
            if (carrierState.contains("attached=true")) return carrierState.contains("powered=true") ? "berry_bush_stem" : "berry_bush";
            return carrierState.contains("powered=true") ? "purple_flower" : "white_flower";
        }
        return carrierToBlock.get(carrierState);
    }

    public boolean isKnown(String id) {
        return blocks.containsKey(id) || items.containsKey(id);
    }

    public boolean isBlock(String id) {
        return blocks.containsKey(id);
    }
}
