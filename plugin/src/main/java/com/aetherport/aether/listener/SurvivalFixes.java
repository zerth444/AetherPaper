package com.aetherport.aether.listener;

import com.aetherport.aether.AetherPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;

public final class SurvivalFixes implements Listener {
    private final AetherPlugin plugin;
    public SurvivalFixes(AetherPlugin plugin) {
        this.plugin=plugin;
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getScheduler().runTaskTimer(plugin,()-> {
            for(var player:Bukkit.getOnlinePlayers()) for(int slot=0;slot<player.getInventory().getSize();slot++) {
                var item=player.getInventory().getItem(slot);
                if(item!=null && item.getType()==Material.PAPER && java.util.Set.of("ambrosium_shard","blue_berry","enchanted_berry").contains(java.util.Objects.toString(plugin.aetherItems().idOf(item),"")))
                    player.getInventory().setItem(slot,plugin.aetherItems().create(plugin.aetherItems().idOf(item),item.getAmount()));
                else if(item!=null && java.util.Set.of("blue_berry","enchanted_berry").contains(java.util.Objects.toString(plugin.aetherItems().idOf(item),""))) {
                    var meta=item.getItemMeta();var food=meta.getFood();
                    if(food.canAlwaysEat()){food.setCanAlwaysEat(false);meta.setFood(food);item.setItemMeta(meta);}
                }
                if(item!=null){String toolId=plugin.aetherItems().idOf(item);
                    if(toolId!=null && (toolId.startsWith("gravitite_")||toolId.startsWith("valkyrie_")) && java.util.Set.of("pickaxe","axe","shovel","hoe").stream().anyMatch(s->toolId.endsWith("_"+s))){
                        Material expected=plugin.aetherItems().baseMaterial(toolId);if(item.getType()!=expected)item.setType(expected);
                    }
                }
            }
        },20,20);
    }
    private String id(Block b){return plugin.content().blockId(b);}
    @EventHandler(priority=EventPriority.HIGHEST)
    public void harvest(PlayerInteractEvent e) {
        if(e.getHand()!=EquipmentSlot.HAND || e.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null)return;
        Block b=e.getClickedBlock();
        if(!"berry_bush".equals(id(b)))return;
        e.setCancelled(true);
        b.setBlockData(Bukkit.createBlockData(plugin.content().block("berry_bush_stem").carrier()),false);
        b.getWorld().dropItemNaturally(b.getLocation(),plugin.aetherItems().create("blue_berry",2));
        Bukkit.getScheduler().runTaskLater(plugin,()-> {
            if(b.getChunk().isLoaded() && "berry_bush_stem".equals(id(b)))
                b.setBlockData(Bukkit.createBlockData(plugin.content().block("berry_bush").carrier()),false);
        },1200);
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void decay(BlockBreakEvent e) {
        String broken=id(e.getBlock());
        if(broken==null || !(broken.endsWith("_log") || broken.endsWith("_wood")))return;
        Block origin=e.getBlock();
        Bukkit.getScheduler().runTaskLater(plugin,()-> {
            if (id(origin)!=null && (id(origin).endsWith("_log") || id(origin).endsWith("_wood"))) return;
            for(int dx=-8;dx<=8;dx++)for(int dy=-8;dy<=8;dy++)for(int dz=-8;dz<=8;dz++) {
                Block leaf=origin.getRelative(dx,dy,dz);String type=id(leaf);
                if(type==null || !type.endsWith("_leaves"))continue;
                var queue=new ArrayDeque<Block>();var seen=new HashSet<String>();queue.add(leaf);boolean supported=false;
                for(int depth=0;depth<7 && !queue.isEmpty() && !supported;depth++) {
                    int count=queue.size();
                    while(count-->0) {
                        Block at=queue.remove();
                        for(var face:List.of(org.bukkit.block.BlockFace.UP,org.bukkit.block.BlockFace.DOWN,org.bukkit.block.BlockFace.NORTH,org.bukkit.block.BlockFace.SOUTH,org.bukkit.block.BlockFace.EAST,org.bukkit.block.BlockFace.WEST)) {
                            Block next=at.getRelative(face);String n=id(next);
                            if(n!=null && (n.endsWith("_log") || n.endsWith("_wood"))) {supported=true;break;}
                            if(n!=null && n.endsWith("_leaves") && seen.add(next.getLocation().toString()))queue.add(next);
                        }
                    }
                }
                if(!supported) Bukkit.getScheduler().runTaskLater(plugin,()->{
                    if(type.equals(id(leaf))) {
                        leaf.setType(Material.AIR,false);
                        for(var drop:plugin.content().block(type).drops()) {
                            if(drop.require()!=null)continue;
                            double chance=drop.fortune()!=null && !drop.fortune().isEmpty()?drop.fortune().getFirst():drop.chance();
                            if(java.util.concurrent.ThreadLocalRandom.current().nextDouble()>=chance)continue;
                            int amount=java.util.concurrent.ThreadLocalRandom.current().nextInt(drop.min(),drop.max()+1);
                            var item=drop.vanilla()?new org.bukkit.inventory.ItemStack(Material.valueOf(drop.item().toUpperCase()),amount):plugin.aetherItems().create(drop.item(),amount);
                            if(item!=null && amount>0)leaf.getWorld().dropItemNaturally(leaf.getLocation(),item);
                        }
                    }
                },20+java.util.concurrent.ThreadLocalRandom.current().nextInt(181));
            }
        },80);
    }
}
