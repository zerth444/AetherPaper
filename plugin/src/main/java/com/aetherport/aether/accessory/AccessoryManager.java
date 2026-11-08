package com.aetherport.aether.accessory;

import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.content.Content;
import com.aetherport.aether.item.AetherItems;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Curios-like accessory slots (pendant/cape/shield/ring/ring/gloves/charm/charm)
 * with the original per-accessory behaviors.
 */
public final class AccessoryManager implements Listener {

    private static final String GUI_TITLE = "Аксессуары";
    private static final int GUI_SIZE = 9;

    private static final Map<String, Double> GLOVE_DAMAGE = Map.of(
            "leather_gloves", 0.25, "chainmail_gloves", 0.35, "iron_gloves", 0.5,
            "golden_gloves", 0.25, "diamond_gloves", 0.75, "netherite_gloves", 1.0,
            "zanite_gloves", 0.5);

    private static final Set<org.bukkit.entity.EntityType> DEFLECTABLE = EnumSet.of(
            org.bukkit.entity.EntityType.ARROW, org.bukkit.entity.EntityType.SPECTRAL_ARROW,
            org.bukkit.entity.EntityType.TRIDENT, org.bukkit.entity.EntityType.SNOWBALL,
            org.bukkit.entity.EntityType.EGG, org.bukkit.entity.EntityType.FIREBALL,
            org.bukkit.entity.EntityType.SMALL_FIREBALL,
            org.bukkit.entity.EntityType.SHULKER_BULLET,
            org.bukkit.entity.EntityType.LLAMA_SPIT);

    private final AetherPlugin plugin;
    private final AetherItems items;
    private final Map<UUID, Set<String>> worn = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> regenTick = new ConcurrentHashMap<>();
    private final Map<UUID, Long> shieldCd = new ConcurrentHashMap<>();

    public AccessoryManager(AetherPlugin plugin, AetherItems items) {
        this.plugin = plugin;
        this.items = items;
    }

    // ------------------------------------------------------------------ slots

    /** Slot index -> accepted item-id predicate. */
    public static boolean fitsSlot(int slot, String id) {
        if (id == null) {
            return false;
        }
        return switch (slot) {
            case 0 -> id.endsWith("_pendant");
            case 1 -> id.endsWith("_cape") || id.equals("invisibility_cloak");
            case 2 -> id.equals("shield_of_repulsion");
            case 3, 4 -> id.endsWith("_ring");
            case 5 -> id.endsWith("_gloves");
            case 6, 7 -> id.equals("golden_feather") || id.equals("iron_bubble")
                    || id.equals("regeneration_stone");
            default -> false;
        };
    }

    public static String slotName(int slot) {
        return switch (slot) {
            case 0 -> "Подвеска";
            case 1 -> "Плащ";
            case 2 -> "Щит";
            case 3, 4 -> "Кольцо";
            case 5 -> "Перчатки";
            case 6, 7 -> "Амулет";
            default -> "?";
        };
    }

    private static class Gui implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public void openGui(Player player) {
        Inventory gui = Bukkit.createInventory(new Gui(), GUI_SIZE, Component.text(GUI_TITLE));
        Map<Integer, String> saved = loadItems(player.getUniqueId());
        for (Map.Entry<Integer, String> e : saved.entrySet()) {
            if (e.getKey() >= 0 && e.getKey() < 8) {
                ItemStack stack = decode(e.getValue());
                if (stack != null) {
                    gui.setItem(e.getKey(), stack);
                }
            }
        }
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(slotName(8), NamedTextColor.GRAY));
            filler.setItemMeta(meta);
        }
        gui.setItem(8, filler);
        refreshWorn(player, gui);
        player.openInventory(gui);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Gui)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == 8) {
            event.setCancelled(true);
            return;
        }
        if (slot < 0 || slot >= 8) {
            return; // player inventory side: allow
        }
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            String id = items.idOf(cursor);
            if (!fitsSlot(slot, id)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player p) {
                    p.sendMessage(Component.text("Сюда подходит: " + slotName(slot),
                            NamedTextColor.RED));
                }
            }
        }
    }

    @EventHandler
    public void onGuiClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Gui)) {
            return;
        }
        if (event.getPlayer() instanceof Player player) {
            saveGui(player, event.getInventory());
            refreshWorn(player, event.getInventory());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refreshWorn(event.getPlayer(), null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        worn.remove(event.getPlayer().getUniqueId());
    }

    private void refreshWorn(Player player, Inventory gui) {
        Set<String> ids = ConcurrentHashMap.newKeySet();
        if (gui != null) {
            for (int i = 0; i < 8; i++) {
                String id = items.idOf(gui.getItem(i));
                if (id != null) {
                    ids.add(id);
                }
            }
        } else {
            for (String encoded : loadItems(player.getUniqueId()).values()) {
                ItemStack stack = decode(encoded);
                String id = items.idOf(stack);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            worn.remove(player.getUniqueId());
        } else {
            worn.put(player.getUniqueId(), ids);
        }
    }

    public boolean has(Player player, String id) {
        Set<String> set = worn.get(player.getUniqueId());
        return set != null && set.contains(id);
    }

    public boolean hasAny(Player player, String... ids) {
        Set<String> set = worn.get(player.getUniqueId());
        if (set == null) {
            return false;
        }
        for (String id : ids) {
            if (set.contains(id)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasGloves(Player player) {
        Set<String> set = worn.get(player.getUniqueId());
        if (set == null) {
            return false;
        }
        return set.stream().anyMatch(s -> s.endsWith("_gloves"));
    }

    public double gloveDamage(Player player) {
        Set<String> set = worn.get(player.getUniqueId());
        if (set == null) {
            return 0.0;
        }
        double best = 0.0;
        for (String id : set) {
            best = Math.max(best, GLOVE_DAMAGE.getOrDefault(id, 0.0));
        }
        return best;
    }

    // -------------------------------------------------------------- persistence

    private File file(UUID uuid) {
        return new File(plugin.getDataFolder(), "accessories" + File.separator + uuid + ".json");
    }

    private Map<Integer, String> loadItems(UUID uuid) {
        Map<Integer, String> out = new HashMap<>();
        File f = file(uuid);
        if (!f.isFile()) {
            return out;
        }
        try {
            String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
                out.put(Integer.parseInt(e.getKey()), e.getValue().getAsString());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load accessories for " + uuid + ": " + e);
        }
        return out;
    }

    private void saveGui(Player player, Inventory gui) {
        JsonObject root = new JsonObject();
        for (int i = 0; i < 8; i++) {
            String encoded = encode(gui.getItem(i));
            if (encoded != null) {
                root.addProperty(String.valueOf(i), encoded);
            }
        }
        try {
            File f = file(player.getUniqueId());
            Files.createDirectories(f.toPath().getParent());
            Files.writeString(f.toPath(), root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save accessories for "
                    + player.getName() + ": " + e);
        }
    }

    public void saveAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory() != null
                    && player.getOpenInventory().getTopInventory().getHolder() instanceof Gui) {
                saveGui(player, player.getOpenInventory().getTopInventory());
            }
        }
    }

    private static String encode(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(stack);
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private static ItemStack decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            Object o = in.readObject();
            return o instanceof ItemStack stack ? stack : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------- ticks

    public void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<String> set = worn.get(player.getUniqueId());
            if (set == null || set.isEmpty()) {
                continue;
            }
            if (set.contains("regeneration_stone")) {
                int t = regenTick.getOrDefault(player.getUniqueId(), 0) + 1;
                if (t >= 5) { // every 50 ticks
                    t = 0;
                    if (player.getHealth() < maxHealth(player)) {
                        player.heal(1.0); // half a heart
                    }
                }
                regenTick.put(player.getUniqueId(), t);
            }
            if (set.contains("iron_bubble") && player.isUnderWater()) {
                player.setRemainingAir(player.getMaximumAir()); // air locked at max
            }
            tickStepHeight(player, set.contains("agility_cape"));
            tickInvisibility(player, set.contains("invisibility_cloak"));
            if (set.contains("zanite_ring") || set.contains("zanite_pendant")) {
                // approximates the zanite mining-speed buff (client-computed originally)
                player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 220, 1,
                        false, false, false));
            }
            if (set.contains("ice_ring") || set.contains("ice_pendant")) {
                freezeTick(player);
            }
        }
    }

    public void tickSlowFall() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<String> set = worn.get(player.getUniqueId());
            if (set == null) {
                continue;
            }
            boolean slow = set.contains("valkyrie_cape") || set.contains("golden_feather");
            if (!slow || player.isOnGround() || player.isGliding() || player.isInWater()
                    || player.isSneaking()) {
                continue;
            }
            if (player.getVelocity().getY() < -0.5) {
                player.setVelocity(player.getVelocity().multiply(1.0).setY(
                        player.getVelocity().getY() * 0.6)); // gentle descent
                player.setFallDistance(0);
            }
        }
    }

    private void tickStepHeight(Player player, boolean wearing) {
        try {
            Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("step_height"));
            if (attr == null) {
                return;
            }
            AttributeInstance inst = player.getAttribute(attr);
            if (inst == null) {
                return;
            }
            double want = wearing ? 1.0 : 0.5; // agility cape: full-block step
            if (Math.abs(inst.getBaseValue() - want) > 0.01
                    && (!wearing || Math.abs(inst.getBaseValue() - 0.5) < 0.01)) {
                inst.setBaseValue(want);
            }
        } catch (Throwable ignored) {
        }
    }

    private void tickInvisibility(Player player, boolean wearing) {
        if (wearing && player.isSneaking()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 220, 0,
                    false, false, false));
        } else if (!wearing) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    private void freezeTick(Player player) {
        int frozen = 0;
        var loc = player.getLocation();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 3.61) { // radius 1.9
                    continue;
                }
                Block block = loc.clone().add(dx, -1, dz).getBlock();
                if (block.getType() == Material.WATER) {
                    block.setType(Material.FROSTED_ICE, false);
                    frozen++;
                }
            }
        }
        if (frozen >= 3) { // damages the accessory per 3 blocks frozen
            damageAccessory(player, frozen / 3);
        }
    }

    private void damageAccessory(Player player, int amount) {
        Map<Integer, String> saved = loadItems(player.getUniqueId());
        boolean changed = false;
        for (Map.Entry<Integer, String> e : new HashMap<>(saved).entrySet()) {
            ItemStack stack = decode(e.getValue());
            String id = items.idOf(stack);
            if (id == null || (!id.equals("ice_ring") && !id.equals("ice_pendant"))) {
                continue;
            }
            ItemMeta meta = stack.getItemMeta();
            if (!(meta instanceof Damageable damageable) || !damageable.hasMaxDamage()) {
                continue;
            }
            int next = damageable.getDamage() + amount;
            if (next >= damageable.getMaxDamage()) {
                saved.remove(e.getKey()); // breaks
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK,
                        0.8f, 1.0f);
            } else {
                damageable.setDamage(next);
                stack.setItemMeta(meta);
                saved.put(e.getKey(), encode(stack));
            }
            changed = true;
        }
        if (changed) {
            try {
                Files.writeString(file(player.getUniqueId()).toPath(),
                        toJson(saved).toString(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            refreshWorn(player, null);
        }
    }

    private static com.google.gson.JsonObject toJson(Map<Integer, String> map) {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        for (Map.Entry<Integer, String> e : map.entrySet()) {
            root.addProperty(String.valueOf(e.getKey()), e.getValue());
        }
        return root;
    }

    private static double maxHealth(org.bukkit.entity.LivingEntity e) {
        try {
            Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
            AttributeInstance inst = attr == null ? null : e.getAttribute(attr);
            return inst == null ? 20.0 : inst.getValue();
        } catch (Throwable t) {
            return 20.0;
        }
    }

    // ------------------------------------------------------------------ combat

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPunch(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand != null && !hand.getType().isAir()) {
            return;
        }
        double bonus = gloveDamage(player); // unarmed punch damage
        if (bonus > 0) {
            event.setDamage(event.getDamage() + bonus);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getHitEntity() instanceof Player player)) {
            return;
        }
        if (!has(player, "shield_of_repulsion")) {
            return;
        }
        Projectile proj = event.getEntity();
        if (!DEFLECTABLE.contains(proj.getType())) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = shieldCd.get(player.getUniqueId());
        if (last != null && now - last < 7500) { // 150 tick cooldown
            return;
        }
        shieldCd.put(player.getUniqueId(), now);
        event.setCancelled(true); // deflect
        proj.setVelocity(proj.getVelocity().multiply(-0.8).add(new org.bukkit.util.Vector(0, 0.4, 0)));
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.4f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFreezeDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FREEZE) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (hasAny(player, "valkyrie_cape", "golden_feather")) {
            event.setCancelled(true); // walk on powder snow safely
        }
    }

    // ------------------------------------------------------- headless self-test

    /** Validates slot mapping and file persistence without a player present. */
    public void accessorySelfTest(Content content) {
        if (!plugin.getConfig().getBoolean("debug.accessory-test", false)) {
            return;
        }
        int fails = 0;
        for (Map.Entry<String, com.aetherport.aether.content.ItemDef> e
                : content.items().entrySet()) {
            if (!e.getValue().kind().equals("accessory")) {
                continue;
            }
            boolean fits = false;
            for (int slot = 0; slot < 8; slot++) {
                if (fitsSlot(slot, e.getKey())) {
                    fits = true;
                    break;
                }
            }
            if (!fits) {
                plugin.getLogger().info("  accessory self-test slot: " + e.getKey());
                fails++;
            }
        }
        UUID probe = UUID.randomUUID();
        Map<Integer, String> probeMap = Map.of(0, "aGVsbG8=");
        try {
            File f = file(probe);
            Files.createDirectories(f.toPath().getParent());
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            root.addProperty("0", "aGVsbG8=");
            Files.writeString(f.toPath(), root.toString(), StandardCharsets.UTF_8);
            if (!loadItems(probe).getOrDefault(0, "").equals("aGVsbG8=")) {
                plugin.getLogger().info("  accessory self-test persistence");
                fails++;
            }
            Files.deleteIfExists(f.toPath());
        } catch (Exception e) {
            plugin.getLogger().info("  accessory self-test io: " + e);
            fails++;
        }
        plugin.getLogger().info("Accessory self-test: " + (fails == 0 ? "PASS" : "FAIL-" + fails));
    }

    public List<String> accessoryIds() {
        List<String> out = new ArrayList<>();
        for (int slot = 0; slot < 8; slot++) {
            final int s = slot;
            out.add(slotName(s));
        }
        return out;
    }
}
