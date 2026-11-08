package com.aetherport.aether.listener;

import com.aetherport.aether.AetherPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Complements the resource pack's suppression of client-predicted carrier sounds. */
public final class CarrierSounds implements Listener {
    private final AetherPlugin plugin;
    private final Map<UUID, Location> steps = new HashMap<>();
    private final Map<UUID, Block> digging = new HashMap<>();

    public CarrierSounds(AetherPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5, 5);
    }

    public static String audible(Sound sound) {
        String key = sound.getKey().toString();
        return (key.startsWith("minecraft:block.wood.") || key.startsWith("minecraft:block.stone.") || key.startsWith("minecraft:block.glass.") || key.startsWith("minecraft:block.snow."))
                ? "aether:carrier_original." + sound.getKey().getKey() : key;
    }

    private String custom(Block block) {
        if(block.getType()==org.bukkit.Material.FROSTED_ICE && !BlockListener.customQuicksoil(block)) return null;
        return plugin.content().blockIdForCarrier(block.getBlockData().getAsString());
    }

    private void play(Block block, String key, float volume, float pitch) {
        block.getWorld().playSound(block.getLocation().add(.5, .5, .5), key, volume, pitch);
    }

    private void restoreWood(Block block, Sound sound, float volume, float pitch) {
        if (sound.getKey().getKey().startsWith("block.wood.") || sound.getKey().getKey().startsWith("block.stone.") || sound.getKey().getKey().startsWith("block.glass.") || sound.getKey().getKey().startsWith("block.snow.")) {
            play(block, audible(sound), volume, pitch);
        }
    }

    private String materialSound(Block block, String action) {
        String id = custom(block);
        if (id == null) return null;
        String material = id.endsWith("aercloud") ? "wool" : id.endsWith("_leaves") ? "grass" : plugin.content().block(id).sound();
        if (!java.util.Set.of("grass", "stone", "gravel", "sand", "glass", "wood", "wool",
                "snow", "metal", "anvil", "ladder", "vine", "wet_grass").contains(material)) material = "stone";
        return (material.equals("wood") || material.equals("stone") || material.equals("glass") || material.equals("snow")) ? "aether:carrier_original.block." + material + "." + action
                : "minecraft:block." + material + "." + action;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        digging.remove(event.getPlayer().getUniqueId());
        if (custom(event.getBlock()) == null) {
            restoreWood(event.getBlock(), event.getBlock().getBlockData().getSoundGroup().getBreakSound(), 1, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (custom(event.getBlock()) == null) {
            restoreWood(event.getBlock(), event.getBlock().getBlockData().getSoundGroup().getPlaceSound(), 1, .8f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        if (event.getInstaBreak()) return;
        digging.put(event.getPlayer().getUniqueId(), event.getBlock());
    }

    @EventHandler
    public void onAbort(BlockDamageAbortEvent event) { digging.remove(event.getPlayer().getUniqueId()); }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        digging.remove(event.getPlayer().getUniqueId());
        steps.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        Block floor = event.getEntity().getLocation().subtract(0, .05, 0).getBlock();
        String sound = materialSound(floor, "fall");
        if (sound != null) play(floor, sound, .5f, .75f);
        else restoreWood(floor, floor.getBlockData().getSoundGroup().getFallSound(), .5f, .75f);
    }

    private void tick() {
        digging.entrySet().removeIf(entry -> {
            Player p = Bukkit.getPlayer(entry.getKey());
            Block b = entry.getValue();
            if (p == null || !p.getWorld().equals(b.getWorld()) || b.isEmpty()
                    || p.getLocation().distanceSquared(b.getLocation()) > 49) return true;
            String sound = materialSound(b, "hit");
            if (sound != null) play(b, sound, .25f, .5f);
            else restoreWood(b, b.getBlockData().getSoundGroup().getHitSound(), .25f, .5f);
            return false;
        });
        java.util.Set<UUID> loaded = new java.util.HashSet<>();
        for (var world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                loaded.add(entity.getUniqueId());
                Location now = entity.getLocation();
                Location last = steps.get(entity.getUniqueId());
                if (last == null || !last.getWorld().equals(now.getWorld())) {
                    steps.put(entity.getUniqueId(), now); continue;
                }
                double distance = last.distanceSquared(now);
                if (distance < .36) continue;
                steps.put(entity.getUniqueId(), now);
                if (Math.abs(now.getY()-last.getY()) > .6 || distance > 16 || entity instanceof Player p && p.isSneaking()) continue;
                Block floor = now.clone().subtract(0, .15, 0).getBlock();
                if (floor.isPassable()) continue;
                String sound = materialSound(floor, "step");
                if (sound != null) play(floor, sound, .5f, 1);
                else restoreWood(floor, floor.getBlockData().getSoundGroup().getStepSound(), .5f, 1);
            }
        }
        steps.keySet().retainAll(loaded);
    }
}
