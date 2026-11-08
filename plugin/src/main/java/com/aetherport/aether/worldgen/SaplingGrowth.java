package com.aetherport.aether.worldgen;

import com.aetherport.aether.AetherPlugin;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;

/** Loaded saplings only; a persistent chunk index survives unload/restart. */
public final class SaplingGrowth implements Listener {
    private final AetherPlugin plugin;
    private final Set<Block> tracked=new HashSet<>();
    private final Map<Block,String> identities=new HashMap<>();
    private final NamespacedKey index;
    private final Random random=new Random();
    public SaplingGrowth(AetherPlugin plugin){
        this.plugin=plugin;index=new NamespacedKey(plugin,"sapling_positions_v14");
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,20);
        Bukkit.getScheduler().runTaskTimer(plugin,this::restore,1,1);
        Bukkit.getScheduler().runTask(plugin,()->Bukkit.getWorlds().forEach(w->{for(Chunk c:w.getLoadedChunks())load(c);}));
    }
    public String idAt(Block b){return b.getType()==Material.TRIPWIRE?identities.get(b):null;}
    private String id(Block b){String stored=idAt(b);return stored!=null?stored:plugin.content().blockIdForCarrier(b.getBlockData().getAsString());}
    private boolean sapling(Block b){return Set.of("skyroot_sapling","golden_oak_sapling").contains(Objects.toString(id(b),""));}
    private int position(Block b){return (b.getY()<<8)|((b.getX()&15)<<4)|(b.getZ()&15);}
    private NamespacedKey stage(Block b){return new NamespacedKey(plugin,"sapling_stage_"+position(b));}
    private NamespacedKey type(Block b){return new NamespacedKey(plugin,"sapling_type_"+position(b));}
    public void track(Block b){
        if(!sapling(b))return;
        identities.put(b,id(b));tracked.add(b);var pdc=b.getChunk().getPersistentDataContainer();
        pdc.set(type(b),PersistentDataType.STRING,identities.get(b));
        int[] old=pdc.getOrDefault(index,PersistentDataType.INTEGER_ARRAY,new int[0]);int pos=position(b);
        if(Arrays.stream(old).noneMatch(p->p==pos)){int[] next=Arrays.copyOf(old,old.length+1);next[old.length]=pos;pdc.set(index,PersistentDataType.INTEGER_ARRAY,next);}
    }
    private void forget(Block b){
        tracked.remove(b);identities.remove(b);var pdc=b.getChunk().getPersistentDataContainer();pdc.remove(stage(b));pdc.remove(type(b));
        int[] old=pdc.getOrDefault(index,PersistentDataType.INTEGER_ARRAY,new int[0]);
        pdc.set(index,PersistentDataType.INTEGER_ARRAY,Arrays.stream(old).filter(p->p!=position(b)).toArray());
    }
    private void load(Chunk c){for(int p:c.getPersistentDataContainer().getOrDefault(index,PersistentDataType.INTEGER_ARRAY,new int[0])){Block b=c.getBlock((p>>4)&15,p>>8,p&15);String id=c.getPersistentDataContainer().get(type(b),PersistentDataType.STRING);if(id!=null){tracked.add(b);identities.put(b,id);}}restore();}
    @EventHandler public void load(ChunkLoadEvent e){load(e.getChunk());}
    @EventHandler public void unload(ChunkUnloadEvent e){tracked.removeIf(b->b.getChunk().equals(e.getChunk()));identities.keySet().removeIf(b->b.getChunk().equals(e.getChunk()));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void place(BlockPlaceEvent e){
        if(identities.containsKey(e.getBlock()) && !Set.of("skyroot_sapling","golden_oak_sapling").contains(Objects.toString(plugin.aetherItems().idOf(e.getItemInHand()),"")))forget(e.getBlock());
        if(sapling(e.getBlock())){e.getBlock().getChunk().getPersistentDataContainer().remove(stage(e.getBlock()));track(e.getBlock());}
    }
    private void restore(){for(Block b:List.copyOf(tracked)){
        if(!b.getWorld().isChunkLoaded(b.getX()>>4,b.getZ()>>4))continue;
        if(b.getType()!=Material.TRIPWIRE){forget(b);continue;}
        String carrier=plugin.content().block(identities.get(b)).carrier();
        if(!b.getBlockData().getAsString().equals(carrier))b.setBlockData(Bukkit.createBlockData(carrier),false);
    }}
    private void tick(){
        for(Block b:List.copyOf(tracked)){
            if(!b.getWorld().isChunkLoaded(b.getX()>>4,b.getZ()>>4)){tracked.remove(b);continue;}
            if(!sapling(b)){forget(b);continue;}
            int speed=Objects.requireNonNullElse(b.getWorld().getGameRuleValue(GameRule.RANDOM_TICK_SPEED),3);
            if(b.getLightLevel()>=9 && random.nextDouble()<1-Math.pow(1-1d/(4096*7),20d*speed))advance(b,false,null);
        }
    }
    private boolean advance(Block b,boolean bonemeal,Player player){
        var pdc=b.getChunk().getPersistentDataContainer();
        if(pdc.getOrDefault(stage(b),PersistentDataType.BYTE,(byte)0)==0){pdc.set(stage(b),PersistentDataType.BYTE,(byte)1);return true;}
        return grow(b,bonemeal,player);
    }
    @EventHandler(priority=EventPriority.HIGH) public void bonemeal(PlayerInteractEvent e){
        if(e.useItemInHand()==Event.Result.DENY)return;
        if(e.getHand()!=EquipmentSlot.HAND || e.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null || e.getItem()==null || e.getItem().getType()!=Material.BONE_MEAL || !sapling(e.getClickedBlock()))return;
        e.setCancelled(true);if(e.getPlayer().getGameMode()!=GameMode.CREATIVE)e.getItem().setAmount(e.getItem().getAmount()-1);
        e.getClickedBlock().getWorld().spawnParticle(Particle.HAPPY_VILLAGER,e.getClickedBlock().getLocation().add(.5,.5,.5),8,.3,.3,.3);
        if(random.nextFloat()<.45f)advance(e.getClickedBlock(),true,e.getPlayer());
    }
    public boolean grow(Block root,boolean bonemeal,Player player){
        if(!sapling(root))return false;
        String soil=id(root.getRelative(BlockFace.DOWN));Material ground=root.getRelative(BlockFace.DOWN).getType();
        if(!Set.of("aether_grass_block","enchanted_aether_grass_block","aether_dirt").contains(Objects.toString(soil,"")) && !Set.of(Material.GRASS_BLOCK,Material.DIRT,Material.COARSE_DIRT,Material.PODZOL,Material.ROOTED_DIRT,Material.MOSS_BLOCK).contains(ground))return false;
        boolean golden="golden_oak_sapling".equals(id(root));int height=golden?8+random.nextInt(3):4+random.nextInt(3);
        if(root.getY()+height>=root.getWorld().getMaxHeight())return false;
        for(int dx:new int[]{-2,2})for(int dz:new int[]{-2,2})if(!root.getWorld().isChunkLoaded((root.getX()+dx)>>4,(root.getZ()+dz)>>4))return false;
        Map<Block,BlockState> changes=new LinkedHashMap<>();
        for(int dy=0;dy<height;dy++)plan(changes,root.getRelative(0,dy,0),golden?"golden_oak_log":"skyroot_log");
        for(int dy=height-3;dy<=height;dy++)for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            if(Math.abs(x)+Math.abs(z)>(dy==height?2:3))continue;
            Block b=root.getRelative(x,dy,z);if(!changes.containsKey(b))plan(changes,b,golden?"golden_oak_leaves":"skyroot_leaves");
        }
        for(Block b:changes.keySet()){
            if(b.getY()<b.getWorld().getMinHeight() || b.getY()>=b.getWorld().getMaxHeight() || !b.getWorld().isChunkLoaded(b.getX()>>4,b.getZ()>>4))return false;
            String existing=id(b);
            if(!b.equals(root) && !b.getType().isAir() && !b.getType().name().endsWith("LEAVES") && !(existing!=null && existing.endsWith("_leaves")))return false;
        }
        var event=new StructureGrowEvent(root.getLocation(),golden?TreeType.BIG_TREE:TreeType.TREE,bonemeal,player,new ArrayList<>(changes.values()));
        Bukkit.getPluginManager().callEvent(event);if(event.isCancelled())return false;
        for(BlockState state:event.getBlocks())state.update(true,false);
        if(!sapling(root))forget(root);return !sapling(root);
    }
    private void plan(Map<Block,BlockState> changes,Block b,String id){BlockState state=b.getState();state.setBlockData(Bukkit.createBlockData(plugin.content().block(id).carrier()));changes.put(b,state);}
}
