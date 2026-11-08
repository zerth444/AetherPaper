package com.aetherport.aether.listener;

import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.content.BlockDef;
import com.aetherport.aether.content.Content;
import com.aetherport.aether.item.AetherItems;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event;
import io.papermc.paper.event.player.PlayerPickBlockEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Handles placement, breaking, drops and suppression of the vanilla carrier block
 * behaviour for all mapped Aether blocks (carrier technique, see DECISIONS D-004).
 */
public final class BlockListener implements Listener {

    private final AetherPlugin plugin;
    private final Content content;
    private final AetherItems items;
    private VanillaBlockPlacement vanillaPlacement;

    public BlockListener(AetherPlugin plugin, Content content, AetherItems items) {
        this.plugin = plugin;
        this.content = content;
        this.items = items;
    }

    private BlockDef defAt(Block block) {
        String sapling=plugin.saplings().idAt(block);if(sapling!=null)return content.block(sapling);
        if(block.getType()==Material.FROSTED_ICE && !customQuicksoil(block)) return null;
        String state = block.getBlockData().getAsString();
        String id = content.blockIdForCarrier(state);
        return id == null ? null : content.block(id);
    }

    private static org.bukkit.NamespacedKey quicksoilKey(Block b) {
        return new org.bukkit.NamespacedKey("aether","quicksoil_"+(b.getX()&15)+"_"+b.getY()+"_"+(b.getZ()&15));
    }
    static boolean customQuicksoil(Block b) {
        return b.getWorld().getKey().toString().equals(AetherPlugin.get().aetherDimensionKey())
                || b.getChunk().getPersistentDataContainer().has(quicksoilKey(b),org.bukkit.persistence.PersistentDataType.BYTE);
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onQuicksoilMelt(org.bukkit.event.block.BlockFadeEvent event) {
        if(event.getBlock().getType()==Material.FROSTED_ICE && customQuicksoil(event.getBlock())
                && "quicksoil".equals(content.blockIdForCarrier(event.getBlock().getBlockData().getAsString()))) event.setCancelled(true);
    }

    private static final java.util.Map<String, String> SOUND_MAP = java.util.Map.ofEntries(
            java.util.Map.entry("grass", "BLOCK_GRASS_BREAK"),
            java.util.Map.entry("stone", "BLOCK_STONE_BREAK"),
            java.util.Map.entry("gravel", "BLOCK_GRAVEL_BREAK"),
            java.util.Map.entry("sand", "BLOCK_SAND_BREAK"),
            java.util.Map.entry("glass", "BLOCK_GLASS_BREAK"),
            java.util.Map.entry("wood", "BLOCK_WOOD_BREAK"),
            java.util.Map.entry("wool", "BLOCK_WOOL_BREAK"),
            java.util.Map.entry("snow", "BLOCK_SNOW_BREAK"),
            java.util.Map.entry("metal", "BLOCK_METAL_BREAK"),
            java.util.Map.entry("anvil", "BLOCK_ANVIL_LAND"),
            java.util.Map.entry("ladder", "BLOCK_LADDER_BREAK"),
            java.util.Map.entry("vine", "BLOCK_VINE_BREAK"),
            java.util.Map.entry("wet_grass", "BLOCK_WET_GRASS_BREAK"));

    private static org.bukkit.Sound breakSound(String soundType) {
        String name = SOUND_MAP.getOrDefault(soundType.toLowerCase(), "BLOCK_STONE_BREAK");
        try {
            return org.bukkit.Sound.valueOf(name);
        } catch (Exception e) {
            return org.bukkit.Sound.BLOCK_STONE_BREAK;
        }
    }

    private static void playBreakSound(Block block, BlockDef def) {
        try {
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
                    CarrierSounds.audible(breakSound(def.id().endsWith("aercloud") ? "wool" : def.id().endsWith("_leaves") ? "grass" : def.sound())), 1.0f, 0.9f
                            + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.2f);
        } catch (Throwable ignored) {
        }
    }

    private static void playPlaceSound(Block block, BlockDef def) {
        try {
            String sound = CarrierSounds.audible(breakSound(def.id().endsWith("aercloud") ? "wool" : def.id().endsWith("_leaves") ? "grass" : def.sound()))
                    .replace(".break", ".place");
            if (sound.equals("minecraft:block.anvil.land")) sound = "minecraft:block.anvil.place";
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
                    sound, 1.0f, 0.9f
                            + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.2f);
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasSilkTouch(ItemStack held) {
        return held != null && held.getEnchantments()
                .getOrDefault(org.bukkit.enchantments.Enchantment.SILK_TOUCH, 0) > 0;
    }

    private static boolean passesTool(BlockDef.Drop drop, ItemStack held,boolean mining) {
        if (drop.require() == null) {
            // pool-level shear/silk exclusion
            return !drop.exclude() || !isShears(held) && !hasSilkTouch(held);
        }
        if (drop.require().equals("shears")) {
            return isShears(held);
        }
        if(drop.require().equals("shears_or_silk"))return isShears(held)||hasSilkTouch(held);
        if(drop.require().equals("explosion"))return !mining;
        if (drop.require().equals("silk")) {
            return hasSilkTouch(held);
        }
        return true;
    }

    private static boolean isShears(ItemStack held) {
        return held != null && held.getType() == org.bukkit.Material.SHEARS;
    }

    public static boolean correctTool(BlockDef def, ItemStack held) {
        return def.tool()==null || held!=null && (held.getType().name().endsWith("_"+def.tool().toUpperCase())
                || held.getType().name().endsWith("_SWORD") && def.tool().equals("hoe"));
    }
    public static int toolTier(ItemStack held) {
        String type=held==null?"":held.getType().name();
        return type.startsWith("NETHERITE_")?4:type.startsWith("DIAMOND_")?3:type.startsWith("IRON_")?2:type.startsWith("STONE_")?1:0;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        BlockDef def = defAt(event.getBlock());
        if (def == null) {
            return;
        }
        event.setDropItems(false);
        if (def.id().equals("present")
                && java.util.concurrent.ThreadLocalRandom.current().nextInt(4) == 0) {
            event.getBlock().getWorld().spawn(event.getBlock().getLocation().add(0.5, 0.5, 0.5),
                    org.bukkit.entity.TNTPrimed.class, t -> t.setFuseTicks(30)); // trick present
        }
        Player player = event.getPlayer();
        playBreakSound(event.getBlock(), def);
        Block block = event.getBlock();
        if(block.getType()==Material.FROSTED_ICE) block.getChunk().getPersistentDataContainer().remove(quicksoilKey(block));
        try {
            block.getWorld().spawnParticle(org.bukkit.Particle.BLOCK,
                    block.getLocation().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, 0,
                    block.getBlockData());
        } catch (Throwable ignored) {
        }
        event.setCancelled(true);
        block.setType(Material.AIR, false);
        if (player.getGameMode().name().equals("CREATIVE")) {
            return; // creative never drops
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        dropCustom(block,def,held,1,true);
        if(def.id().equals("berry_bush") && !isShears(held) && !hasSilkTouch(held)){
            block.setBlockData(Bukkit.createBlockData(content.block("berry_bush_stem").carrier()),false);
            Bukkit.getScheduler().runTaskLater(plugin,()->{
                if(block.getWorld().isChunkLoaded(block.getX()>>4,block.getZ()>>4) && "berry_bush_stem".equals(content.blockIdForCarrier(block.getBlockData().getAsString())))block.setBlockData(Bukkit.createBlockData(content.block("berry_bush").carrier()),false);
            },1200);
        }
        if(def.hardness()>0 && held.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable) held.damage(1,player);
    }

    private void dropCustom(Block block,BlockDef def,ItemStack held,double yield,boolean mining) {
        boolean correctTool=correctTool(def,held);
        if (mining && def.requiresTool() && (!correctTool || toolTier(held)<def.requiredTier())) {
            return;
        }
        for (BlockDef.Drop drop : def.drops()) {
            if (!passesTool(drop, held,mining)) {
                continue;
            }
            double chance = drop.chance();
            if (!drop.fortune().isEmpty()) {
                int fortune = held.getEnchantments().getOrDefault(
                        org.bukkit.enchantments.Enchantment.FORTUNE, 0);
                int idx = Math.max(0, Math.min(fortune, drop.fortune().size() - 1));
                chance = drop.fortune().get(idx);
            }
            if (chance < 1.0
                    && java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= chance) {
                continue;
            }
            int count = drop.min() + (drop.max() > drop.min()
                    ? java.util.concurrent.ThreadLocalRandom.current()
                            .nextInt(drop.max() - drop.min() + 1) : 0);
            int fortune=held.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FORTUNE);
            if(fortune>0 && "minecraft:ore_drops".equals(drop.bonus()))count*=Math.max(0,java.util.concurrent.ThreadLocalRandom.current().nextInt(fortune+2)-1)+1;
            if(fortune>0 && "minecraft:uniform_bonus_count".equals(drop.bonus()))count+=java.util.concurrent.ThreadLocalRandom.current().nextInt(fortune+1);
            String heldId=items.idOf(held);
            if (mining && def.doubleDrops() && !"pickaxe".equals(def.tool()) && correctTool && heldId!=null && heldId.startsWith("skyroot_") && !hasSilkTouch(held)) {
                count *= 2; // skyroot-style doubling of double-drop blocks
            }
            if (count <= 0 || java.util.concurrent.ThreadLocalRandom.current().nextDouble()>=yield) {
                continue;
            }
            ItemStack out = drop.vanilla()
                    ? new ItemStack(org.bukkit.Material.matchMaterial(
                            drop.item().toUpperCase()), count)
                    : items.create(drop.item(), count);
            if (out != null && !out.getType().isAir()) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), out);
            }
        }
    }

    private void explosion(java.util.List<Block> blocks,float yield) {
        var iterator=blocks.iterator();
        while(iterator.hasNext()) {
            Block block=iterator.next();BlockDef def=defAt(block);if(def==null)continue;
            iterator.remove();
            if(def.hardness()<0 || plugin.dungeons()!=null && com.aetherport.aether.dungeon.DungeonManager.isLockedBlock(def.id()) && plugin.dungeons().isLocked(block.getLocation()))continue;
            dropCustom(block,def,new ItemStack(Material.AIR),Math.max(0,Math.min(1,yield)),false);
            block.setType(Material.AIR,false);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onEntityExplosion(org.bukkit.event.entity.EntityExplodeEvent event) { explosion(event.blockList(),event.getYield()); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onBlockExplosion(org.bukkit.event.block.BlockExplodeEvent event) { explosion(event.blockList(),event.getYield()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() == null) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        BlockDef hit = defAt(clicked);
        ItemStack held = event.getItem();
        String id = items.idOf(held);
        if (id != null && content.isBlock(id)) {
            if (event.useItemInHand() == Event.Result.DENY
                    || event.getPlayer().getGameMode() == GameMode.ADVENTURE
                    || event.getPlayer().getGameMode() == GameMode.SPECTATOR) {
                return;
            }
            if (hit != null && !event.getPlayer().isSneaking()
                    && plugin.stations() != null && plugin.stations().isStation(hit.id())) {
                plugin.stations().open(event.getPlayer(), clicked, hit.id());
                event.setCancelled(true);
                return;
            }
            if (hit == null && clicked.getType().isInteractable() && !event.getPlayer().isSneaking()) {
                return;
            }
            // our block item: place the carrier
            BlockDef def = content.block(id);
            if (def == null || !(def.carrier().startsWith("minecraft:note_block")
                    || def.carrier().startsWith("minecraft:tripwire[") || def.carrier().startsWith("minecraft:frosted_ice[") || def.carrier().startsWith("minecraft:snow[") || def.carrier().endsWith("_stained_glass"))) {
                return;
            }
            Block target = clicked.getRelative(event.getBlockFace());
            if (target.getY() < target.getWorld().getMinHeight()
                    || target.getY() >= target.getWorld().getMaxHeight()
                    || !target.getWorld().getWorldBorder().isInside(target.getLocation())
                    || !def.carrier().startsWith("minecraft:tripwire[") && target.getWorld().getNearbyEntities(
                            org.bukkit.util.BoundingBox.of(target)).stream()
                            .anyMatch(entity -> entity instanceof org.bukkit.entity.LivingEntity
                                    && entity.getBoundingBox().overlaps(org.bukkit.util.BoundingBox.of(target)))) {
                event.setCancelled(true);
                return;
            }
            Material type = target.getType();
            if (!type.isAir() && !type.equals(Material.WATER) && !type.equals(Material.LAVA)
                    && !type.equals(Material.SHORT_GRASS) && !type.equals(Material.TALL_GRASS)) {
                return;
            }
            org.bukkit.block.BlockState previous = target.getState();
            try {
                BlockData data = Bukkit.createBlockData(def.carrier());
                target.setBlockData(data, false);
            } catch (Exception e) {
                return;
            }
            Player player = event.getPlayer();
            org.bukkit.event.block.BlockPlaceEvent place = new org.bukkit.event.block.BlockPlaceEvent(
                    target, previous, clicked, held, player, true, event.getHand());
            Bukkit.getPluginManager().callEvent(place);
            if (place.isCancelled() || !place.canBuild()) {
                previous.update(true, false);
                event.setCancelled(true);
                return;
            }
            if (!player.getGameMode().name().equals("CREATIVE")) {
                held.setAmount(held.getAmount() - 1);
            }
            if(target.getType()==Material.FROSTED_ICE) target.getChunk().getPersistentDataContainer().set(quicksoilKey(target),org.bukkit.persistence.PersistentDataType.BYTE,(byte)1);
            playPlaceSound(target, def);
            event.setCancelled(true);
            return;
        }
        if (hit == null) {
            return;
        }
        if (event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        if (!event.getPlayer().isSneaking() && plugin.stations() != null
                && plugin.stations().isStation(hit.id())) {
            plugin.stations().open(event.getPlayer(), clicked, hit.id());
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            return;
        }
        if (held == null || held.getType().isAir()) {
            // empty hand on a carrier: open workstations, otherwise suppress the note
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCarrierUse(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null && defAt(event.getClickedBlock()) != null) {
            ItemStack held = event.getItem();
            Player player = event.getPlayer();
            if (event.useItemInHand() != Event.Result.DENY
                    && held != null && held.getType().isBlock() && items.idOf(held) == null
                    && (player.getGameMode() == GameMode.CREATIVE
                            || player.getGameMode() == GameMode.SURVIVAL)) {
                try {
                    if (vanillaPlacement == null) {
                        vanillaPlacement = new VanillaBlockPlacement(player);
                    }
                    vanillaPlacement.place(event);
                } catch (ReflectiveOperationException error) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "Paper 26.2 native block placement failed", error);
                }
                event.setUseItemInHand(Event.Result.DENY);
            }
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickBlock(PlayerPickBlockEvent event) {
        BlockDef def = defAt(event.getBlock());
        if (def == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        int target = event.getTargetSlot();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!def.id().equals(items.idOf(stack))) {
                continue;
            }
            if (slot < 9) {
                inventory.setHeldItemSlot(slot);
            } else {
                ItemStack replaced = inventory.getItem(target);
                inventory.setItem(target, stack);
                inventory.setItem(slot, replaced);
                inventory.setHeldItemSlot(target);
            }
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            inventory.setItem(target, items.create(def.id(), 1));
            inventory.setHeldItemSlot(target);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onSlide(org.bukkit.event.player.PlayerMoveEvent event) {
        Block below=event.getTo().clone().subtract(0,.01,0).getBlock();
        if(customQuicksoil(below) && below.getType()==Material.NOTE_BLOCK && "quicksoil".equals(content.blockIdForCarrier(below.getBlockData().getAsString()))) {
            below.setBlockData(Bukkit.createBlockData(content.block("quicksoil").carrier()),false);
            below.getChunk().getPersistentDataContainer().set(quicksoilKey(below),org.bukkit.persistence.PersistentDataType.BYTE,(byte)1);
        }
        // Native frosted-ice friction is simulated by the client. Never inject velocity.
    }
    @EventHandler(ignoreCancelled = true)
    public void onNotePlay(NotePlayEvent event) {
        if (defAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (defAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlantContact(io.papermc.paper.event.entity.EntityInsideBlockEvent event) {
        if (event.getBlock().getType() == Material.TRIPWIRE && defAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }
}
