package com.aetherport.aether.advancement;

import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.dungeon.ChestLoot;
import com.aetherport.aether.item.AetherItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;
import org.bukkit.inventory.ItemStack;

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
import java.util.UUID;

/**
 * All 343 Aether advancements registered as real server advancements with
 * impossible triggers, unlocked by code listeners that mirror the original
 * trigger conditions (items, kills, dimensions, mounts, recipes...).
 */
public final class AdvancementManager implements Listener {

    private record Criterion(String name, String trigger, List<String> items,
                             List<String> entities, List<String> vehicle,
                             String to, String from, List<String> recipes) {
    }

    private record Adv(String id, String parent, JsonObject title, JsonObject description,
                       String icon, int iconCount, String frame, String background,
                       List<Criterion> criteria, List<List<String>> requirements,
                       List<String> loot, List<String> rewardRecipes) {
    }

    private final AetherPlugin plugin;
    private final AetherItems items;
    private final ChestLoot loot;
    private final Map<String, Adv> advs = new HashMap<>();
    private final Map<UUID, Map<String, Set<String>>> done = new HashMap<>();
    private final Map<String, List<Set<String>>> recipeIngredients = new HashMap<>();

    public AdvancementManager(AetherPlugin plugin, AetherItems items, ChestLoot loot) {
        this.plugin = plugin;
        this.items = items;
        this.loot = loot;
    }

    public void load() {
        try (InputStream in = getClass().getResourceAsStream("/content/advancements.json")) {
            if (in == null) {
                plugin.getLogger().warning("Missing content resource /content/advancements.json");
                return;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                List<Criterion> criteria = new ArrayList<>();
                for (JsonElement c : o.getAsJsonArray("criteria")) {
                    JsonObject co = c.getAsJsonObject();
                    criteria.add(new Criterion(co.get("name").getAsString(),
                            co.get("trigger").getAsString(), strings(co, "items"),
                            strings(co, "entities"), strings(co, "vehicle"),
                            str(co, "to"), str(co, "from_"), strings(co, "recipes")));
                }
                List<List<String>> reqs = new ArrayList<>();
                for (JsonElement r : o.getAsJsonArray("requirements")) {
                    List<String> group = new ArrayList<>();
                    for (JsonElement g : r.getAsJsonArray()) {
                        group.add(g.getAsString());
                    }
                    reqs.add(group);
                }
                JsonObject icon = o.getAsJsonObject("icon");
                advs.put(e.getKey(), new Adv(e.getKey(), o.get("parent").getAsString(),
                        o.getAsJsonObject("title"), o.getAsJsonObject("description"),
                        icon.has("id") ? icon.get("id").getAsString() : "minecraft:stone",
                        icon.has("count") ? icon.get("count").getAsInt() : 1,
                        str(o, "frame"), str(o, "background"), criteria, reqs,
                        strings(o, "loot"), strings(o, "reward_recipes")));
            }
            plugin.getLogger().info("Advancements loaded: " + advs.size());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load advancements: " + e);
        }
        loadRecipeIndex();
    }

    /** Distinct ingredient refs per custom recipe key, for book discovery. */
    private void loadRecipeIndex() {
        try (InputStream in = getClass().getResourceAsStream("/content/recipes.json")) {
            if (in == null) {
                return;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String section : new String[]{"craft_shaped", "craft_shapeless", "smelting",
                    "blasting", "stonecutting", "smithing"}) {
                if (!root.has(section)) {
                    continue;
                }
                for (JsonElement e : root.getAsJsonArray(section)) {
                    JsonObject o = e.getAsJsonObject();
                    String rkey = null;
                    if (o.has("rkey") && o.get("rkey").isJsonPrimitive()) {
                        rkey = o.get("rkey").getAsString();
                    } else if (o.has("key") && o.get("key").isJsonPrimitive()) {
                        rkey = o.get("key").getAsString();
                    }
                    if (rkey == null || rkey.isEmpty()) {
                        continue;
                    }
                    List<Set<String>> groups=new ArrayList<>();
                    if(section.equals("craft_shaped"))for(var value:o.getAsJsonObject("key").entrySet())addIngredientGroup(groups,value.getValue());
                    else if(section.equals("craft_shapeless"))for(var value:o.getAsJsonArray("ingredients"))addIngredientGroup(groups,value);
                    else if(section.equals("smithing"))for(String name:List.of("template","base","addition"))addIngredientGroup(groups,o.get(name));
                    else addIngredientGroup(groups,o.get("in"));
                    if(!groups.isEmpty())recipeIngredients.put("aether:"+rkey,groups);
                }
            }
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            plugin.getLogger().warning("Failed to index recipes: " + e + "\n" + sw);
        }
    }

    private static void collectRefs(JsonElement el, Set<String> refs) {
        if (el == null || el.isJsonNull()) {
            return;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String s = el.getAsString();
            if (s.startsWith("aether:") || s.startsWith("minecraft:")) {
                refs.add(s);
            }
            return;
        }
        if (el.isJsonArray()) {
            for (JsonElement c : el.getAsJsonArray()) {
                collectRefs(c, refs);
            }
            return;
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            // skip outputs/results to avoid self-triggering discovery
            for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                String k = en.getKey();
                if (k.equals("out") || k.equals("result") || k.equals("count")
                        || k.equals("time") || k.equals("xp") || k.equals("pattern")
                        || k.equals("extra")) {
                    continue;
                }
                collectRefs(en.getValue(), refs);
            }
        }
    }
    private static void addIngredientGroup(List<Set<String>> groups,JsonElement value){Set<String> refs=new HashSet<>();collectRefs(value,refs);if(!refs.isEmpty())groups.add(refs);}

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                if (e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------- registration

    public void registerAll() {
        diagnose();
        List<Adv> ordered = new ArrayList<>(advs.values());
        ordered.sort((a, b) -> Integer.compare(depth(a, 0), depth(b, 0)));
        int ok = 0;
        int vanish = 0;
        String firstVanish = "";
        int skippedNull = 0;
        int returnedNull = 0;
        for (Adv adv : ordered) {
            try {
                NamespacedKey key = NamespacedKey.fromString(adv.id());
                if (key == null) {
                    skippedNull++;
                    plugin.getLogger().warning("Advancement null key: " + adv.id());
                    continue;
                }
                var loaded = AdvancementLoader.load(Bukkit.getUnsafe(),key,registrationJson(adv));
                if (loaded == null) {
                    returnedNull++;
                    if (returnedNull <= 3) {
                        plugin.getLogger().warning("Advancement load returned null: " + adv.id());
                    }
                    continue;
                }
                ok++;
                if (Bukkit.getAdvancement(key) == null) {
                    vanish++;
                    if (firstVanish.isEmpty()) {
                        firstVanish = adv.id();
                    }
                }
            } catch (Throwable t) {
                String msg = String.valueOf(t.getMessage());
                if (msg.contains("already exists")
                        && Bukkit.getAdvancement(NamespacedKey.fromString(adv.id())) != null) {
                    ok++; // persisted from an earlier run
                    continue;
                }
                plugin.getLogger().warning("Advancement registration failed for "
                        + adv.id() + ": " + t.getMessage());
            }
        }
        plugin.getLogger().info("Advancements registered: " + ok + "/" + advs.size()
                + " vanish=" + vanish + " " + firstVanish + " nullKey=" + skippedNull
                + " nullReturn=" + returnedNull);
        loadProgress();
    }

    private void diagnose() {
        if (!plugin.getConfig().getBoolean("debug.advancement-diag", false)) {
            return;
        }
        String base = "{\"criteria\":CRIT,\"display\":{\"title\":\"t\",\"description\":\"d\","
                + "\"icon\":{\"id\":\"minecraft:stone\"}}}";
        String[][] variants = {
            {"diagA", base.replace("CRIT", "{\"x\":{\"trigger\":\"minecraft:impossible\"}}")},
            {"diagB", base.replace("CRIT",
                "{\"x\":{\"trigger\":\"minecraft:impossible\"},\"y\":{\"trigger\":\"minecraft:impossible\"}}")
                .replaceFirst("\\}$", ",\"requirements\":[[\"x\",\"y\"]]}")},
            {"diagC", "{\"parent\":\"minecraft:recipes/root\"," + base.replace("CRIT",
                "{\"x\":{\"trigger\":\"minecraft:impossible\"}}").substring(1)},
        };
        for (String[] v : variants) {
            try {
                NamespacedKey key = new NamespacedKey(plugin, v[0]);
                var adv = AdvancementLoader.load(Bukkit.getUnsafe(),key,v[1]);
                boolean visible = Bukkit.getAdvancement(key) != null;
                plugin.getLogger().info("Advancement " + v[0] + ": returned="
                        + (adv != null) + " visible=" + visible);
            } catch (Throwable t) {
                plugin.getLogger().warning("Advancement " + v[0] + ": " + t);
            }
        }
    }

    private int depth(Adv adv, int guard) {
        if (guard > 12 || adv.parent().isEmpty() || !adv.parent().startsWith("aether:")) {
            return 0;
        }
        Adv parent = advs.get(adv.parent());
        return parent == null ? 0 : 1 + depth(parent, guard + 1);
    }

    private String registrationJson(Adv adv) {
        JsonObject root = new JsonObject();
        if (!adv.parent().isEmpty()) {
            root.addProperty("parent", adv.parent());
        }
        boolean hasDisplay = !adv.title().entrySet().isEmpty()
                || !adv.description().entrySet().isEmpty();
        if (hasDisplay) {
            JsonObject display = new JsonObject();
            display.add("title", adv.title());
            display.add("description", adv.description());
            JsonObject icon = new JsonObject();
            icon.addProperty("id", baseIcon(adv.icon()));
            icon.addProperty("count", Math.max(1, adv.iconCount()));
            display.add("icon", icon);
            if (!adv.frame().isEmpty() && !adv.frame().equals("task")) {
                display.addProperty("frame", adv.frame());
            }
            if (!adv.background().isEmpty()) {
                display.addProperty("background", adv.background());
            }
            root.add("display", display);
        }
        JsonObject criteria = new JsonObject();
        for (Criterion c : adv.criteria()) {
            JsonObject co = new JsonObject();
            co.addProperty("trigger", "minecraft:impossible");
            criteria.add(c.name(), co);
        }
        root.add("criteria", criteria);
        JsonArray reqs = new JsonArray();
        for (List<String> group : adv.requirements()) {
            JsonArray g = new JsonArray();
            for (String s : group) {
                g.add(s);
            }
            reqs.add(g);
        }
        root.add("requirements", reqs);
        return root.toString();
    }

    private String baseIcon(String id) {
        if (id.startsWith("aether:")) {
            Material mat = items.baseMaterial(id.substring(7));
            return "minecraft:" + mat.name().toLowerCase();
        }
        return id;
    }

    // ---------------------------------------------------------------- progress

    private Map<String, Set<String>> progress(Player player) {
        return done.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
    }

    public void grant(Player player, String advId, String criterion) {
        Adv adv = advs.get(advId);
        if (adv == null) {
            return;
        }
        Map<String, Set<String>> map = progress(player);
        Set<String> set = map.computeIfAbsent(advId, k -> new HashSet<>());
        if (!set.add(criterion)) {
            return;
        }
        saveSoon(player);
        boolean complete = false;
        for (List<String> group : adv.requirements()) {
            if (set.containsAll(group)) {
                complete = true;
                break;
            }
        }
        if (!complete) {
            return;
        }
        try {
            var bukkitAdv = Bukkit.getAdvancement(NamespacedKey.fromString(advId));
            if (bukkitAdv != null) {
                var progress = player.getAdvancementProgress(bukkitAdv);
                for (String remaining : progress.getRemainingCriteria()) {
                    progress.awardCriteria(remaining);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Advancement award failed for " + advId + ": " + t);
        }
        for (String recipe : adv.rewardRecipes()) {
            try {
                NamespacedKey key = NamespacedKey.fromString(recipe);
                if (key != null && Bukkit.getRecipe(key)!=null && !player.hasDiscoveredRecipe(key)) {
                    player.discoverRecipe(key);
                }
            } catch (Throwable ignored) {
            }
        }
        if (!adv.loot().isEmpty()) {
            var rng = new java.util.Random();
            for (String table : adv.loot()) {
                for (ItemStack s : loot.roll(table, rng)) {
                    player.getInventory().addItem(s);
                }
            }
        }
    }

    public void grantTrigger(Player player, String trigger) {
        for (Adv adv : advs.values()) {
            Map<String, Set<String>> map = done.get(player.getUniqueId());
            Set<String> set = map == null ? null : map.get(adv.id());
            for (Criterion c : adv.criteria()) {
                if (c.trigger().equals(trigger)
                        && (set == null || !set.contains(c.name()))) {
                    grant(player, adv.id(), c.name());
                }
            }
        }
    }

    private void saveSoon(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveNow);
    }

    private void saveNow() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, Map<String, Set<String>>> e : done.entrySet()) {
                JsonObject player = new JsonObject();
                for (Map.Entry<String, Set<String>> a : e.getValue().entrySet()) {
                    JsonArray arr = new JsonArray();
                    for (String c : a.getValue()) {
                        arr.add(c);
                    }
                    player.add(a.getKey(), arr);
                }
                root.add(e.getKey().toString(), player);
            }
            File f = new File(plugin.getDataFolder(), "advancement_progress.json");
            Files.createDirectories(f.toPath().getParent());
            Files.writeString(f.toPath(), root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save advancement progress: " + e);
        }
    }

    private void loadProgress() {
        File f = new File(plugin.getDataFolder(), "advancement_progress.json");
        if (!f.isFile()) {
            return;
        }
        try {
            String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                Map<String, Set<String>> map = new HashMap<>();
                for (Map.Entry<String, JsonElement> a : e.getValue().getAsJsonObject().entrySet()) {
                    Set<String> set = new HashSet<>();
                    for (JsonElement c : a.getValue().getAsJsonArray()) {
                        set.add(c.getAsString());
                    }
                    map.put(a.getKey(), set);
                }
                done.put(UUID.fromString(e.getKey()), map);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load advancement progress: " + e);
        }
    }

    // ---------------------------------------------------------------- listeners

    private boolean matchesItem(String ref, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (ref.startsWith("aether:")) {
            return ref.substring(7).equals(items.idOf(stack));
        }
        if (ref.startsWith("minecraft:")) {
            Material mat = Material.matchMaterial(ref.substring(10).toUpperCase());
            return mat != null && stack.getType() == mat;
        }
        return false;
    }

    private void checkInventory(Player player) {
        Map<String, Set<String>> map = done.get(player.getUniqueId());
        ItemStack[] contents = player.getInventory().getContents();
        for (Adv adv : advs.values()) {
            for (Criterion c : adv.criteria()) {
                if (!c.trigger().equals("minecraft:inventory_changed")
                        || c.items().isEmpty()) {
                    continue;
                }
                if (map != null && map.getOrDefault(adv.id(), Set.of()).contains(c.name())) {
                    continue;
                }
                for (String ref : c.items()) {
                    for (ItemStack stack : contents) {
                        if (matchesItem(ref, stack)) {
                            grant(player, adv.id(), c.name());
                            break;
                        }
                    }
                }
            }
        }
        discoverRecipes(player, contents);
    }

    private void discoverRecipes(Player player, ItemStack[] contents) {
        Set<String> have = new HashSet<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            String id = items.idOf(stack);
            have.add(id != null ? "aether:" + id
                    : "minecraft:" + stack.getType().name().toLowerCase());
        }
        for (Map.Entry<String, List<Set<String>>> e : recipeIngredients.entrySet()) {
            NamespacedKey key = NamespacedKey.fromString(e.getKey());
            if (key == null || Bukkit.getRecipe(key)==null || player.hasDiscoveredRecipe(key)) {
                continue;
            }
            if (e.getValue().stream().allMatch(group->group.stream().anyMatch(have::contains))) {
                try {
                    player.discoverRecipe(key);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> checkInventory(event.getPlayer()), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> checkInventory(player), 2L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> checkInventory(player), 2L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        String victim = plugin.mobs().mobOf(event.getEntity());
        String key = victim != null ? "aether:" + victim
                : "minecraft:" + event.getEntityType().name().toLowerCase();
        for (Adv adv : advs.values()) {
            for (Criterion c : adv.criteria()) {
                if (!c.trigger().equals("minecraft:player_killed_entity")) {
                    continue;
                }
                if (c.entities().contains(key)) {
                    grant(killer, adv.id(), c.name());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        String from = event.getFrom().getKey().toString();
        String to = event.getPlayer().getWorld().getKey().toString();
        for (Adv adv : advs.values()) {
            for (Criterion c : adv.criteria()) {
                if (!c.trigger().equals("minecraft:changed_dimension")) {
                    continue;
                }
                if (!c.to().isEmpty() && !c.to().equals(to)) {
                    continue;
                }
                if (!c.from().isEmpty() && !c.from().equals(from)) {
                    continue;
                }
                grant(event.getPlayer(), adv.id(), c.name());
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> checkInventory(event.getPlayer()), 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        String vehicle = plugin.mobs().mobOf(event.getMount());
        String key = vehicle != null ? "aether:" + vehicle
                : "minecraft:" + event.getMount().getType().name().toLowerCase();
        for (Adv adv : advs.values()) {
            for (Criterion c : adv.criteria()) {
                if (!c.trigger().equals("minecraft:started_riding")) {
                    continue;
                }
                if (c.vehicle().isEmpty() || c.vehicle().contains(key)) {
                    grant(player, adv.id(), c.name());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSleep(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) {
            return;
        }
        grantTrigger(event.getPlayer(), "minecraft:slept_in_bed");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDiscover(PlayerRecipeDiscoverEvent event) {
        String recipe = event.getRecipe().toString();
        for (Adv adv : advs.values()) {
            for (Criterion c : adv.criteria()) {
                if (!c.trigger().equals("minecraft:recipe_unlocked")) {
                    continue;
                }
                if (c.recipes().contains(recipe)) {
                    grant(event.getPlayer(), adv.id(), c.name());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUseBlock(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack held = event.getItem();
        if (held == null || held.getType().isAir()) {
            return;
        }
        for (Adv adv : advs.values()) {
            for (Criterion c : adv.criteria()) {
                if (!c.trigger().equals("minecraft:item_used_on_block")
                        || c.items().isEmpty()) {
                    continue;
                }
                for (String ref : c.items()) {
                    if (matchesItem(ref, held)) {
                        grant(event.getPlayer(), adv.id(), c.name());
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        org.bukkit.block.Block feet = event.getTo().clone().subtract(0,.05,0).getBlock();
        if (feet.getType() == Material.NOTE_BLOCK) {
            String id = plugin.content().blockIdForCarrier(feet.getBlockData().getAsString());
            if ("blue_aercloud".equals(id)) {
                grantAdvTrigger(player, "aether:blue_aercloud", "minecraft:enter_block");
            }
        } else if (feet.getType() == Material.WATER) {
            grantAdvTrigger(player, "aether:recipes/transportation/skyroot_boat",
                    "minecraft:enter_block");
        }
    }

    private void grantAdvTrigger(Player player, String advId, String trigger) {
        Adv adv = advs.get(advId);
        if (adv == null) {
            return;
        }
        for (Criterion c : adv.criteria()) {
            if (c.trigger().equals(trigger)) {
                grant(player, adv.id(), c.name());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAercloudFall(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        org.bukkit.block.Block at = player.getLocation().subtract(0, 0.5, 0).getBlock();
        if (at.getType() == Material.NOTE_BLOCK
                && plugin.content().blockIdForCarrier(at.getBlockData().getAsString()) != null
                && plugin.content().blockIdForCarrier(at.getBlockData().getAsString())
                        .endsWith("aercloud")) {
            event.setCancelled(true); // aerclouds never deal fall damage
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> checkInventory(event.getPlayer()), 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        saveNow();
        done.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------- headless self-test

    /** Verifies registration of every advancement without players. */
    public void advancementSelfTest() {
        if (!plugin.getConfig().getBoolean("debug.advancement-test", false)) {
            return;
        }
        int missing = 0;
        int criteria = 0;
        for (Adv adv : advs.values()) {
            criteria += adv.criteria().size();
            try {
                if (Bukkit.getAdvancement(NamespacedKey.fromString(adv.id())) == null) {
                    missing++;
                    if (missing < 4) {
                        plugin.getLogger().info("  advancement self-test missing: " + adv.id());
                    }
                }
            } catch (Throwable t) {
                missing++;
            }
        }
        plugin.getLogger().info("Advancement self-test: " + advs.size() + " defs, "
                + criteria + " criteria, missing=" + missing
                + (missing == 0 ? " PASS" : " FAIL"));
    }
}
