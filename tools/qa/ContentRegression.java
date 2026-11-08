import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.listener.CarrierSounds;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.nio.file.*;

/** Local-only regression harness, never packaged with the release plugin. */
public class ContentRegression extends JavaPlugin {
    private final List<LivingEntity> mobs = new ArrayList<>();
    private final Map<UUID, Location> initial = new HashMap<>();
    private final List<String> report = new ArrayList<>();
    private final Set<String> legAngles = new HashSet<>();
    private void check(boolean ok, String message) {
        String line = (ok ? "PASS " : "FAIL ") + message;
        report.add(line); getLogger().info(line);
    }
    public void onEnable() {
        getCommand("qacontent").setExecutor((sender, cmd, label, args) -> {
            if(args.length>0 && args[0].equals("geometry")){geometryChecks();return true;}
            if (args.length>0 && args[0].equals("world")) {worldChecks();return true;}
            if (args.length>0 && args[0].equals("sounds")) {
                var a=AetherPlugin.get();
                soundChecks(a,a.aetherWorldNoCreate(),Bukkit.getPlayerExact("AetherBot"));
            } else run();
            return true;
        });
    }

    private void geometryChecks() {
        var a=AetherPlugin.get(); World w=a.aetherWorldNoCreate();
        LivingEntity cow=a.mobs().spawnMob(w,new Location(w,200,201,207),"flying_cow",false);
        if(cow instanceof Mob mob)mob.getPathfinder().moveTo(cow.getLocation().add(3,0,0),1);
        Bukkit.getScheduler().runTaskLater(this,()->{
            try(var stream=AetherPlugin.class.getResourceAsStream("/assets/aether/models/entity/flying_cow.json")) {
                var model=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream)).getAsJsonObject();
                for(var node:model.getAsJsonArray("aether_limbs")) {
                    var def=node.getAsJsonObject(); var key=NamespacedKey.fromString(def.get("model").getAsString());
                    ItemDisplay part=w.getEntitiesByClass(ItemDisplay.class).stream().filter(d->d.getItemStack().hasItemMeta()
                            && key.equals(d.getItemStack().getItemMeta().getItemModel()) && d.getLocation().distanceSquared(cow.getLocation())<.1)
                            .findFirst().orElseThrow();
                    var t=part.getTransformation(); var local=new org.joml.Vector3f(0,-.5f,0).mul(t.getScale());
                    t.getLeftRotation().transform(local);local.add(t.getTranslation());
                    var p=def.getAsJsonArray("pivot"); var expected=new org.joml.Vector3f(p.get(0).getAsFloat(),p.get(1).getAsFloat(),p.get(2).getAsFloat());
                    check(local.distance(expected)<.0001,"cow joint attached "+key+" error="+local.distance(expected));
                }
                for(String id:List.of("berry_bush","berry_bush_stem")) {
                    var b=w.getBlockAt(199,201,208);b.setBlockData(Bukkit.createBlockData(a.content().block(id).carrier()),false);
                    check(!b.getBlockData().isOccluding(),id+" does not occlude soil");
                    check(id.equals(a.content().blockIdForCarrier(b.getBlockData().getAsString())),id+" identity retained");
                    b.setType(Material.AIR,false);
                }
                check(a.mobs().defs().values().stream().noneMatch(d->d.carrier()==EntityType.PHANTOM),"no phantom carriers remain");
                Files.write(Path.of("../history/verification/geometry-regression.txt"),report);
                getLogger().info("QA_GEOMETRY_FINISHED");
            }catch(Exception e){throw new RuntimeException(e);}finally{cow.remove();}
        },40);
    }

    private void worldChecks() {
        var a=AetherPlugin.get(); World world=a.aetherWorldNoCreate();
        try {
            Object handle=world.getClass().getMethod("getHandle").invoke(world);
            Object dimension=handle.getClass().getMethod("dimensionType").invoke(handle);
            Object skybox=dimension.getClass().getMethod("skybox").invoke(dimension);
            check(skybox.toString().equals("OVERWORLD"),"runtime skybox="+skybox);
        } catch(Exception e) {throw new RuntimeException(e);}
        int before=a.dungeons().all().size();
        java.util.Queue<int[]> candidates=new java.util.ArrayDeque<>();
        for(int rx=30;rx<36;rx++) for(int rz=30;rz<34;rz++) {
            for(int dx=0;dx<16;dx++) for(int dz=0;dz<16;dz++) {
                int cx=rx*16+dx,cz=rz*16+dz;
                if ("bronze".equals(com.aetherport.aether.dungeon.DungeonManager.naturalType(world.getSeed(),cx,cz))) candidates.add(new int[]{cx,cz});
            }
        }
        int[] scanned={0},trees={0};
        new org.bukkit.scheduler.BukkitRunnable() {
            public void run() {
                if(candidates.isEmpty() || scanned[0]>=12 || a.dungeons().all().size()>before && trees[0]>0) {
                    check(trees[0]>0,"trees rooted on ordinary Aether grass="+trees[0]);
                    check(a.dungeons().all().size()>before,"naturally generated dungeons="+(a.dungeons().all().size()-before));
                    getLogger().info("QA_WORLD_FINISHED candidates="+scanned[0]);
                    try {Files.write(Path.of("../history/verification/world-regression.txt"),report);}catch(Exception e){throw new RuntimeException(e);}
                    cancel(); return;
                }
                int[] c=candidates.remove(); var chunk=world.getChunkAt(c[0],c[1]); scanned[0]++;
                for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=21;y<220;y++) {
                    var b=chunk.getBlock(x,y,z);
                    String id=a.content().blockIdForCarrier(b.getBlockData().getAsString());
                    if(!"skyroot_log".equals(id) && !"golden_oak_log".equals(id))continue;
                    String below=a.content().blockIdForCarrier(b.getRelative(0,-1,0).getBlockData().getAsString());
                    if("aether_grass_block".equals(below)||"enchanted_aether_grass_block".equals(below))trees[0]++;
                }
                getLogger().info("QA_WORLD candidate="+c[0]+","+c[1]+" trees="+trees[0]+" dungeons="+(a.dungeons().all().size()-before));
            }
        }.runTaskTimer(this,1,10);
    }
    private void run() {
        AetherPlugin a = AetherPlugin.get();
        World world = a.aetherWorldNoCreate();
        if (world == null || Bukkit.getPlayerExact("AetherBot") == null) throw new IllegalStateException("Missing QA world/client");
        for (int x=180; x<=220; x++) for (int z=180; z<=220; z++) {
            world.getBlockAt(x, 200, z).setType(Material.GRASS_BLOCK, false);
            for (int y=201; y<=212; y++) world.getBlockAt(x,y,z).setType(Material.AIR, false);
        }
        Player bot = Bukkit.getPlayerExact("AetherBot");
        bot.setGameMode(GameMode.CREATIVE);
        bot.teleport(new Location(world,200,201,200));
        String[] ids = {"flying_cow","phyg","aerbunny","moa","cockatrice","aerwhale","sheepuff"};
        for (int i=0; i<ids.length; i++) {
            LivingEntity mob = a.mobs().spawnMob(world, new Location(world,185+i*5,201,192),ids[i],false);
            mobs.add(mob); initial.put(mob.getUniqueId(),mob.getLocation());
            check(mob.isInvisible(), ids[i]+" carrier hidden");
            var max = mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
            check(Math.abs(mob.getHealth()-max)<.001, ids[i]+" full health="+mob.getHealth()+"/"+max);
            if (mob instanceof Mob m) check(!Bukkit.getMobGoals().getAllGoals(m).isEmpty(), ids[i]+" AI goals retained");
        }
        for (int i=0;i<2;i++) {
            String id = i==0 ? "white_flower" : "purple_flower";
            var block=world.getBlockAt(195+i,201,205);
            block.setBlockData(Bukkit.createBlockData(a.content().block(id).carrier()),false);
            check(id.equals(a.content().blockIdForCarrier(block.getBlockData().getAsString())),id+" state roundtrip");
            check(block.isPassable() && block.getCollisionShape().getBoundingBoxes().isEmpty(), id+" no solid collision");
            check(!block.getBlockData().isOccluding(),id+" does not occlude soil");
            check(block.getBoundingBox().getHeight()<.6,id+" selection height="+block.getBoundingBox().getHeight());
        }
        check(CarrierSounds.audible(Sound.BLOCK_WOOD_BREAK).equals("aether:carrier_original.block.wood.break"),"wood sound alias");
        check(CarrierSounds.audible(Sound.BLOCK_STONE_BREAK).equals("aether:carrier_original.block.stone.break"),"stone carrier sound alias");
        LivingEntity bird=mobs.get(4);
        check(bird.getType()==EntityType.HUSK && bird.isSilent(),"cockatrice has no spider carrier/eyes/sounds");
        LivingEntity oldBird=(LivingEntity)world.spawnEntity(new Location(world,190,201,210),EntityType.SPIDER);
        oldBird.getPersistentDataContainer().set(a.mobs().mobKey(),org.bukkit.persistence.PersistentDataType.STRING,"cockatrice");
        LivingEntity migrated=a.mobs().migrateCarrier(oldBird,"cockatrice");
        check(!oldBird.isValid() && migrated.getType()==EntityType.HUSK,"old spider carrier migrated");
        migrated.remove();
        var sampler=Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (ItemDisplay display: world.getEntitiesByClass(ItemDisplay.class)) {
                var meta=display.getItemStack().getItemMeta();
                if (meta!=null && meta.getItemModel()!=null && meta.getItemModel().toString().startsWith("aether:entity/flying_cow__leg"))
                    legAngles.add(display.getTransformation().getLeftRotation().toString());
            }
        },2,2);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (LivingEntity e:mobs) if (e instanceof Mob m && !"aerwhale".equals(a.mobs().mobOf(e))) {
                m.getPathfinder().moveTo(e.getLocation().add(0,0,6),1.0);
            }
            LivingEntity rabbit=mobs.get(2);
            bot.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.AIR));
            bot.attack(rabbit);
            check(rabbit.getHealth()<rabbit.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(),"aerbunny damage accepted");
            world.getBlockAt(194,201,205).setType(Material.STONE);
            world.getBlockAt(197,201,205).setType(Material.STONE);
            bot.teleport(new Location(world,195.5,201,205.5));
            var book=a.aetherItems().create("book_of_lore",1);
            bot.getInventory().setItemInMainHand(book);
            var use=new org.bukkit.event.player.PlayerInteractEvent(bot,
                    org.bukkit.event.block.Action.RIGHT_CLICK_AIR,book,null,null,org.bukkit.inventory.EquipmentSlot.HAND);
            use.setCancelled(true);
            Bukkit.getPluginManager().callEvent(use);
            getLogger().info("QA_BOOK_INTERACTION");
            Bukkit.getScheduler().runTaskLater(this,()->bot.performCommand("aetherlore 2"),10);
        },20);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            sampler.cancel();
            check(legAngles.size()>3,"cow leg animation rotations="+legAngles.size());
            check(world.getEntitiesByClass(ItemDisplay.class).stream().anyMatch(d->d.getItemStack().hasItemMeta()
                    && NamespacedKey.fromString("aether:entity/sheepuff__wool_body").equals(d.getItemStack().getItemMeta().getItemModel())),"sheepuff wool body present");
            for (LivingEntity e:mobs) {
                String id=a.mobs().mobOf(e);
                double distance=e.getLocation().distance(initial.get(e.getUniqueId()));
                if (!id.equals("aerwhale")) check(distance>.5,id+" moved="+distance);
                ItemDisplay display=world.getEntitiesByClass(ItemDisplay.class).stream().filter(d -> {
                    var meta=d.getItemStack().getItemMeta();
                    return meta!=null && NamespacedKey.fromString("aether:entity/"+id).equals(meta.getItemModel());
                }).min(Comparator.comparingDouble(d->d.getLocation().distanceSquared(e.getLocation()))).orElse(null);
                check(display!=null && display.getLocation().distance(e.getLocation())<.5,id+" visual follows feet");
                if (id.equals("aerwhale") && display!=null) check(display.getTransformation().getScale().x>4,"aerwhale full normalised scale="+display.getTransformation().getScale().x);
                if (display!=null) {
                    var t=display.getTransformation();
                    var origin=new org.joml.Vector3f(0,-.5f,0).mul(t.getScale());
                    t.getLeftRotation().transform(origin); origin.add(t.getTranslation());
                    check(origin.length()<.001,id+" body model origin at carrier feet");
                }
            }
            for (int i=0;i<2;i++) {
                String id=i==0 ? "white_flower" : "purple_flower";
                var block=world.getBlockAt(195+i,201,205);
                check(id.equals(a.content().blockIdForCarrier(block.getBlockData().getAsString())),id+" survives neighbor updates/player contact");
            }
            soundChecks(a,world,bot);
            try { Files.write(Path.of("../history/verification/content-regression.txt"),report); }
            catch(Exception ex) {throw new RuntimeException(ex);}
            for (LivingEntity e:mobs) e.remove();
            getLogger().info("QA_FINISHED failures="+report.stream().filter(s->s.startsWith("FAIL")).count());
        },240);
    }

    private void soundChecks(AetherPlugin a, World world, Player bot) {
        getLogger().info("QA_SOUND_START");
        for (String id: List.of("holystone", "aether_grass_block", "white_flower", "skyroot_log")) {
                var block=world.getBlockAt(199,201,205);
                block.setBlockData(Bukkit.createBlockData(a.content().block(id).carrier()), false);
                Bukkit.getPluginManager().callEvent(new org.bukkit.event.block.BlockBreakEvent(block,bot));
                check(block.isEmpty(),id+" break handled");
                var item=a.aetherItems().create(id,1);
                bot.getInventory().setItemInMainHand(item);
                var event=new org.bukkit.event.player.PlayerInteractEvent(bot,
                        org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, item,
                        block.getRelative(0,-1,0), org.bukkit.block.BlockFace.UP,
                        org.bukkit.inventory.EquipmentSlot.HAND);
                Bukkit.getPluginManager().callEvent(event);
                check(id.equals(a.content().blockIdForCarrier(block.getBlockData().getAsString())),id+" placement handled");
                block.setType(Material.AIR,false);
        }
        getLogger().info("QA_SOUND_END");
    }
}
