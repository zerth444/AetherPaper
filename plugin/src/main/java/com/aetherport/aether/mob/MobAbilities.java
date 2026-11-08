package com.aetherport.aether.mob;

import com.aetherport.aether.AetherPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Signature mob interactions: flying-cow milking, swet absorption, zephyr
 * snowballs, moa fall safety. Sheepuff shearing/dyeing and moa taming/riding
 * are native to the carriers and need no code.
 */
public final class MobAbilities implements Listener {

    private final AetherPlugin plugin;
    private final NamespacedKey moaTypeKey;
    private final Map<UUID, Long> absorbedSince = new HashMap<>();
    private final Map<UUID, Integer> eggTimer = new HashMap<>();

    public MobAbilities(AetherPlugin plugin) {
        this.plugin = plugin;
        this.moaTypeKey = new NamespacedKey(plugin, "moa_type");
    }

    public NamespacedKey moaTypeKey() {
        return moaTypeKey;
    }

    private String mobOf(Entity entity) {
        return plugin.mobs().mobOf(entity);
    }

    // ------------------------------------------------------------- milking

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMilk(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!"flying_cow".equals(mobOf(event.getRightClicked()))) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.BUCKET) {
            return;
        }
        event.setCancelled(true);
        if (!player.getGameMode().name().equals("CREATIVE")) {
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().addItem(new ItemStack(Material.MILK_BUCKET));
        }
        player.getWorld().playSound(event.getRightClicked().getLocation(),
                "aether:entity.flying_cow.milk", 1.0f, 1.0f);
    }

    // --------------------------------------------------------------- absorb

    /** Swets capture prey on contact; victims ride, take damage, escape sneakily. */
    public void tickAbsorb() {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                String id = mobOf(e);
                if (!"blue_swet".equals(id) && !"golden_swet".equals(id)) {
                    continue;
                }
                if (!(e instanceof LivingEntity swet) || !e.getPassengers().isEmpty()) {
                    continue;
                }
                for (Entity near : world.getNearbyEntities(e.getLocation(), 1.1, 1.1, 1.1)) {
                    if (near instanceof Player player
                            && !player.getGameMode().name().equals("CREATIVE")
                            && !player.getGameMode().name().equals("SPECTATOR")) {
                        swet.addPassenger(player); // consumePassenger
                        absorbedSince.put(player.getUniqueId(), System.currentTimeMillis());
                        break;
                    }
                }
            }
        }
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Entity vehicle = player.getVehicle();
            if (vehicle == null || (!"blue_swet".equals(mobOf(vehicle))
                    && !"golden_swet".equals(mobOf(vehicle)))) {
                absorbedSince.remove(player.getUniqueId());
                continue;
            }
            Long since = absorbedSince.get(player.getUniqueId());
            if (since != null && now - since > 6000) {
                vehicle.removePassenger(player); // escape after 6s
                absorbedSince.remove(player.getUniqueId());
                continue;
            }
            if (now % 2000 < 60) {
                player.damage(1.0, vehicle); // slow suffocation
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneakEscape(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        Entity vehicle = player.getVehicle();
        if (vehicle != null && ("blue_swet".equals(mobOf(vehicle))
                || "golden_swet".equals(mobOf(vehicle)))) {
            vehicle.removePassenger(player);
            absorbedSince.remove(player.getUniqueId());
        }
    }

    // ---------------------------------------------------------------- zephyr

    /** Zephyrs hurl snowballs at their targets, ghast-style. */
    public void tickZephyrs() {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (!"zephyr".equals(mobOf(e)) || !(e instanceof Mob mob)) {
                    continue;
                }
                if (!(mob.getTarget() instanceof LivingEntity target) || !target.isValid()) {
                    continue;
                }
                if (mob.getTicksLived() % 80 != 0) {
                    continue;
                }
                if (mob.getLocation().distanceSquared(target.getLocation()) > 48 * 48) {
                    continue;
                }
                Snowball ball = world.spawn(mob.getEyeLocation(), Snowball.class);
                Vector dir = target.getEyeLocation().subtract(mob.getEyeLocation())
                        .toVector().normalize().multiply(1.2);
                ball.setVelocity(dir);
                world.playSound(mob.getLocation(), "aether:entity.zephyr.shoot", 1.0f, 1.0f);
            }
        }
    }

    // ------------------------------------------------------- till/path/strip

    /** Hoe tills dirt into farmland, shovel flattens paths, axe strips logs. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTillPathStrip(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        org.bukkit.block.Block clicked = event.getClickedBlock();
        ItemStack held = event.getItem();
        if (clicked == null || held == null || clicked.getType() != Material.NOTE_BLOCK) {
            return;
        }
        String blockId = plugin.content().blockIdForCarrier(
                clicked.getBlockData().getAsString());
        if (blockId == null) {
            return;
        }
        String heldId = plugin.aetherItems().idOf(held);
        String out = null;
        if (held.getType().name().endsWith("_HOE") || (heldId != null && heldId.endsWith("_hoe"))) {
            if (blockId.equals("aether_dirt") || blockId.equals("aether_grass_block")) {
                out = "aether_farmland";
            }
        } else if (held.getType().name().endsWith("_SHOVEL")
                || (heldId != null && heldId.endsWith("_shovel"))) {
            if (blockId.equals("aether_dirt") || blockId.equals("aether_grass_block")) {
                out = "aether_dirt_path";
            }
        } else if (held.getType().name().endsWith("_AXE")
                || (heldId != null && heldId.endsWith("_axe"))) {
            if (blockId.equals("skyroot_log")) {
                out = "stripped_skyroot_log";
            }
        }
        if (out == null) {
            return;
        }
        var def = plugin.content().block(out);
        if (def == null) {
            return;
        }
        try {
            clicked.setBlockData(org.bukkit.Bukkit.createBlockData(def.carrier()), false);
        } catch (Exception e) {
            return;
        }
        clicked.getWorld().playSound(clicked.getLocation(), Sound.ITEM_HOE_TILL, 1.0f, 1.0f);
        event.setCancelled(true);
    }

    // ----------------------------------------------------------- moa egg laying

    /** Tamed adult moas lay an egg every 6000-12000 ticks, like the original. */
    public void tickMoaEggs() {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (!"moa".equals(mobOf(e)) || !(e instanceof org.bukkit.entity.Horse horse)) {
                    continue;
                }
                if (!horse.isTamed() || !horse.getPassengers().isEmpty()) {
                    continue;
                }
                int left = eggTimer.getOrDefault(e.getUniqueId(), 6000
                        + new java.util.Random().nextInt(6001));
                left -= 100;
                if (left <= 0) {
                    String type = horse.getPersistentDataContainer().get(moaTypeKey,
                            org.bukkit.persistence.PersistentDataType.STRING);
                    String egg = switch (type == null ? "" : type) {
                        case "white" -> "white_moa_egg";
                        case "black" -> "black_moa_egg";
                        default -> "blue_moa_egg";
                    };
                    var stack = plugin.aetherItems().create(egg, 1);
                    if (stack != null) {
                        world.dropItemNaturally(e.getLocation(), stack);
                        world.playSound(e.getLocation(), Sound.ENTITY_CHICKEN_EGG, 1.0f, 1.0f);
                    }
                    left = 6000 + new java.util.Random().nextInt(6001);
                }
                eggTimer.put(e.getUniqueId(), left);
            }
        }
    }

    // --------------------------------------------------------------------- moa

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMoaFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if ("moa".equals(mobOf(player.getVehicle()))) {
            event.setCancelled(true); // winged landing
        }
    }

    /** Moas glide: ridden moas fall slowly and jump higher (multi-jump approx). */
    public void tickMoaRide() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!"moa".equals(mobOf(player.getVehicle()))) {
                continue;
            }
            if (player.isOnGround()) {
                continue;
            }
            if (player.getVelocity().getY() < -0.4) {
                player.setVelocity(player.getVelocity().setY(-0.4)); // glide
                player.setFallDistance(0);
            }
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.JUMP_BOOST, 60, 1, false, false, false));
        }
    }
}
