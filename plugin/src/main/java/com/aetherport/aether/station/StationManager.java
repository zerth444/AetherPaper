package com.aetherport.aether.station;

import com.aetherport.aether.AetherPlugin;
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
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Altar / Freezer / Incubator workstations: furnace-like processing driven by the
 * original recipes (enchanting / freezing (+altar repairing) / incubation).
 */
public final class StationManager implements Listener {

    public static final Map<String, String> STATION_BLOCKS = Map.of(
            "altar", "Алтарь", "freezer", "Морозильник", "incubator", "Инкубатор");

    private static class Gui implements InventoryHolder {
        final String key;

        Gui(String key) {
            this.key = key;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class State {
        String type;
        Location loc;
        ItemStack input;
        ItemStack fuel;
        ItemStack output;
        int progress;
        int burn;
        UUID lastUser;
    }

    private final AetherPlugin plugin;
    private final AetherItems items;
    private final Recipes recipes;
    private final Map<String, State> stations = new HashMap<>();

    public StationManager(AetherPlugin plugin, AetherItems items, Recipes recipes) {
        this.plugin = plugin;
        this.items = items;
        this.recipes = recipes;
    }

    public Recipes recipes() {
        return recipes;
    }

    private static String keyOf(Location loc) {
        return loc.getWorld().getKey() + "|" + loc.getBlockX() + "," + loc.getBlockY()
                + "," + loc.getBlockZ();
    }

    public boolean isStation(String blockId) {
        return STATION_BLOCKS.containsKey(blockId);
    }

    // --------------------------------------------------------------------- gui

    public void open(Player player, Block block, String blockId) {
        String type = blockId;
        Location loc = block.getLocation();
        State state = stations.computeIfAbsent(keyOf(loc), k -> {
            State s = new State();
            s.type = type;
            s.loc = loc.clone();
            return s;
        });
        state.lastUser = player.getUniqueId();
        Inventory gui = Bukkit.createInventory(new Gui(keyOf(loc)), 9,
                Component.text(STATION_BLOCKS.get(type)));
        gui.setItem(0, state.input);
        gui.setItem(1, state.fuel);
        gui.setItem(2, state.output);
        updateProgressItem(gui, state);
        for (int i = 3; i < 8; i++) {
            gui.setItem(i, filler());
        }
        player.openInventory(gui);
    }

    private static ItemStack filler() {
        ItemStack f = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = f.getItemMeta();
        if (m != null) {
            m.displayName(Component.text(" "));
            f.setItemMeta(m);
        }
        return f;
    }

    private void updateProgressItem(Inventory gui, State state) {
        int need = need(state);
        int pct = need <= 0 ? 0 : Math.min(100, state.progress * 100 / need);
        ItemStack arrow = new ItemStack(Material.ARROW);
        ItemMeta m = arrow.getItemMeta();
        if (m != null) {
            m.displayName(Component.text("Прогресс: " + pct + "%", NamedTextColor.YELLOW));
            arrow.setItemMeta(m);
        }
        gui.setItem(8, arrow);
    }

    private int need(State state) {
        if (state.type.equals("incubator")) {
            Recipes.Hatch h = findHatch(state.input);
            return h == null ? 0 : h.time();
        }
        Recipes.Cook c = findCook(state);
        if (c != null) {
            return c.time();
        }
        Recipes.Repair r = findRepair(state);
        return r == null ? 0 : r.time();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 9) {
            return;
        }
        if (slot > 2) {
            event.setCancelled(true);
            return;
        }
        State state = stations.get(gui.key);
        if (state == null) {
            event.setCancelled(true);
            return;
        }
        if (slot == 2) { // output: take only
            if (event.getCursor() != null && !event.getCursor().getType().isAir()) {
                event.setCancelled(true);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                state.output = event.getInventory().getItem(2);
                saveAsync();
            });
        }
    }

    @EventHandler
    public void onGuiClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        State state = stations.get(gui.key);
        if (state == null || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        state.input = event.getInventory().getItem(0);
        state.fuel = event.getInventory().getItem(1);
        ItemStack left = event.getInventory().getItem(2);
        if (left != null && !left.getType().isAir()) {
            state.output = left;
        }
        state.lastUser = player.getUniqueId();
        saveAsync();
    }

    // -------------------------------------------------------------------- tick

    public void tick() {
        for (State state : stations.values()) {
            if (state.loc.getWorld() == null) {
                continue;
            }
            if (!state.loc.getChunk().isLoaded()) {
                continue;
            }
            process(state);
        }
    }

    private void process(State state) {
        if (isEmpty(state.input)) {
            state.progress = 0;
            return;
        }
        if (state.type.equals("incubator")) {
            processHatch(state);
            return;
        }
        Recipes.Cook cook = findCook(state);
        Recipes.Repair repair = cook == null ? findRepair(state) : null;
        int need = cook != null ? cook.time() : repair != null ? repair.time() : 0;
        if (need <= 0) {
            state.progress = 0;
            return;
        }
        if (cook != null && !outputFits(state, cook.out(), cook.count())) {
            return;
        }
        Map<String, Integer> fuel = state.type.equals("freezer")
                ? Recipes.FREEZER_FUEL : Recipes.ALTAR_FUEL;
        if (state.burn <= 0) {
            int burn = fuelTimeOf(fuel, state.fuel);
            if (burn <= 0) {
                state.progress = 0;
                return;
            }
            consumeOne(state, true);
            state.burn = burn;
        }
        state.burn--;
        state.progress++;
        refreshViewers(state);
        if (state.progress >= need) {
            state.progress = 0;
            if (cook != null) {
                ItemStack out = "minecraft:air".equals(cook.out()) ? null
                        : buildOutput(cook.out(), cook.count());
                addOutput(state, out);
                consumeOne(state, false);
            } else {
                ItemStack fixed = state.input.clone();
                ItemMeta meta = fixed.getItemMeta();
                if (meta instanceof Damageable d) {
                    d.setDamage(0); // altar repair restores fully
                    fixed.setItemMeta(meta);
                }
                addOutput(state, fixed);
                consumeOne(state, false);
            }
        }
    }

    private void processHatch(State state) {
        Recipes.Hatch hatch = findHatch(state.input);
        if (hatch == null) {
            state.progress = 0;
            return;
        }
        if (state.burn <= 0) {
            int burn = fuelTimeOf(Recipes.INCUBATOR_FUEL, state.fuel);
            if (burn <= 0) {
                state.progress = 0;
                return;
            }
            consumeOne(state, true);
            state.burn = burn;
        }
        state.burn--;
        state.progress++;
        refreshViewers(state);
        if (state.progress >= hatch.time()) {
            state.progress = 0;
            String eggId = plugin.aetherItems().idOf(state.input);
            consumeOne(state, false);
            Location at = state.loc.clone().add(0.5, 1.0, 0.5);
            var moa = plugin.mobs().spawnMob(at.getWorld(), at, "moa", false);
            if (moa instanceof Horse horse && state.lastUser != null) {
                horse.setTamed(true); // PlayerGrown
                horse.setOwner(Bukkit.getOfflinePlayer(state.lastUser));
            }
            if (moa != null) {
                String type = eggId != null && eggId.startsWith("white_") ? "white"
                        : eggId != null && eggId.startsWith("black_") ? "black" : "blue";
                moa.getPersistentDataContainer().set(
                        new NamespacedKey(plugin, "moa_type"),
                        org.bukkit.persistence.PersistentDataType.STRING, type);
            }
            at.getWorld().playSound(at, org.bukkit.Sound.ENTITY_CHICKEN_EGG, 1.0f, 1.2f);
            if (state.lastUser != null && plugin.advancements() != null) {
                Player hatched = Bukkit.getPlayer(state.lastUser);
                if (hatched != null) {
                    plugin.advancements().grantTrigger(hatched, "aether:incubation_trigger");
                }
            }
        }
    }

    private Recipes.Cook findCook(State state) {
        List<Recipes.Cook> list = state.type.equals("freezer")
                ? recipes.freezing() : recipes.enchanting();
        return recipes.findCook(list, state.input);
    }

    private Recipes.Repair findRepair(State state) {
        if (!state.type.equals("altar") || isEmpty(state.input)) {
            return null;
        }
        String id = items.idOf(state.input);
        if (id == null) {
            return null;
        }
        ItemMeta meta = state.input.getItemMeta();
        if (!(meta instanceof Damageable d) || d.getDamage() <= 0) {
            return null;
        }
        for (Recipes.Repair r : recipes.repairing()) {
            for (String ref : r.in()) {
                if (recipes.matches(ref, state.input)) {
                    return r;
                }
            }
        }
        return null;
    }

    private Recipes.Hatch findHatch(ItemStack input) {
        if (isEmpty(input)) {
            return null;
        }
        for (Recipes.Hatch h : recipes.incubation()) {
            for (String ref : h.egg()) {
                if (recipes.matches(ref, input)) {
                    return h;
                }
            }
        }
        return null;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    private int fuelTimeOf(Map<String, Integer> fuel, ItemStack stack) {
        if (isEmpty(stack)) {
            return 0;
        }
        String id = items.idOf(stack);
        if (id != null && fuel.containsKey(id)) {
            return fuel.get(id);
        }
        if (id == null && stack.getType() == Material.ICE) {
            return fuel.getOrDefault("icestone", 0);
        }
        return 0;
    }

    private void consumeOne(State state, boolean fuelSlot) {
        ItemStack stack = fuelSlot ? state.fuel : state.input;
        if (isEmpty(stack)) {
            return;
        }
        stack.setAmount(stack.getAmount() - 1);
        if (stack.getAmount() <= 0) {
            if (fuelSlot) {
                state.fuel = null;
            } else {
                state.input = null;
            }
        }
        refreshViewers(state);
    }

    private boolean outputFits(State state, String outId, int count) {
        if (isEmpty(state.output)) {
            return true;
        }
        ItemStack probe = buildOutput(outId, count);
        return probe != null && state.output.isSimilar(probe)
                && state.output.getAmount() + count <= state.output.getMaxStackSize();
    }

    private ItemStack buildOutput(String ref, int count) {
        if (ref.startsWith("aether:")) {
            return items.create(ref.substring(7), count);
        }
        Material mat = Material.matchMaterial(ref.substring(10).toUpperCase());
        return mat == null ? null : new ItemStack(mat, count);
    }

    private void addOutput(State state, ItemStack out) {
        if (out == null) {
            return;
        }
        if (isEmpty(state.output)) {
            state.output = out;
        } else {
            state.output.setAmount(state.output.getAmount() + out.getAmount());
        }
        refreshViewers(state);
    }

    private void refreshViewers(State state) {
        updateProgressItemFor(state);
    }

    private void updateProgressItemFor(State state) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            var view = viewer.getOpenInventory();
            if (view.getTopInventory().getHolder() instanceof Gui gui
                    && gui.key.equals(keyOf(state.loc))) {
                view.getTopInventory().setItem(0, state.input);
                view.getTopInventory().setItem(1, state.fuel);
                view.getTopInventory().setItem(2, state.output);
                updateProgressItem(view.getTopInventory(), state);
            }
        }
    }

    // ------------------------------------------------------------- persistence

    public void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveNow);
    }

    private void saveNow() {
        try {
            JsonArray arr = new JsonArray();
            for (State s : stations.values()) {
                if (s.loc.getWorld() == null) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("type", s.type);
                o.addProperty("world", s.loc.getWorld().getKey().toString());
                o.addProperty("x", s.loc.getBlockX());
                o.addProperty("y", s.loc.getBlockY());
                o.addProperty("z", s.loc.getBlockZ());
                o.addProperty("input", encode(s.input));
                o.addProperty("fuel", encode(s.fuel));
                o.addProperty("output", encode(s.output));
                o.addProperty("progress", s.progress);
                o.addProperty("burn", s.burn);
                arr.add(o);
            }
            File f = new File(plugin.getDataFolder(), "stations.json");
            Files.createDirectories(f.toPath().getParent());
            Files.writeString(f.toPath(), arr.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save stations: " + e);
        }
    }

    public void loadStations() {
        File f = new File(plugin.getDataFolder(), "stations.json");
        if (!f.isFile()) {
            return;
        }
        try {
            String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            JsonArray arr = JsonParser.parseString(text).getAsJsonArray();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                World world = Bukkit.getWorld(NamespacedKey.fromString(o.get("world").getAsString()));
                if (world == null) {
                    continue;
                }
                State s = new State();
                s.type = o.get("type").getAsString();
                s.loc = new Location(world, o.get("x").getAsInt(), o.get("y").getAsInt(),
                        o.get("z").getAsInt());
                s.input = decode(str(o, "input"));
                s.fuel = decode(str(o, "fuel"));
                s.output = decode(str(o, "output"));
                s.progress = o.get("progress").getAsInt();
                s.burn = o.get("burn").getAsInt();
                stations.put(keyOf(s.loc), s);
            }
            plugin.getLogger().info("Loaded " + stations.size() + " stations.");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load stations: " + e);
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    static String encode(ItemStack stack) {        if (stack == null || stack.getType().isAir()) {
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

    static ItemStack decode(String encoded) {
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

    public static String encodeAll(java.util.List<ItemStack> items) {
        JsonArray arr = new JsonArray();
        for (ItemStack s : items) {
            String e = encode(s);
            if (e != null) {
                arr.add(e);
            }
        }
        return arr.toString();
    }

    public static ItemStack[] decodeAll(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return new ItemStack[0];
        }
        try {
            JsonArray arr = JsonParser.parseString(encoded).getAsJsonArray();
            ItemStack[] out = new ItemStack[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                out[i] = decode(arr.get(i).getAsString());
            }
            return out;
        } catch (Exception e) {
            return new ItemStack[0];
        }
    }

    // ------------------------------------------------------- headless self-test

    /** Simulates full station cycles without players. */
    public void stationSelfTest() {
        if (!plugin.getConfig().getBoolean("debug.station-test", false)) {
            return;
        }
        World world = plugin.mainWorld();
        if (world == null) {
            return;
        }
        int fails = 0;
        if (recipes.enchanting().isEmpty() || recipes.freezing().isEmpty()
                || recipes.incubation().isEmpty()) {
            plugin.getLogger().info("  station self-test recipes missing");
            fails++;
        }
        // altar: smelt one music disc recipe end-to-end
        Recipes.Cook sample = recipes.enchanting().stream()
                .filter(c -> c.in().stream().anyMatch(s -> s.startsWith("minecraft:")))
                .findFirst().orElse(recipes.enchanting().isEmpty() ? null
                        : recipes.enchanting().get(0));
        if (sample != null) {
            State s = new State();
            s.type = "altar";
            s.loc = new Location(world, 70, 210, 90);
            s.input = buildInput(sample.in().get(0));
            s.fuel = items.create("ambrosium_shard", 4);
            if (s.input == null) {
                plugin.getLogger().info("  station self-test input");
                fails++;
            } else {
                int guard = 0;
                while (guard++ < sample.time() + 400 && isEmpty(s.output)) {
                    process(s);
                }
                ItemStack expect = buildOutput(sample.out(), sample.count());
                if (expect == null || !expect.isSimilar(s.output)) {
                    plugin.getLogger().info("  station self-test altar output");
                    fails++;
                }
            }
        }
        // incubator: hatch an egg end-to-end (accelerated)
        Recipes.Hatch hatch = recipes.incubation().isEmpty() ? null : recipes.incubation().get(0);
        if (hatch != null) {
            State s = new State();
            s.type = "incubator";
            s.loc = new Location(world, 74, 210, 90);
            s.input = buildInput(hatch.egg().get(0));
            s.fuel = items.create("ambrosium_torch", 4);
            int guard = 0;
            int mobsBefore = countMobs(world, 74, 90);
            while (guard++ < hatch.time() + 1200 && countMobs(world, 74, 90) <= mobsBefore) {
                process(s);
            }
            if (countMobs(world, 74, 90) <= mobsBefore) {
                plugin.getLogger().info("  station self-test incubator");
                fails++;
            }
        }
        plugin.getLogger().info("Station self-test: " + (fails == 0 ? "PASS" : "FAIL-" + fails));
    }

    private ItemStack buildInput(String ref) {
        if (ref.startsWith("aether:")) {
            return items.create(ref.substring(7), 1);
        }
        Material mat = Material.matchMaterial(ref.substring(10).toUpperCase());
        return mat == null ? null : new ItemStack(mat, 1);
    }

    private int countMobs(World world, int x, int z) {
        int n = 0;
        for (org.bukkit.entity.Entity e : world.getNearbyEntities(new Location(world, x, 210, z), 8, 8, 8)) {
            if (e instanceof org.bukkit.entity.LivingEntity) {
                n++;
            }
        }
        return n;
    }
}
