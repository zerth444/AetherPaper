package com.aetherport.aether.item;

import com.aetherport.aether.content.BlockDef;
import com.aetherport.aether.content.Content;
import com.aetherport.aether.content.ItemDef;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Builds Aether ItemStacks (item_model component + PDC identity). */
public final class AetherItems {

    private final Content content;
    private final NamespacedKey idKey;

    public AetherItems(Plugin plugin, Content content) {
        this.content = content;
        this.idKey = new NamespacedKey(plugin, "id");
    }

    public NamespacedKey idKey() {
        return idKey;
    }

    public String idOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    public ItemStack create(String id, int amount) {
        BlockDef bd = content.block(id);
        ItemDef it = content.item(id);
        if (bd == null && it == null) {
            return null;
        }
        Material material = baseMaterial(id, bd, it);
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        String name = bd != null ? bd.langEn() : it.langEn();
        if (name == null || name.isEmpty()) {
            name = prettify(id);
        }
        meta.displayName(Component.text(name));
        String model = bd != null ? bd.model() : it.model();
        if (model == null && bd != null) {
            model = "aether:block/" + id;
        }
        if (model != null) {
            try {
                meta.setItemModel(NamespacedKey.fromString(model));
            } catch (Throwable ignored) {
                // model component unsupported -> fall back to base material look
            }
        }
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
        if (bd == null && it != null) {
            try {
                meta.setMaxStackSize(it.durability()!=null ? 1 : it.maxStack());
            } catch (Throwable ignored) {
            }
            if (it.durability() != null && meta instanceof org.bukkit.inventory.meta.Damageable d) {
                try {
                    d.setMaxDamage(it.durability());
                } catch (Throwable ignored) {
                }
            }
        }
        if ("blue_berry".equals(id) || "enchanted_berry".equals(id)) {
            var food=meta.getFood();food.setNutrition("blue_berry".equals(id)?2:6);
            food.setSaturation("blue_berry".equals(id)?.6f:1.6f);
            food.setCanAlwaysEat(false);meta.setFood(food);
        }
        stack.setItemMeta(meta);
        if (bd==null && it!=null && it.durability()!=null)
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE,0);
        return stack;
    }

    /** Vanilla base material used to carry an Aether id (visuals come from the pack). */
    public Material baseMaterial(String id) {
        BlockDef bd = content.block(id);
        ItemDef it = content.item(id);
        if (bd == null && it == null) {
            return Material.PAPER;
        }
        return baseMaterial(id, bd, it);
    }

    private Material baseMaterial(String id, BlockDef bd, ItemDef it) {
        if ("blue_berry".equals(id) || "enchanted_berry".equals(id)) return Material.SWEET_BERRIES;
        if ("ambrosium_shard".equals(id)) return Material.COAL;
        if (bd != null) {
            return Material.PAPER;
        }
        return materialFor(id, it);
    }

    private static String tier(String id) {
        if (id.startsWith("skyroot")) {
            return "WOODEN";
        }
        if (id.startsWith("holystone")) {
            return "STONE";
        }
        if (id.startsWith("zanite")) {
            return "IRON";
        }
        if (id.startsWith("gravitite") || id.startsWith("valkyrie") || id.startsWith("phoenix")
                || id.startsWith("neptune") || id.startsWith("obsidian") || id.startsWith("sentry")) {
            return "NETHERITE";
        }
        return "IRON";
    }

    private static Material materialFor(String id, ItemDef it) {
        if (it == null) {
            return Material.PAPER;
        }
        String kind = it.kind();
        if (kind.equals("armor")) {
            String slot = id.endsWith("_helmet") ? "HELMET"
                    : id.endsWith("_chestplate") ? "CHESTPLATE"
                    : id.endsWith("_leggings") ? "LEGGINGS" : "BOOTS";
            return Material.matchMaterial(tier(id) + "_" + slot) != null
                    ? Material.matchMaterial(tier(id) + "_" + slot) : Material.IRON_BOOTS;
        }
        if (kind.equals("sword")) {
            return mat(tier(id) + "_SWORD", Material.IRON_SWORD);
        }
        if (kind.equals("pickaxe")) {
            return mat(toolTier(id) + "_PICKAXE", Material.IRON_PICKAXE);
        }
        if (kind.equals("axe")) {
            return mat(toolTier(id) + "_AXE", Material.IRON_AXE);
        }
        if (kind.equals("shovel")) {
            return mat(toolTier(id) + "_SHOVEL", Material.IRON_SHOVEL);
        }
        if (kind.equals("hoe")) {
            return mat(toolTier(id) + "_HOE", Material.IRON_HOE);
        }
        if (kind.equals("spawn_egg")) {
            return Material.PIG_SPAWN_EGG;
        }
        return Material.PAPER;
    }

    private static Material mat(String name, Material fallback) {
        Material m = Material.matchMaterial(name);
        return m != null ? m : fallback;
    }
    private static String toolTier(String id){return id.startsWith("gravitite_")||id.startsWith("valkyrie_")?"DIAMOND":tier(id);}

    private static String prettify(String id) {
        String[] parts = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
