package com.aetherport.aether.listener;
import com.aetherport.aether.AetherPlugin;
import org.bukkit.*;import org.bukkit.entity.Player;import org.bukkit.event.*;import org.bukkit.event.player.PlayerMoveEvent;import org.bukkit.event.entity.EntityDamageEvent;
import java.util.*;
public final class WorldFall implements Listener {
 private final AetherPlugin plugin;private final Map<UUID,Float> carried=new HashMap<>();
 public WorldFall(AetherPlugin plugin){this.plugin=plugin;Bukkit.getPluginManager().registerEvents(this,plugin);Bukkit.getScheduler().runTaskTimer(plugin,()->carried.keySet().removeIf(id->{Player p=Bukkit.getPlayer(id);return p==null || p.isDead() || p.isOnGround() || p.isInWater();}),1,1);}
 @EventHandler(ignoreCancelled=true) public void move(PlayerMoveEvent e){
  Player p=e.getPlayer();if(!p.getWorld().getKey().toString().equals(plugin.aetherDimensionKey()) || e.getTo().getY()>=p.getWorld().getMinHeight()-4)return;
  float falling=p.getFallDistance();var velocity=p.getVelocity();World target=plugin.mainWorld();
  Location to=new Location(target,p.getX(),target.getMaxHeight()-4,p.getZ(),p.getYaw(),p.getPitch());
  if(!target.getWorldBorder().isInside(to))return;
  if(p.teleport(to,org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN)){
   carried.put(p.getUniqueId(),falling);p.setNoDamageTicks(0);p.setVelocity(velocity);
   Bukkit.getScheduler().runTask(plugin,()->p.setFallDistance(falling));
  }
 }
 @EventHandler(priority=EventPriority.LOWEST) public void land(EntityDamageEvent e){
  if(e.getCause()!=EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p))return;
  Float distance=carried.remove(p.getUniqueId());if(distance==null)return;
  if(!p.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING))e.setDamage(Math.max(e.getDamage(),Math.ceil(distance-3)));
 }
 @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent e){carried.remove(e.getPlayer().getUniqueId());}
}
