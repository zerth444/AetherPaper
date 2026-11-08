import com.aetherport.aether.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import java.nio.file.*;
import java.util.*;

public class Revision20 extends JavaPlugin implements Listener {
 AetherPlugin a;Player p;World w;Block base;int phase,events;boolean checking;boolean baseline;final List<String> report=new ArrayList<>();
 void check(boolean ok,String label){String s=(ok?"PASS ":"FAIL ")+label;report.add(s);getLogger().info(s);}
 void later(long ticks,Runnable r){Bukkit.getScheduler().runTaskLater(this,()->{try{r.run();}catch(Throwable e){check(false,e.toString());e.printStackTrace();finish();}},ticks);}
 public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);}
 @EventHandler public void join(PlayerJoinEvent e){if(e.getPlayer().getName().equals("AetherBot"))later(30,()->setup(e.getPlayer()));}
 ItemStack custom(String id,int count){return a.aetherItems().create(id,count);}
 void setup(Player player){a=AetherPlugin.get();p=player;w=a.mainWorld();try{baseline=((String)AetherPlugin.class.getField("BUILD_ID").get(null)).endsWith("r19");}catch(Exception e){throw new RuntimeException(e);}p.setGameMode(GameMode.SURVIVAL);p.getInventory().clear();p.setAllowFlight(true);
  for(int x=0;x<=5;x++)for(int z=0;z<=1;z++)w.getChunkAt(x,z).setForceLoaded(true);
  if(Files.exists(Path.of("qa20-restart"))&&!baseline){restart();return;}
  for(int x=7;x<=9;x++)for(int z=5;z<=10;z++)w.getBlockAt(x,200,z).setType(Material.STONE,false);
  base=w.getBlockAt(8,200,9);p.teleport(new Location(w,8.5,201,6.5,0,30));burst(0);
 }
 void request(String lines){try{Files.writeString(Path.of("qa-input.jsonl"),lines,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
 void burst(int n){phase=n;events=0;checking=false;base.getRelative(0,1,0).setType(Material.AIR,false);base.getRelative(0,2,0).setType(Material.AIR,false);base.getRelative(0,3,0).setType(Material.AIR,false);
  ItemStack stack=custom("aether_dirt",8);if(n==1)stack.setType(Material.DIRT);
  p.getInventory().setItemInMainHand(n==2?new ItemStack(Material.AIR):stack);p.getInventory().setItemInOffHand(n==2?stack:null);
  String hand=n==2?",\"hand\":1":"";
  later(6,()->request("{\"op\":\"use\",\"pos\":[8,200,9]"+hand+"}\n{\"op\":\"use\",\"pos\":[8,201,9]"+hand+"}\n"));
  later(80,()->{if(!checking){check(false,"native placement packet reached listener");finish();}});
 }
 @EventHandler(priority=EventPriority.MONITOR) public void use(PlayerInteractEvent e){if(e.getPlayer()!=p||phase>=3||e.getAction()!=Action.RIGHT_CLICK_BLOCK)return;events++;if(!checking){checking=true;later(8,this::checkBurst);}}
 void checkBurst(){
  int placed=0;for(int y=201;y<=203;y++)if(a.content().blockIdForCarrier(w.getBlockAt(8,y,9).getBlockData().getAsString())!=null)placed++;
  ItemStack stack=phase==2?p.getInventory().getItemInOffHand():p.getInventory().getItemInMainHand();
  if(baseline){check(placed==2&&stack.getAmount()==6,"r19 reproduces duplicate server placements for rapid use commands: "+placed+" blocks, "+stack.getAmount()+" items left");finish();return;}
  check(events==2,"received both real use packets, case "+phase);check(placed==1&&stack.getAmount()==7,"one placement and one item consumed per ordinary interval, case "+phase+": "+placed+" / "+stack.getAmount());
  if(phase<2){burst(phase+1);return;}phase=3;slabs();
 }
 PlayerInteractEvent event(Block clicked,BlockFace face,double y){return new PlayerInteractEvent(p,Action.RIGHT_CLICK_BLOCK,p.getInventory().getItemInMainHand(),clicked,face,EquipmentSlot.HAND,new Vector(.5,y,.5));}
 boolean place(Block clicked,BlockFace face,double y,String id){p.getInventory().setItemInMainHand(custom(id,8));return a.slabs().place(event(clicked,face,y),id);}
 void erase(Block b){a.slabs().forget(b);b.setType(Material.AIR,false);}
 double height(Block b){return b.getCollisionShape().getBoundingBoxes().stream().mapToDouble(box->box.getHeight()).max().orElse(0);}
 int drops(String id){return w.getEntitiesByClass(Item.class).stream().map(Item::getItemStack).filter(i->id.equals(a.aetherItems().idOf(i))).mapToInt(ItemStack::getAmount).sum();}
 void clearDrops(){for(Item item:w.getEntitiesByClass(Item.class))item.remove();}
 void slabs(){
  int index=0;for(String id:a.content().blocks().keySet())if(id.endsWith("_slab")){
   Block floor=w.getBlockAt(16+index*2,200,4);index++;floor.setType(Material.STONE,false);Block b=floor.getRelative(BlockFace.UP);erase(b);b.getRelative(BlockFace.UP).setType(Material.AIR,false);
   check(place(floor,BlockFace.UP,1,id),id+" places bottom");check(b.getBlockData() instanceof Slab data&&data.getType()==Slab.Type.BOTTOM&&Math.abs(height(b)-.5)<1e-6,id+" has native half-height bottom collision");check(id.equals(a.content().blockId(b)),id+" per-position identity resolves");
   erase(b);Block ceiling=b.getRelative(BlockFace.UP);ceiling.setType(Material.STONE,false);check(place(ceiling,BlockFace.DOWN,0,id),id+" places top");check(b.getBlockData() instanceof Slab data&&data.getType()==Slab.Type.TOP&&Math.abs(height(b)-.5)<1e-6,id+" has native top collision");
   check(place(b,BlockFace.DOWN,0,id),id+" joins matching top into double");check(b.getBlockData() instanceof Slab data&&data.getType()==Slab.Type.DOUBLE&&Math.abs(height(b)-1)<1e-6,id+" double has full native collision");
   var display=w.getEntitiesByClass(ItemDisplay.class).stream().filter(d->d.getLocation().distanceSquared(b.getLocation().add(.5,.5,.5))<.01).findFirst().orElse(null);check(display!=null&&display.getItemStack().getItemMeta().getItemModel().getKey().equals("slabs/"+id+"/double"),id+" display uses original double model");
   clearDrops();p.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE));p.breakBlock(b);check(drops(id)==2&&a.content().blockId(b)==null,id+" double drops two and removes identity");
   ceiling.setType(Material.AIR,false);check(place(floor,BlockFace.UP,1,id),id+" replaces after break");clearDrops();p.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE));p.breakBlock(b);check(drops(id)==1,id+" single drops one");
  }
  Block floor=w.getBlockAt(40,200,4);floor.setType(Material.STONE,false);Block b=floor.getRelative(BlockFace.UP);erase(b);b.setType(Material.WATER,false);check(place(floor,BlockFace.UP,1,"holystone_slab")&&((Slab)b.getBlockData()).isWaterlogged(),"slab placed in water is waterlogged");p.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE));p.breakBlock(b);check(b.getType()==Material.WATER,"breaking waterlogged slab preserves water");
  Block nativeBlock=w.getBlockAt(42,201,4);nativeBlock.setType(Material.OAK_SLAB,false);check(a.content().blockId(nativeBlock)==null&&Math.abs(height(nativeBlock)-.5)<1e-6,"ordinary vanilla slab retains identity and collision");
  Block legacy=w.getBlockAt(44,201,4);legacy.setBlockData(Bukkit.createBlockData(a.content().block("holystone_slab").carrier()),false);a.slabs().migrate(legacy,"holystone_slab");check(legacy.getBlockData() instanceof Slab&&Math.abs(height(legacy)-.5)<1e-6,"old note-block slab migrates to real half-height slab");
  mining();
 }
 String model(Block b){return w.getEntitiesByClass(ItemDisplay.class).stream().filter(d->d.getLocation().distanceSquared(b.getLocation().add(.5,.5,.5))<.01).findFirst().orElseThrow().getItemStack().getItemMeta().getItemModel().getKey();}
 void mining(){
  Block floor=w.getBlockAt(46,200,4);floor.setType(Material.STONE,false);Block b=floor.getRelative(BlockFace.UP);erase(b);check(place(floor,BlockFace.UP,1,"holystone_slab"),"mining test slab placed");p.teleport(new Location(w,46.5,202,4.5,0,90));p.getInventory().setItemInMainHand(custom("zanite_pickaxe",1));
  Bukkit.getPluginManager().callEvent(new BlockDamageEvent(p,b,BlockFace.UP,p.getInventory().getItemInMainHand(),false));
  later(3,()->{
   check(model(b).contains("_crack_"),"actual mining progress updates custom slab visual damage model");
   try{var f=AetherPlugin.class.getDeclaredField("miningListener");f.setAccessible(true);((com.aetherport.aether.listener.MiningListener)f.get(a)).clear(p);}catch(Exception e){throw new RuntimeException(e);}
   check(!model(b).contains("_crack_"),"abort restores original slab visual");a.slabs().cracks(b,100,7);a.slabs().cracks(b,101,3);check(model(b).endsWith("_crack_7"),"two miners show highest crack stage");a.slabs().cracks(b,100,-1);check(model(b).endsWith("_crack_3"),"one miner abort does not erase another miner's cracks");a.slabs().cracks(b,101,-1);check(!model(b).contains("_crack_"),"last miner abort clears overlay");pistons();
  });
 }
 void pistons(){
  Block source=w.getBlockAt(48,201,4),floor=source.getRelative(BlockFace.DOWN);floor.setType(Material.STONE,false);erase(source);erase(source.getRelative(BlockFace.EAST));check(place(floor,BlockFace.UP,1,"holystone_slab"),"piston test slab placed");
  Block piston=source.getRelative(BlockFace.WEST);piston.setBlockData(Bukkit.createBlockData("minecraft:sticky_piston[facing=east,extended=false]"),false);piston.getRelative(BlockFace.DOWN).setType(Material.REDSTONE_BLOCK,true);
  later(8,()->{check("holystone_slab".equals(a.content().blockId(source.getRelative(BlockFace.EAST)))&&a.content().blockId(source)==null,"real piston extension moves slab identity and visual");
   piston.getRelative(BlockFace.DOWN).setType(Material.AIR,true);later(8,()->{check("holystone_slab".equals(a.content().blockId(source))&&a.content().blockId(source.getRelative(BlockFace.EAST))==null,"sticky piston retraction preserves custom slab");fixtures();});
  });
 }
 void fixtures(){
  Block floor=w.getBlockAt(80,200,4);floor.setType(Material.STONE,false);Block b=floor.getRelative(BlockFace.UP);erase(b);check(place(floor,BlockFace.UP,1,"holystone_slab"),"persistent slab fixture placed");
  try{Files.writeString(Path.of("qa20-restart"),"ready");}catch(Exception e){throw new RuntimeException(e);}finish();
 }
 void restart(){phase=3;Block b=w.getBlockAt(80,201,4);later(40,()->{
  check(b.getBlockData() instanceof Slab&&Math.abs(height(b)-.5)<1e-6,"real half-height slab survives server restart");check("holystone_slab".equals(a.content().blockId(b)),"custom identity survives real chunk save/restart");
  check(w.getEntitiesByClass(ItemDisplay.class).stream().filter(d->d.getLocation().distanceSquared(b.getLocation().add(.5,.5,.5))<.01).count()==1,"exactly one slab visual restored after restart");finish();
 });}
 void finish(){try{Files.write(Path.of("revision20.txt"),report);}catch(Exception e){throw new RuntimeException(e);}getLogger().info("REVISION20_FINISHED "+report.stream().filter(s->s.startsWith("FAIL")).count()+" failures / "+report.size());}
}
