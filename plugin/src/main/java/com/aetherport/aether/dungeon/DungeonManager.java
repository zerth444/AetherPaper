package com.aetherport.aether.dungeon;

import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.station.StationManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Builds and runs the bronze/silver/gold dungeons: rooms from the original NBT
 * pieces, locked blocks, trapped blocks, guardians, bosses, treasure.
 */
public final class DungeonManager implements Listener {

    public static class Dungeon {
        public String id;
        public String type;
        public String world;
        public int cx;
        public int cy;
        public int cz;
        public int radius;
        public UUID boss;
        public boolean locked = true;
        public boolean treasureDone;
        public String treasureTable;
        public String chestTable;
        public String treasureItems;
        public Map<String, String> chests = new HashMap<>();
        public Map<String, String> chestKinds = new HashMap<>();
    }

    public static String posKey(String world, int x, int y, int z) {
        return world + "|" + x + "," + y + "," + z;
    }

    private static class TreasureGui implements InventoryHolder {
        final String dungeonId;
        final String chestKey;

        TreasureGui(String dungeonId, String chestKey) {
            this.dungeonId = dungeonId;
            this.chestKey = chestKey;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final AetherPlugin plugin;
    private final DungeonPieces pieces;
    private final ChestLoot loot;
    private final BossFight fights;
    private final Map<String, Dungeon> dungeons = new HashMap<>();

    public DungeonManager(AetherPlugin plugin, DungeonPieces pieces, ChestLoot loot,
                          BossFight fights) {
        this.plugin = plugin;
        this.pieces = pieces;
        this.loot = loot;
        this.fights = fights;
    }

    public BossFight fights() {
        return fights;
    }

    public static boolean isLockedBlock(String id) {
        return id != null && (id.startsWith("locked_") || id.contains("doorway"));
    }

    public static boolean isTrappedBlock(String id) {
        return id != null && id.startsWith("trapped_");
    }

    public Dungeon at(Location loc) {
        String world = loc.getWorld().getKey().toString();
        for (Dungeon d : dungeons.values()) {
            if (!d.world.equals(world)) {
                continue;
            }
            double dx = d.cx - loc.getX();
            double dy = d.cy - loc.getY();
            double dz = d.cz - loc.getZ();
            if (dx * dx + dy * dy + dz * dz < d.radius * d.radius) {
                return d;
            }
        }
        return null;
    }

    public boolean isLocked(Location loc) {
        Dungeon d = at(loc);
        return d != null && d.locked;
    }

    // ------------------------------------------------------------------ bronze

    public Dungeon buildBronze(World world, int x, int y, int z) {
        return buildBronze(world, x, y, z, 8);
    }

    public Dungeon buildBronze(World world, int x, int y, int z, int maxRooms) {
        Random rng = new Random(world.getSeed() ^ (x * 341873128712L + z * 132897987541L));
        List<int[]> rooms = new ArrayList<>();
        rooms.add(new int[]{x, y, z});
        int cx = x;
        int cz = z;
        int dir = rng.nextInt(4);
        List<DungeonPieces.Place> placed = new ArrayList<>();
        placed.add(pieces.place(world, "bronze_dungeon", "lobby", cx, y, cz, 0));
        placed.add(pieces.place(world, "bronze_dungeon", "entrance", cx + 3, y + 1, cz - 1, 0));
        for (int i = 0; i < maxRooms; i++) {
            dir = pickDir(rng, dir);
            int[] step = step(dir);
            cx += step[0] * 12;
            cz += step[1] * 12;
            String room = rng.nextInt(4) == 0 ? "chest_room" : "lobby";
            placed.add(pieces.place(world, "bronze_dungeon", "square_tunnel",
                    cx - step[0] * 6 - 3, y + 3, cz - step[1] * 6 - 3, dir % 2 == 0 ? 0 : 1));
            placed.add(pieces.place(world, "bronze_dungeon", room, cx, y, cz, 0));
            rooms.add(new int[]{cx, y, cz});
        }
        // boss room at the far end
        int[] last = rooms.get(rooms.size() - 1);
        DungeonPieces.Place boss = pieces.place(world, "bronze_dungeon", "boss_room",
                last[0] - 2, last[1], last[2] + 12, 0);
        placed.add(boss);
        Dungeon dungeon = register(world, "bronze", boss,
                "aether:chests/dungeon/bronze/bronze_dungeon_reward",
                "aether:chests/dungeon/bronze/bronze_dungeon");
        for (DungeonPieces.Place p : placed) {
            fillChests(world, p, dungeon);
        }
        // guardians + boss
        Location center = new Location(world, last[0] + 6, last[1] + 2, last[2] + 18);
        for (int i = 0; i < 2; i++) {
            plugin.mobs().spawnMob(world, center.clone().add(i * 2 - 1, 0, -4), "sentry", false);
        }
        var slider = plugin.mobs().spawnMob(world, center, "slider", false);
        if (slider != null) {
            dungeon.boss = slider.getUniqueId();
            fights.register(slider, dungeon.id);
        }
        saveAsync();
        plugin.getLogger().info("Built bronze dungeon " + dungeon.id + " with "
                + rooms.size() + " rooms.");
        return dungeon;
    }

    private static int pickDir(Random rng, int prev) {
        int d = rng.nextInt(4);
        return d == (prev + 2) % 4 ? prev : d;
    }

    private static int[] step(int dir) {
        return switch (dir) {
            case 0 -> new int[]{1, 0};
            case 1 -> new int[]{0, 1};
            case 2 -> new int[]{-1, 0};
            default -> new int[]{0, -1};
        };
    }

    // ---------------------------------------------------------- silver and gold

    public Dungeon buildSilver(World world, int x, int y, int z) {
        // The original silver temple has a cloud bed; it is intentionally a
        // sky structure, unlike bronze rooms embedded in an ordinary island.
        var cloud=Bukkit.createBlockData(plugin.content().block("cold_aercloud").carrier());
        for(int dx=-24;dx<=24;dx++)for(int dz=-28;dz<=52;dz++)for(int layer=1;layer<=3;layer++) {
            double radius=dx*dx/(24.0*24)+(dz-12)*(dz-12)/(40.0*40);
            if(radius>1-(layer-1)*.15 || y-layer<world.getMinHeight())continue;
            Block at=world.getBlockAt(x+dx,y-layer,z+dz);
            if(at.isEmpty())at.setBlockData(cloud,false);
        }
        // palace shell + attached boss room + staircase entry (simplified layout)
        DungeonPieces.Place shell = pieces.place(world, "silver_dungeon", "skeleton",
                x - 16, y, z - 13, 0);
        pieces.place(world, "silver_dungeon", "staircase", x - 2, y, z - 18, 0);
        DungeonPieces.Place boss = pieces.place(world, "silver_dungeon", "boss_room",
                x - 11, y, z + 13, 0);
        Dungeon dungeon = register(world, "silver", boss,
                "aether:chests/dungeon/silver/silver_dungeon_reward",
                "aether:chests/dungeon/silver/silver_dungeon");
        fillChests(world, shell, dungeon);
        fillChests(world, boss, dungeon);
        Location center = new Location(world, x, y + 2, z + 20);
        for (int i = 0; i < 2; i++) {
            plugin.mobs().spawnMob(world, center.clone().add(i * 2 - 1, 0, -4), "valkyrie", false);
        }
        var queen = plugin.mobs().spawnMob(world, center, "valkyrie_queen", false);
        if (queen != null) {
            dungeon.boss = queen.getUniqueId();
            fights.register(queen, dungeon.id);
        }
        saveAsync();
        plugin.getLogger().info("Built silver dungeon " + dungeon.id);
        return dungeon;
    }

    public Dungeon buildGold(World world, int x, int y, int z) {
        // island base + tunnel to the boss room + side stub
        pieces.place(world, "gold_dungeon", "island", x - 19, y - 20, z - 19, 0);
        pieces.place(world, "gold_dungeon", "tunnel", x - 3, y + 4, z - 20, 0);
        DungeonPieces.Place boss = pieces.place(world, "gold_dungeon", "boss_room",
                x - 11, y + 4, z - 48, 0);
        pieces.place(world, "gold_dungeon", "stub", x + 12, y + 4, z - 20, 0);
        Dungeon dungeon = register(world, "gold", boss,
                "aether:chests/dungeon/gold/gold_dungeon_reward",
                "aether:chests/dungeon/gold/gold_dungeon");
        fillChests(world, boss, dungeon);
        Location center = new Location(world, x, y + 6, z - 34);
        for (int i = 0; i < 2; i++) {
            plugin.mobs().spawnMob(world, center.clone().add(i * 2 - 1, 0, -4), "sentry", false);
        }
        var sun = plugin.mobs().spawnMob(world, center, "sun_spirit", false);
        if (sun != null) {
            dungeon.boss = sun.getUniqueId();
            fights.register(sun, dungeon.id);
        }
        saveAsync();
        plugin.getLogger().info("Built gold dungeon " + dungeon.id);
        return dungeon;
    }

    private Dungeon register(World world, String type, DungeonPieces.Place bossRoom,
                             String rewardTable, String chestTable) {
        Dungeon dungeon = new Dungeon();
        dungeon.id = type + "-" + (dungeons.size() + 1) + "-" + System.currentTimeMillis() % 100000;
        dungeon.type = type;
        dungeon.world = world.getKey().toString();
        dungeon.cx = bossRoom.ox();
        dungeon.cy = bossRoom.oy();
        dungeon.cz = bossRoom.oz();
        dungeon.radius = 40;
        dungeon.treasureTable = rewardTable;
        dungeon.chestTable = chestTable;
        dungeons.put(dungeon.id, dungeon);
        return dungeon;
    }

    private void fillChests(World world, DungeonPieces.Place place, Dungeon dungeon) {
        Random rng = new Random(world.getSeed() ^ place.ox() ^ place.oz());
        for (DungeonPieces.Marker m : place.markers()) {
            boolean treasure = m.kind().contains("Treasure");
            boolean normal = m.kind().equals("Chest");
            if (!treasure && !normal) {
                continue;
            }
            Location at = new Location(world, m.x(), m.y(), m.z());
            var def = plugin.content().block("treasure_chest");
            if (def != null) {
                try {
                    at.getBlock().setBlockData(Bukkit.createBlockData(def.carrier()), false);
                } catch (Exception ignored) {
                }
            }
            String key = posKey(world.getKey().toString(), m.x(), m.y(), m.z());
            dungeon.chestKinds.put(key, treasure ? "treasure" : "normal");
            if (normal) {
                List<ItemStack> rolled = loot().roll(dungeon.chestTable, rng);
                dungeon.chests.put(key, StationManager.encodeAll(rolled));
            }
        }
    }

    // ------------------------------------------------------------------ treasure

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChestOpen(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.NOTE_BLOCK) {
            return;
        }
        String id = plugin.content().blockIdForCarrier(clicked.getBlockData().getAsString());
        if (id == null || !id.equals("treasure_chest")) {
            return;
        }
        Dungeon dungeon = at(clicked.getLocation());
        if (dungeon == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        String key = posKey(clicked.getWorld().getKey().toString(),
                clicked.getX(), clicked.getY(), clicked.getZ());
        String kind = dungeon.chestKinds.getOrDefault(key, "treasure");
        if (kind.equals("treasure")) {
            if (dungeon.locked) {
                player.sendMessage(Component.text("Сундук запечатан. Победите стража подземелья.",
                        NamedTextColor.RED));
                return;
            }
            Inventory gui = Bukkit.createInventory(new TreasureGui(dungeon.id, null), 27,
                    Component.text("Сокровищница"));
            if (dungeon.treasureItems != null) {
                ItemStack[] stored = StationManager.decodeAll(dungeon.treasureItems);
                for (int i = 0; i < Math.min(stored.length, 27); i++) {
                    gui.setItem(i, stored[i]);
                }
            }
            player.openInventory(gui);
            return;
        }
        Inventory gui = Bukkit.createInventory(new TreasureGui(dungeon.id, key), 27,
                Component.text("Сундук подземелья"));
        String stored = dungeon.chests.get(key);
        if (stored != null) {
            ItemStack[] items = StationManager.decodeAll(stored);
            for (int i = 0; i < Math.min(items.length, 27); i++) {
                gui.setItem(i, items[i]);
            }
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onTreasureClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof TreasureGui gui)) {
            return;
        }
        Dungeon dungeon = dungeons.get(gui.dungeonId);
        if (dungeon == null) {
            return;
        }
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack s : event.getInventory().getContents()) {
            if (s != null && !s.getType().isAir()) {
                leftovers.add(s);
            }
        }
        if (gui.chestKey == null) {
            dungeon.treasureItems = StationManager.encodeAll(leftovers);
        } else {
            dungeon.chests.put(gui.chestKey, StationManager.encodeAll(leftovers));
        }
        saveAsync();
    }

    public void onBossDeath(UUID bossUuid) {
        for (Dungeon dungeon : dungeons.values()) {
            if (bossUuid.equals(dungeon.boss)) {
                dungeon.locked = false;
                dungeon.boss = null;
                fights.unregister(bossUuid);
                // open doorways
                World world = Bukkit.getWorld(
                        org.bukkit.NamespacedKey.fromString(dungeon.world));
                if (world != null) {
                    for (int x = dungeon.cx - dungeon.radius; x <= dungeon.cx + dungeon.radius; x++) {
                        for (int y = Math.max(world.getMinHeight(),dungeon.cy - 10); y <= Math.min(world.getMaxHeight()-1,dungeon.cy + 20); y++) {
                            for (int z = dungeon.cz - dungeon.radius; z <= dungeon.cz + dungeon.radius; z++) {
                                if(!world.isChunkLoaded(x>>4,z>>4))continue;
                                Block block = world.getBlockAt(x, y, z);
                                if(block.getType()==Material.STRUCTURE_VOID){block.setType(Material.AIR,false);continue;}
                                if (block.getType() != Material.NOTE_BLOCK) {
                                    continue;
                                }
                                String id = plugin.content().blockIdForCarrier(
                                        block.getBlockData().getAsString());
                                if (id != null && id.contains("doorway")) {
                                    block.setType(Material.AIR, false);
                                }
                            }
                        }
                    }
                }
                // roll treasure
                if (!dungeon.treasureDone) {
                    dungeon.treasureDone = true;
                    List<ItemStack> loot = loot().roll(dungeon.treasureTable,
                            new Random(world == null ? 0 : world.getSeed()));
                    ItemStack key = plugin.aetherItems().create(
                            dungeon.type + "_dungeon_key", 1);
                    if (key != null) {
                        loot.add(key);
                    }
                    dungeon.treasureItems = StationManager.encodeAll(loot);
                }
                saveAsync();
                plugin.getLogger().info("Dungeon beaten: " + dungeon.id);
            }
        }
    }

    private ChestLoot loot() {
        return plugin.chestLoot();
    }

    // --------------------------------------------------------------- protection

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreakLocked(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.NOTE_BLOCK) {
            return;
        }
        String id = plugin.content().blockIdForCarrier(block.getBlockData().getAsString());
        if (id == null) {
            return;
        }
        Dungeon dungeon = at(block.getLocation());
        if (dungeon == null || !dungeon.locked) {
            return;
        }
        if (isLockedBlock(id)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "Запечатано силой подземелья.", NamedTextColor.RED));
        } else if (isTrappedBlock(id)) {
            event.setCancelled(true);
            block.setType(Material.AIR, false);
            block.getWorld().createExplosion(block.getLocation().add(0.5, 0.5, 0.5),
                    4.0f, false, true);
        }
    }

    // -------------------------------------------------------------- generation

    private record BronzePart(String piece,int x,int y,int z,int rotation) {}
    private List<BronzePart> bronzePlan(long seed,int x,int y,int z,int rooms) {
        Random random=new Random(seed^(x*341873128712L+z*132897987541L));
        List<BronzePart> result=new ArrayList<>();
        result.add(new BronzePart("lobby",x,y,z,0));result.add(new BronzePart("entrance",x+3,y+1,z-1,0));
        int cx=x,cz=z,dir=random.nextInt(4);
        for(int i=0;i<rooms;i++){
            dir=pickDir(random,dir);int[] step=step(dir);cx+=step[0]*12;cz+=step[1]*12;
            String room=random.nextInt(4)==0?"chest_room":"lobby";
            result.add(new BronzePart("square_tunnel",cx-step[0]*6-3,y+3,cz-step[1]*6-3,dir%2==0?0:1));
            result.add(new BronzePart(room,cx,y,cz,0));
        }
        result.add(new BronzePart("boss_room",cx-2,y,cz+12,0));return result;
    }
    private boolean naturalStone(Block block) {
        String id=plugin.content().blockIdForCarrier(block.getBlockData().getAsString());
        return id!=null && (java.util.Set.of("aether_grass_block","enchanted_aether_grass_block","aether_dirt","holystone","mossy_holystone","icestone").contains(id)||id.endsWith("_ore"));
    }
    public boolean bronzeFits(World world,int x,int y,int z,int rooms) {
        for(BronzePart part:bronzePlan(world.getSeed(),x,y,z,rooms)){
            int[] size=pieces.sizeOf("bronze_dungeon",part.piece());
            int sx=part.rotation()%2==0?size[0]:size[2],sz=part.rotation()%2==0?size[2]:size[0];
            if(part.y()-1<world.getMinHeight() || part.y()+size[1]>=world.getMaxHeight())return false;
            for(int dx=-4;dx<sx+4;dx++)for(int dz=-4;dz<sz+4;dz++) {
                if(!naturalStone(world.getBlockAt(part.x()+dx,part.y()-1,part.z()+dz))
                        ||!naturalStone(world.getBlockAt(part.x()+dx,part.y()+size[1],part.z()+dz)))return false;
                if(dx<=0||dz<=0||dx>=sx-1||dz>=sz-1) for(int dy=0;dy<size[1];dy++)
                    if(!naturalStone(world.getBlockAt(part.x()+dx,part.y()+dy,part.z()+dz)))return false;
            }
        }
        return true;
    }
    private int surface(World world,int x,int z) {
        for(int y=world.getHighestBlockYAt(x,z);y>world.getMinHeight();y--)if(naturalStone(world.getBlockAt(x,y,z)))return y;
        return world.getMinHeight();
    }

    public static String naturalType(long seed,int chunkX,int chunkZ) {
        for (String type:List.of("gold","silver","bronze")) {
            int spacing=type.equals("bronze")?32:type.equals("silver")?64:64;
            int rx=Math.floorDiv(chunkX,spacing), rz=Math.floorDiv(chunkZ,spacing);
            Random random=new Random(seed ^ rx*341873128712L ^ rz*132897987541L ^ type.hashCode());
            if (chunkX==rx*spacing+random.nextInt(spacing) && chunkZ==rz*spacing+random.nextInt(spacing)) return type;
        }
        return null;
    }

    public void maybeGenerate(World world, int chunkX, int chunkZ) {
        if (!plugin.getConfig().getBoolean("dungeons.enabled",false)) return;
        if (!world.getKey().toString().equals(plugin.aetherDimensionKey())) {
            return;
        }
        Random rng = new Random(world.getSeed() ^ (chunkX * 341873128712L + chunkZ * 132897987541L));
        String type = naturalType(world.getSeed(),chunkX,chunkZ);
        int sep = type==null?0:type.equals("bronze")?10:type.equals("silver")?16:24;
        if (type == null) {
            return;
        }
        int x = chunkX * 16 + 8;
        int z = chunkZ * 16 + 8;
        for (Dungeon d : dungeons.values()) {
            // Keep all dungeon types separated, including silver and gold.
            double dist = Math.hypot(d.cx - x, d.cz - z) / 16.0;
            if (dist < Math.max(sep, 32)) {
                return;
            }
        }
        int ground = surface(world,x,z);
        if (ground < 20 || ground > 220) {
            return;
        }
        if (type.equals("bronze")) {
            for(int[] offset:new int[][]{{0,0},{16,0},{-16,0},{0,16},{0,-16},{32,0},{-32,0},{0,32},{0,-32}}) {
                int px=x+offset[0]-6,pz=z+offset[1]-6,py=surface(world,px+6,pz+6)-16;
                if(!bronzeFits(world,px,py,pz,4))continue;
                buildBronze(world,px,py,pz,4);
                carveBronzeEntrance(world,px,py,pz);return;
            }
        } else if (type.equals("silver")) {
            buildSilver(world, x, ground, z);
        } else {
            if(ground<40)return;
            buildGold(world, x, ground - 10, z);
        }
    }

    private void carveBronzeEntrance(World world,int x,int y,int z) {
        for(int distance=1;distance<=64;distance++){
            boolean outside=true;
            for(int dx=4;dx<=7;dx++)for(int dy=1;dy<=4;dy++)if(!world.getBlockAt(x+dx,y+dy,z-distance).isPassable())outside=false;
            if(outside)break;
            for(int dx=4;dx<=7;dx++)for(int dy=1;dy<=4;dy++)world.getBlockAt(x+dx,y+dy,z-distance).setType(Material.AIR,false);
        }
    }

    // -------------------------------------------------------------- persistence

    public void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveNow);
    }

    private void saveNow() {
        try {
            JsonArray arr = new JsonArray();
            for (Dungeon d : dungeons.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", d.id);
                o.addProperty("type", d.type);
                o.addProperty("world", d.world);
                o.addProperty("cx", d.cx);
                o.addProperty("cy", d.cy);
                o.addProperty("cz", d.cz);
                o.addProperty("radius", d.radius);
                o.addProperty("boss", d.boss == null ? "" : d.boss.toString());
                o.addProperty("locked", d.locked);
                o.addProperty("treasureDone", d.treasureDone);
                o.addProperty("treasureTable", d.treasureTable == null ? "" : d.treasureTable);
                o.addProperty("chestTable", d.chestTable == null ? "" : d.chestTable);
                o.addProperty("treasureItems", d.treasureItems == null ? "" : d.treasureItems);
                JsonObject chests = new JsonObject();
                for (Map.Entry<String, String> e : d.chests.entrySet()) {
                    chests.addProperty(e.getKey(), e.getValue());
                }
                o.add("chests", chests);
                JsonObject kinds = new JsonObject();
                for (Map.Entry<String, String> e : d.chestKinds.entrySet()) {
                    kinds.addProperty(e.getKey(), e.getValue());
                }
                o.add("chestKinds", kinds);
                arr.add(o);
            }
            File f = new File(plugin.getDataFolder(), "dungeons.json");
            Files.createDirectories(f.toPath().getParent());
            Files.writeString(f.toPath(), arr.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save dungeons: " + e);
        }
    }

    public void loadDungeons() {
        File f = new File(plugin.getDataFolder(), "dungeons.json");
        if (!f.isFile()) {
            return;
        }
        try {
            String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            for (JsonElement e : JsonParser.parseString(text).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                Dungeon d = new Dungeon();
                d.id = o.get("id").getAsString();
                d.type = o.get("type").getAsString();
                d.world = o.get("world").getAsString();
                d.cx = o.get("cx").getAsInt();
                d.cy = o.get("cy").getAsInt();
                d.cz = o.get("cz").getAsInt();
                d.radius = o.get("radius").getAsInt();
                String boss = o.get("boss").getAsString();
                d.boss = boss.isEmpty() ? null : UUID.fromString(boss);
                d.locked = o.get("locked").getAsBoolean();
                d.treasureDone = o.get("treasureDone").getAsBoolean();
                d.treasureTable = o.get("treasureTable").getAsString();
                d.chestTable = o.get("chestTable").getAsString();
                String ti = o.get("treasureItems").getAsString();
                d.treasureItems = ti.isEmpty() ? null : ti;
                if (o.has("chests") && o.get("chests").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> ce
                            : o.getAsJsonObject("chests").entrySet()) {
                        d.chests.put(ce.getKey(), ce.getValue().getAsString());
                    }
                }
                if (o.has("chestKinds") && o.get("chestKinds").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> ce
                            : o.getAsJsonObject("chestKinds").entrySet()) {
                        d.chestKinds.put(ce.getKey(), ce.getValue().getAsString());
                    }
                }
                dungeons.put(d.id, d);
            }
            plugin.getLogger().info("Loaded " + dungeons.size() + " dungeons.");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load dungeons: " + e);
        }
    }

    public Map<String, Dungeon> all() {
        return dungeons;
    }

    // ------------------------------------------------------- headless self-test

    /** Builds a bronze dungeon and runs a full boss cycle without players. */
    public void dungeonSelfTest() {
        if (!plugin.getConfig().getBoolean("debug.dungeon-test", false)) {
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
        Dungeon dungeon = buildBronze(world, 300, 160, 300, 3);
        if (dungeon.boss == null) {
            plugin.getLogger().info("  dungeon self-test boss");
            fails++;
        } else {
            var boss = Bukkit.getEntity(dungeon.boss);
            if (!(boss instanceof LivingEntity)) {
                plugin.getLogger().info("  dungeon self-test spawn");
                fails++;
            } else {
                // locked while alive?
                if (!dungeon.locked) {
                    plugin.getLogger().info("  dungeon self-test lock");
                    fails++;
                }
                ((LivingEntity) boss).damage(100000.0);
            }
        }
        final int fFails = fails;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int f = fFails;
            Dungeon d = all().get(dungeon.id);
            if (d == null || d.locked) {
                plugin.getLogger().info("  dungeon self-test unlock");
                f++;
            }
            if (d != null && d.treasureItems == null) {
                plugin.getLogger().info("  dungeon self-test treasure");
                f++;
            }
            plugin.getLogger().info("Dungeon self-test: " + (f == 0 ? "PASS" : "FAIL-" + f));
        }, 40L);
    }
}
