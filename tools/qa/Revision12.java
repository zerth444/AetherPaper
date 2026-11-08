import com.aetherport.aether.AetherPlugin;
import org.bukkit.*;import org.bukkit.entity.*;import org.bukkit.event.*;import org.bukkit.event.player.*;import org.bukkit.event.block.*;import org.bukkit.event.entity.*;import org.bukkit.inventory.*;import org.bukkit.inventory.meta.*;import org.bukkit.plugin.java.JavaPlugin;import java.util.*;import java.nio.file.*;
public class Revision12 extends JavaPlugin implements Listener {
 final List<String> results=new ArrayList<>();
 void check(boolean ok,String text){String s=(ok?"PASS ":"FAIL ")+text;results.add(s);getLogger().info(s);}
 public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);}
 @EventHandler public void join(PlayerJoinEvent e){if(e.getPlayer().getName().equals("AetherBot"))Bukkit.getScheduler().runTaskLater(this,()->run(e.getPlayer()),20);}
 void run(Player p){try{
  var a=AetherPlugin.get();World w=a.mainWorld();p.setGameMode(GameMode.CREATIVE);p.setAllowFlight(true);
  var at=w.getBlockAt(4220,200,4220);at.setType(Material.NOTE_BLOCK,false);
  var hand=new ItemStack(Material.NOTE_BLOCK);var previous=at.getState();p.getInventory().setItemInMainHand(hand);
  Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(at,previous,at.getRelative(0,-1,0),hand,p,true,EquipmentSlot.HAND));
  check(a.content().blockIdForCarrier(at.getBlockData().getAsString())==null && at.getBlockData().getAsString().contains("note=22,"),"ordinary note block remains native and is not Aether grass");
  at.setType(Material.BEDROCK,false);at.getRelative(0,1,0).setType(Material.AIR,false);hand=a.aetherItems().create("cold_aercloud",1);p.getInventory().setItemInMainHand(hand);
  Bukkit.getPluginManager().callEvent(new PlayerInteractEvent(p,Action.RIGHT_CLICK_BLOCK,hand,at,org.bukkit.block.BlockFace.UP,EquipmentSlot.HAND));
  check(at.getRelative(0,1,0).getType()==Material.SNOW,"cloud item can be placed through actual interaction event");
  check(!a.getConfig().getBoolean("dungeons.enabled",false),"dungeons disabled by default");
  for(String mob:List.of("slider","valkyrie","valkyrie_queen","sun_spirit","sentry","mimic"))check(a.mobs().spawnMob(w,at.getLocation().add(0,5,0),mob,false)==null,"disabled special mob "+mob);
  check(a.loreMenu().entryCount()==242,"r15 guide contains all original lore entries");
  a.loreMenu().open(p);
  check(p.getOpenInventory().getTopInventory().getSize()==54 && p.getOpenInventory().getTopInventory().getHolder() instanceof com.aetherport.aether.item.LoreMenu.Session,"r15 guide opens interactive item-inspection menu");p.closeInventory();
  var stone=at.getRelative(3,0,0);stone.setBlockData(Bukkit.createBlockData(a.content().block("holystone").carrier()),false);
  p.teleport(new Location(w,4223.5,201,4220.5,0,90));
  Bukkit.getScheduler().runTaskLater(this,()->{
   var displays=w.getEntitiesByClass(BlockDisplay.class).stream().filter(d->!d.isVisibleByDefault() && d.getLocation().distanceSquared(stone.getLocation())<10).count();
   check(displays==0 && stone.getType()==Material.NOTE_BLOCK,"terrain stays in chunk renderer without private display proxies (r13)");
   testFall(p);
  },10);
 }catch(Exception e){throw new RuntimeException(e);}}
 void testFall(Player p){var a=AetherPlugin.get();World sky=a.aetherWorldNoCreate();for(var effect:p.getActivePotionEffects())p.removePotionEffect(effect.getType());p.setGameMode(GameMode.SURVIVAL);p.setAllowFlight(true);p.teleport(new Location(sky,4250,sky.getMinHeight()-5,4250));p.setFallDistance(70);
  Bukkit.getPluginManager().callEvent(new PlayerMoveEvent(p,p.getLocation(),p.getLocation().clone().subtract(0,1,0)));
  check(p.getWorld().equals(a.mainWorld()) && p.getY()>300,"Aether void transfers to high Overworld position");
  var fall=new EntityDamageEvent(p,EntityDamageEvent.DamageCause.FALL,10);Bukkit.getPluginManager().callEvent(fall);
  check(!fall.isCancelled() && fall.getDamage()>=67,"dimension exit grants no fall damage immunity");
  p.teleport(new Location(a.mainWorld(),4260,250,4260));p.setFallDistance(20);p.setHealth(20);var chute=a.aetherItems().create("golden_parachute",1);p.getInventory().setItemInMainHand(chute);
  var use=new PlayerInteractEvent(p,Action.RIGHT_CLICK_AIR,chute,null,org.bukkit.block.BlockFace.SELF,EquipmentSlot.HAND);use.setCancelled(true);Bukkit.getPluginManager().callEvent(use);
  check(p.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING),"golden parachute opens despite native pre-cancelled PAPER air use");
  check(((org.bukkit.inventory.meta.Damageable)p.getInventory().getItemInMainHand().getItemMeta()).getDamage()==1,"golden parachute consumes one durability per deployment");
  Bukkit.getScheduler().runTaskLater(this,()->{
   check(p.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING),"parachute protection still active after former10second timeout");
   check(p.getY()<=251,"canopy follows player without moving the player upward");
   try{Files.write(Path.of("../history/verification/revision-r12.txt"),results);}catch(Exception e){throw new RuntimeException(e);}getLogger().info("REVISION12_FINISHED");
  },230);
 }
}
