package com.aetherport.aether;

import com.aetherport.aether.content.Content;
import com.aetherport.aether.accessory.AccessoryManager;
import com.aetherport.aether.advancement.AdvancementManager;
import com.aetherport.aether.dungeon.BossFight;
import com.aetherport.aether.dungeon.ChestLoot;
import com.aetherport.aether.dungeon.DungeonManager;
import com.aetherport.aether.dungeon.DungeonPieces;
import com.aetherport.aether.item.AbilityListener;
import com.aetherport.aether.item.AetherItems;
import com.aetherport.aether.listener.BlockListener;
import com.aetherport.aether.listener.PortalListener;
import com.aetherport.aether.mob.MobAbilities;
import com.aetherport.aether.mob.MobManager;
import com.aetherport.aether.mob.MobModels;
import com.aetherport.aether.station.RecipeRegistrar;
import com.aetherport.aether.station.Recipes;
import com.aetherport.aether.station.StationManager;
import com.aetherport.aether.station.WorkshopListener;
import com.aetherport.aether.world.PortalManager;
import com.aetherport.aether.worldgen.AetherPopulator;
import com.aetherport.aether.worldgen.CloudPopulator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point of the private Aether port for Paper 26.2.
 */
public final class AetherPlugin extends JavaPlugin {
    public static final String BUILD_ID = "2026.11.08-r18";
    private RecipeRegistrar recipeRegistrar;
    public void refreshServerRegistries(){
        if(recipeRegistrar!=null)recipeRegistrar.registerAll(this,getLogger());
        if(advancementManager!=null)advancementManager.registerAll();
    }
    private com.aetherport.aether.item.LoreMenu loreMenu;
    public com.aetherport.aether.item.LoreMenu loreMenu(){return loreMenu;}
    private com.aetherport.aether.worldgen.SaplingGrowth saplings;
    public com.aetherport.aether.worldgen.SaplingGrowth saplings(){return saplings;}
    private AetherVisuals visuals;
    private com.aetherport.aether.listener.MiningListener miningListener;

    private static AetherPlugin instance;

    private final ResourcePackHost resourcePackHost = new ResourcePackHost(getLogger());
    private final Content content = new Content();
    private final PortalManager portalManager = new PortalManager();
    private PortalListener portalListener;
    private MobManager mobManager;
    private MobModels mobModels;
    private AccessoryManager accessoryManager;
    private StationManager stationManager;
    private WorkshopListener workshop;
    private DungeonManager dungeons;
    private BossFight bossFights;
    private ChestLoot chestLoot;
    private AdvancementManager advancementManager;
    private AetherItems aetherItems;
    private String resourcePackUrl;
    private String resourcePackHash;
    private boolean pendingRestart;

    public void setPendingRestart(boolean value) {
        this.pendingRestart = value;
    }

    public static AetherPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        visuals=new AetherVisuals(this);getServer().getPluginManager().registerEvents(visuals,this);
        instance = this;
        saveDefaultConfig();
        getLogger().info("The Aether port build " + BUILD_ID + " enabling on " + getServer().getVersion());

        ensureNoteblockUpdatesDisabled();

        content.load(getLogger());
        saplings=new com.aetherport.aether.worldgen.SaplingGrowth(this);
        portalManager.load(this);
        aetherItems = new AetherItems(this, content);
        loreMenu=new com.aetherport.aether.item.LoreMenu(this);
        new com.aetherport.aether.listener.SurvivalFixes(this);
        new com.aetherport.aether.listener.VanillaNotes(this);
        // Keep terrain in the chunk renderer. Moving a private display/barrier
        // patch with the crosshair causes visible handoffs on camera movement.
        new com.aetherport.aether.listener.WorldFall(this);
        portalListener = new PortalListener(this);

        setupResourcePack();

        if (getCommand("aether") != null) {
            getCommand("aether").setExecutor(this);
            getCommand("aether").setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new ServerListener(this), this);
        getServer().getPluginManager().registerEvents(new BlockListener(this, content, aetherItems), this);
        miningListener=new com.aetherport.aether.listener.MiningListener(this);
        getServer().getPluginManager().registerEvents(miningListener,this);
        getServer().getPluginManager().registerEvents(new com.aetherport.aether.listener.CloudListener(this),this);
        getServer().getPluginManager().registerEvents(
                new com.aetherport.aether.listener.CarrierSounds(this), this);
        getServer().getPluginManager().registerEvents(
                new com.aetherport.aether.worldgen.PlantSupport(this, content, aetherItems), this);
        getServer().getPluginManager().registerEvents(portalListener, this);
        AetherPopulator populator = new AetherPopulator(this, content);
        populator.load();
        getServer().getPluginManager().registerEvents(populator, this);
        getServer().getPluginManager().registerEvents(new CloudPopulator(this, content), this);
        mobManager = new MobManager(this, aetherItems);
        mobManager.load();
        getServer().getPluginManager().registerEvents(mobManager, this);
        getServer().getScheduler().runTaskTimer(this, mobManager::tickSpawns, 160L, 160L);
        mobModels = new MobModels(this);
        getServer().getPluginManager().registerEvents(mobModels, this);
        getServer().getScheduler().runTaskTimer(this, mobModels::tick, 1L, 1L);
        MobAbilities mobAbilities = new MobAbilities(this);
        getServer().getPluginManager().registerEvents(mobAbilities, this);
        getServer().getScheduler().runTaskTimer(this, mobAbilities::tickAbsorb, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, mobAbilities::tickZephyrs, 80L, 80L);
        getServer().getScheduler().runTaskTimer(this, mobAbilities::tickMoaRide, 5L, 5L);
        getServer().getScheduler().runTaskTimer(this, mobAbilities::tickMoaEggs, 100L, 100L);
        AbilityListener abilities = new AbilityListener(this, content, aetherItems);
        getCommand("aetherlore").setExecutor((sender,command,label,args) -> {
            if (!(sender instanceof Player player)) return true;
            getServer().getScheduler().runTask(this,()->loreMenu.open(player));
            return true;
        });
        getServer().getPluginManager().registerEvents(abilities, this);
        getServer().getScheduler().runTaskTimer(this, abilities::tickArmor, 5L, 5L);
        accessoryManager = new AccessoryManager(this, aetherItems);
        getServer().getPluginManager().registerEvents(accessoryManager, this);
        getServer().getScheduler().runTaskTimer(this, accessoryManager::tick, 10L, 10L);
        getServer().getScheduler().runTaskTimer(this, accessoryManager::tickSlowFall, 2L, 2L);
        if (getConfig().getBoolean("debug.ability-test", false)) {
            getServer().getScheduler().runTaskLater(this, abilities::abilitySelfTest, 240L);
        }
        if (getConfig().getBoolean("debug.accessory-test", false)) {
            getServer().getScheduler().runTaskLater(this,
                    () -> accessoryManager.accessorySelfTest(content), 250L);
        }
        Recipes recipes = new Recipes(content, aetherItems);
        recipes.load(getLogger());
        stationManager = new StationManager(this, aetherItems, recipes);
        stationManager.loadStations();
        getServer().getPluginManager().registerEvents(stationManager, this);
        getServer().getScheduler().runTaskTimer(this, stationManager::tick, 2L, 2L);
        if (getConfig().getBoolean("debug.station-test", false)) {
            getServer().getScheduler().runTaskLater(this, stationManager::stationSelfTest, 260L);
        }
        recipeRegistrar=new RecipeRegistrar(content,aetherItems);
        recipeRegistrar.registerAll(this,getLogger());
        workshop = new WorkshopListener(this, content, aetherItems);
        workshop.load();
        getServer().getPluginManager().registerEvents(workshop, this);
        if (getConfig().getBoolean("debug.workshop-test", false)) {
            getServer().getScheduler().runTaskLater(this, workshop::workshopSelfTest, 265L);
        }
        DungeonPieces pieces = new DungeonPieces();
        pieces.load(getLogger());
        chestLoot = new ChestLoot(aetherItems);
        bossFights = new BossFight(this);
        dungeons = new DungeonManager(this, pieces, chestLoot, bossFights);
        dungeons.loadDungeons();
        getServer().getPluginManager().registerEvents(dungeons, this);
        getServer().getPluginManager().registerEvents(bossFights, this);
        getServer().getScheduler().runTaskTimer(this, bossFights::tick, 10L, 10L);
        getServer().getScheduler().runTaskTimer(this, bossFights::minionThrows, 140L, 140L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            World aether = aetherWorldNoCreate();
            if (aether != null) {
                ensureAetherSky(aether);
            }
        }, 6000L, 6000L);
        if (getConfig().getBoolean("debug.dungeon-test", false)) {
            getServer().getScheduler().runTaskLater(this, dungeons::dungeonSelfTest, 280L);
        }
        if (getConfig().getBoolean("debug.boss-test", false)) {
            getServer().getScheduler().runTaskLater(this, bossFights::bossSelfTest, 300L);
        }
        advancementManager = new AdvancementManager(this, aetherItems, chestLoot);
        advancementManager.load();
        advancementManager.registerAll();
        getServer().getPluginManager().registerEvents(advancementManager, this);
        if (getConfig().getBoolean("debug.advancement-test", false)) {
            getServer().getScheduler().runTaskLater(this,
                    advancementManager::advancementSelfTest, 320L);
        }
        if (getConfig().getBoolean("debug.mob-test", false)) {
            getServer().getScheduler().runTaskLater(this, mobManager::mobSelfTest, 220L);
        }
        getServer().getScheduler().runTaskTimer(this, portalListener::tickAmbient, 20L, 20L);
        if (getConfig().getBoolean("debug.portal-test", false)) {
            getServer().getScheduler().runTaskLater(this, portalListener::portalSelfTest, 160L);
        }
        getLogger().info("The Aether port enabled.");
        if (getConfig().getBoolean("debug.content", false)) {
            getServer().getScheduler().runTaskLater(this, this::debugContent, 60L);
        }
    }

    private void debugContent() {
        int ok = 0;
        int total = 0;
        for (String id : new String[]{"holystone", "aether_grass_block", "quicksoil",
                "skyroot_pickaxe", "zanite_sword", "phoenix_helmet", "ambrosium_ore"}) {
            total++;
            ItemStack s = aetherItems.create(id, 1);
            boolean good = s != null && !s.getType().isAir()
                    && id.equals(aetherItems.idOf(s)) && s.getItemMeta() != null
                    && s.getItemMeta().hasItemModel();
            if (good) {
                ok++;
            } else {
                getLogger().info("  self-test miss: " + id + " model="
                        + (s != null && s.getItemMeta() != null ? s.getItemMeta().getItemModel() : "n/a"));
            }
        }
        getLogger().info("Content self-test: " + ok + "/" + total + " items built with model+PDC.");

        // carrier round-trip in the Aether world
        try {
            World w = Bukkit.getWorld(NamespacedKey.fromString(
                    getConfig().getString("world.dimension", "aether:the_aether")));
            if (w != null) {
                org.bukkit.block.Block b = w.getBlockAt(0, 200, 0);
                b.setBlockData(Bukkit.createBlockData(content.block("quicksoil").carrier()), false);
                String state = b.getBlockData().getAsString();
                String resolved = content.blockIdForCarrier(state);
                getLogger().info("Carrier round-trip: set quicksoil -> " + state
                        + " -> resolved=" + resolved);
                b.setType(org.bukkit.Material.AIR, false);
            }
        } catch (Throwable t) {
            getLogger().warning("Carrier round-trip failed: " + t);
        }
    }

    private void setupResourcePack() {
        String configuredUrl = getConfig().getString("resource-pack.url", "");
        File packFile = new File(getDataFolder(), "resourcepack.zip");
        if (getConfig().getBoolean("resource-pack.self-host", true) && packFile.isFile()) {
            try {
                resourcePackHost.start(packFile.toPath(),
                        getConfig().getInt("resource-pack.port", 8127));
                resourcePackHash = resourcePackHost.sha1();
                String host = getConfig().getString("resource-pack.public-host", "");
                if (host == null || host.isEmpty()) {
                    host = getServer().getIp();
                }
                if (host == null || host.isEmpty()) {
                    host = "127.0.0.1";
                }
                resourcePackUrl = "http://" + host + ":" + resourcePackHost.port() + "/aether.zip";
            } catch (Exception e) {
                getLogger().warning("Could not start resource pack host: " + e);
            }
        } else if (configuredUrl != null && !configuredUrl.isEmpty()) {
            resourcePackUrl = configuredUrl;
        }
        if (resourcePackUrl != null) {
            getLogger().info("Resource pack URL: " + resourcePackUrl);
        }
    }

    public String resourcePackUrl() {
        return resourcePackUrl;
    }

    public String resourcePackHash() {
        return resourcePackHash;
    }

    /**
     * Custom blocks are vanilla note_block states; any instrument rewrite corrupts
     * them. Paper must run with block-updates.disable-noteblock-updates=true.
     */
    private void ensureNoteblockUpdatesDisabled() {
        try {
            java.io.File config = new java.io.File("config/paper-global.yml");
            if (!config.isFile()) {
                return;
            }
            java.util.List<String> lines = java.nio.file.Files.readAllLines(
                    config.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith("disable-noteblock-updates:")) {
                    if (!lines.get(i).contains("true")) {
                        lines.set(i, lines.get(i).replace("false", "true"));
                        changed = true;
                    }
                }
            }
            if (changed) {
                java.nio.file.Files.write(config.toPath(), lines,
                        java.nio.charset.StandardCharsets.UTF_8);
                getLogger().warning("Enabled block-updates.disable-noteblock-updates in "
                        + "paper-global.yml (required for Aether blocks). RESTART the server.");
            }
        } catch (Exception e) {
            getLogger().warning("Could not verify paper-global.yml noteblock setting: " + e);
        }
    }

    public PortalManager portals() {
        return portalManager;
    }

    public MobManager mobs() {
        return mobManager;
    }

    public MobModels mobModels() {
        return mobModels;
    }

    public AccessoryManager accessories() {
        return accessoryManager;
    }

    public StationManager stations() {
        return stationManager;
    }

    public WorkshopListener workshop() {
        return workshop;
    }

    public DungeonManager dungeons() {
        return dungeons;
    }

    public BossFight bossFights() {
        return bossFights;
    }

    public ChestLoot chestLoot() {
        return chestLoot;
    }

    public AdvancementManager advancements() {
        return advancementManager;
    }

    public Content content() {
        return content;
    }

    public AetherItems aetherItems() {
        return aetherItems;
    }

    public String aetherDimensionKey() {
        return getConfig().getString("world.dimension", "aether:the_aether");
    }

    public boolean isPortalWorld(String worldKey) {
        return worldKey.equals(aetherDimensionKey())
                || worldKey.equals("minecraft:overworld")
                || (!getWorlds().isEmpty() && worldKey.equals(getWorlds().get(0).getKey().toString()));
    }

    private java.util.List<World> getWorlds() {
        return Bukkit.getWorlds();
    }

    public World mainWorld() {
        World overworld=Bukkit.getWorld(NamespacedKey.minecraft("overworld"));
        if(overworld!=null)return overworld;
        return Bukkit.getWorlds().stream().filter(w->w.getEnvironment()==World.Environment.NORMAL && !w.getKey().toString().equals(aetherDimensionKey())).findFirst().orElse(null);
    }

    /** Resolves (and optionally creates) the Aether dimension world. */
    public World aetherWorld() {        NamespacedKey nk = NamespacedKey.fromString(aetherDimensionKey());
        World w = nk == null ? null : Bukkit.getWorld(nk);
        if (w == null && pendingRestart) {
            return null;
        }
        // Datapack dimensions are loaded by Paper at startup. WorldCreator.ofKey
        // uses the NORMAL environment and can silently create vanilla terrain.
        return w;
    }

    public String dimensionStatus() {
        return "dimension=" + aetherDimensionKey() + "; loaded=" + (aetherWorldNoCreate() != null)
                + "; restartPending=" + pendingRestart + "; install-datapack="
                + getConfig().getBoolean("world.install-datapack", true)
                + "; enabledPacks=" + getServer().getDatapackManager().getEnabledPacks().stream()
                .map(io.papermc.paper.datapack.Datapack::getName).toList()
                + "; loadedWorlds=" + Bukkit.getWorlds().stream().map(v -> v.getKey().toString()).toList();
    }

    public World aetherWorldNoCreate() {
        NamespacedKey nk = NamespacedKey.fromString(aetherDimensionKey());
        return nk == null ? null : Bukkit.getWorld(nk);
    }

    /** The Aether has an eternal day and no weather. */
    public void ensureAetherSky(World world) {
        if (world == null) {
            return;
        }
        try {
            // setTime must run while the world still has a clock, otherwise
            // 26.x refuses with "Cannot set time in world without world clock"
            world.setStorm(false);
            world.setThundering(false);
            world.setTime(6000);
            world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        } catch (Throwable t) {
            getLogger().warning("Could not lock Aether sky: " + t);
        }
    }

    public void debugDimensions() {
        World w = aetherWorld();
        if (w == null && pendingRestart) {
            getLogger().info("Aether dimension will be available after the next restart.");
            return;
        }
        getLogger().info("Loaded worlds: " + Bukkit.getWorlds().stream()
                .map(x -> x.getKey().toString()).toList());
        getLogger().info("Aether world = " + (w == null ? "null" : w.getName()));
        if (w != null) {
            ensureAetherSky(w);
            if (getConfig().getBoolean("debug.terrain-test", false)) {
                probeTerrain(w);
            }
        }
    }

    private void probeTerrain(World w) {
        try {
            int columns = 0;
            int solidColumns = 0;
            int topY = Integer.MIN_VALUE;
            String topBlock = "none";
            for (int cx = -4; cx <= 4; cx++) {
                for (int cz = -4; cz <= 4; cz++) {
                    w.getChunkAt(cx, cz).load(true);
                    for (int x = 0; x < 16; x += 4) {
                        for (int z = 0; z < 16; z += 4) {
                            int wx = cx * 16 + x;
                            int wz = cz * 16 + z;
                            columns++;
                            int h = w.getHighestBlockYAt(wx, wz);
                            org.bukkit.block.Block b = w.getBlockAt(wx, h, wz);
                            if (!b.isEmpty() && !b.isLiquid()) {
                                solidColumns++;
                                if (h > topY) {
                                    topY = h;
                                    topBlock = b.getType() + " " + b.getBlockData().getAsString();
                                }
                            }
                        }
                    }
                }
            }
            getLogger().info("Terrain probe 9x9 chunks: columns=" + columns
                    + " solidColumns=" + solidColumns + " topY=" + topY + " top=" + topBlock
                    + " biome=" + w.getBiome(0, 64, 0).getKey());
            surveyWorld(w);
        } catch (Throwable t) {
            getLogger().warning("Terrain probe failed: " + t);
        }
    }

    /** Samples a wider area and counts resolved Aether blocks (trees/ores/patches). */
    private void surveyWorld(World w) {
        try {
            java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
            int solids = 0;
            for (int cx = -8; cx <= 8; cx++) {
                for (int cz = -8; cz <= 8; cz++) {
                    w.getChunkAt(cx, cz).load(true);
                    for (int x = 0; x < 16; x += 8) {
                        for (int z = 0; z < 16; z += 8) {
                            for (int y = w.getMinHeight(); y < w.getMaxHeight(); y += 2) {
                                org.bukkit.block.Block b = w.getBlockAt(cx * 16 + x, y, cz * 16 + z);
                                if (b.isEmpty() || b.isLiquid()) {
                                    continue;
                                }
                                solids++;
                                String id = content.blockIdForCarrier(b.getBlockData().getAsString());
                                String key = id != null ? id : b.getType().name();
                                counts.merge(key, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
            StringBuilder sb = new StringBuilder("Worldgen survey 17x17 chunks: solids=" + solids);
            for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
                sb.append(" ").append(e.getKey()).append("=").append(e.getValue());
            }
            getLogger().info(sb.toString());
        } catch (Throwable t) {
            getLogger().warning("Worldgen survey failed: " + t);
        }
    }

    @Override
    public void onDisable() {
        if(loreMenu!=null)loreMenu.close();
        if(visuals!=null)visuals.close();
        if(miningListener!=null)miningListener.close();
        resourcePackHost.stop();
        if (accessoryManager != null) {
            accessoryManager.saveAll();
        }
        getLogger().info("The Aether port disabled.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("aether")) {
            return false;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(Component.text("The Aether — private fan port v"
                    + getDescription().getVersion(), NamedTextColor.AQUA));
            sender.sendMessage(Component.text("Server: " + getServer().getVersion(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Build: " + BUILD_ID, NamedTextColor.GRAY));
            sender.sendMessage(Component.text(dimensionStatus(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Resource pack: "
                    + (resourcePackUrl == null ? "not configured" : resourcePackUrl), NamedTextColor.GRAY));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(Component.text("Aether config reloaded.", NamedTextColor.GREEN));
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can receive items.", NamedTextColor.RED));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(Component.text("Usage: /aether give <id> [amount]", NamedTextColor.YELLOW));
                return true;
            }
            String id = args[1].toLowerCase();
            int amount = 1;
            if (args.length >= 3) {
                try {
                    amount = Math.max(1, Math.min(2304, Integer.parseInt(args[2])));
                } catch (NumberFormatException ignored) {
                    amount = 1;
                }
            }
            if (!content.isKnown(id)) {
                sender.sendMessage(Component.text("Unknown Aether id: " + id, NamedTextColor.RED));
                return true;
            }
            ItemStack stack = aetherItems.create(id, amount);
            player.getInventory().addItem(stack);
            sender.sendMessage(Component.text("Given " + amount + "x " + id, NamedTextColor.GREEN));
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) {
            List<String> ids = new ArrayList<>(content.blocks().keySet());
            ids.addAll(content.items().keySet());
            sender.sendMessage(Component.text(ids.size() + " content ids:", NamedTextColor.AQUA));
            sender.sendMessage(Component.text(String.join(", ", ids), NamedTextColor.GRAY));
            return true;
        }
        if (args[0].equalsIgnoreCase("portal")) {
            sender.sendMessage(Component.text("Aether portals: " + portalManager.count(),
                    NamedTextColor.AQUA));
            return true;
        }
        if (args[0].equalsIgnoreCase("accessories")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players have accessories.",
                        NamedTextColor.RED));
                return true;
            }
            accessoryManager.openGui(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("spawn")) {            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can spawn mobs.",
                        NamedTextColor.RED));
                return true;
            }
            if (args.length < 2 || mobManager.defs().get(args[1].toLowerCase()) == null) {
                sender.sendMessage(Component.text("Usage: /aether spawn <id> [n]",
                        NamedTextColor.YELLOW));
                return true;
            }
            String id = args[1].toLowerCase();
            int n = 1;
            if (args.length >= 3) {
                try {
                    n = Math.max(1, Math.min(20, Integer.parseInt(args[2])));
                } catch (NumberFormatException ignored) {
                    n = 1;
                }
            }
            for (int i = 0; i < n; i++) {
                mobManager.spawnMob(player.getWorld(),
                        player.getLocation().add(player.getLocation().getDirection().setY(0)
                                .normalize().multiply(3).add(new org.bukkit.util.Vector(0, 1, 0))),
                        id, false);
            }
            sender.sendMessage(Component.text("Spawned " + n + "x " + id,
                    NamedTextColor.GREEN));
            return true;
        }
        if (args[0].equalsIgnoreCase("build-portal")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can build portals.", NamedTextColor.RED));
                return true;
            }
            float yaw = (player.getLocation().getYaw() % 360 + 360) % 360;
            char axis = (yaw < 45 || yaw >= 315 || (yaw >= 135 && yaw < 225)) ? 'x' : 'z';
            org.bukkit.util.Vector dir = player.getLocation().getDirection().setY(0).normalize();
            int bx = player.getLocation().getBlockX() + (int) Math.round(dir.getX() * 3);
            int by = player.getLocation().getBlockY();
            int bz = player.getLocation().getBlockZ() + (int) Math.round(dir.getZ() * 3);
            int x0 = axis == 'x' ? bx - 1 : bx;
            int z0 = axis == 'z' ? bz - 1 : bz;
            if (!com.aetherport.aether.world.PortalShape.buildRect(player.getWorld(),
                    x0, by, z0, axis, 2, 3, org.bukkit.Material.WATER)) {
                sender.sendMessage(Component.text("No room to build a portal here.",
                        NamedTextColor.RED));
                return true;
            }
            boolean lit = portalListener.tryIgnite(player.getWorld(), bx, by + 1, bz);
            sender.sendMessage(Component.text(lit ? "Portal lit." : "Portal built but not lit.",
                    lit ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            return true;
        }
        if (args[0].equalsIgnoreCase("tpaether")) {
            if (!(sender instanceof Player player)) {
                return true;
            }
            if (player.getWorld().getKey().toString().equals(aetherDimensionKey())) {
                sender.sendMessage(Component.text("You are already in the Aether.",
                        NamedTextColor.YELLOW));
                return true;
            }
            if (portalListener.travel(player)) {
                sender.sendMessage(Component.text("Travelling to the Aether...",
                        NamedTextColor.AQUA));
            } else {
                sender.sendMessage(Component.text("The Aether is not available yet.",
                        NamedTextColor.RED));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("tpover")) {
            if (!(sender instanceof Player player)) {
                return true;
            }
            if (!player.getWorld().getKey().toString().equals(aetherDimensionKey())) {
                sender.sendMessage(Component.text("You are already in the overworld.",
                        NamedTextColor.YELLOW));
                return true;
            }
            if (portalListener.travel(player)) {
                sender.sendMessage(Component.text("Returning...", NamedTextColor.AQUA));
            } else {
                sender.sendMessage(Component.text("No way back yet.", NamedTextColor.RED));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("dungeon")) {
            if (!(sender instanceof Player player) || args.length < 2) {
                sender.sendMessage(Component.text("Usage: /aether dungeon <bronze|silver|gold>",
                        NamedTextColor.YELLOW));
                return true;
            }
            var loc = player.getLocation();
            int ground = loc.getWorld().getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
            if (dungeons == null) {
                sender.sendMessage(Component.text("Dungeons not ready.", NamedTextColor.RED));
                return true;
            }
            var dungeon = switch (args[1].toLowerCase()) {
                case "silver" -> dungeons.buildSilver(loc.getWorld(), loc.getBlockX() - 6,
                        Math.max(60, ground), loc.getBlockZ() - 6);
                case "gold" -> dungeons.buildGold(loc.getWorld(), loc.getBlockX(),
                        Math.max(60, ground), loc.getBlockZ());
                default -> dungeons.buildBronze(loc.getWorld(), loc.getBlockX() - 6,
                        Math.max(60, ground) - 4, loc.getBlockZ() - 6, 5);
            };
            player.teleport(new org.bukkit.Location(loc.getWorld(), dungeon.cx, dungeon.cy + 2,
                    dungeon.cz));
            sender.sendMessage(Component.text("Dungeon built: " + dungeon.id,
                    NamedTextColor.GREEN));
            return true;
        }
        if (args[0].equalsIgnoreCase("boss")) {            if (!(sender instanceof Player player) || args.length < 2
                    || mobManager.defs().get(args[1].toLowerCase()) == null) {
                sender.sendMessage(Component.text("Usage: /aether boss <slider|valkyrie_queen|sun_spirit>",
                        NamedTextColor.YELLOW));
                return true;
            }
            var boss = mobManager.spawnMob(player.getWorld(),
                    player.getLocation().add(0, 1, 3), args[1].toLowerCase(), false);
            if (boss != null && bossFights != null
                    && mobManager.defs().get(args[1].toLowerCase()).boss()) {
                bossFights.register(boss, "command");
            }
            sender.sendMessage(Component.text(boss == null ? "Spawn failed." : "Boss spawned.",
                    boss == null ? NamedTextColor.RED : NamedTextColor.GREEN));
            return true;
        }
        if (args[0].equalsIgnoreCase("stats")) {
            int mobs = 0;
            for (World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntities()) {
                    if (mobManager.mobOf(e) != null) {
                        mobs++;
                    }
                }
            }
            sender.sendMessage(Component.text("Aether stats: worlds=" + Bukkit.getWorlds().size()
                    + " portals=" + portalManager.count()
                    + " dungeons=" + (dungeons == null ? 0 : dungeons.all().size())
                    + " mobs=" + mobs, NamedTextColor.AQUA));
            return true;
        }
        if (args[0].equalsIgnoreCase("tpdungeon")) {
            if (!(sender instanceof Player player) || args.length < 2 || dungeons == null) {
                sender.sendMessage(Component.text("Usage: /aether tpdungeon <id>",
                        NamedTextColor.YELLOW));
                return true;
            }
            var dungeon = dungeons.all().get(args[1]);
            if (dungeon == null) {
                sender.sendMessage(Component.text("Unknown dungeon. Use /aether stats, then id.",
                        NamedTextColor.RED));
                return true;
            }
            World world = Bukkit.getWorld(NamespacedKey.fromString(dungeon.world));
            if (world == null) {
                sender.sendMessage(Component.text("World not loaded.", NamedTextColor.RED));
                return true;
            }
            player.teleport(new org.bukkit.Location(world, dungeon.cx, dungeon.cy + 2,
                    dungeon.cz));
            return true;
        }
        if (args[0].equalsIgnoreCase("resetboss")) {
            if (args.length < 2 || dungeons == null) {
                sender.sendMessage(Component.text("Usage: /aether resetboss <dungeon-id>",
                        NamedTextColor.YELLOW));
                return true;
            }
            var dungeon = dungeons.all().get(args[1]);
            if (dungeon == null) {
                sender.sendMessage(Component.text("Unknown dungeon.", NamedTextColor.RED));
                return true;
            }
            if (dungeon.boss != null) {
                Entity boss = Bukkit.getEntity(dungeon.boss);
                if (boss != null) {
                    boss.remove();
                }
                dungeon.boss = null;
            }
            dungeon.locked = true;
            dungeon.treasureDone = false;
            dungeon.treasureItems = null;
            dungeons.saveAsync();
            sender.sendMessage(Component.text("Boss fight reset: " + dungeon.id,
                    NamedTextColor.GREEN));
            return true;
        }
        sender.sendMessage(Component.text("Usage: /aether <info|reload|give|list|portal|build-portal|tpaether|tpover|spawn|accessories|dungeon|boss|stats|tpdungeon|resetboss>",
                NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("aether")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("info", "reload", "give", "list", "portal", "build-portal",
                    "tpaether", "tpover", "spawn", "accessories", "dungeon", "boss",
                    "stats", "tpdungeon", "resetboss").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            String prefix = args[1].toLowerCase();
            List<String> out = new ArrayList<>(mobManager.defs().keySet());
            out.removeIf(s -> !s.startsWith(prefix));
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("dungeon")) {
            return List.of("bronze", "silver", "gold").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("boss")) {
            return List.of("slider", "valkyrie_queen", "sun_spirit").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase();
            List<String> out = new ArrayList<>(content.blocks().keySet());
            out.addAll(content.items().keySet());
            out.removeIf(s -> !s.startsWith(prefix));
            return out.size() > 60 ? out.subList(0, 60) : out;
        }
        return List.of();
    }
}
