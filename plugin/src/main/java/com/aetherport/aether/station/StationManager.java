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
import org.bukkit.event.inventory.*;
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
        Inventory inventory;

        Gui(String key) {
            this.key = key;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
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
        int burnTotal;
        Inventory inventory;
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
    @EventHandler public void onServerLoad(org.bukkit.event.server.ServerLoadEvent event){Bukkit.getScheduler().runTask(plugin,this::loadStations);}

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
        if(state.inventory==null){
            Gui holder=new Gui(keyOf(loc));
            state.inventory=Bukkit.createInventory(holder,InventoryType.FURNACE,Component.text(STATION_BLOCKS.get(type)));
            holder.inventory=state.inventory;
        }
        refreshViewers(state);
        player.openInventory(state.inventory);
        refreshViewers(state);
        player.sendMessage(Component.text(STATION_BLOCKS.get(type)+": верхний слот — "+(type.equals("incubator")?"яйцо моа":"предмет")+", нижний — "+fuelLabel(type)+". "+(type.equals("incubator")?"Птенец появится над блоком.":"Готовый предмет появится справа."),NamedTextColor.GRAY));
    }

    private String fuelLabel(String type){return type.equals("freezer")?"ледяной камень":type.equals("incubator")?"амброзиевый факел":"осколки или блоки амброзия";}
    private Map<String,Integer> fuels(State state){return state.type.equals("freezer")?Recipes.FREEZER_FUEL:state.type.equals("incubator")?Recipes.INCUBATOR_FUEL:Recipes.ALTAR_FUEL;}
    private static ItemStack copy(ItemStack item){return isEmpty(item)?null:item.clone();}
    private boolean accepts(State state,int slot,ItemStack item){
        if(isEmpty(item))return true;
        if(slot==1)return fuelTimeOf(fuels(state),item)>0;
        if(slot!=0)return false;
        if(state.type.equals("incubator"))return findHatch(item)!=null;
        if(recipes.findCook(state.type.equals("freezer")?recipes.freezing():recipes.enchanting(),item)!=null)return true;
        return findRepair(state.type,item)!=null;
    }
    private ItemStack get(State state,int slot){return slot==0?state.input:slot==1?state.fuel:state.output;}
    private void set(State state,int slot,ItemStack item){
        item=copy(item);
        if(slot==0){if(isEmpty(item)||isEmpty(state.input)||!state.input.isSimilar(item))state.progress=0;state.input=item;}
        else if(slot==1)state.fuel=item;else state.output=item;
    }
    private int insert(State state,int slot,ItemStack source){
        if(!accepts(state,slot,source)||isEmpty(source))return 0;
        ItemStack current=get(state,slot);if(!isEmpty(current)&&!current.isSimilar(source))return 0;
        int amount=Math.min(source.getAmount(),source.getMaxStackSize()-(isEmpty(current)?0:current.getAmount()));
        if(amount>0){ItemStack combined=isEmpty(current)?source.clone():current.clone();combined.setAmount((isEmpty(current)?0:current.getAmount())+amount);set(state,slot,combined);}
        return amount;
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

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onGuiClick(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof Gui gui))return;
        State state=stations.get(gui.key);Player player=(Player)event.getWhoClicked();int slot=event.getRawSlot();
        event.setCancelled(true);
        if(state==null)return;
        if(event.getClick()==ClickType.DOUBLE_CLICK || event.getAction()==InventoryAction.COLLECT_TO_CURSOR)return;
        if(slot<0){event.setCancelled(false);return;}
        if(slot>=3){
            if(!event.isShiftClick()){event.setCancelled(false);return;}
            ItemStack source=copy(event.getCurrentItem());if(isEmpty(source))return;
            int target=accepts(state,1,source)?1:0;int moved=insert(state,target,source);
            if(moved>0){source.setAmount(source.getAmount()-moved);event.setCurrentItem(copy(source));if(target==0)state.lastUser=player.getUniqueId();}
        }else{
            if(slot==2 && state.type.equals("incubator"))return;
            ItemStack current=copy(get(state,slot)),cursor=copy(player.getItemOnCursor());
            if(event.isShiftClick()){
                if(isEmpty(current))return;
                var left=player.getInventory().addItem(current);set(state,slot,left.isEmpty()?null:left.values().iterator().next());
            }else if(event.getClick()==ClickType.NUMBER_KEY || event.getClick()==ClickType.SWAP_OFFHAND){
                int hot=event.getHotbarButton();boolean off=event.getClick()==ClickType.SWAP_OFFHAND;
                ItemStack held=copy(off?player.getInventory().getItemInOffHand():player.getInventory().getItem(hot));
                if(slot==2){
                    if(isEmpty(current)||!isEmpty(held)&&!held.isSimilar(current))return;
                    int count=Math.min(current.getAmount(),current.getMaxStackSize()-(isEmpty(held)?0:held.getAmount()));if(count<=0)return;
                    ItemStack result=current.clone();result.setAmount(count+(isEmpty(held)?0:held.getAmount()));current.setAmount(current.getAmount()-count);set(state,slot,current);
                    if(off)player.getInventory().setItemInOffHand(result);else player.getInventory().setItem(hot,result);
                }else{
                    if(!accepts(state,slot,held))return;set(state,slot,held);
                    if(off)player.getInventory().setItemInOffHand(current);else player.getInventory().setItem(hot,current);
                }
            }else if(event.getClick()==ClickType.DROP || event.getClick()==ClickType.CONTROL_DROP){
                if(isEmpty(current))return;ItemStack dropped=current.clone();int count=event.getClick()==ClickType.DROP?1:current.getAmount();dropped.setAmount(count);current.setAmount(current.getAmount()-count);set(state,slot,current);player.getWorld().dropItemNaturally(player.getLocation(),dropped);
            }else if(event.getClick()==ClickType.LEFT || event.getClick()==ClickType.RIGHT){
                boolean right=event.getClick()==ClickType.RIGHT;
                if(isEmpty(cursor)){
                    if(isEmpty(current))return;int count=right?(current.getAmount()+1)/2:current.getAmount();cursor=current.clone();cursor.setAmount(count);current.setAmount(current.getAmount()-count);set(state,slot,current);player.setItemOnCursor(cursor);
                }else if(slot==2){
                    if(isEmpty(current)||!cursor.isSimilar(current))return;int count=Math.min(current.getAmount(),cursor.getMaxStackSize()-cursor.getAmount());cursor.setAmount(cursor.getAmount()+count);current.setAmount(current.getAmount()-count);set(state,slot,current);player.setItemOnCursor(cursor);
                }else if(isEmpty(current)||current.isSimilar(cursor)){
                    ItemStack portion=cursor.clone();if(right)portion.setAmount(1);int moved=insert(state,slot,portion);cursor.setAmount(cursor.getAmount()-moved);player.setItemOnCursor(copy(cursor));
                }else if(accepts(state,slot,cursor)){set(state,slot,cursor);player.setItemOnCursor(current);}
            }
            if(slot==0&&!isEmpty(state.input))state.lastUser=player.getUniqueId();
        }
        refreshViewers(state);saveAsync();
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onGuiDrag(InventoryDragEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof Gui gui))return;
        if(event.getRawSlots().stream().noneMatch(slot->slot<3))return;
        event.setCancelled(true);State state=stations.get(gui.key);if(state==null)return;
        for(var entry:event.getNewItems().entrySet())if(entry.getKey()<3 && !accepts(state,entry.getKey(),entry.getValue()))return;
        for(var entry:event.getNewItems().entrySet()){
            if(entry.getKey()<3){set(state,entry.getKey(),entry.getValue());if(entry.getKey()==0)state.lastUser=event.getWhoClicked().getUniqueId();}
            else event.getView().setItem(entry.getKey(),entry.getValue().clone());
        }
        event.getWhoClicked().setItemOnCursor(copy(event.getCursor()));refreshViewers(state);saveAsync();
    }
    @EventHandler public void onGuiClose(InventoryCloseEvent event){if(event.getInventory().getHolder() instanceof Gui)saveAsync();}
    @EventHandler(ignoreCancelled=true) public void onVanillaRecipeBook(com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent event){
        if(event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Gui)event.setCancelled(true);
    }

    // -------------------------------------------------------------------- tick

    public void tick() {
        for (State state : List.copyOf(stations.values())) {
            if (state.loc.getWorld() == null) {
                continue;
            }
            if (!state.loc.getWorld().isChunkLoaded(state.loc.getBlockX()>>4,state.loc.getBlockZ()>>4)) {
                continue;
            }
            String actual=plugin.content().blockIdForCarrier(state.loc.getBlock().getBlockData().getAsString());
            if(!state.type.equals(actual)){breakStation(state.loc.getBlock());continue;}
            process(state);
        }
        if(Bukkit.getCurrentTick()%100==0)saveAsync();
    }

    private void process(State state) {
        if(state.burn>0)state.burn--;
        if (isEmpty(state.input)) {
            state.progress = 0;
            refreshViewers(state);
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
            refreshViewers(state);
            return;
        }
        if (cook != null && !outputFits(state, cook.out(), cook.count())) {
            state.progress=0;refreshViewers(state);
            return;
        }
        ItemStack repaired=null;
        if(repair!=null){repaired=state.input.clone();repaired.setAmount(1);var meta=(Damageable)repaired.getItemMeta();meta.setDamage(0);repaired.setItemMeta(meta);
            if(!stackFits(state,repaired)){state.progress=0;refreshViewers(state);return;}}
        Map<String, Integer> fuel = state.type.equals("freezer")
                ? Recipes.FREEZER_FUEL : Recipes.ALTAR_FUEL;
        if (state.burn <= 0) {
            int burn = fuelTimeOf(fuel, state.fuel);
            if (burn <= 0) {
                state.progress = 0;
                refreshViewers(state);
                return;
            }
            consumeOne(state, true);
            state.burn = burn;
            state.burnTotal=burn;
        }
        state.progress++;
        refreshViewers(state);
        if (state.progress >= need) {
            state.progress = 0;
            if (cook != null) {
                ItemStack out = "minecraft:air".equals(cook.out()) ? null
                        : buildOutput(cook.out(), cook.count());
                addOutput(state, out);
                consumeIngredient(state);
            } else {
                addOutput(state, repaired);
                consumeOne(state, false);
            }
            refreshViewers(state);saveAsync();
        }
    }

    private void processHatch(State state) {
        Recipes.Hatch hatch = findHatch(state.input);
        if (hatch == null) {
            state.progress = 0;
            refreshViewers(state);
            return;
        }
        if(!state.loc.getBlock().getRelative(0,1,0).isPassable() || !state.loc.getBlock().getRelative(0,2,0).isPassable()){refreshViewers(state);return;}
        if (state.burn <= 0) {
            int burn = fuelTimeOf(Recipes.INCUBATOR_FUEL, state.fuel);
            if (burn <= 0) {
                state.progress = 0;
                refreshViewers(state);
                return;
            }
            consumeOne(state, true);
            state.burn = burn;
            state.burnTotal=burn;
        }
        state.progress++;
        refreshViewers(state);
        if (state.progress >= hatch.time()) {
            String eggId = plugin.aetherItems().idOf(state.input);
            Location at = state.loc.clone().add(0.5, 1.0, 0.5);
            var moa = plugin.mobs().spawnMob(at.getWorld(), at, "moa", false);
            if(moa==null){refreshViewers(state);return;}
            state.progress = 0;
            consumeOne(state, false);
            if (moa instanceof Horse horse) {
                horse.setBaby();
                if(state.lastUser!=null){horse.setTamed(true);horse.setOwner(Bukkit.getOfflinePlayer(state.lastUser));}
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
            refreshViewers(state);saveAsync();
        }
    }

    private Recipes.Cook findCook(State state) {
        List<Recipes.Cook> list = state.type.equals("freezer")
                ? recipes.freezing() : recipes.enchanting();
        return recipes.findCook(list, state.input);
    }

    private Recipes.Repair findRepair(State state) {
        return findRepair(state.type,state.input);
    }
    private Recipes.Repair findRepair(String type,ItemStack input){
        if (!type.equals("altar") || isEmpty(input)) {
            return null;
        }
        ItemMeta meta = input.getItemMeta();
        if (!(meta instanceof Damageable d) || d.getDamage() <= 0) {
            return null;
        }
        for (Recipes.Repair r : recipes.repairing()) {
            for (String ref : r.in()) {
                if (recipes.matches(ref, input)) {
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
        ItemStack probe = buildOutput(outId, count);
        return stackFits(state,probe);
    }
    private boolean stackFits(State state,ItemStack probe){return probe!=null && (isEmpty(state.output)?probe.getAmount()<=probe.getMaxStackSize():state.output.isSimilar(probe)&&state.output.getAmount()+probe.getAmount()<=state.output.getMaxStackSize());}
    private void consumeIngredient(State state){
        ItemStack input=state.input;
        Material remainder=input.getType()==Material.WATER_BUCKET||input.getType()==Material.LAVA_BUCKET?Material.BUCKET:null;
        consumeOne(state,false);
        if(remainder!=null){state.input=new ItemStack(remainder);refreshViewers(state);}
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

    private void updateProgressItemFor(State state){
        if(state.inventory==null)return;
        state.inventory.setItem(0,copy(state.input));state.inventory.setItem(1,copy(state.fuel));state.inventory.setItem(2,copy(state.output));
        if(state.type.equals("incubator")){
            ItemStack info=new ItemStack(Material.EGG);var meta=info.getItemMeta();int total=need(state);
            meta.displayName(Component.text("Инкубация: "+(total==0?0:Math.min(100,state.progress*100/total))+"%"));
            meta.lore(List.of(Component.text("Яйцо — сверху, амброзиевый факел — снизу."),Component.text("Птенец появится над инкубатором.")));info.setItemMeta(meta);state.inventory.setItem(2,info);
        }
        for(var viewer:List.copyOf(state.inventory.getViewers())){
            var view=viewer.getOpenInventory();
            view.setProperty(org.bukkit.inventory.InventoryView.Property.COOK_TIME,state.progress);
            view.setProperty(org.bukkit.inventory.InventoryView.Property.TICKS_FOR_CURRENT_SMELTING,Math.max(1,need(state)));
            view.setProperty(org.bukkit.inventory.InventoryView.Property.BURN_TIME,state.burn);
            view.setProperty(org.bukkit.inventory.InventoryView.Property.TICKS_FOR_CURRENT_FUEL,Math.max(1,state.burnTotal));
        }
    }
    public void breakStation(Block block){
        State state=stations.remove(keyOf(block.getLocation()));if(state==null)return;
        if(state.inventory!=null)for(var viewer:List.copyOf(state.inventory.getViewers()))viewer.closeInventory();
        for(ItemStack item:new ItemStack[]{state.input,state.fuel,state.output})if(!isEmpty(item))block.getWorld().dropItemNaturally(block.getLocation().add(.5,.5,.5),item.clone());
        saveAsync();
    }
    public void close(){closing=true;for(State state:stations.values())if(state.inventory!=null)for(var viewer:List.copyOf(state.inventory.getViewers()))viewer.closeInventory();saveNow();}

    // ------------------------------------------------------------- persistence

    private boolean saveQueued;
    private boolean closing;
    private final java.util.concurrent.atomic.AtomicReference<String> pendingSave=new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.ExecutorService writer=java.util.concurrent.Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"Aether-stations-save");thread.setDaemon(true);return thread;});
    private void drainSave(){String latest=pendingSave.getAndSet(null);if(latest!=null)writeSnapshot(latest);}
    private void writeSnapshot(String text){
        try{
            var file=plugin.getDataFolder().toPath().resolve("stations.json");Files.createDirectories(file.getParent());var temp=file.resolveSibling("stations.json.tmp");Files.writeString(temp,text,StandardCharsets.UTF_8);
            try{Files.move(temp,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(temp,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception e){plugin.getLogger().warning("Failed to save stations: "+e);}
    }
    public void saveAsync() {
        if(closing||!plugin.isEnabled())return;
        if(saveQueued)return;saveQueued=true;
        Bukkit.getScheduler().runTask(plugin,()->{saveQueued=false;pendingSave.set(snapshot());writer.execute(this::drainSave);});
    }

    private void saveNow(){
        pendingSave.set(snapshot());
        try{writer.submit(this::drainSave).get(30,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){plugin.getLogger().warning("Station save flush: "+e);}
        if(closing)writer.shutdown();
    }
    private String snapshot() {
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
                o.addProperty("burnTotal",s.burnTotal);
                if(s.lastUser!=null)o.addProperty("lastUser",s.lastUser.toString());
                arr.add(o);
            }
        return arr.toString();
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
                s.burnTotal=o.has("burnTotal")?o.get("burnTotal").getAsInt():s.burn;
                if(o.has("lastUser"))s.lastUser=UUID.fromString(o.get("lastUser").getAsString());
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
