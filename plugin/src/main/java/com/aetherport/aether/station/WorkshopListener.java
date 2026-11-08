package com.aetherport.aether.station;

import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.content.Content;
import com.aetherport.aether.item.AetherItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Placement conversions and bans of the cold Aether dimension, ambrosium
 * enchanting, anvil repairs and swet-banner tracking.
 */
public final class WorkshopListener implements Listener {

    private record Conversion(String inBlock, Map<String, String> inProps,
                              String outBlock, Map<String, String> outProps) {
    }

    private final AetherPlugin plugin;
    private final Content content;
    private final AetherItems items;
    private final List<Conversion> conversions = new ArrayList<>();
    private final Set<Material> bannedBlocks = new HashSet<>();
    private final Set<Material> bannedItems = new HashSet<>();
    private final Set<String> bannerSpots = new HashSet<>();

    public WorkshopListener(AetherPlugin plugin, Content content, AetherItems items) {
        this.plugin = plugin;
        this.content = content;
        this.items = items;
    }

    public void load() {
        try (InputStream in = getClass().getResourceAsStream("/content/recipes.json")) {
            if (in == null) {
                return;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("placement")) {
                JsonObject o = e.getAsJsonObject();
                conversions.add(new Conversion(o.get("in").getAsString(),
                        props(o, "in_props"), o.get("out").getAsString(), props(o, "out_props")));
            }
            for (JsonElement e : root.getAsJsonArray("bans_block")) {
                Material m = Material.matchMaterial(
                        e.getAsJsonObject().get("block").getAsString().substring(10).toUpperCase());
                if (m != null) {
                    bannedBlocks.add(m);
                }
            }
            for (JsonElement e : root.getAsJsonArray("bans_item")) {
                Material m = Material.matchMaterial(
                        e.getAsJsonObject().get("item").getAsString().substring(10).toUpperCase());
                if (m != null) {
                    bannedItems.add(m);
                }
            }
            loadBanners();
            plugin.getLogger().info("Workshop loaded: conversions=" + conversions.size()
                    + " bans=" + (bannedBlocks.size() + bannedItems.size()));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load workshop data: " + e);
        }
    }

    private static Map<String, String> props(JsonObject o, String key) {
        Map<String, String> out = new HashMap<>();
        if (o.has(key) && o.get(key).isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return out;
    }

    private boolean inAether(Block block) {
        return block.getWorld().getKey().toString().equals(plugin.aetherDimensionKey());
    }

    // ------------------------------------------------------------- conversions

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceConvert(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        if (!inAether(placed)) {
            return;
        }
        if (bannedBlocks.contains(placed.getType()) && !bypassBelow(placed)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "Здесь слишком холодно для этого.", NamedTextColor.AQUA));
            return;
        }
        applyConversion(placed);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketConvert(PlayerBucketEmptyEvent event) {
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        if (!inAether(target)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (target.getType() == Material.LAVA) {
                var def = content.block("aerogel");
                if (def != null) {
                    try {
                        target.setBlockData(Bukkit.createBlockData(def.carrier()), false);
                    } catch (Exception ignored) {
                    }
                }
            } else {
                applyConversion(target);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUseBanned(PlayerInteractEvent event) {
        if ((event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack held = event.getItem();
        if (held == null || !bannedItems.contains(held.getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.getWorld().getKey().toString().equals(plugin.aetherDimensionKey())) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(Component.text("Здесь слишком холодно для этого.",
                NamedTextColor.AQUA));
    }

    private boolean bypassBelow(Block block) {
        Material below = block.getRelative(0, -1, 0).getType();
        if (below == Material.SOUL_SAND || below == Material.SOUL_SOIL
                || below == Material.MAGMA_BLOCK) {
            return true;
        }
        String id = content.blockIdForCarrier(below.createBlockData().getAsString());
        return id != null && (id.equals("hellfire_stone") || id.equals("light_hellfire_stone"));
    }

    private void applyConversion(Block block) {
        String data = block.getBlockData().getAsString();
        String name = data.contains("[") ? data.substring(0, data.indexOf('[')) : data;
        for (Conversion c : conversions) {
            if (!c.inBlock().equals(name)) {
                continue;
            }
            boolean propsOk = true;
            for (Map.Entry<String, String> p : c.inProps().entrySet()) {
                if (!data.contains(p.getKey() + "=" + p.getValue())) {
                    propsOk = false;
                    break;
                }
            }
            if (!propsOk) {
                continue;
            }
            var def = content.block(c.outBlock().startsWith("aether:")
                    ? c.outBlock().substring(7) : null);
            try {
                if (def != null) {
                    block.setBlockData(Bukkit.createBlockData(def.carrier()), false);
                } else {
                    Material mat = Material.matchMaterial(
                            c.outBlock().substring(10).toUpperCase());
                    if (mat == null) {
                        return;
                    }
                    if (c.outProps().isEmpty()) {
                        block.setType(mat, false);
                    } else {
                        StringBuilder sb = new StringBuilder("minecraft:")
                                .append(mat.name().toLowerCase()).append("[");
                        boolean first = true;
                        for (Map.Entry<String, String> p : c.outProps().entrySet()) {
                            if (!first) {
                                sb.append(",");
                            }
                            sb.append(p.getKey()).append("=").append(p.getValue());
                            first = false;
                        }
                        block.setBlockData(Bukkit.createBlockData(sb.append("]").toString()), false);
                    }
                }
            } catch (Exception ignored) {
            }
            return;
        }
    }

    // ------------------------------------------------------- ambrosium enchanting

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAmbrosium(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack held = event.getItem();
        if (!"ambrosium_shard".equals(items.idOf(held))) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.NOTE_BLOCK) {
            return;
        }
        if (!"aether_grass_block".equals(content.blockIdForCarrier(
                clicked.getBlockData().getAsString()))) {
            return;
        }
        var def = content.block("enchanted_aether_grass_block");
        if (def == null) {
            return;
        }
        try {
            clicked.setBlockData(Bukkit.createBlockData(def.carrier()), false);
        } catch (Exception e) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.getGameMode().name().equals("CREATIVE")) {
            held.setAmount(held.getAmount() - 1);
        }
        player.getWorld().playSound(clicked.getLocation(),
                "aether:item.ambrosium_shard.use", 1.0f, 1.0f);
        event.setCancelled(true);
    }

    // ------------------------------------------------------------------- anvil

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);
        String toolId = items.idOf(left);
        if (toolId == null) {
            return;
        }
        String tier = plugin.stations().recipes().repairTier(toolId);
        List<String> mats = tier == null ? null
                : plugin.stations().recipes().anvilRepair().get(tier);
        if (mats == null) {
            return;
        }
        boolean ok = false;
        for (String ref : mats) {
            if (plugin.stations().recipes().matches(ref, right)) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            return;
        }
        ItemStack result = left.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta instanceof Damageable d) {
            d.setDamage(0); // full repair for one material (simplification, documented)
            result.setItemMeta(meta);
        }
        event.setResult(result);
        inv.setRepairCost(2);
    }

    // ------------------------------------------------------------- swet banners

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBannerPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        if (placed.getType() != Material.BLACK_BANNER) {
            return;
        }
        var state = placed.getState(false);
        if (state instanceof org.bukkit.block.Banner banner) {
            if (banner.getPatterns().size() >= 6) { // our patterned banner
                bannerSpots.add(spotKey(placed.getLocation()));
                saveBanners();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBannerBreak(BlockBreakEvent event) {
        if (bannerSpots.remove(spotKey(event.getBlock().getLocation()))) {
            saveBanners();
        }
    }

    private static String spotKey(Location loc) {
        return loc.getWorld().getKey() + "|" + loc.getBlockX() + "," + loc.getBlockY()
                + "," + loc.getBlockZ();
    }

    public boolean isSwetRepelled(Location loc) {
        for (String spot : bannerSpots) {
            int bar = spot.indexOf('|');
            if (bar < 0) {
                continue;
            }
            String world = spot.substring(0, bar);
            if (!world.equals(loc.getWorld().getKey().toString())) {
                continue;
            }
            String[] xyz = spot.substring(bar + 1).split(",");
            double dx = Integer.parseInt(xyz[0]) - loc.getX();
            double dy = Integer.parseInt(xyz[1]) - loc.getY();
            double dz = Integer.parseInt(xyz[2]) - loc.getZ();
            if (dx * dx + dy * dy + dz * dz < 16 * 16) {
                return true;
            }
        }
        return false;
    }

    private void loadBanners() {
        try {
            File f = new File(plugin.getDataFolder(), "banners.json");
            if (!f.isFile()) {
                return;
            }
            String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            for (com.google.gson.JsonElement e
                    : com.google.gson.JsonParser.parseString(text).getAsJsonArray()) {
                bannerSpots.add(e.getAsString());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load banners: " + e);
        }
    }

    private void saveBanners() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (String s : bannerSpots) {
                    arr.add(s);
                }
                File f = new File(plugin.getDataFolder(), "banners.json");
                Files.createDirectories(f.toPath().getParent());
                Files.writeString(f.toPath(), arr.toString(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save banners: " + e);
            }
        });
    }

    // ------------------------------------------------------- headless self-test

    /** Counts parsed data and validates banner-pattern enums. */
    public int workshopSelfTest() {
        int fails = 0;
        if (conversions.isEmpty() || bannedBlocks.isEmpty() || bannedItems.isEmpty()) {
            plugin.getLogger().info("  workshop self-test data");
            fails++;
        }
        try {
            org.bukkit.DyeColor.valueOf("CYAN");
            org.bukkit.block.banner.PatternType.valueOf("STRIPE_DOWNLEFT");
        } catch (Exception e) {
            plugin.getLogger().info("  workshop self-test patterns");
            fails++;
        }
        plugin.getLogger().info("Workshop self-test: " + (fails == 0 ? "PASS" : "FAIL-" + fails));
        return fails;
    }
}
