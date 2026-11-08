package com.aetherport.aether.station;

import com.aetherport.aether.content.Content;
import com.aetherport.aether.item.AetherItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Registers the mod's vanilla-type recipes natively so workbenches, furnaces,
 * stonecutters and smithing tables accept Aether ingredients and produce Aether
 * results (custom items via ExactChoice + item_model components).
 */
public final class RecipeRegistrar {

    private final Content content;
    private final AetherItems items;
    private int registered;
    private int failed;

    public RecipeRegistrar(Content content, AetherItems items) {
        this.content = content;
        this.items = items;
    }

    public void registerAll(org.bukkit.plugin.Plugin plugin, Logger logger) {
        registered=0;failed=0;
        try (InputStream in = getClass().getResourceAsStream("/content/recipes.json")) {
            if (in == null) {
                logger.warning("Missing content resource /content/recipes.json");
                return;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            int i = 0;
            for (JsonElement e : root.getAsJsonArray("craft_shaped")) {
                registerShaped(plugin, logger, i++, e.getAsJsonObject());
            }
            i = 0;
            for (JsonElement e : root.getAsJsonArray("craft_shapeless")) {
                registerShapeless(plugin, logger, i++, e.getAsJsonObject());
            }
            i = 0;
            for (JsonElement e : root.getAsJsonArray("smelting")) {
                registerCooking(plugin, logger, "smelt", i++, e.getAsJsonObject(), true);
            }
            i = 0;
            for (JsonElement e : root.getAsJsonArray("blasting")) {
                registerCooking(plugin, logger, "blast", i++, e.getAsJsonObject(), false);
            }
            i = 0;
            for (JsonElement e : root.getAsJsonArray("stonecutting")) {
                registerStonecutting(plugin, logger, i++, e.getAsJsonObject());
            }
            i = 0;
            for (JsonElement e : root.getAsJsonArray("smithing")) {
                registerSmithing(plugin, logger, i++, e.getAsJsonObject());
            }
            logger.info("Native recipes registered: " + registered + " (failed: " + failed + ")");
        } catch (Exception e) {
            logger.warning("Failed to register recipes: " + e);
        }
    }

    private ItemStack result(JsonObject o) {
        String out = o.get("out").getAsString();
        int count = o.has("count") ? o.get("count").getAsInt() : 1;
        ItemStack stack;
        if (out.startsWith("aether:")) {
            stack = items.create(out.substring(7), count);
        } else {
            Material mat = Material.matchMaterial(out.substring(10).toUpperCase());
            stack = mat == null ? null : new ItemStack(mat, count);
        }
        if (stack != null && o.has("extra")) {
            applyExtra(stack, o.getAsJsonObject("extra"));
        }
        return stack;
    }

    private void applyExtra(ItemStack stack, JsonObject extra) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        if (extra.has("name")) {
            meta.displayName(Component.text(extra.get("name").getAsString(),
                    NamedTextColor.GOLD));
        }
        if (extra.has("banner") && meta instanceof BannerMeta banner) {
            for (JsonElement p : extra.getAsJsonArray("banner")) {
                JsonObject po = p.getAsJsonObject();
                try {
                    DyeColor color = DyeColor.valueOf(po.get("color").getAsString().toUpperCase());
                    PatternType type = PatternType.valueOf(
                            po.get("pattern").getAsString().toUpperCase());
                    banner.addPattern(new Pattern(color, type));
                } catch (Exception ignored) {
                }
            }
        }
        stack.setItemMeta(meta);
        // mark swet banners so spawners can recognise them
        if (extra.has("banner")) {
            stack.editPersistentDataContainer(c -> c.set(items.idKey(),
                    org.bukkit.persistence.PersistentDataType.STRING, "swet_banner"));
        }
    }

    private RecipeChoice choice(JsonArray arr) {
        List<ItemStack> exact = new ArrayList<>();
        List<Material> mats = new ArrayList<>();
        for (JsonElement e : arr) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                continue;
            }
            String ref = e.getAsString();
            if (ref.startsWith("aether:")) {
                ItemStack s = refId(ref) != null ? items.create(refId(ref), 1) : null;
                if (s != null) {
                    exact.add(s);
                }
            } else if (ref.startsWith("minecraft:") && ref.length() > 10) {
                Material mat = Material.matchMaterial(ref.substring(10).toUpperCase());
                if (mat != null) {
                    mats.add(mat);
                }
            }
        }
        if (!exact.isEmpty()) {
            for (Material m : mats) {
                exact.add(new ItemStack(m));
            }
            return new RecipeChoice.ExactChoice(exact);
        }
        if (!mats.isEmpty()) {
            return new RecipeChoice.MaterialChoice(mats);
        }
        return null;
    }

    private RecipeChoice choiceSingle(String ref) {
        if (ref.startsWith("aether:")) {
            ItemStack s = items.create(ref.substring(7), 1);
            return s == null ? null : new RecipeChoice.ExactChoice(s);
        }
        if (!ref.startsWith("minecraft:") || ref.length() <= 10) {
            return null;
        }
        Material mat = Material.matchMaterial(ref.substring(10).toUpperCase());
        return mat == null ? null : new RecipeChoice.MaterialChoice(mat);
    }

    private static String refId(String ref) {
        return ref.startsWith("aether:") ? ref.substring(7) : null;
    }

    private void registerShaped(org.bukkit.plugin.Plugin plugin, Logger logger, int i, JsonObject o) {
        try {
            if(existing(plugin,o)){registered++;return;}
            ItemStack out = result(o);
            if (out == null) {
                failed++;
                if (loggedFails < 20) {
                    loggedFails++;
                    logger.warning("Recipe null-result [" + keyOf(o) + "]");
                }
                return;
            }
            ShapedRecipe recipe = new ShapedRecipe(
                    new NamespacedKey(plugin, keyOf(o)), out);
            JsonArray pattern = o.getAsJsonArray("pattern");
            String[] rows = new String[pattern.size()];
            for (int r = 0; r < rows.length; r++) {
                rows[r] = pattern.get(r).getAsString();
            }
            recipe.shape(rows);
            JsonObject key = o.getAsJsonObject("key");
            for (Map.Entry<String, JsonElement> e : key.entrySet()) {
                RecipeChoice c = choice(e.getValue().getAsJsonArray());
                if (c == null) {
                    failed++;
                    if (loggedFails < 20) {
                        loggedFails++;
                        logger.warning("Recipe null-choice [" + keyOf(o) + "]");
                    }
                    return;
                }
                recipe.setIngredient(e.getKey().charAt(0), c);
            }
            if (Bukkit.addRecipe(recipe)) {
                registered++;
            } else {
                failed++;
                if (loggedFails < 12) {
                    loggedFails++;
                    try {
                        logger.warning("Recipe rejected [" + keyOf(o) + "]");
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Exception e) {
            fail(logger, o, e);
        }
    }

    private void registerShapeless(org.bukkit.plugin.Plugin plugin, Logger logger, int i, JsonObject o) {
        try {
            if(existing(plugin,o)){registered++;return;}
            ItemStack out = result(o);
            if (out == null) {
                failed++;
                if (loggedFails < 20) {
                    loggedFails++;
                    logger.warning("Recipe null-result [" + keyOf(o) + "]");
                }
                return;
            }
            ShapelessRecipe recipe = new ShapelessRecipe(
                    new NamespacedKey(plugin, keyOf(o)), out);
            for (JsonElement ing : o.getAsJsonArray("ingredients")) {
                RecipeChoice c = choiceSingleList(ing);
                if (c == null) {
                    failed++;
                    return;
                }
                recipe.addIngredient(c);
            }
            if (Bukkit.addRecipe(recipe)) {
                registered++;
            } else {
                failed++;
                if (loggedFails < 12) {
                    loggedFails++;
                    try {
                        logger.warning("Recipe rejected [" + keyOf(o) + "]");
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Exception e) {
            fail(logger, o, e);
        }
    }

    private RecipeChoice choiceSingleList(JsonElement ing) {
        if (ing.isJsonArray()) {
            return choice(ing.getAsJsonArray());
        }
        return choiceSingle(ing.getAsString());
    }

    private void registerCooking(org.bukkit.plugin.Plugin plugin, Logger logger, String kind, int i,
                                 JsonObject o, boolean furnace) {
        try {
            if(existing(plugin,o)){registered++;return;}
            ItemStack out = result(o);
            JsonArray in = o.getAsJsonArray("in");
            RecipeChoice c = choice(in);
            if (c == null) {
                failed++;
                if (loggedFails < 20) {
                    loggedFails++;
                    logger.warning("Recipe null-choice [" + keyOf(o) + "]");
                }
                return;
            }
            float xp = o.has("xp") ? o.get("xp").getAsFloat() : 0.1f;
            int time = o.has("time") ? o.get("time").getAsInt() : 200;
            if (out == null || c == null) {
                failed++;
                return;
            }
            boolean ok;
            if (furnace) {
                ok = Bukkit.addRecipe(new FurnaceRecipe(
                        new NamespacedKey(plugin, keyOf(o)), out, c, xp, time));
            } else {
                ok = Bukkit.addRecipe(new BlastingRecipe(
                        new NamespacedKey(plugin, keyOf(o)), out, c, xp, time));
            }
            if (ok) {
                registered++;
            } else {
                failed++;
                if (loggedFails < 12) {
                    loggedFails++;
                    try {
                        logger.warning("Recipe rejected [" + keyOf(o) + "]");
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Exception e) {
            fail(logger, o, e);
        }
    }

    private void registerStonecutting(org.bukkit.plugin.Plugin plugin, Logger logger, int i, JsonObject o) {
        try {
            if(existing(plugin,o)){registered++;return;}
            ItemStack out = result(o);
            RecipeChoice c = choice(o.getAsJsonArray("in"));
            if (out == null || c == null) {
                failed++;
                return;
            }
            if (Bukkit.addRecipe(new StonecuttingRecipe(
                    new NamespacedKey(plugin, keyOf(o)), out, c))) {
                registered++;
            } else {
                failed++;
                if (loggedFails < 12) {
                    loggedFails++;
                    try {
                        logger.warning("Recipe rejected [" + keyOf(o) + "]");
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Exception e) {
            fail(logger, o, e);
        }
    }

    private void registerSmithing(org.bukkit.plugin.Plugin plugin, Logger logger, int i, JsonObject o) {
        try {
            if(existing(plugin,o)){registered++;return;}
            ItemStack out = result(o);
            RecipeChoice template = choice(o.getAsJsonArray("template"));
            RecipeChoice base = choice(o.getAsJsonArray("base"));
            RecipeChoice addition = choice(o.getAsJsonArray("addition"));
            if (out == null || template == null || base == null || addition == null) {
                failed++;
                return;
            }
            if (Bukkit.addRecipe(new SmithingTransformRecipe(
                    new NamespacedKey(plugin, keyOf(o)), out,
                    template, base, addition))) {
                registered++;
            } else {
                failed++;
                if (loggedFails < 12) {
                    loggedFails++;
                    try {
                        logger.warning("Recipe rejected [" + keyOf(o) + "]");
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Exception e) {
            fail(logger, o, e);
        }
    }

    private int loggedFails;

    private void fail(Logger logger, JsonObject o, Exception e) {
        failed++;
        if (loggedFails < 6) {
            loggedFails++;
            String key = "?";
            try {
                key = keyOf(o);
            } catch (Throwable ignored) {
            }
            logger.warning("Recipe failed [" + key + "]: " + e);
        }
    }

    private boolean existing(org.bukkit.plugin.Plugin plugin,JsonObject o){return Bukkit.getRecipe(new NamespacedKey(plugin,keyOf(o)))!=null;}
    private static String keyOf(JsonObject o) {
        String key = o.has("rkey") ? o.get("rkey").getAsString() : null;
        if (key == null || key.isEmpty()) {
            key = "r" + System.nanoTime();
        }
        return key.replaceAll("[^a-z0-9_./-]", "_");
    }
}
