package com.aetherport.aether.dungeon;

import com.aetherport.aether.AetherPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Boss mechanics: Slider slams (pickaxe-only damage), Valkyrie Queen teleports
 * and thunder crystals, Sun Spirit crystal volleys, fire minions and cold-only
 * damage (reflected ice crystals).
 */
public final class BossFight implements Listener {

    private record Fight(String bossId, String dungeonId, LivingEntity boss, long started,
                         Map<String, Long> cooldowns, Set<UUID> minions, int volley) {
    }

    private final AetherPlugin plugin;
    private final NamespacedKey projKey;
    private final Map<UUID, Fight> fights = new HashMap<>();

    public BossFight(AetherPlugin plugin) {
        this.plugin = plugin;
        this.projKey = new NamespacedKey(plugin, "proj");
    }

    public void register(LivingEntity boss, String dungeonId) {
        String id = plugin.mobs().mobOf(boss);
        if (id == null) {
            return;
        }
        fights.put(boss.getUniqueId(),
                new Fight(id, dungeonId, boss, System.currentTimeMillis(),
                        new HashMap<>(), new HashSet<>(), 0));
    }

    public void unregister(UUID boss) {
        fights.remove(boss);
    }

    private boolean ready(Fight fight, String key, long ms) {
        long now = System.currentTimeMillis();
        Long last = fight.cooldowns().get(key);
        if (last != null && now - last < ms) {
            return false;
        }
        fight.cooldowns().put(key, now);
        return true;
    }

    // ------------------------------------------------------------------- gating

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        String id = plugin.mobs().mobOf(target);
        if (id == null) {
            return;
        }
        if (id.equals("slider") && event.getDamager() instanceof LivingEntity attacker) {
            ItemStack held = attacker.getEquipment() != null
                    ? attacker.getEquipment().getItemInMainHand() : null;
            String tool = held == null ? null : plugin.aetherItems().idOf(held);
            boolean pickaxe = held != null && (held.getType().name().endsWith("_PICKAXE")
                    || (tool != null && tool.endsWith("_pickaxe")));
            if (!pickaxe) {
                event.setCancelled(true); // only pickaxes harm the Slider
            }
            return;
        }
        if (id.equals("sun_spirit")) {
            boolean cold = event.getDamager() instanceof Projectile proj
                    && "ice-reflected".equals(proj.getPersistentDataContainer()
                            .get(projKey, PersistentDataType.STRING));
            if (!cold) {
                event.setCancelled(true); // only reflected ice crystals harm it
            }
        }
    }

    // -------------------------------------------------------------------- tick

    public void tick() {
        if (!plugin.getConfig().getBoolean("dungeons.enabled",false)) return;
        for (Fight fight : new HashMap<>(fights).values()) {
            LivingEntity boss = fight.boss();
            if (!boss.isValid() || boss.isDead()) {
                continue;
            }
            Player target = nearestPlayer(boss);
            if (target == null) {
                continue;
            }
            switch (fight.bossId()) {
                case "slider" -> tickSlider(fight, target);
                case "valkyrie_queen" -> tickQueen(fight, target);
                case "sun_spirit" -> tickSun(fight, target);
                default -> {
                }
            }
        }
    }

    private static Player nearestPlayer(LivingEntity boss) {
        Player best = null;
        double bestD = 48 * 48;
        for (Player p : boss.getWorld().getPlayers()) {
            if (p.getGameMode().name().equals("SPECTATOR")) {
                continue;
            }
            double d = p.getLocation().distanceSquared(boss.getLocation());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private void tickSlider(Fight fight, Player target) {
        LivingEntity boss = fight.boss();
        if (boss.isOnGround() && ready(fight, "slam", 7000)
                && boss.getLocation().distanceSquared(target.getLocation()) < 12 * 12) {
            boss.setVelocity(new Vector(0, 1.1, 0)); // leap...
        }
        if (!boss.isOnGround() && boss.getVelocity().getY() < -0.5
                && ready(fight, "land", 7000)) {
            slam(boss);
        }
    }

    private void slam(LivingEntity boss) {
        Location at = boss.getLocation();
        World world = at.getWorld();
        world.spawnParticle(Particle.EXPLOSION, at, 12, 2, 1, 2, 0.1);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.6f);
        for (Entity e : world.getNearbyEntities(at, 4, 3, 4)) {
            if (e instanceof Player p) {
                p.damage(14.0, boss); // crush
            }
        }
        // smash soft natural blocks, never dungeon stone
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    var block = at.clone().add(dx, dy, dz).getBlock();
                    if (block.getType() != Material.NOTE_BLOCK) {
                        continue;
                    }
                    String id = plugin.content().blockIdForCarrier(
                            block.getBlockData().getAsString());
                    if (id != null && (id.contains("dirt") || id.contains("grass")
                            || id.contains("leaves") || id.contains("quicksoil"))) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private void tickQueen(Fight fight, Player target) {
        LivingEntity boss = fight.boss();
        if (ready(fight, "teleport", 6000) && (boss.getLocation()
                .distanceSquared(target.getLocation()) > 10 * 10
                || java.util.concurrent.ThreadLocalRandom.current().nextInt(3) == 0)) {
            Location to = target.getLocation().add(
                    java.util.concurrent.ThreadLocalRandom.current().nextInt(7) - 3, 1,
                    java.util.concurrent.ThreadLocalRandom.current().nextInt(7) - 3);
            if (to.getBlock().isEmpty()) {
                boss.teleport(to);
                boss.getWorld().spawnParticle(Particle.PORTAL, to, 20, 1, 1, 1, 0.2);
            }
        }
        if (ready(fight, "crystal", 5000)) {
            throwCrystal(boss, target, "thunder", 6.0);
        }
    }

    private void tickSun(Fight fight, Player target) {
        LivingEntity boss = fight.boss();
        if (ready(fight, "volley", 2500)) { // crystal every ~50 ticks
            String[] kinds = {"fire", "ice", "thunder"};
            String kind = kinds[fight.volley() % kinds.length];
            fights.put(boss.getUniqueId(), new Fight(fight.bossId(), fight.dungeonId(), boss,
                    fight.started(), fight.cooldowns(), fight.minions(), fight.volley() + 1));
            throwCrystal(boss, target, kind, 6.0);
        }
        if (ready(fight, "ignite", 1750)) { // fire burst every ~35 ticks
            boss.getWorld().spawnParticle(Particle.FLAME, boss.getLocation().add(0, 1, 0),
                    24, 2, 2, 2, 0.05);
            for (Entity e : boss.getWorld().getNearbyEntities(boss.getLocation(), 3, 3, 3)) {
                if (e instanceof Player p) {
                    p.setFireTicks(60);
                }
            }
        }
    }

    /** Fire minions answer when the Sun Spirit is hurt. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSunHurt(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity boss)) {
            return;
        }
        if (!"sun_spirit".equals(plugin.mobs().mobOf(boss))) {
            return;
        }
        Fight fight = fights.get(boss.getUniqueId());
        if (fight == null) {
            return;
        }
        fight.minions().removeIf(uuid -> {
            Entity e = Bukkit.getEntity(uuid);
            return e == null || e.isDead();
        });
        if (fight.minions().size() >= 3 || !ready(fight, "minion", 10000)) {
            return;
        }
        Entity attacker = event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Entity s ? s
                : event.getDamager() instanceof LivingEntity l ? l : null;
        var minion = plugin.mobs().spawnMob(boss.getWorld(),
                boss.getLocation().add(1, 1, 0), "fire_minion", false);
        if (minion != null) {
            if (attacker instanceof LivingEntity target && minion instanceof org.bukkit.entity.Mob mob) {
                try {
                    mob.setTarget(target);
                } catch (Throwable ignored) {
                }
            }
            fight.minions().add(minion.getUniqueId());
        }
    }

    // ---------------------------------------------------------------- crystals

    public void throwCrystal(LivingEntity shooter, LivingEntity target, String kind, double damage) {
        Snowball ball = shooter.getWorld().spawn(shooter.getEyeLocation(), Snowball.class);
        ball.setShooter(shooter);
        Vector dir = target.getEyeLocation().subtract(shooter.getEyeLocation()).toVector()
                .normalize().multiply(1.4);
        ball.setVelocity(dir);
        ball.getPersistentDataContainer().set(projKey, PersistentDataType.STRING, kind);
        if (shooter instanceof Player p) {
            ball.setShooter(p);
        }
    }

    /** Valkyrie mobs hurl thunder crystals at their targets. */
    public void minionThrows() {
        if (!plugin.getConfig().getBoolean("dungeons.enabled",false)) return;
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (!(e instanceof LivingEntity living)) {
                    continue;
                }
                String id = plugin.mobs().mobOf(living);
                if (!"valkyrie".equals(id) && !"valkyrie_queen".equals(id)) {
                    continue;
                }
                if (!(living instanceof org.bukkit.entity.Mob mob)
                        || !(mob.getTarget() instanceof LivingEntity target)) {
                    continue;
                }
                if (living.getTicksLived() % 140 != 0) {
                    continue;
                }
                if (!living.hasLineOfSight(target) || living.getLocation().distanceSquared(target.getLocation()) > 16 * 16) {
                    continue;
                }
                throwCrystal(living, target, "thunder", 6.0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrystalDeflect(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Snowball ball)
                || !(event.getDamager() instanceof Player player)) {
            return;
        }
        String kind = ball.getPersistentDataContainer().get(projKey, PersistentDataType.STRING);
        if (kind == null || (!kind.equals("ice") && !kind.equals("fire")
                && !kind.equals("thunder"))) {
            return;
        }
        event.setCancelled(true); // bat it back, ghast-style
        LivingEntity boss = nearestBoss(ball, 48);
        Vector dir;
        if (boss != null) {
            dir = boss.getEyeLocation().subtract(ball.getLocation()).toVector().normalize()
                    .multiply(1.8);
            ball.getPersistentDataContainer().set(projKey, PersistentDataType.STRING,
                    kind + "-reflected");
        } else {
            dir = ball.getVelocity().multiply(-1.2);
        }
        ball.setVelocity(dir);
        if (player.getWorld() != null) {
            player.getWorld().playSound(ball.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.7f, 1.4f);
        }
    }

    private LivingEntity nearestBoss(Snowball ball, double radius) {
        LivingEntity best = null;
        double bestD = radius * radius;
        for (Fight fight : fights.values()) {
            LivingEntity boss = fight.boss();
            if (!boss.isValid() || boss.isDead()
                    || !boss.getWorld().equals(ball.getWorld())) {
                continue;
            }
            double d = boss.getLocation().distanceSquared(ball.getLocation());
            if (d < bestD) {
                bestD = d;
                best = boss;
            }
        }
        return best;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrystalHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball ball)) {
            return;
        }
        String kind = ball.getPersistentDataContainer().get(projKey, PersistentDataType.STRING);
        if (kind == null) {
            return;
        }
        boolean reflected = kind.endsWith("-reflected");
        String base = reflected ? kind.substring(0, kind.length() - 10) : kind;
        if (event.getHitEntity() instanceof LivingEntity living) {
            String mobId = plugin.mobs().mobOf(living);
            boolean isBoss = mobId != null && (mobId.equals("slider")
                    || mobId.equals("valkyrie_queen") || mobId.equals("sun_spirit"));
            if (isBoss) {
                if (reflected && base.equals("ice")) {
                    living.damage(8.0); // cold rebuke
                }
                ball.remove();
                return;
            }
            switch (base) {
                case "fire" -> {
                    living.setFireTicks(80);
                    living.damage(6.0);
                }
                case "ice" -> {
                    living.damage(5.0);
                    living.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.SLOWNESS, 100, 1));
                }
                case "thunder" -> {
                    living.getWorld().strikeLightningEffect(living.getLocation());
                    living.damage(6.0, ball.getShooter() instanceof Entity source ? source : null);
                }
                default -> {
                }
            }
            ball.remove();
        } else if (event.getHitBlock() != null && (base.equals("fire") || base.equals("thunder"))) {
            if (base.equals("thunder")) {
                ball.getWorld().strikeLightningEffect(ball.getLocation());
            }
            ball.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        String id = plugin.mobs().mobOf(event.getEntity());
        if (id == null || (!id.equals("slider") && !id.equals("valkyrie_queen")
                && !id.equals("sun_spirit"))) {
            return;
        }
        plugin.dungeons().onBossDeath(event.getEntity().getUniqueId());
    }

    // ------------------------------------------------------- headless self-test

    /** Simulates slider immunity gating and crystal reflection without players. */
    public void bossSelfTest() {
        if (!plugin.getConfig().getBoolean("debug.boss-test", false)) {
            return;
        }
        World world = plugin.aetherWorld();
        if (world == null) {
            world = plugin.mainWorld();
        }
        if (world == null) {
            return;
        }
        int fails = 0;
        Location at = new Location(world, 120.5, 210, 120.5);
        world.getChunkAt(at).load(true);
        var slider = plugin.mobs().spawnMob(world, at, "slider", false);
        if (slider == null) {
            plugin.getLogger().info("  boss self-test spawn");
            fails++;
        } else {
            fights.put(slider.getUniqueId(), new Fight("slider", "test", slider,
                    System.currentTimeMillis(), new HashMap<>(), new HashSet<>(), 0));
            Snowball ice = world.spawn(at.clone().add(2, 1, 0), Snowball.class);
            ice.getPersistentDataContainer().set(projKey, PersistentDataType.STRING, "ice");
            // deflect path: mark reflected, then hit the boss
            ice.getPersistentDataContainer().set(projKey, PersistentDataType.STRING,
                    "ice-reflected");
            double before = slider.getHealth();
            var hit = new ProjectileHitEvent(ice, slider, null);
            Bukkit.getPluginManager().callEvent(hit);
            if (slider.getHealth() >= before) {
                plugin.getLogger().info("  boss self-test cold damage");
                fails++;
            }
            slider.remove();
            ice.remove();
            fights.remove(slider.getUniqueId());
        }
        plugin.getLogger().info("Boss self-test: " + (fails == 0 ? "PASS" : "FAIL-" + fails));
    }
}
