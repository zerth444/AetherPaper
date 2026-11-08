import com.aetherport.aether.*;
import com.aetherport.aether.listener.MiningListener;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.potion.*;
import io.netty.channel.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Uses real protocol START/ABORT packets, never synthetic BlockDamageEvent. */
public class Revision17 extends JavaPlugin implements Listener {
 Player p;Block block;int phase,started,damageEvents,abortEvents;Channel channel;
 final List<String> results=new ArrayList<>();final Queue<String> trace=new ConcurrentLinkedQueue<>();
 record Crack(int tick,int stage){}final Queue<Crack> cracks=new ConcurrentLinkedQueue<>();final Queue<Integer> attributes=new ConcurrentLinkedQueue<>();
 void check(boolean ok,String s){String line=(ok?"PASS ":"FAIL ")+s;results.add(line);getLogger().info(line);}
 void later(long ticks,Runnable task){Bukkit.getScheduler().runTaskLater(this,()->{try{task.run();}catch(Throwable e){check(false,e.toString());e.printStackTrace();finish();}},ticks);}
 public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);}
 @EventHandler public void join(PlayerJoinEvent e){if(e.getPlayer().getName().equals("AetherBot"))later(30,()->setup(e.getPlayer()));}
 void setup(Player player){try{
  p=player;World world=AetherPlugin.get().aetherWorldNoCreate();
  for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)world.getChunkAt(x,z).setForceLoaded(true);
  for(int x=7;x<=9;x++)for(int z=7;z<=9;z++)world.getBlockAt(x,200,z).setType(Material.STONE,false);
  block=world.getBlockAt(8,200,8);p.setGameMode(GameMode.SURVIVAL);p.getInventory().clear();p.setAllowFlight(true);p.setFlying(false);
  for(var effect:p.getActivePotionEffects())p.removePotionEffect(effect.getType());
  p.teleport(new Location(world,8.5,201,8.5,0,90));
  Object handle=p.getClass().getMethod("getHandle").invoke(p),listener=handle.getClass().getField("connection").get(handle),connection=listener.getClass().getField("connection").get(listener);channel=(Channel)connection.getClass().getField("channel").get(connection);
  channel.eventLoop().execute(()->channel.pipeline().addBefore("packet_handler","qa17",new ChannelDuplexHandler(){
   @Override public void write(ChannelHandlerContext c,Object packet,ChannelPromise promise)throws Exception{
    String name=packet.getClass().getSimpleName();int tick=Bukkit.getCurrentTick();
    if(name.equals("ClientboundBlockDestructionPacket")){
     int source=(Integer)packet.getClass().getMethod("getId").invoke(packet),stage=(Integer)packet.getClass().getMethod("getProgress").invoke(packet);
     trace.add("OUT tick="+tick+" source="+source+" stage="+stage);
     if(source==MiningListener.crackSource(p))cracks.add(new Crack(tick,stage));
    }else if(name.equals("ClientboundUpdateAttributesPacket") && (Integer)packet.getClass().getMethod("getEntityId").invoke(packet)==p.getEntityId()){attributes.add(tick);trace.add("OUT attributes tick="+tick);}
    else if(name.equals("ClientboundBlockUpdatePacket"))trace.add("OUT block_update tick="+tick+" "+packet.getClass().getMethod("getPos").invoke(packet));
    super.write(c,packet,promise);
   }
   @Override public void channelRead(ChannelHandlerContext c,Object packet)throws Exception{
    if(packet.getClass().getSimpleName().equals("ServerboundPlayerActionPacket"))trace.add("IN "+packet.getClass().getMethod("getAction").invoke(packet)+" tick="+Bukkit.getCurrentTick());
    super.channelRead(c,packet);
   }
  }));later(10,()->begin(1));
 }catch(Exception e){throw new RuntimeException(e);}}
 void request(int action){try{Files.writeString(Path.of("qa-input.jsonl"),"{\"op\":\"dig\",\"action\":"+action+",\"pos\":[8,200,8]}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
 void begin(int next){phase=next;started=0;damageEvents=0;abortEvents=0;cracks.clear();attributes.clear();
  block.setBlockData(Bukkit.createBlockData(AetherPlugin.get().content().block("holystone").carrier()),false);trace.add("PHASE "+phase);request(0);
 }
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void damage(BlockDamageEvent e){
  if(e.getPlayer()!=p || !e.getBlock().equals(block))return;damageEvents++;trace.add("EVENT damage phase="+phase+" tick="+Bukkit.getCurrentTick());
  if(started!=0)return;started=Bukkit.getCurrentTick();
  if(phase==1)later(180,this::checkHold);
  else if(phase==2){later(20,()->{request(1);request(0);});later(210,this::checkRestart);}
  else if(phase==3){later(20,()->request(1));later(60,this::checkRelease);}
 }
 @EventHandler(priority=EventPriority.MONITOR) public void abort(BlockDamageAbortEvent e){if(e.getPlayer()==p){abortEvents++;trace.add("EVENT abort phase="+phase+" tick="+Bukkit.getCurrentTick()+" pos="+e.getBlock().getLocation());}}
 void checkHold(){
  check(damageEvents==1,"real START generated exactly one damage event");check(abortEvents==0,"continuous hold has no native abort");
  check(block.getType()==Material.AIR,"continuous native packet mining completed");
  long churn=attributes.stream().filter(t->t>started+5 && t<started+140).count();check(churn==0,"steady mining does not resend attributes each tick: "+churn);
  check(cracks.stream().filter(c->c.stage()<0).count()==1,"continuous hold clears cracks only at completion: "+cracks);begin(2);
 }
 void checkRestart(){
  check(damageEvents==2 && abortEvents>=1,"native abort/start pair received for same block");
  check(block.getType()==Material.AIR,"same-target restart completes mining");
  check(cracks.stream().filter(c->c.stage()<0).count()==1,"same-tick abort/start does not flash crack clear: "+cracks);
  int previous=-1;boolean monotonic=true;for(Crack c:cracks)if(c.stage()>=0){if(c.stage()<previous)monotonic=false;previous=c.stage();}check(monotonic,"same-target restart preserves crack stages");begin(3);
 }
 void checkRelease(){
  check(abortEvents>=1,"real release reached server");check(block.getType()==Material.NOTE_BLOCK,"release stops mining without destroying block");
  check(cracks.stream().filter(c->c.stage()<0).count()==1,"real release clears animation once");cacheChecks();
 }
 void cacheChecks(){try{
  var field=AetherPlugin.class.getDeclaredField("miningListener");field.setAccessible(true);MiningListener mining=(MiningListener)field.get(AetherPlugin.get());
  Block analogue=block.getRelative(1,0,0);analogue.setType(Material.STONE,false);p.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.IRON_PICKAXE));
  Bukkit.getPluginManager().callEvent(new BlockDamageEvent(p,block,org.bukkit.block.BlockFace.UP,p.getInventory().getItemInMainHand(),false));
  compareRate(mining,analogue,"initial cached rate");
  p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,200,1));compareRate(mining,analogue,"haste invalidates cached rate");
  p.removePotionEffect(PotionEffectType.HASTE);compareRate(mining,analogue,"haste expiry invalidates cached rate");
  p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE,200,2));compareRate(mining,analogue,"fatigue invalidates cached rate");p.removePotionEffect(PotionEffectType.MINING_FATIGUE);
  var attribute=p.getAttribute(Attribute.BLOCK_BREAK_SPEED);double base=attribute.getBaseValue();attribute.setBaseValue(2);compareRate(mining,analogue,"external mining attribute invalidates cached rate");attribute.setBaseValue(base);
  Object handle=p.getClass().getMethod("getHandle").invoke(p);boolean grounded=p.isOnGround();handle.getClass().getMethod("setOnGround",boolean.class).invoke(handle,!grounded);compareRate(mining,analogue,"ground penalty invalidates cached rate");handle.getClass().getMethod("setOnGround",boolean.class).invoke(handle,grounded);
  block.getRelative(0,1,0).setType(Material.WATER,false);block.getRelative(0,2,0).setType(Material.WATER,false);
  later(5,()->{
   check(new com.aetherport.aether.listener.VanillaMining().eyesInWater(p),"native eye fluid state is underwater");compareRate(mining,analogue,"water penalty invalidates cached rate");
   mining.clear(p);block.getRelative(0,1,0).setType(Material.AIR,false);block.getRelative(0,2,0).setType(Material.AIR,false);finish();
  });
 }catch(Exception e){throw new RuntimeException(e);}}
 void compareRate(MiningListener mining,Block analogue,String label){
  double actual=mining.rate(p,block);var attribute=p.getAttribute(Attribute.BLOCK_BREAK_SPEED);AttributeModifier limiter=attribute.getModifiers().stream().filter(m->m.getKey().toString().equals("aether:carrier_mining")).findFirst().orElseThrow();
  attribute.removeModifier(limiter);double expected=analogue.getBreakSpeed(p)*.5;attribute.addTransientModifier(limiter);
  check(Math.abs(actual-expected)<1e-7,label+": "+actual+" expected="+expected);
 }
 void finish(){try{Files.write(Path.of("revision17.txt"),results);Files.write(Path.of("revision17-trace.txt"),trace);}catch(Exception e){throw new RuntimeException(e);}getLogger().info("REVISION17_FINISHED "+results.stream().filter(s->s.startsWith("FAIL")).count()+" failures / "+results.size());}
}
