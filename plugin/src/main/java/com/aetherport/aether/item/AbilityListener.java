package com.aetherport.aether.item;

import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.content.Content;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.EnumSet;import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Item abilities ported from the original mod (verified numbers in comments).
 * Full-strength gate mirrors EquipmentUtil.isFullStrength via per-attacker timing.
 */
public final class AbilityListener implements Listener {

    private static final long FULL_STRENGTH_MS = 600L;

    private static final Set<EntityType> UNDEAD = EnumSet.of(
            EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED, EntityType.SKELETON,
            EntityType.STRAY, EntityType.BOGGED, EntityType.WITHER_SKELETON,
            EntityType.ZOMBIFIED_PIGLIN, EntityType.SKELETON_HORSE, EntityType.ZOMBIE_HORSE,
            EntityType.ZOGLIN, EntityType.PHANTOM, EntityType.WITHER);

    private static final Set<EntityType> PIGS = EnumSet.of(
            EntityType.PIG, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.HOGLIN,
            EntityType.ZOGLIN, EntityType.ZOMBIFIED_PIGLIN);

    private final AetherPlugin plugin;
    private final Content content;
    private final AetherItems items;
    private final NamespacedKey projKey;
    private final Map<String, Song> songs = new HashMap<>();
    private final Map<String, Integer> jukeboxTasks = new HashMap<>();
    private final Map<UUID, Long> lastHit = new ConcurrentHashMap<>();
    private final Map<UUID, Long> itemCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> minions = new ConcurrentHashMap<>();
    private final Set<UUID> parachuting = ConcurrentHashMap.newKeySet();
    private final Map<UUID,UUID> parachuteVisuals = new HashMap<>();
    private final Map<UUID,String> parachuteTypes = new HashMap<>();
    private final Map<UUID, Long> gravititeJumpCd = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> flightTicks = new ConcurrentHashMap<>();

    private record Song(String sound, double length) {
    }

    public AbilityListener(AetherPlugin plugin, Content content, AetherItems items) {
        this.plugin = plugin;
        this.content = content;
        this.items = items;
        this.projKey = new NamespacedKey(plugin, "proj");
        loadSongs();
        Bukkit.getScheduler().runTaskTimer(plugin,this::tickParachutes,1,1);
    }

    private void loadSongs() {
        try (InputStream in = getClass().getResourceAsStream("/content/jukebox.json")) {
            if (in == null) {
                return;
            }
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseReader(
                    new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
                com.google.gson.JsonObject o = e.getValue().getAsJsonObject();
                songs.put(e.getKey(), new Song(o.get("sound").getAsString(),
                        o.get("length").getAsDouble()));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load jukebox songs: " + e);
        }
    }

    private static int enchantLevel(ItemStack stack, Enchantment ench) {
        if (stack == null) {
            return 0;
        }
        try {
            return stack.getEnchantments().getOrDefault(ench, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean fullStrength(Entity attacker) {
        long now = System.currentTimeMillis();
        Long last = lastHit.get(attacker.getUniqueId());
        boolean full = last == null || now - last >= FULL_STRENGTH_MS;
        lastHit.put(attacker.getUniqueId(), now);
        return full;
    }

    // ------------------------------------------------------------------ weapons

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof LivingEntity attacker)
                || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        ItemStack held = attacker.getEquipment() != null
                ? attacker.getEquipment().getItemInMainHand() : null;
        String id = items.idOf(held);
        if (id == null) {
            return;
        }
        boolean full = fullStrength(attacker);
        switch (id) {
            case "holy_sword" -> {
                if (full && UNDEAD.contains(target.getType())) {
                    int smite = enchantLevel(held, Enchantment.SMITE);
                    event.setDamage(event.getDamage() + 8.25 + 2.5 * smite); // +bonus vs undead
                    damage(held, attacker, 10); // costs 10 durability on proc
                }
            }
            case "lightning_sword" -> {
                if (full) {
                    target.getWorld().strikeLightning(target.getLocation());
                }
            }
            case "vampire_blade" -> {
                if (full && attacker.getHealth() < maxHealth(attacker)) {
                    attacker.heal(1.0); // lifesteal
                }
            }
            case "candy_cane_sword" -> {
                if (full && target.getType() != EntityType.PLAYER
                        && java.util.concurrent.ThreadLocalRandom.current().nextBoolean()) {
                    ItemStack cane = items.create("candy_cane", 1); // 50% candy drop
                    if (cane != null) {
                        target.getWorld().dropItemNaturally(target.getLocation(), cane);
                    }
                }
            }
            case "pig_slayer" -> {
                if (full && PIGS.contains(target.getType())) {
                    for (Entity near : target.getWorld().getNearbyEntities(
                            target.getLocation(), 32, 16, 32)) {
                        if (near.getType() == EntityType.ZOMBIFIED_PIGLIN
                                && near instanceof org.bukkit.entity.Mob mob) {
                            mob.setTarget(attacker); // angers the pack
                        }
                    }
                }
            }
            case "holystone_sword" -> {
                if (full && target.getType() != EntityType.PLAYER
                        && java.util.concurrent.ThreadLocalRandom.current().nextInt(25) == 0) {
                    ItemStack shard = items.create("ambrosium_shard", 1); // 1/25 ambrosium
                    if (shard != null) {
                        target.getWorld().dropItemNaturally(target.getLocation(), shard);
                    }
                }
            }
            case "gravitite_sword" -> {
                if (full && (target.isOnGround() || target.isInWater())
                        && !"aechor_plant".equals(plugin.mobs().mobOf(target))) {
                    target.setVelocity(target.getVelocity().add(new Vector(0, 1.0, 0))); // launch
                }
            }
            default -> {
            }
        }
    }

    private static double maxHealth(LivingEntity e) {
        try {
            Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
            AttributeInstance inst = attr == null ? null : e.getAttribute(attr);
            return inst == null ? 20.0 : inst.getValue();
        } catch (Throwable t) {
            return 20.0;
        }
    }

    private static void damage(ItemStack stack, LivingEntity holder, int amount) {
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(damageable.getDamage() + amount);
            stack.setItemMeta(meta);
        }
    }

    // -------------------------------------------------------------------- tools

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToolBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        String toolId = items.idOf(held);
        if (toolId == null || !toolId.startsWith("holystone_")) {
            return;
        }
        if (player.getWorld() == null
                || java.util.concurrent.ThreadLocalRandom.current().nextInt(50) != 0) {
            return;
        }
            ItemStack shard = items.create("ambrosium_shard", 1);
            if (shard != null) {
                event.getBlock().getWorld().dropItemNaturally(
                        event.getBlock().getLocation().add(0.5, 0.5, 0.5), shard);
            }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFloatBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        String id = items.idOf(held);
        if (id == null || !id.startsWith("gravitite_") || player.isSneaking()) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType().isAir() || clicked.isLiquid()
                || clicked.getType() == Material.BEDROCK
                || clicked.getType() == Material.BARRIER) {
            return;
        }
        Block above = clicked.getRelative(0, 1, 0);
        if (!above.getType().isAir() && !above.isLiquid()) {
            return;
        }
        Location at = clicked.getLocation().add(0.5, 0.0, 0.5);
        FallingBlock falling = clicked.getWorld().spawnFallingBlock(at, clicked.getBlockData());
        falling.setGravity(false); // floats upward like the original FloatingBlock
        falling.setVelocity(new Vector(0, 0.6, 0));
        falling.setDropItem(false);
        clicked.setType(Material.AIR, false);
        damage(held, player, 1);
        event.setCancelled(true);
    }

    // --------------------------------------------------------------- projectiles

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLoreUse(PlayerInteractEvent event) {
        if (event.getHand() == null || !"book_of_lore".equals(items.idOf(event.getItem()))
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        // PAPER has no native use action; right-click-air arrives pre-cancelled.
        // Open after the interaction finishes so vanilla cannot close the screen.
        event.setCancelled(true);
        ItemStack held = event.getItem().clone();
        Bukkit.getScheduler().runTask(plugin, () -> openLore(event.getPlayer(), held));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!rightClick || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        String id = items.idOf(held);
        if (id == null) {
            return;
        }
        if (event.isCancelled() && !java.util.Set.of("blue_berry","enchanted_berry","candy_cane","gingerbread_man","golden_parachute","cold_parachute").contains(id)) return;
        if (java.util.Set.of("blue_berry","enchanted_berry").contains(id) && held.getType()!=Material.PAPER) return;
        switch (id) {
            case "golden_dart_shooter", "poison_dart_shooter", "enchanted_dart_shooter" ->
                    shootDart(player, held, id);
            case "lightning_knife" -> throwKnife(player, held);
            case "hammer_of_kingbdogz" -> throwHammer(player, held);
            case "cloud_staff" -> summonMinions(player, held);
            case "blue_moa_egg", "white_moa_egg", "black_moa_egg" -> hatchEgg(player, held, event);
            case "cold_parachute", "golden_parachute" -> deployParachute(player, held);
            case "swet_ball" -> {
                if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    convertBlock(player, held, event.getClickedBlock());
                }
            }
            case "life_shard" -> useLifeShard(player, held);
            case "skyroot_water_bucket" -> placeSkyrootWater(player, held, event);
            case "skyroot_remedy_bucket" -> drinkRemedy(player, held);
            case "skyroot_poison_bucket" -> drinkPoison(player, held);
            case "ambrosium_shard" -> eatShards(player, held, 1.0f);
            case "white_apple" -> {
                eatFood(player, held, 0, 0.0f, true, List.of());
                drinkRemedyNoConsume(player); // Remedy 15s: cures afflictions
            }
            case "healing_stone" -> eatFood(player, held, 0, 0.0f, true,
                    List.of(new PotionEffect(PotionEffectType.REGENERATION, 610, 0)));
            case "blue_berry" -> eatFood(player, held, 2, 0.3f, false, List.of());
            case "enchanted_berry" -> eatFood(player, held, 6, 0.8f, false, List.of());
            case "candy_cane" -> eatFood(player, held, 2, 0.2f, false, List.of());
            case "gingerbread_man" -> eatFood(player, held, 2, 0.2f, false, List.of());
            case "blue_gummy_swet", "golden_gummy_swet" ->
                    eatFood(player, held, 20, 0.9f, false, List.of());
            default -> {
            }
        }
    }

    private void markProj(Projectile proj, String kind, Player shooter) {
        proj.getPersistentDataContainer().set(projKey, PersistentDataType.STRING, kind);
        if (shooter != null) {
            proj.setShooter(shooter);
        }
    }

    private void shootDart(Player player, ItemStack held, String shooterId) {
        String ammo = shooterId.replace("_shooter", "");
        ItemStack found = null;
        for (ItemStack s : player.getInventory().getContents()) {
            if (s != null && ammo.equals(items.idOf(s))) {
                found = s;
                break;
            }
        }
        if (found == null) {
            return;
        }
        if (!player.getGameMode().name().equals("CREATIVE")) {
            found.setAmount(found.getAmount() - 1);
        }
        Arrow arrow = player.launchProjectile(Arrow.class);
        double damage = ammo.equals("enchanted_dart") ? 1.5 : ammo.equals("poison_dart") ? 0.25 : 0.5;
        arrow.setDamage(damage);
        markProj(arrow, "dart_" + ammo, player);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0f, 1.2f);
    }

    private void throwKnife(Player player, ItemStack held) {
        if (!player.getGameMode().name().equals("CREATIVE")) {
            held.setAmount(held.getAmount() - 1);
        }
        Snowball ball = player.launchProjectile(Snowball.class);
        ball.setVelocity(ball.getVelocity().multiply(1.6));
        markProj(ball, "knife", player);
        player.getWorld().playSound(player.getLocation(), "aether:item.lightning_knife.shoot", 1.0f, 1.0f);
    }

    private void throwHammer(Player player, ItemStack held) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = itemCooldown.get(uuid);
        if (last != null && now - last < 2000) { // ~40 tick cooldown
            return;
        }
        itemCooldown.put(uuid, now);
        if (!player.getGameMode().name().equals("CREATIVE")) {
            damage(held, player, 1);
        }
        Snowball ball = player.launchProjectile(Snowball.class);
        ball.setVelocity(ball.getVelocity().multiply(2.2));
        markProj(ball, "hammer", player);
        player.getWorld().playSound(player.getLocation(),
                "aether:item.hammer_of_kingbdogz.shoot", 1.0f, 1.0f);
    }

    private void summonMinions(Player player, ItemStack held) {
        List<UUID> current = minions.getOrDefault(player.getUniqueId(), new ArrayList<>());
        current.removeIf(uuid -> {
            Entity e = Bukkit.getEntity(uuid);
            return e == null || e.isDead();
        });
        if (current.size() >= 2) {
            return;
        }
        if (!player.getGameMode().name().equals("CREATIVE")) {
            damage(held, player, 1);
        }
        World world = player.getWorld();
        current.clear();
        for (int i = 0; i < 2; i++) {
            var minion = plugin.mobs().spawnMob(world,
                    player.getLocation().add(i == 0 ? 1 : -1, 1, 0), "cloud_minion", false);
            if (minion != null) {
                current.add(minion.getUniqueId());
            }
        }
        minions.put(player.getUniqueId(), current);
        world.spawnParticle(org.bukkit.Particle.CLOUD, player.getLocation(), 20, 1, 1, 1, 0.1);
    }

    private void hatchEgg(Player player, ItemStack held, PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.WATER) {
            return;
        }
        var moa = plugin.mobs().spawnMob(clicked.getWorld(),
                clicked.getLocation().add(0.5, 1.0, 0.5), "moa", false);
        if (moa != null) {
            String eggId = items.idOf(held);
            String type = eggId != null && eggId.startsWith("white_") ? "white"
                    : eggId != null && eggId.startsWith("black_") ? "black" : "blue";
            moa.getPersistentDataContainer().set(new NamespacedKey(plugin, "moa_type"),
                    PersistentDataType.STRING, type);
            if (!player.getGameMode().name().equals("CREATIVE")) {
                held.setAmount(held.getAmount() - 1);
            }
        }
        event.setCancelled(true);
    }

    private void deployParachute(Player player, ItemStack held) {
        if (parachuteGrounded(player) || player.isInWater() || player.isSneaking()) {
            return;
        }
        if (parachuting.contains(player.getUniqueId())) return;
        held=player.getInventory().getItemInMainHand();
        String canopyType=items.idOf(held);
        if(held.getData(io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE)==null)
            held.setData(io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE,0);
        held=held.damage(1,player);
        player.getInventory().setItemInMainHand(held);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 40, 0));
        parachuting.add(player.getUniqueId());
        parachuteTypes.put(player.getUniqueId(),canopyType);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_WOOL_PLACE,
                0.6f, 1.0f);
    }

    private void tickParachutes() {
        for(UUID id:List.copyOf(parachuting)) {
            Player p=Bukkit.getPlayer(id);
            if(p==null || p.isDead() || parachuteGrounded(p) || p.isInWater() || p.isSneaking()) {
                parachuting.remove(id);
                if(p!=null)p.removePotionEffect(PotionEffectType.SLOW_FALLING);
                continue;
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,40,0,false,false,false));
            p.setFallDistance(0);
            UUID visualId=parachuteVisuals.get(id);
            Entity visual=visualId==null?null:Bukkit.getEntity(visualId);
            if(visual!=null && !visual.getWorld().equals(p.getWorld())){visual.remove();visual=null;}
            if(visual==null) {
                var canopy=p.getWorld().spawn(p.getLocation().add(0,2.2,0),org.bukkit.entity.BlockDisplay.class,d->{
                    String cloud="golden_parachute".equals(parachuteTypes.get(id))?"golden_aercloud":"cold_aercloud";
                    d.setBlock(Bukkit.createBlockData(content.block(cloud).carrier()));d.setPersistent(false);d.setTeleportDuration(1);
                    d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(-1.5f,0,-1.5f),new org.joml.Quaternionf(),new org.joml.Vector3f(3,.15f,3),new org.joml.Quaternionf()));
                });parachuteVisuals.put(id,canopy.getUniqueId());visual=canopy;
            }
            visual.teleport(p.getLocation().add(0,2.2,0));
        }
        for(UUID id:List.copyOf(parachuteVisuals.keySet()))if(!parachuting.contains(id)){
            Entity d=Bukkit.getEntity(parachuteVisuals.remove(id));if(d!=null)d.remove();parachuteTypes.remove(id);
        }
    }

    private boolean parachuteGrounded(Player player) {
        return player.isOnGround() && !player.getLocation().subtract(0,.05,0).getBlock().isPassable();
    }

    private static final Map<String, String> SWET_CONVERT = Map.of(
            "aether_dirt", "aether_grass_block",
            "dirt", "grass_block",
            "grass_block", "podzol",
            "netherrack", "crimson_nylium");

    private void convertBlock(Player player, ItemStack held, Block clicked) {
        if (clicked == null) {
            return;
        }
        String target;
        if (clicked.getType() == Material.NOTE_BLOCK) {
            String blockId = content.blockIdForCarrier(clicked.getBlockData().getAsString());
            target = blockId == null ? null : SWET_CONVERT.get(blockId);
        } else {
            target = SWET_CONVERT.get(clicked.getType().name().toLowerCase());
        }
        if (target == null) {
            return;
        }
        var def = content.blocks().containsKey(target) ? content.block(target) : null;
        try {
            if (def != null) {
                clicked.setBlockData(Bukkit.createBlockData(def.carrier()), false);
            } else {
                clicked.setType(Material.matchMaterial(target.toUpperCase()), false);
            }
        } catch (Exception e) {
            return;
        }
        if (!player.getGameMode().name().equals("CREATIVE")) {
            held.setAmount(held.getAmount() - 1);
        }
        player.getWorld().playSound(clicked.getLocation(), "aether:item.swet_ball.use", 0.8f, 1.0f);
    }

    private void useLifeShard(Player player, ItemStack held) {
        int count = player.getPersistentDataContainer().getOrDefault(
                new NamespacedKey(plugin, "life_shards"), PersistentDataType.INTEGER, 0);
        if (count >= 10) { // original limit
            return;
        }
        try {
            Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
            AttributeInstance inst = attr == null ? null : player.getAttribute(attr);
            if (inst != null) {
                inst.setBaseValue(inst.getBaseValue() + 2.0); // +1 heart, permanent
            }
        } catch (Throwable ignored) {
            return;
        }
        player.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "life_shards"), PersistentDataType.INTEGER, count + 1);
        player.heal(2.0);
        if (!player.getGameMode().name().equals("CREATIVE")) {
            held.setAmount(held.getAmount() - 1);
        }
    }

    private void placeSkyrootWater(Player player, ItemStack held, PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block target = clicked.getRelative(event.getBlockFace());
        if (!target.getType().isAir()) {
            return;
        }
        target.setType(Material.WATER, true);
        if (!player.getGameMode().name().equals("CREATIVE")) {
            ItemStack empty = items.create("skyroot_bucket", 1);
            if (held.getAmount() <= 1) {
                player.getInventory().setItemInMainHand(empty);
            } else {
                held.setAmount(held.getAmount() - 1);
                player.getInventory().addItem(empty);
            }
        }
        event.setCancelled(true);
    }

    private void drinkRemedy(Player player, ItemStack held) {
        player.removePotionEffect(PotionEffectType.NAUSEA);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
        player.removePotionEffect(PotionEffectType.POISON);
        consumeDrink(player, held, "skyroot_bucket");
    }

    private void drinkPoison(Player player, ItemStack held) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 600, 0)); // inebriation
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 300, 0));
        consumeDrink(player, held, "skyroot_bucket");
    }

    private void consumeDrink(Player player, ItemStack held, String emptyId) {
        if (!player.getGameMode().name().equals("CREATIVE")) {
            ItemStack empty = items.create(emptyId, 1);
            if (held.getAmount() <= 1) {
                player.getInventory().setItemInMainHand(empty);
            } else {
                held.setAmount(held.getAmount() - 1);
                player.getInventory().addItem(empty);
            }
        }
    }

    private void eatShards(Player player, ItemStack held, float heal) {
        player.heal(heal);
        if (!player.getGameMode().name().equals("CREATIVE")) {
            held.setAmount(held.getAmount() - 1);
        }
    }

    private static final Map<String, List<ItemStack>> LORE_BOOKS = new ConcurrentHashMap<>();

    /** Opens the real Book of Lore: a written book baked from the mod's lore entries. */
    private void openLore(Player player, ItemStack held) {
        plugin.loreMenu().open(player);
    }

    private void eatFood(Player player, ItemStack held, int hunger, float saturation,
                         boolean alwaysEdible, List<PotionEffect> effects) {
        if (player.getFoodLevel() >= 20 && !alwaysEdible) {
            return;
        }
        player.setFoodLevel(Math.min(20, player.getFoodLevel() + hunger));
        player.setSaturation(Math.min(player.getFoodLevel(),
                player.getSaturation() + saturation * 2.0f));
        for (PotionEffect effect : effects) {
            player.addPotionEffect(effect);
        }
        if (!player.getGameMode().name().equals("CREATIVE")) {
            held.setAmount(held.getAmount() - 1);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.5f, 1.0f);
    }

    private void drinkRemedyNoConsume(Player player) {
        player.removePotionEffect(PotionEffectType.NAUSEA);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
        player.removePotionEffect(PotionEffectType.POISON);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile proj = event.getEntity();
        String kind = proj.getPersistentDataContainer().get(projKey, PersistentDataType.STRING);
        if (kind == null) {
            return;
        }
        Entity shooter = proj.getShooter() instanceof Entity e ? e : null;
        switch (kind) {
            case "knife" -> {
                proj.getWorld().strikeLightning(proj.getLocation());
                proj.remove();
            }
            case "hammer" -> {
                if (event.getHitEntity() instanceof LivingEntity living
                        && (shooter == null || !living.equals(shooter))) {
                    living.damage(7.0, shooter instanceof LivingEntity l ? l : null); // thrown damage
                }
                proj.remove();
            }
            default -> {
                if (kind.startsWith("dart_") && event.getHitEntity() instanceof LivingEntity living) {
                    String ammo = kind.substring(5);
                    if (ammo.equals("poison_dart")) {
                        living.addPotionEffect(
                                new PotionEffect(PotionEffectType.NAUSEA, 200, 0)); // inebriation
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onJukebox(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.JUKEBOX) {
            return;
        }
        if (!(clicked.getState(false) instanceof org.bukkit.block.Jukebox jukebox)) {
            return;
        }
        Player player = event.getPlayer();
        String disc = items.idOf(event.getItem());
        if (disc != null && songs.containsKey(disc)) {
            if (jukebox.hasRecord()) {
                return;
            }
            event.setCancelled(true);
            ItemStack held = event.getItem();
            ItemStack record = held.clone();
            record.setAmount(1);
            jukebox.setRecord(record);
            jukebox.update(true, false);
            if (!player.getGameMode().name().equals("CREATIVE")) {
                held.setAmount(held.getAmount() - 1);
            }
            Song song = songs.get(disc);
            String key = jukeKey(clicked);
            stopJukebox(clicked, null);
            clicked.getWorld().playSound(clicked.getLocation(), song.sound(),
                    org.bukkit.SoundCategory.RECORDS, 1.0f, 1.0f);
            int task = Bukkit.getScheduler().runTaskLater(plugin,
                    () -> stopJukebox(clicked, song.sound()),
                    (long) (song.length() * 20)).getTaskId();
            jukeboxTasks.put(key, task);
            return;
        }
        if ((event.getItem() == null || event.getItem().getType().isAir())
                && jukebox.hasRecord()
                && songs.containsKey(items.idOf(jukebox.getRecord()))) {
            // eject our record
            event.setCancelled(true);
            ItemStack record = jukebox.getRecord();
            jukebox.setRecord(null);
            jukebox.update(true, false);
            String sound = songs.get(items.idOf(record)).sound();
            stopJukebox(clicked, sound);
            player.getWorld().dropItemNaturally(player.getLocation(), record);
        }
    }

    private static String jukeKey(Block block) {
        return block.getWorld().getKey() + "|" + block.getX() + "," + block.getY()
                + "," + block.getZ();
    }

    private void stopJukebox(Block block, String sound) {
        Integer task = jukeboxTasks.remove(jukeKey(block));
        if (task != null) {
            Bukkit.getScheduler().cancelTask(task);
        }
        if (sound != null) {
            for (Player p : block.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(block.getLocation()) < 64 * 64) {
                    try {
                        p.stopSound(sound);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJukeboxBreak(org.bukkit.event.block.BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.JUKEBOX) {
            return;
        }
        if (block.getState(false) instanceof org.bukkit.block.Jukebox jukebox
                && songs.containsKey(items.idOf(jukebox.getRecord()))) {
            stopJukebox(block, songs.get(items.idOf(jukebox.getRecord())).sound());
        }
    }

    // ------------------------------------------------------- headless self-test

    /** Verifies data wiring and projectile marking without a player present. */
    public void abilitySelfTest() {
        if (!plugin.getConfig().getBoolean("debug.ability-test", false)) {
            return;
        }
        int fails = 0;
        for (Map.Entry<String, String> e : SWET_CONVERT.entrySet()) {
            boolean ok = content.blocks().containsKey(e.getKey())
                    || Material.matchMaterial(e.getKey().toUpperCase()) != null;
            String target = e.getValue();
            boolean tok = content.blocks().containsKey(target)
                    || Material.matchMaterial(target.toUpperCase()) != null;
            if (!ok || !tok) {
                plugin.getLogger().info("  ability self-test convert: " + e.getKey());
                fails++;
            }
        }
        World world = plugin.mainWorld();
        if (world != null) {
            Location at = new Location(world, 60.5, 210, 80.5);
            world.getChunkAt(at).load(true);
            Snowball ball = world.spawn(at, Snowball.class);
            markProj(ball, "knife", null);
            String back = ball.getPersistentDataContainer().get(projKey, PersistentDataType.STRING);
            if (!"knife".equals(back)) {
                plugin.getLogger().info("  ability self-test proj");
                fails++;
            }
            ball.remove();
        }
        if (UNDEAD.isEmpty() || PIGS.isEmpty()) {
            fails++;
        }
        plugin.getLogger().info("Ability self-test: " + (fails == 0 ? "PASS" : "FAIL-" + fails));
    }

    // ------------------------------------------------------- fall and armor tick

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (parachuting.remove(player.getUniqueId())) {
            event.setCancelled(true); // parachute landing
            return;
        }
        if (fullSet(player, "gravitite_")) {
            event.setCancelled(true); // gravitite negates fall damage
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking() || player.isOnGround()) {
            return;
        }
        if (!fullSet(player, "gravitite_")) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = gravititeJumpCd.get(player.getUniqueId());
        if (last != null && now - last < 1000) {
            return;
        }
        gravititeJumpCd.put(player.getUniqueId(), now);
        player.setVelocity(player.getVelocity().setY(0.8)); // mid-air jump boost
        player.setFallDistance(0);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.5f, 1.2f);
    }

    /** Periodic armor-set effects (phoenix/neptune/valkyrie). */
    public void tickArmor() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (fullSet(player, "phoenix_")) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 220, 0,
                        false, false, false));
                if (player.getFireTicks() > 0) {
                    player.setFireTicks(0);
                }
            }
            if (fullSet(player, "neptune_")) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 220, 0,
                        false, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 220, 0,
                        false, false, false));
            }
            tickValkyrieFlight(player);
        }
    }

    private void tickValkyrieFlight(Player player) {
        UUID uuid = player.getUniqueId();
        if (player.getGameMode().name().equals("CREATIVE")
                || player.getGameMode().name().equals("SPECTATOR")) {
            return;
        }
        if (!fullSet(player, "valkyrie_")) {
            if (player.getAllowFlight() && flightTicks.containsKey(uuid)) {
                player.setAllowFlight(false);
            }
            flightTicks.remove(uuid);
            return;
        }        if (player.isOnGround()) {
            flightTicks.remove(uuid);
            if (player.getAllowFlight()) {
                player.setAllowFlight(false);
            }
            return;
        }
        int t = flightTicks.getOrDefault(uuid, 0) + 1;
        if (t > 140) { // ~7s flight limit, recharges on landing
            player.setAllowFlight(false);
            return;
        }
        flightTicks.put(uuid, t);
        if (!player.getAllowFlight()) {
            player.setAllowFlight(true); // hold jump to ascend
        }
    }

    private boolean fullSet(Player player, String prefix) {
        var inv = player.getInventory().getArmorContents();
        String[] slots = {"boots", "leggings", "chestplate", "helmet"};
        for (int i = 0; i < 4; i++) {
            String id = items.idOf(inv[i]);
            if (id == null || !id.equals(prefix + slots[i])) {
                return false;
            }
        }
        return true;
    }
}
