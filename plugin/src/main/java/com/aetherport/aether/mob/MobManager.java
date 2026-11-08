package com.aetherport.aether.mob;

import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.item.AetherItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aether mobs on vanilla carrier entities: data-driven stats, drops and spawns
 * from the original mod data (see tools/gen_mobs.py).
 */
public final class MobManager implements Listener {

    public record DropEntry(String item, int min, int max, int weight) {
    }

    public record DropPool(int rollsMin, int rollsMax, List<DropEntry> entries) {
    }

    public record SpawnRule(String biome, String category, int weight, int min, int max) {
    }

    public record MobDef(String id, EntityType carrier, boolean boss,
                         Map<String, Double> attributes, List<DropPool> drops,
                         List<SpawnRule> spawns, String langEn, String langRu) {
    }

    private static final Set<String> PASSIVE = Set.of(
            "phyg", "flying_cow", "sheepuff", "moa", "aerbunny",
            "aerwhale", "whirlwind", "cloud_minion", "aechor_plant");

    private final AetherPlugin plugin;
    private final AetherItems items;
    private final NamespacedKey mobKey;
    private final Map<String, MobDef> defs = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Set<String> soundEvents = new HashSet<>();
    private final NamespacedKey moaSpeedVersion=new NamespacedKey("aether","moa_speed_v2");

    public MobManager(AetherPlugin plugin, AetherItems items) {
        this.plugin = plugin;
        this.items = items;
        this.mobKey = new NamespacedKey(plugin, "mob");
    }

    public NamespacedKey mobKey() {
        return mobKey;
    }

    public Map<String, MobDef> defs() {
        return defs;
    }

    public String mobOf(Entity entity) {
        if (entity == null) {
            return null;
        }
        return entity.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
    }

    public void load() {
        try (InputStream sounds = getClass().getResourceAsStream("/content/sound_events.json")) {
            if (sounds != null) for (JsonElement sound : JsonParser.parseReader(
                    new InputStreamReader(sounds, StandardCharsets.UTF_8)).getAsJsonArray()) soundEvents.add(sound.getAsString());
        } catch (Exception e) { plugin.getLogger().warning("Cannot load mob sounds: " + e); }
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (World world : Bukkit.getWorlds()) for (LivingEntity mob : world.getLivingEntities()) {
                String id = mobOf(mob);
                if (id != null && java.util.concurrent.ThreadLocalRandom.current().nextInt(8) == 0) playMobSound(mob,id,"ambient");
            }
        },20,20);
        try (InputStream in = getClass().getResourceAsStream("/content/mobs.json")) {
            if (in == null) {
                plugin.getLogger().warning("Missing content resource /content/mobs.json");
                return;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                EntityType carrier;
                try {
                    carrier = EntityType.valueOf(o.get("carrier").getAsString());
                } catch (Exception ex) {
                    continue;
                }
                Map<String, Double> attrs = new HashMap<>();
                for (Map.Entry<String, JsonElement> a : o.getAsJsonObject("attributes").entrySet()) {
                    attrs.put(a.getKey(), a.getValue().getAsDouble());
                }
                List<DropPool> drops = new ArrayList<>();
                for (JsonElement d : o.getAsJsonArray("drops")) {
                    JsonObject pool = d.getAsJsonObject();
                    List<DropEntry> entries = new ArrayList<>();
                    for (JsonElement en : pool.getAsJsonArray("entries")) {
                        JsonObject eo = en.getAsJsonObject();
                        entries.add(new DropEntry(eo.get("item").getAsString(),
                                eo.get("min").getAsInt(), eo.get("max").getAsInt(),
                                eo.has("weight") ? eo.get("weight").getAsInt() : 1));
                    }
                    drops.add(new DropPool(pool.get("rolls_min").getAsInt(),
                            pool.get("rolls_max").getAsInt(), entries));
                }
                List<SpawnRule> spawns = new ArrayList<>();
                for (JsonElement s : o.getAsJsonArray("spawns")) {
                    JsonObject sob = s.getAsJsonObject();
                    spawns.add(new SpawnRule(sob.get("biome").getAsString(),
                            sob.get("category").getAsString(), sob.get("weight").getAsInt(),
                            sob.get("min").getAsInt(), sob.get("max").getAsInt()));
                }
                defs.put(e.getKey(), new MobDef(e.getKey(), carrier,
                        o.get("boss").getAsBoolean(), attrs, drops, spawns,
                        str(o, "lang_en"), str(o, "lang_ru")));
            }
            plugin.getLogger().info("Mobs loaded: " + defs.size());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load mobs.json: " + e);
            StackTraceElement[] st = e.getStackTrace();
            for (int i = 0; i < Math.min(8, st.length); i++) {
                plugin.getLogger().warning("  ST " + st[i]);
            }
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    public LivingEntity spawnMob(World world, Location loc, String id, boolean natural) {
        if (!plugin.getConfig().getBoolean("dungeons.enabled",false) && java.util.Set.of("slider","valkyrie","valkyrie_queen","sun_spirit","fire_minion","sentry","mimic").contains(id)) return null;
        MobDef def = defs.get(id);
        if (def == null) {
            return null;
        }
        Entity entity = world.spawnEntity(loc, def.carrier(),
                natural ? CreatureSpawnEvent.SpawnReason.NATURAL : CreatureSpawnEvent.SpawnReason.CUSTOM);
        if (!(entity instanceof LivingEntity living)) {
            entity.remove();
            return null;
        }
        living.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, id);
        living.setSilent(true);
        if ("cockatrice".equals(id) && living.getEquipment() != null) living.getEquipment().clear();
        for (Map.Entry<String, Double> a : def.attributes().entrySet()) {
            try {
                Attribute attr = Registry.ATTRIBUTE.get(
                        NamespacedKey.minecraft(a.getKey().toLowerCase()));
                if (attr == null) {
                    continue;
                }
                AttributeInstance inst = living.getAttribute(attr);
                if (inst == null) {
                    continue;
                }
                inst.setBaseValue(a.getValue());
            } catch (Throwable ignored) {
            }
        }
        Double max = def.attributes().get("MAX_HEALTH");
        updateMoaSpeed(living,id);
        if (max != null) {
            living.setHealth(max);
        }
        if (PASSIVE.contains(id) && living instanceof Mob mob) {
            Bukkit.getMobGoals().removeAllGoals(mob, com.destroystokyo.paper.entity.ai.GoalType.TARGET);
        }
        if (def.boss()) {
            living.setRemoveWhenFarAway(false);
            String name = def.langEn().isEmpty() ? id : def.langEn();
            living.customName(Component.text(name));
            living.setCustomNameVisible(true);
            BossBar bar = Bukkit.createBossBar(name, BarColor.RED, BarStyle.SEGMENTED_20);
            bossBars.put(living.getUniqueId(), bar);
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(loc) < 48 * 48) {
                    bar.addPlayer(p);
                }
            }
            if (plugin.bossFights() != null) {
                plugin.bossFights().register(living, "wild");
            }
        }
        if (plugin.mobModels() != null) {
            try {
                plugin.mobModels().attach(living, id);
            } catch (Throwable t) {
                plugin.getLogger().warning("Display attach failed for " + id + ": " + t);
            }
        }
        return living;
    }

    // ------------------------------------------------------------ natural spawn

    public LivingEntity migrateCarrier(LivingEntity old, String id) {
        updateMoaSpeed(old,id);
        MobDef def = defs.get(id);
        if (def == null || old.getType() == def.carrier()) return old;
        double health = old.getHealth();
        LivingEntity replacement = spawnMob(old.getWorld(), old.getLocation(), id, false);
        if (replacement == null) return old;
        replacement.setHealth(Math.min(health, replacement.getAttribute(Attribute.MAX_HEALTH).getValue()));
        replacement.setVelocity(old.getVelocity());
        old.remove();
        return replacement;
    }

    private void updateMoaSpeed(LivingEntity mob,String id) {
        if(!"moa".equals(id) || mob.getPersistentDataContainer().has(moaSpeedVersion,PersistentDataType.BYTE))return;
        var speed=mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if(speed!=null)speed.setBaseValue(.2);
        mob.getPersistentDataContainer().set(moaSpeedVersion,PersistentDataType.BYTE,(byte)1);
    }

    public boolean hasCustomSounds(String id) {
        return soundEvents.contains("entity." + id + ".hurt") || soundEvents.contains("entity." + id + ".ambient");
    }

    private void playMobSound(LivingEntity mob, String id, String action) {
        String key = "entity." + id + "." + action;
        if(!soundEvents.contains(key)) {
            String shared=switch(id) {case "valkyrie_queen"->"valkyrie";case "fire_minion"->"sun_spirit";case "blue_swet","golden_swet"->"swet";default->id;};
            key="entity."+shared+"."+action;
        }
        if (soundEvents.contains(key)) mob.getWorld().playSound(mob.getLocation(), "aether:" + key,1,1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobHurt(EntityDamageEvent event) {
        String id = mobOf(event.getEntity());
        if (id != null && event.getEntity() instanceof LivingEntity mob && event.getFinalDamage() > 0
                && event.getFinalDamage() < mob.getHealth()) playMobSound(mob,id,"hurt");
    }

    public void tickSpawns() {
        World aether = plugin.aetherWorld();
        if (aether == null) {
            return;
        }
        Random rng = new Random();
        for (Player player : aether.getPlayers()) {
            int nearby = 0;
            for (Entity e : aether.getNearbyEntities(player.getLocation(), 64, 32, 64)) {
                // Adopt legacy untagged children produced by vanilla slime
                // splitting before the Swet split handler existed.
                if (e.getType()==EntityType.SLIME && mobOf(e)==null && e instanceof LivingEntity legacy) {
                    e.getPersistentDataContainer().set(mobKey,PersistentDataType.STRING,"blue_swet");
                    plugin.mobModels().attach(legacy,"blue_swet");
                }
                MobDef definition=defs.get(mobOf(e));
                if (definition!=null && !definition.boss() && !definition.spawns().isEmpty() && ++nearby >= 14) {
                    break;
                }
            }
            if (nearby >= 14) {
                continue;
            }
            int before=countWild(aether,player.getLocation());
            for (int attempt = 0; attempt < 8; attempt++) {
                trySpawnNear(aether, player, rng);
                if(countWild(aether,player.getLocation())>before) break;
            }
        }
        tickBossBars();
    }

    private int countWild(World world, Location at) {
        int count=0;
        for(Entity e:world.getNearbyEntities(at,64,32,64)) {
            MobDef def=defs.get(mobOf(e));
            if(def!=null && !def.boss() && !def.spawns().isEmpty()) count++;
        }
        return count;
    }

    private int spawnGround(World world,int x,int z) {
        for(int y=world.getHighestBlockYAt(x,z);y>world.getMinHeight();y--) {
            var block=world.getBlockAt(x,y,z);
            String id=plugin.content().blockIdForCarrier(block.getBlockData().getAsString());
            if (java.util.Set.of("aether_grass_block","enchanted_aether_grass_block","aether_dirt","holystone","quicksoil","icestone").contains(id==null?"":id)) return y;
            if(block.isLiquid()) return world.getMinHeight();
        }
        return world.getMinHeight();
    }

    private void trySpawnNear(World world, Player player, Random rng) {
        int x = player.getLocation().getBlockX() + rng.nextInt(97) - 48;
        int z = player.getLocation().getBlockZ() + rng.nextInt(97) - 48;
        if (Math.abs(x - player.getLocation().getBlockX()) < 20
                && Math.abs(z - player.getLocation().getBlockZ()) < 20) {
            return;
        }
        if (!world.isChunkLoaded(x >> 4, z >> 4)) { return; }
        int ground = spawnGround(world,x,z);
        if (ground <= world.getMinHeight() + 2) {
            return;
        }
        String biome = keyStem(world.getBiome(x, ground, z).getKey().toString());
        int pool = rng.nextInt(10);
        String category = pool < 6 ? "creature" : pool < 9 ? "monster" : "ambient";
        List<MobDef> candidates = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (MobDef def : defs.values()) {
            if (def.boss()) {
                continue;
            }
            for (SpawnRule rule : def.spawns()) {
                if (rule.biome().equals(biome) && rule.category().equals(category)) {
                    candidates.add(def);
                    weights.add(rule.weight());
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        int total = weights.stream().mapToInt(Integer::intValue).sum();
        int roll = rng.nextInt(total);
        MobDef picked = candidates.get(0);
        SpawnRule rule = null;
        for (int i = 0; i < candidates.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) {
                picked = candidates.get(i);
                break;
            }
        }
        for (SpawnRule r : picked.spawns()) {
            if (r.biome().equals(biome) && r.category().equals(category)) {
                rule = r;
                break;
            }
        }
        if (rule == null) {
            return;
        }
        boolean flying = picked.id().equals("zephyr") || picked.id().equals("aerwhale")
                || picked.id().equals("whirlwind") || picked.id().equals("evil_whirlwind");
        if ((picked.id().equals("blue_swet") || picked.id().equals("golden_swet"))
                && plugin.workshop() != null
                && plugin.workshop().isSwetRepelled(new Location(world, x, ground + 1, z))) {
            return; // swet banner nearby
        }
        int n = Math.min(Math.max(0,14-countWild(world,player.getLocation())),Math.min(2,rule.min() + rng.nextInt(rule.max() - rule.min() + 1)));
        for (int i = 0; i < n; i++) {
            int sx = x + rng.nextInt(5) - 2;
            int sz = z + rng.nextInt(5) - 2;
            if (!world.isChunkLoaded(sx >> 4, sz >> 4)) { continue; }
            int localGround = spawnGround(world,sx,sz);
            int sy = flying ? Math.max(32, localGround + 8 + rng.nextInt(16)) : localGround + 1;
            if (sy < world.getMinHeight() + 1 || sy > world.getMaxHeight() - 2) {
                continue;
            }
            if (!flying && localGround <= world.getMinHeight() + 2) { continue; }
            if (!world.getBlockAt(sx, sy, sz).isEmpty()
                    || !world.getBlockAt(sx, sy + 1, sz).isEmpty()) {
                continue;
            }
            spawnMob(world, new Location(world, sx + 0.5, sy, sz + 0.5), picked.id(), true);
        }
    }

    private static String keyStem(String key) {
        int i = key.indexOf(':');
        return i < 0 ? key : key.substring(i + 1);
    }

    // ------------------------------------------------------------------- drops

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        String id = mobOf(event.getEntity());
        if (id == null) {
            return;
        }
        playMobSound(event.getEntity(),id,"death");
        if (!plugin.getConfig().getBoolean("dungeons.enabled",false) && java.util.Set.of("slider","valkyrie","valkyrie_queen","sun_spirit","fire_minion","sentry","mimic").contains(id)) return;
        MobDef def = defs.get(id);
        if (def == null) {
            return;
        }
        event.getDrops().clear();
        Random rng = new Random();
        for (DropPool pool : def.drops()) {
            int rolls = pool.rollsMin() + (pool.rollsMax() > pool.rollsMin()
                    ? rng.nextInt(pool.rollsMax() - pool.rollsMin() + 1) : 0);
            int totalWeight = pool.entries().stream().mapToInt(DropEntry::weight).sum();
            for (int r = 0; r < rolls; r++) {
                int pick = totalWeight > 0 ? rng.nextInt(totalWeight) : 0;
                DropEntry chosen = pool.entries().get(0);
                for (DropEntry e : pool.entries()) {
                    pick -= e.weight();
                    if (pick < 0) {
                        chosen = e;
                        break;
                    }
                }
                int count = chosen.min() + (chosen.max() > chosen.min()
                        ? rng.nextInt(chosen.max() - chosen.min() + 1) : 0);
                if (count <= 0) {
                    continue;
                }
                ItemStack out = chosen.item().startsWith("aether:")
                        ? items().create(chosen.item().substring(7), count)
                        : new ItemStack(Material.matchMaterial(chosen.item().substring(10)), count);
                if (out != null && !out.getType().isAir()) {
                    event.getDrops().add(out);
                }
            }
        }
        bossBars.remove(event.getEntity().getUniqueId());
    }

    private AetherItems items() {
        return plugin.aetherItems();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossHurt(EntityDamageEvent event) {
        BossBar bar = bossBars.get(event.getEntity().getUniqueId());
        if (bar != null && event.getEntity() instanceof LivingEntity living) {
            double max = living.getAttribute(Registry.ATTRIBUTE.get(
                    NamespacedKey.minecraft("max_health"))).getBaseValue();
            bar.setProgress(Math.max(0.0, Math.min(1.0, living.getHealth() / max)));
        }
    }

    private void tickBossBars() {
        Set<UUID> dead = new HashSet<>();
        for (Map.Entry<UUID, BossBar> e : bossBars.entrySet()) {
            Entity entity = Bukkit.getEntity(e.getKey());
            if (!(entity instanceof LivingEntity living) || living.isDead()) {
                e.getValue().removeAll();
                dead.add(e.getKey());
                continue;
            }
            for (Player p : living.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(living.getLocation()) < 48 * 48) {
                    e.getValue().addPlayer(p);
                } else {
                    e.getValue().removePlayer(p);
                }
            }
        }
        dead.forEach(bossBars::remove);
    }

    // ---------------------------------------------------------------- self-test

    public void mobSelfTest() {
        if (!plugin.getConfig().getBoolean("debug.mob-test", false)) {
            return;
        }
        World world = plugin.aetherWorld();
        if (world == null) {
            world = plugin.mainWorld();
        }
        if (world == null) {
            return;
        }
        int baseX = 40;
        int baseZ = 60;
        int y = 210;
        Map<String, Location> markers = new HashMap<>();
        int spawned = 0;
        int failed = 0;
        for (MobDef def : defs.values()) {
            int i = spawned + failed;
            Location loc = new Location(world, baseX + i * 3 + 0.5, y, baseZ + 0.5);
            world.getChunkAt(loc).load(true);
            LivingEntity entity = spawnMob(world, loc, def.id(), false);
            if (entity == null || !def.id().equals(mobOf(entity))) {
                plugin.getLogger().info("  mob self-test miss: " + def.id());
                failed++;
                continue;
            }
            Double max = def.attributes().get("MAX_HEALTH");
            if (max != null && Math.abs(entity.getMaxHealth() - max) > 0.01) {
                plugin.getLogger().info("  mob self-test health: " + def.id()
                        + " expected=" + max + " actual=" + entity.getMaxHealth());
                failed++;
                entity.remove();
                continue;
            }
            if (def.id().equals("aerbunny")) {
                boolean displayOk = false;
                for (Entity passenger : entity.getPassengers()) {
                    if (passenger instanceof org.bukkit.entity.ItemDisplay display) {
                        var model = display.getItemStack() != null
                                && display.getItemStack().getItemMeta() != null
                                ? display.getItemStack().getItemMeta().getItemModel() : null;
                        if (model != null && model.toString().equals("aether:entity/aerbunny")) {
                            displayOk = true;
                        }
                    }
                }
                if (!displayOk || !entity.isInvisible()) {
                    plugin.getLogger().info("  mob self-test display: aerbunny");
                    failed++;
                    entity.remove();
                    continue;
                }
            }
            markers.put(def.id(), loc);
            spawned++;
            entity.damage(100000.0);
        }
        final World fWorld = world;
        final Map<String, Location> fMarkers = markers;
        final int fSpawned = spawned;
        final int[] fFailed = {failed};
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int dropFails = 0;
            for (Map.Entry<String, Location> e : fMarkers.entrySet()) {
                MobDef def = defs.get(e.getKey());
                boolean expects = def.drops().stream().anyMatch(
                        p -> p.rollsMin() >= 1 && p.entries().stream().anyMatch(en -> en.min() >= 1));
                int found = 0;
                for (Entity near : fWorld.getNearbyEntities(e.getValue(), 5, 5, 5)) {
                    if (near instanceof Item) {
                        found++;
                    }
                }
                if (expects && found == 0) {
                    plugin.getLogger().info("  mob self-test drops: " + e.getKey());
                    dropFails++;
                }
            }
            plugin.getLogger().info("Mob self-test: spawned=" + fSpawned + "/" + defs.size()
                    + " dropFails=" + dropFails);
        }, 30L);
    }
}
