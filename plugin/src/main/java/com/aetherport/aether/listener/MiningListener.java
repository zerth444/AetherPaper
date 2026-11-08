package com.aetherport.aether.listener;

import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.content.BlockDef;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import java.util.*;

/** Evaluates vanilla analogue progress on the server while suppressing client carrier prediction. */
public final class MiningListener implements Listener {
    private final AetherPlugin plugin;
    private final VanillaMining vanilla=new VanillaMining();
    private final NamespacedKey key=new NamespacedKey("aether","carrier_mining");
    private final Map<UUID,Block> active=new HashMap<>();
    private final Map<UUID,Double> progress=new HashMap<>();
    private final Map<UUID,Integer> stages=new HashMap<>();
    private final Map<UUID,Integer> lastCrackTick=new HashMap<>();
    private final Map<UUID,ItemStack> tools=new HashMap<>();
    private final Map<UUID,Integer> pendingAborts=new HashMap<>();
    private record Environment(String carrier,ItemStack tool,boolean grounded,boolean underwater,
                               int haste,int conduit,int fatigue,List<Object> attributes){}
    private record Rate(Environment environment,double value){}
    private final Map<UUID,Rate> rates=new HashMap<>();
    private int amplifier(Player player,PotionEffectType type){var effect=player.getPotionEffect(type);return effect==null?-1:effect.getAmplifier();}
    private Environment environment(Player player,Block block){
        List<Object> values=new ArrayList<>();
        for(Attribute type:List.of(Attribute.BLOCK_BREAK_SPEED,Attribute.MINING_EFFICIENCY,Attribute.SUBMERGED_MINING_SPEED)){
            var attribute=player.getAttribute(type);
            if(attribute==null){values.add(type);continue;}
            values.add(attribute.getBaseValue());
            // The prediction limiter is deliberately excluded. Effect durations decrease every
            // tick, but only their amplifiers affect vanilla mining speed.
            values.add(attribute.getModifiers().stream().filter(m->!m.getKey().equals(key)).collect(java.util.stream.Collectors.toSet()));
        }
        return new Environment(block.getBlockData().getAsString(),player.getInventory().getItemInMainHand().clone(),player.isOnGround(),vanilla.eyesInWater(player),
                amplifier(player,PotionEffectType.HASTE),amplifier(player,PotionEffectType.CONDUIT_POWER),amplifier(player,PotionEffectType.MINING_FATIGUE),values);
    }
    public static int crackSource(Player player){return -player.getEntityId()-1;}
    private void cracks(Player player,Block block,int stage){
        float value=stage<0?0:stage==0?.001f:stage/9f;
        for(Player viewer:block.getWorld().getPlayers())if(viewer.getLocation().distanceSquared(block.getLocation())<=4096)viewer.sendBlockDamage(block.getLocation(),value,crackSource(player));
    }
    public MiningListener(AetherPlugin plugin) {
        this.plugin=plugin;
        Bukkit.getScheduler().runTaskTimer(plugin,()->{
            for(UUID id:List.copyOf(active.keySet())) {
                Player player=Bukkit.getPlayer(id);Block block=active.get(id);
                if(player==null) {active.remove(id);progress.remove(id);stages.remove(id);lastCrackTick.remove(id);tools.remove(id);pendingAborts.remove(id);rates.remove(id);continue;}
                Integer aborted=pendingAborts.get(id);
                if(aborted!=null){if(Bukkit.getCurrentTick()>aborted+1)clear(player);continue;}
                if(!player.getInventory().getItemInMainHand().isSimilar(tools.get(id))){clear(player);continue;}
                if(!player.getWorld().equals(block.getWorld()) || player.getLocation().distanceSquared(block.getLocation())>49
                        || plugin.content().blockIdForCarrier(block.getBlockData().getAsString())==null
                        || !block.equals(player.getTargetBlockExact(6))) {clear(player);continue;}
                double done=progress.getOrDefault(id,0d)+rate(player,block);
                progress.put(id,done);
                if(done>=1) {
                    // Send the server's removal before restoring client mining speed.
                    // The client never predicts a note-block removal or its neighbours.
                    player.breakBlock(block);clear(player);
                } else {
                    int stage=Math.min(9,(int)(done*10));
                    if(stages.getOrDefault(id,-1)!=stage || Bukkit.getCurrentTick()-lastCrackTick.getOrDefault(id,0)>=100){stages.put(id,stage);lastCrackTick.put(id,Bukkit.getCurrentTick());cracks(player,block,stage);}
                }
            }
        },1,1);
    }
    public void clear(Player player) {
        Block old=active.remove(player.getUniqueId());
        if(old!=null && player.getWorld().equals(old.getWorld()))cracks(player,old,-1);
        progress.remove(player.getUniqueId());
        stages.remove(player.getUniqueId());lastCrackTick.remove(player.getUniqueId());tools.remove(player.getUniqueId());
        pendingAborts.remove(player.getUniqueId());rates.remove(player.getUniqueId());
        var attribute=player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if(attribute!=null)for(var modifier:List.copyOf(attribute.getModifiers()))if(modifier.getKey().equals(key))attribute.removeModifier(modifier);
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onDamage(BlockDamageEvent event) {
        Player player=event.getPlayer();
        // A repeated START packet for the same tool/target is not an abort.
        if(event.getBlock().equals(active.get(player.getUniqueId())) && player.getInventory().getItemInMainHand().isSimilar(tools.get(player.getUniqueId()))){pendingAborts.remove(player.getUniqueId());event.setInstaBreak(false);return;}
        clear(player);
        String id=plugin.content().blockIdForCarrier(event.getBlock().getBlockData().getAsString());
        BlockDef def=id==null?null:plugin.content().block(id);
        if(def==null || player.getGameMode()==GameMode.CREATIVE)return;
        if(def.hardness()<0) {event.setCancelled(true);return;}
        if(VanillaMining.analogue(def).getHardness()==0) {event.setInstaBreak(true);return;}
        var attribute=player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        active.put(player.getUniqueId(),event.getBlock());
        tools.put(player.getUniqueId(),player.getInventory().getItemInMainHand().clone());
        progress.put(player.getUniqueId(),0d);
        // Evaluate before installing the limiter, then reuse until tool/effects/attributes
        // or the ground/water penalty change. Removing/readding it every tick dirties
        // the player's attribute and floods the client with speed updates.
        rate(player,event.getBlock());
        if(attribute!=null) {
            for(var modifier:List.copyOf(attribute.getModifiers()))if(modifier.getKey().equals(key))attribute.removeModifier(modifier);
            attribute.addTransientModifier(new AttributeModifier(key,-1,AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        event.setInstaBreak(false);
    }
    public double rate(Player player,Block block){
        String id=plugin.content().blockIdForCarrier(block.getBlockData().getAsString());
        if(id==null)return 0;
        Environment environment=environment(player,block);
        Rate cached=rates.get(player.getUniqueId());
        if(cached!=null && cached.environment().equals(environment))return cached.value();
        var attribute=player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        AttributeModifier limiter=null;
        if(attribute!=null)for(var modifier:List.copyOf(attribute.getModifiers()))if(modifier.getKey().equals(key)){limiter=modifier;attribute.removeModifier(modifier);}
        try {
            double result=vanilla.rate(plugin.content().block(id),block,player);
            var item=player.getInventory().getItemInMainHand();
            if(plugin.aetherItems().idOf(item)==null && VanillaMining.isTool(item.getType()))result*=.5;
            rates.put(player.getUniqueId(),new Rate(environment,result));return result;
        } finally {if(attribute!=null && limiter!=null)attribute.addTransientModifier(limiter);}
    }
    @EventHandler public void onAbort(BlockDamageAbortEvent e){
        UUID id=e.getPlayer().getUniqueId();
        // Ignore an old target's ABORT. Pause immediately, but allow a native
        // same-target ABORT/START pair to resume without clearing the overlay.
        if(e.getBlock().equals(active.get(id)))pendingAborts.putIfAbsent(id,Bukkit.getCurrentTick());
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void guard(BlockBreakEvent e){if(e.getBlock().equals(active.get(e.getPlayer().getUniqueId())) && progress.getOrDefault(e.getPlayer().getUniqueId(),0d)<1)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onBreak(BlockBreakEvent e){
        Block broken=e.getBlock();Player player=e.getPlayer();
        Bukkit.getScheduler().runTask(plugin,()->{if(broken.equals(active.get(player.getUniqueId())))clear(player);});
    }
    @EventHandler public void onHeld(PlayerItemHeldEvent e){clear(e.getPlayer());}
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e){clear(e.getPlayer());}
    @EventHandler public void onQuit(PlayerQuitEvent e){clear(e.getPlayer());}
    @EventHandler public void onMode(PlayerGameModeChangeEvent e){clear(e.getPlayer());}
    @EventHandler public void onWorld(PlayerChangedWorldEvent e){clear(e.getPlayer());}
    public void close(){for(Player player:Bukkit.getOnlinePlayers())clear(player);}
}
