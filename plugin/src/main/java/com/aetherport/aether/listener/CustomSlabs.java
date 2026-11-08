package com.aetherport.aether.listener;

import com.aetherport.aether.AetherPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.*;
import org.bukkit.event.world.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import java.util.*;

/** Actual vanilla slab collision; per-position identity and original model.
 * No vanilla slab material or blockstate is globally reserved/retextured. */
public final class CustomSlabs implements Listener {
    private final AetherPlugin plugin;
    private final Map<Location,ItemDisplay> visuals=new HashMap<>();
    private final Map<Location,Slab.Type> shapes=new HashMap<>();
    private final Map<Location,Map<Integer,Integer>> damage=new HashMap<>();
    private final ArrayDeque<Chunk> pending=new ArrayDeque<>();
    private final Set<Chunk> queued=new HashSet<>();
    public CustomSlabs(AetherPlugin plugin){
        this.plugin=plugin;Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getScheduler().runTaskTimer(plugin,()->{
            for(int n=0;n<2&&!pending.isEmpty();n++){Chunk c=pending.remove();queued.remove(c);if(c.isLoaded())restore(c);}
            if(Bukkit.getCurrentTick()%20==0)for(Location loc:List.copyOf(visuals.keySet())){
                if(!loc.getWorld().isChunkLoaded(loc.getBlockX()>>4,loc.getBlockZ()>>4)){removeVisual(loc);continue;}
                Block b=loc.getBlock();String id=storedId(b);if(id==null){forget(b);continue;}render(b,id);
            }
        },1,1);
    }
    public static NamespacedKey key(Block b){return new NamespacedKey("aether","slab_"+(b.getX()&15)+"_"+b.getY()+"_"+(b.getZ()&15));}
    public static String storedId(Block b){return b.getBlockData() instanceof Slab?b.getChunk().getPersistentDataContainer().get(key(b),PersistentDataType.STRING):null;}
    public boolean isSlab(String id){return id!=null&&id.endsWith("_slab")&&plugin.content().isBlock(id);}
    private void queue(Chunk c){if(queued.add(c)){if(c.getPersistentDataContainer().getKeys().stream().anyMatch(k->k.getNamespace().equals("aether")&&k.getKey().startsWith("slab_")))pending.addFirst(c);else pending.addLast(c);}}
    @EventHandler public void loaded(ChunkLoadEvent e){queue(e.getChunk());}
    @EventHandler public void startup(org.bukkit.event.server.ServerLoadEvent e){for(World w:Bukkit.getWorlds())for(Chunk c:w.getLoadedChunks())queue(c);}
    @EventHandler public void unload(ChunkUnloadEvent e){for(Location loc:List.copyOf(visuals.keySet()))if(loc.getWorld().equals(e.getWorld())&&loc.getBlockX()>>4==e.getChunk().getX()&&loc.getBlockZ()>>4==e.getChunk().getZ())removeVisual(loc);}
    private void restore(Chunk c){
        for(NamespacedKey k:List.copyOf(c.getPersistentDataContainer().getKeys()))if(k.getNamespace().equals("aether")&&k.getKey().startsWith("slab_")){
            try{String[] xyz=k.getKey().substring(5).split("_");Block b=c.getBlock(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]));String id=storedId(b);if(isSlab(id))render(b,id);else c.getPersistentDataContainer().remove(k);}catch(RuntimeException ignored){}
        }
        var snapshot=c.getChunkSnapshot();boolean legacy=false;
        for(var def:plugin.content().blocks().values())if(isSlab(def.id())&&snapshot.contains(Bukkit.createBlockData(def.carrier()))){legacy=true;break;}
        if(!legacy)return;
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=c.getWorld().getMinHeight();y<=snapshot.getHighestBlockYAt(x,z);y++){
            if(snapshot.getBlockType(x,y,z)!=Material.NOTE_BLOCK)continue;
            Block b=c.getBlock(x,y,z);String id=plugin.content().blockIdForCarrier(b.getBlockData().getAsString());if(isSlab(id))migrate(b,id);
        }
    }
    public void migrate(Block b,String id){
        if(!isSlab(id)||b.getBlockData() instanceof Slab)return;
        Slab data=(Slab)Bukkit.createBlockData(id.startsWith("skyroot")?Material.OAK_SLAB:Material.STONE_SLAB);
        data.setType(Slab.Type.BOTTOM);b.setBlockData(data,false);mark(b,id);render(b,id);
    }
    private void mark(Block b,String id){b.getChunk().getPersistentDataContainer().set(key(b),PersistentDataType.STRING,id);}
    public void forget(Block b){b.getChunk().getPersistentDataContainer().remove(key(b));damage.remove(b.getLocation());removeVisual(b.getLocation());}
    private void removeVisual(Location loc){var visual=visuals.remove(loc);if(visual!=null)visual.remove();shapes.remove(loc);}
    private void render(Block b,String id){
        if(!(b.getBlockData() instanceof Slab slab))return;Location loc=b.getLocation();ItemDisplay visual=visuals.get(loc);
        if(visual!=null&&visual.isValid()&&shapes.get(loc)==slab.getType())return;
        removeVisual(loc);ItemStack item=plugin.aetherItems().create(id,1);var meta=item.getItemMeta();meta.setItemModel(new NamespacedKey("aether","slabs/"+id+"/"+slab.getType().name().toLowerCase(Locale.ROOT)));item.setItemMeta(meta);
        visual=b.getWorld().spawn(loc.clone().add(.5,.5,.5),ItemDisplay.class,d->{
            d.setItemStack(item);d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);d.setPersistent(false);d.setInvulnerable(true);d.setSilent(true);d.setDisplayWidth(0);d.setDisplayHeight(0);d.setViewRange(4f);d.setShadowRadius(0);
            Transformation transform=d.getTransformation();transform.getScale().set(new Vector3f(1.002f));d.setTransformation(transform);
        });visuals.put(loc,visual);shapes.put(loc,slab.getType());applyDamage(b,id);
    }
    public void cracks(Block b,int source,int stage){
        Location loc=b.getLocation();
        if(stage<0){var all=damage.get(loc);if(all!=null){all.remove(source);if(all.isEmpty())damage.remove(loc);}}
        else if(b.getBlockData() instanceof Slab&&storedId(b)!=null)damage.computeIfAbsent(loc,k->new HashMap<>()).put(source,stage);
        String id=storedId(b);if(id!=null)applyDamage(b,id);
    }
    private void applyDamage(Block b,String id){
        ItemDisplay visual=visuals.get(b.getLocation());if(visual==null||!(b.getBlockData() instanceof Slab slab))return;
        int stage=damage.getOrDefault(b.getLocation(),Map.of()).values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        String path="slabs/"+id+"/"+slab.getType().name().toLowerCase(Locale.ROOT)+(stage<0?"":"_crack_"+stage);
        ItemStack item=visual.getItemStack();var meta=item.getItemMeta();NamespacedKey model=new NamespacedKey("aether",path);
        if(!model.equals(meta.getItemModel())){meta.setItemModel(model);item.setItemMeta(meta);visual.setItemStack(item);}
    }
    private boolean matches(Block b,String id,Material material){return id==null?storedId(b)==null&&b.getType()==material:id.equals(storedId(b));}
    private boolean merge(Block b,String id,Material material,BlockFace face,double hitY){
        if(!matches(b,id,material)||!(b.getBlockData() instanceof Slab slab)||slab.getType()==Slab.Type.DOUBLE)return false;
        return face==BlockFace.UP&&slab.getType()==Slab.Type.BOTTOM||face==BlockFace.DOWN&&slab.getType()==Slab.Type.TOP
            ||face.getModY()==0&&(slab.getType()==Slab.Type.BOTTOM?hitY>.5:hitY<=.5);
    }
    public boolean place(PlayerInteractEvent event,String id){
        return place(event,id,id.startsWith("skyroot")?Material.OAK_SLAB:Material.STONE_SLAB);
    }
    public boolean placeNative(PlayerInteractEvent event){return place(event,null,event.getItem().getType());}
    private boolean place(PlayerInteractEvent event,String id,Material material){
        Block clicked=event.getClickedBlock();BlockFace face=event.getBlockFace();var point=event.getInteractionPoint();double y=point==null?.5:point.getY()-clicked.getY();
        boolean combine=merge(clicked,id,material,face,y);Block target=combine?clicked:clicked.getRelative(face);
        if(!combine&&matches(target,id,material)&&target.getBlockData() instanceof Slab slab&&slab.getType()!=Slab.Type.DOUBLE)combine=true;
        if(!combine&&!target.isReplaceable()&&!target.isLiquid())return false;
        if(target.getY()<target.getWorld().getMinHeight()||target.getY()>=target.getWorld().getMaxHeight()||!target.getWorld().getWorldBorder().isInside(target.getLocation()))return false;
        Slab data=(Slab)Bukkit.createBlockData(material);
        data.setType(combine?Slab.Type.DOUBLE:face==BlockFace.DOWN||face.getModY()==0&&y>.5?Slab.Type.TOP:Slab.Type.BOTTOM);
        data.setWaterlogged(!combine&&target.getType()==Material.WATER);
        double low=data.getType()==Slab.Type.TOP?.5:0,high=data.getType()==Slab.Type.BOTTOM?.5:1;
        BoundingBox box=new BoundingBox(target.getX(),target.getY()+low,target.getZ(),target.getX()+1,target.getY()+high,target.getZ()+1);
        if(target.getWorld().getNearbyEntities(box).stream().anyMatch(e->e instanceof LivingEntity&&e.getBoundingBox().overlaps(box)))return false;
        var old=target.getState();String previous=storedId(target);target.setBlockData(data,false);if(id!=null)mark(target,id);else target.getChunk().getPersistentDataContainer().remove(key(target));
        var place=new org.bukkit.event.block.BlockPlaceEvent(target,old,clicked,event.getItem(),event.getPlayer(),true,event.getHand());Bukkit.getPluginManager().callEvent(place);
        if(place.isCancelled()||!place.canBuild()){old.update(true,false);if(previous==null)target.getChunk().getPersistentDataContainer().remove(key(target));else mark(target,previous);return false;}
        if(id!=null)render(target,id);else removeVisual(target.getLocation());return true;
    }
    public void close(){for(Location loc:List.copyOf(visuals.keySet()))removeVisual(loc);damage.clear();}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void extend(org.bukkit.event.block.BlockPistonExtendEvent e){move(e.getBlocks(),e.getDirection(),e);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void retract(org.bukkit.event.block.BlockPistonRetractEvent e){move(e.getBlocks(),e.getDirection(),e);}
    private void move(List<Block> blocks,BlockFace direction,Cancellable event){
        Map<Location,String> moved=new LinkedHashMap<>();for(Block b:blocks){String id=storedId(b);if(isSlab(id))moved.put(b.getLocation(),id);}
        if(moved.isEmpty())return;
        Bukkit.getScheduler().runTask(plugin,()->{
            if(event.isCancelled())return;
            for(Location from:moved.keySet())forget(from.getBlock());
            for(var entry:moved.entrySet())mark(entry.getKey().getBlock().getRelative(direction),entry.getValue());
            Bukkit.getScheduler().runTaskLater(plugin,()->{for(var entry:moved.entrySet()){
                Block to=entry.getKey().getBlock().getRelative(direction);if(to.getBlockData() instanceof Slab)render(to,entry.getValue());else to.getChunk().getPersistentDataContainer().remove(key(to));
            }},3);
        });
    }
}
