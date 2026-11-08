package com.aetherport.aether.mob;

import com.aetherport.aether.AetherPlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ItemDisplay visuals built from the original Java entity models
 * (see tools/gen_mob_models.py). The vanilla carrier turns invisible and
 * carries the display as a passenger; hitbox, sounds and AI stay on it.
 */
public final class MobModels implements Listener {

    private static final Set<String> FLYING = Set.of(
            "aerwhale", "zephyr", "valkyrie", "valkyrie_queen", "fire_minion",
            "cloud_minion", "whirlwind", "evil_whirlwind");

    /** Mobs rendered by vanilla models already (no display attached). */
    private static final Set<String> VANILLA_LOOK = Set.of(
            "sentry");

    private final AetherPlugin plugin;
    private final NamespacedKey displayKey;
    private final Map<String, Double> heights = new HashMap<>();
    private final Map<String, Float> scales = new HashMap<>();
    private final Map<String, Float> normalisations = new HashMap<>();
    private final Map<UUID, UUID> displays = new HashMap<>();
    private record LimbDef(String model, Vector3f pivot, float phase, boolean walk, boolean wool) {}
    private record Limb(UUID display, LimbDef def) {}
    private final Map<String, java.util.List<LimbDef>> limbDefs = new HashMap<>();
    private final Map<UUID, java.util.List<Limb>> rigs = new HashMap<>();
    private final Map<UUID, Location> previous = new HashMap<>();
    private final Map<UUID, Float> phases = new HashMap<>();
    private final Set<UUID> dying = new HashSet<>();
    private final Set<UUID> swetGrounded=new HashSet<>();

    private java.util.List<ItemDisplay> visuals(UUID owner) {
        var result=new java.util.ArrayList<ItemDisplay>();
        if (Bukkit.getEntity(displays.getOrDefault(owner,owner)) instanceof ItemDisplay d) result.add(d);
        for(Limb limb:rigs.getOrDefault(owner,java.util.List.of()))
            if(Bukkit.getEntity(limb.display()) instanceof ItemDisplay d) result.add(d);
        return result;
    }
    private void tint(ItemDisplay display, boolean red) {
        var item=display.getItemStack();var meta=item.getItemMeta();
        var data=meta.getCustomModelDataComponent();
        data.setColors(java.util.List.of(red?org.bukkit.Color.fromRGB(255,90,90):org.bukkit.Color.WHITE));
        meta.setCustomModelDataComponent(data);item.setItemMeta(meta);display.setItemStack(item);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onHurt(org.bukkit.event.entity.EntityDamageEvent event) {
        if(event.getFinalDamage()<=0)return;
        var list=visuals(event.getEntity().getUniqueId());
        for(var d:list)tint(d,true);
        Bukkit.getScheduler().runTaskLater(plugin,()->{for(var d:list)if(d.isValid())tint(d,false);},8);
    }

    private ItemStack visualItem(String model) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(NamespacedKey.fromString(model));
        item.setItemMeta(meta);
        return item;
    }

    private void clearRig(UUID owner, boolean removeEntities) {
        var rig = rigs.remove(owner);
        if (rig != null && removeEntities) for (Limb limb : rig) {
            Entity display = Bukkit.getEntity(limb.display());
            if (display != null) display.remove();
        }
        previous.remove(owner); phases.remove(owner);
    }

    private boolean owned(UUID id) {
        return dying.contains(id) || displays.containsValue(id) || rigs.values().stream().flatMap(java.util.Collection::stream)
                .anyMatch(limb -> limb.display().equals(id));
    }

    public MobModels(AetherPlugin plugin) {
        this.plugin = plugin;
        this.displayKey = new NamespacedKey(plugin, "mob_display");
    }

    public void attach(LivingEntity carrier, String mobId) {
        if (!plugin.getConfig().getBoolean("dungeons.enabled",false) && java.util.Set.of("slider","valkyrie","valkyrie_queen","sun_spirit","fire_minion","sentry","mimic").contains(mobId)) {carrier.remove();return;}
        carrier.setSilent(true);
        if ("aechor_plant".equals(mobId)) {
            carrier.setAI(false);
            var size=carrier.getAttribute(org.bukkit.attribute.Attribute.SCALE);
            if(size!=null)size.setBaseValue(2);
        }
        if (("blue_swet".equals(mobId) || "golden_swet".equals(mobId))
                && carrier instanceof org.bukkit.entity.Slime slime) {
            slime.setSize(2);SwetParticles.track(carrier.getEntityId(),carrier.getUniqueId());
        }
        if (displays.containsKey(carrier.getUniqueId())) return;
        for (Entity old : carrier.getPassengers()) {
            if (old.getPersistentDataContainer().has(displayKey, PersistentDataType.STRING)) old.remove();
        }
        if ("whirlwind".equals(mobId) || "evil_whirlwind".equals(mobId)) {
            carrier.setInvisible(true); // shown via cloud particles instead
            return;
        }
        if (VANILLA_LOOK.contains(mobId)) {
            return; // carrier model already matches
        }
        double height = heightOf(mobId);
        if (height <= 0) {
            return;
        }
        World world = carrier.getWorld();
        Location at = carrier.getLocation();
        ItemDisplay display = world.spawn(at, ItemDisplay.class,
                org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,
                e -> {
                    ItemStack stack = new ItemStack(Material.PAPER);
                    ItemMeta meta = stack.getItemMeta();
                    if (meta != null) {
                        meta.setItemModel(NamespacedKey.fromString("aether:entity/" + mobId));
                        stack.setItemMeta(meta);
                    }
                    e.setItemStack(stack);
                    e.setBillboard(Display.Billboard.FIXED);
                    e.setPersistent(false);
                    float scale = scales.getOrDefault(mobId, 1f);
                    float total=scale*normalisations.getOrDefault(mobId,1f);
                    e.setTransformation(new Transformation(new Vector3f(0,total/2,0),
                            new Quaternionf(), new Vector3f(total), new Quaternionf()));
                    e.setTeleportDuration(1);
                    e.getPersistentDataContainer().set(displayKey,
                            PersistentDataType.STRING, mobId);
                });
        // A passenger starts at the carrier's riding seat, which displaced the
        // complete model above the hitbox (especially visible on rabbits).
        try {
            carrier.setInvisible(true);
        } catch (Throwable ignored) {
        }
        displays.put(carrier.getUniqueId(), display.getUniqueId());
        var rig = new java.util.ArrayList<Limb>();
        for (LimbDef def : limbDefs.getOrDefault(mobId, java.util.List.of())) {
            ItemDisplay part = world.spawn(at, ItemDisplay.class, e -> {
                e.setItemStack(visualItem(def.model()));
                e.setTeleportDuration(1);
                e.setInterpolationDuration(0);
                e.setPersistent(false);
                e.getPersistentDataContainer().set(displayKey,PersistentDataType.STRING,mobId);
            });
            rig.add(new Limb(part.getUniqueId(),def));
        }
        rigs.put(carrier.getUniqueId(),rig);
        previous.put(carrier.getUniqueId(),at);
        if ("aerbunny".equals(mobId)) {
            var scale = carrier.getAttribute(org.bukkit.attribute.Attribute.SCALE);
            if (scale != null) scale.setBaseValue(2.0);
        }
    }

    private double heightOf(String mobId) {
        Double cached = heights.get(mobId);
        if (cached != null) {
            return cached;
        }
        double height = -1.0;
        try (InputStream in = getClass().getResourceAsStream(
                "/assets/aether/models/entity/" + mobId + ".json")) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                scales.put(mobId, root.has("aether_renderer_scale")
                        ? root.get("aether_renderer_scale").getAsFloat() : 1f);
                normalisations.put(mobId,root.has("aether_display_scale") ? root.get("aether_display_scale").getAsFloat() : 1f);
                var definitions = new java.util.ArrayList<LimbDef>();
                if (root.has("aether_limbs")) for (JsonElement node : root.getAsJsonArray("aether_limbs")) {
                    JsonObject part = node.getAsJsonObject();
                    JsonArray pivot = part.getAsJsonArray("pivot");
                    definitions.add(new LimbDef(part.get("model").getAsString(),
                            new Vector3f(pivot.get(0).getAsFloat(),pivot.get(1).getAsFloat(),pivot.get(2).getAsFloat()),
                            part.get("phase").getAsFloat(),part.get("walk").getAsBoolean(),part.get("wool").getAsBoolean()));
                }
                limbDefs.put(mobId,definitions);
                JsonArray elements = root.getAsJsonArray("elements");
                if (elements != null) {
                    double top = 0.0;
                    for (JsonElement e : elements) {
                        JsonArray to = e.getAsJsonObject().getAsJsonArray("to");
                        if (to != null && to.size() > 1) {
                            top = Math.max(top, to.get(1).getAsDouble());
                        }
                    }
                    height = root.has("aether_display_height")
                            ? root.get("aether_display_height").getAsDouble() : top / 16.0;
                }
            }
        } catch (Exception ignored) {
        }
        heights.put(mobId, height);
        return height;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        UUID owner=event.getEntity().getUniqueId();var list=visuals(owner);
        var initial=list.stream().map(ItemDisplay::getTransformation).toList();
        list.forEach(d->dying.add(d.getUniqueId()));
        clearRig(owner,false);displays.remove(owner);
        new org.bukkit.scheduler.BukkitRunnable() {
            int age;
            public void run() {
                if(++age>20){list.forEach(d->{dying.remove(d.getUniqueId());d.remove();});cancel();return;}
                Quaternionf roll=new Quaternionf().rotationZ(Math.min(1,age/10f)*(float)Math.PI/2);
                for(int i=0;i<list.size();i++) {
                    var t=initial.get(i);
                    list.get(i).setTransformation(new Transformation(roll.transform(new Vector3f(t.getTranslation())),
                        new Quaternionf(roll).mul(t.getLeftRotation()),new Vector3f(t.getScale()),new Quaternionf(t.getRightRotation())));
                }
            }
        }.runTaskTimer(plugin,1,1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSlimeSplit(org.bukkit.event.entity.SlimeSplitEvent event) {
        String id=plugin.mobs().mobOf(event.getEntity());
        if ("blue_swet".equals(id) || "golden_swet".equals(id)) event.setCancelled(true);
    }

    /** Removes orphaned displays and puffs whirlwind clouds. */
    public void tick() {
        if (Bukkit.getCurrentTick() % 100 == 0) {
            for (World world : Bukkit.getWorlds()) {
                for (LivingEntity carrier : world.getLivingEntities()) {
                    String id = plugin.mobs().mobOf(carrier);
                    if (!plugin.getConfig().getBoolean("dungeons.enabled",false) && id!=null && java.util.Set.of("slider","valkyrie","valkyrie_queen","sun_spirit","fire_minion","sentry","mimic").contains(id)) {carrier.remove();continue;}
                    if (id != null && plugin.mobs().migrateCarrier(carrier, id) != carrier) continue;
                    if (id != null && !displays.containsKey(carrier.getUniqueId())) attach(carrier, id);
                }
                for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                    if (display.getPersistentDataContainer().has(displayKey, PersistentDataType.STRING)
                            && !owned(display.getUniqueId())) display.remove();
                }
            }
        }
        Set<UUID> dead = new HashSet<>();
        for (Map.Entry<UUID, UUID> e : displays.entrySet()) {
            Entity carrier = Bukkit.getEntity(e.getKey());
            Entity display = Bukkit.getEntity(e.getValue());
            if (carrier == null) {
                SwetParticles.forget(e.getKey());swetGrounded.remove(e.getKey());
                // Unloaded entities must survive together in chunk storage.
                dead.add(e.getKey());
                clearRig(e.getKey(),false);
            } else if (!carrier.isValid() || carrier.isDead()) {
                SwetParticles.forget(e.getKey());swetGrounded.remove(e.getKey());
                if (display != null) {
                    display.remove();
                }
                dead.add(e.getKey());
                clearRig(e.getKey(),true);
            } else if (display == null || !display.isValid()) {
                dead.add(e.getKey());
                clearRig(e.getKey(),true);
            } else {
                Location at = carrier.getLocation();
                at.setPitch(0);
                String mobId=plugin.mobs().mobOf(carrier);
                if("blue_swet".equals(mobId)||"golden_swet".equals(mobId)){
                    // Java SlimeModel faces -Z; entity yaw faces +Z.
                    at.setYaw(at.getYaw()+180);
                    if(carrier.isOnGround()){
                        if(swetGrounded.add(e.getKey()))carrier.getWorld().spawnParticle(org.bukkit.Particle.DUST,carrier.getLocation().add(0,.05,0),12,.45,.03,.45,0,new org.bukkit.Particle.DustOptions("blue_swet".equals(mobId)?org.bukkit.Color.fromRGB(80,170,225):org.bukkit.Color.fromRGB(230,220,70),.7f));
                    }else swetGrounded.remove(e.getKey());
                }
                if ("aechor_plant".equals(plugin.mobs().mobOf(carrier))) at.setYaw(0);
                display.teleport(at);
                Location last = previous.put(e.getKey(),at);
                float distance = last != null && last.getWorld().equals(at.getWorld())
                        ? (float)Math.hypot(at.getX()-last.getX(),at.getZ()-last.getZ()) : 0;
                if (distance>1) distance=0;
                float phase = phases.getOrDefault(e.getKey(),0f)+distance*8;
                phases.put(e.getKey(),phase);
                float amplitude = Math.min(1f,distance*12)*.7f;
                boolean bunny="aerbunny".equals(plugin.mobs().mobOf(carrier));
                if (bunny) amplitude=carrier.isOnGround()?0:.35f;
                Quaternionf bodyRotation=new Quaternionf();
                if(bunny && !carrier.isOnGround()) bodyRotation.rotationX((float)Math.max(-.26,Math.min(.26,carrier.getVelocity().getY()*.5)));
                if(bunny && display instanceof ItemDisplay body) {
                    float total=scales.getOrDefault("aerbunny",1f)*normalisations.getOrDefault("aerbunny",1f);
                    body.setTransformation(new Transformation(bodyRotation.transform(new Vector3f(0,total/2,0)),new Quaternionf(bodyRotation),new Vector3f(total),new Quaternionf()));
                }
                float scale = scales.getOrDefault(plugin.mobs().mobOf(carrier),1f);
                for (Limb limb : rigs.getOrDefault(e.getKey(),java.util.List.of())) {
                    if (!(Bukkit.getEntity(limb.display()) instanceof ItemDisplay part)) continue;
                    // Sheepuffs use a vanilla Sheep only as an invisible AI/hitbox
                    // carrier. Its persisted sheared flag is vanilla carrier state,
                    // not the Sheepuff's wool state; using it here made wool vanish
                    // (or appear inconsistently) after a server restart/rejoin.
                    boolean hidden=false;
                    if (hidden != part.getItemStack().getType().isAir())
                        part.setItemStack(hidden ? new ItemStack(Material.AIR) : visualItem(limb.def().model()));
                    part.teleport(at);
                    float angle=limb.def().walk() ? (float)Math.cos(phase+limb.def().phase())*amplitude : 0;
                    part.setInterpolationDelay(0);
                    Quaternionf rotation=new Quaternionf().rotationX(angle);
                    Vector3f centre=rotation.transform(new Vector3f(0,.5f,0)).mul(scale);
                    part.setTransformation(new Transformation(new Quaternionf(bodyRotation).transform(new Vector3f(limb.def().pivot()).mul(scale).add(centre)),
                            new Quaternionf(bodyRotation).mul(rotation),new Vector3f(scale),new Quaternionf()));
                }
            }
        }
        dead.forEach(displays::remove);
        if (Bukkit.getCurrentTick() % 20 != 0) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (Entity near : player.getWorld().getNearbyEntities(player.getLocation(),
                    48, 32, 48)) {
                String id = plugin.mobs().mobOf(near);
                if ("whirlwind".equals(id) || "evil_whirlwind".equals(id)) {
                    near.getWorld().spawnParticle(org.bukkit.Particle.CLOUD,
                            near.getLocation().add(0, 1, 0), 3, 0.6, 0.8, 0.6, 0.02);
                }
            }
        }
    }
}
