package com.aetherport.aether.listener;

import com.aetherport.aether.AetherPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.*;

/** Cloud carriers have a solid top; landing must inspect the supporting block. */
public final class CloudListener implements Listener {
    private final AetherPlugin plugin;
    private final Map<UUID,Integer> bounced=new HashMap<>();
    public CloudListener(AetherPlugin plugin){this.plugin=plugin;}
    public String cloudAt(Location location,double halfWidth) {
        for(double dx:new double[]{0,-halfWidth,halfWidth})for(double dz:new double[]{0,-halfWidth,halfWidth}) {
            Block block=location.clone().add(dx,-.05,dz).getBlock();
            String id=plugin.content().blockIdForCarrier(block.getBlockData().getAsString());
            if(id!=null && id.endsWith("aercloud"))return id;
        }
        return null;
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onMove(PlayerMoveEvent event) {
        Player player=event.getPlayer();String id=cloudAt(event.getTo(),.29);
        if(id==null)return;
        player.setFallDistance(0);
        if(!"blue_aercloud".equals(id) || player.isSneaking() || player.isFlying()
                || Bukkit.getCurrentTick()-bounced.getOrDefault(player.getUniqueId(),-100)<10)return;
        bounced.put(player.getUniqueId(),Bukkit.getCurrentTick());
        // Paper completes movement after this event. Apply the impulse on the next tick.
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!player.isOnline() || player.isSneaking() || !player.getWorld().equals(event.getTo().getWorld()))return;
            if(player.getLocation().distanceSquared(event.getTo())>4)return;
            player.setFallDistance(0);
            try {if(!PlayerLaunch.launch(player,2))return;}
            catch(ReflectiveOperationException error){plugin.getLogger().log(java.util.logging.Level.SEVERE,"Blue aercloud launch synchronization failed",error);return;}
            player.getWorld().playSound(player.getLocation(),"aether:block.aercloud.blue_aercloud_bounce",.8f,1);
            player.getWorld().spawnParticle(Particle.SPLASH,player.getLocation(),20,.35,.1,.35,0);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onFall(EntityDamageEvent event) {
        if(event.getCause()!=EntityDamageEvent.DamageCause.FALL)return;
        Entity entity=event.getEntity();
        double width=Math.max(entity.getBoundingBox().getWidthX(),entity.getBoundingBox().getWidthZ())/2-.01;
        if(cloudAt(entity.getLocation(),Math.max(0,width))!=null) {event.setCancelled(true);entity.setFallDistance(0);}
    }
    @EventHandler public void onQuit(PlayerQuitEvent event){bounced.remove(event.getPlayer().getUniqueId());}
    @EventHandler(ignoreCancelled=true)
    public void onMelt(org.bukkit.event.block.BlockFadeEvent event) {
        String id=plugin.content().blockIdForCarrier(event.getBlock().getBlockData().getAsString());
        if(id!=null && id.endsWith("aercloud"))event.setCancelled(true);
    }
}
