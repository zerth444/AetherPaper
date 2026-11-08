import com.aetherport.aether.*;
import com.aetherport.aether.station.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.view.FurnaceView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import io.netty.channel.*;

public class Revision19 extends JavaPlugin implements Listener {
 AetherPlugin a;StationManager m;Player p;World w;Block block;Object s;int start;boolean started;
 final List<String> report=new ArrayList<>();final List<Block> incubators=new ArrayList<>();
 final java.util.Queue<int[]> data=new java.util.concurrent.ConcurrentLinkedQueue<>();
 void check(boolean ok,String name){String line=(ok?"PASS ":"FAIL ")+name;report.add(line);getLogger().info(line);}
 void later(long ticks,Runnable task){Bukkit.getScheduler().runTaskLater(this,()->{try{task.run();}catch(Throwable e){check(false,e.toString());e.printStackTrace();finish();}},ticks);}
 public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);}
 @EventHandler public void join(PlayerJoinEvent e){if(e.getPlayer().getName().equals("AetherBot"))later(30,()->setup(e.getPlayer()));}
 Object field(Object obj,String name){try{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(obj);}catch(Exception e){throw new RuntimeException(e);}}
 void set(Object obj,String name,Object value){try{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);f.set(obj,value);}catch(Exception e){throw new RuntimeException(e);}}
 @SuppressWarnings("unchecked") Map<String,Object> states(){return (Map<String,Object>)field(m,"stations");}
 Object state(Block b){return states().values().stream().filter(x->b.getLocation().equals(field(x,"loc"))).findFirst().orElseThrow();}
 int number(String name){return (Integer)field(s,name);}
 ItemStack item(String name){return (ItemStack)field(s,name);}
 ItemStack custom(String id,int n){return a.aetherItems().create(id,n);}
 boolean empty(ItemStack item){return item==null||item.getType().isAir()||item.getAmount()==0;}
 boolean id(ItemStack item,String id,int n){return !empty(item)&&id.equals(a.aetherItems().idOf(item))&&item.getAmount()==n;}
 void click(int slot,ClickType type,InventoryAction action){
  InventoryType.SlotType kind=slot==0?InventoryType.SlotType.CRAFTING:slot==1?InventoryType.SlotType.FUEL:slot==2?InventoryType.SlotType.RESULT:InventoryType.SlotType.CONTAINER;
  Bukkit.getPluginManager().callEvent(new InventoryClickEvent(p.getOpenInventory(),kind,slot,type,action));
 }
 void put(int slot,ItemStack stack){p.setItemOnCursor(stack);click(slot,ClickType.LEFT,InventoryAction.PLACE_ALL);}
 void open(Block b,String type){p.setItemOnCursor(null);m.open(p,b,type);s=state(b);}
 void setup(Player player){
  p=player;a=AetherPlugin.get();m=a.stations();w=a.aetherWorldNoCreate();p.setGameMode(GameMode.SURVIVAL);p.getInventory().clear();
  try{
   Object handle=p.getClass().getMethod("getHandle").invoke(p),listener=handle.getClass().getField("connection").get(handle),connection=listener.getClass().getField("connection").get(listener);Channel channel=(Channel)connection.getClass().getField("channel").get(connection);
   channel.eventLoop().execute(()->channel.pipeline().addBefore("packet_handler","qa19",new ChannelDuplexHandler(){public void write(ChannelHandlerContext c,Object packet,ChannelPromise promise)throws Exception{
    if(packet.getClass().getSimpleName().equals("ClientboundContainerSetDataPacket"))data.add(new int[]{(Integer)packet.getClass().getMethod("getId").invoke(packet),(Integer)packet.getClass().getMethod("getValue").invoke(packet)});super.write(c,packet,promise);
   }}));
  }catch(Exception e){throw new RuntimeException(e);}
  for(int x=-1;x<=2;x++)for(int z=-1;z<=1;z++)w.getChunkAt(x,z).setForceLoaded(true);
  block=w.getBlockAt(8,200,8);p.setAllowFlight(true);p.setFlying(false);
  if(Files.exists(Path.of("qa19-quick"))){started=true;quickChecks();return;}
  if(Files.exists(Path.of("qa19-restart"))){started=true;restartChecks();return;}
  for(Object old:List.copyOf(states().values()))m.breakStation(((Location)field(old,"loc")).getBlock());
  for(Horse horse:w.getEntitiesByClass(Horse.class))if(horse.isTamed()&&horse.getOwner()!=null&&p.getUniqueId().equals(horse.getOwner().getUniqueId()))horse.remove();
  for(int x=7;x<=9;x++)for(int z=7;z<=9;z++)w.getBlockAt(x,200,z).setType(Material.STONE,false);
  block.setBlockData(Bukkit.createBlockData(a.content().block("altar").carrier()),false);p.teleport(new Location(w,8.5,201,8.5,0,90));
  later(5,()->{try{Files.writeString(Path.of("qa-input.jsonl"),"{\"op\":\"use\",\"pos\":[8,200,8]}\n",StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}});
  later(100,()->{if(!started){check(false,"real right-click opens station");finish();}});
 }
 @EventHandler public void opened(InventoryOpenEvent e){if(e.getPlayer()!=p||started)return;started=true;later(2,this::begin);}
 void begin(){
  s=state(block);check(p.getOpenInventory().getType()==InventoryType.FURNACE,"real right-click opens furnace layout instead of dummy chest row");
  check(p.getOpenInventory().setProperty(InventoryView.Property.COOK_TIME,0),"native menu supports fuel/progress properties");
  put(0,custom("blue_berry",1));put(1,custom("ambrosium_shard",1));start=Bukkit.getCurrentTick();
  later(4,()->{
   check(number("progress")>0,"inserted GUI items start processing while menu stays open");
   if(p.getOpenInventory().getType()!=InventoryType.FURNACE){finish();return;}
   check(empty(item("fuel")),"one fuel consumed from GUI immediately");
   check(data.stream().anyMatch(v->v[0]==2&&v[1]>0),"cook progress sent in real clientbound container data packets");
   later(246,this::altarDone);
  });
 }
 void altarDone(){
  check(id(item("output"),"enchanted_berry",1)&&empty(item("input")),"altar completes full 250-tick recipe with GUI open");
  check(Bukkit.getCurrentTick()-start==250,"station recipe ticks are server ticks, not doubled");
  put(2,new ItemStack(Material.DIRT));check(p.getItemOnCursor().getType()==Material.DIRT&&id(item("output"),"enchanted_berry",1),"output rejects insertion without changing result");p.setItemOnCursor(null);
  click(2,ClickType.LEFT,InventoryAction.PICKUP_ALL);check(id(p.getItemOnCursor(),"enchanted_berry",1)&&empty(item("output")),"result extracted exactly once");p.setItemOnCursor(null);
  p.closeInventory();open(block,"altar");check(empty(item("output"))&&empty(p.getOpenInventory().getTopInventory().getItem(2)),"close/reopen cannot restore an already taken result");
  put(1,new ItemStack(Material.COAL));check(p.getItemOnCursor().getType()==Material.COAL&&empty(item("fuel")),"altar rejects vanilla furnace fuel");p.setItemOnCursor(null);
  p.getInventory().setItem(9,custom("blue_berry",5));int raw=3; // first player main-inventory slot in furnace menu
  click(raw,ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY);check(id(item("input"),"blue_berry",5)&&empty(p.getInventory().getItem(9)),"shift-click routes inventory ingredient to input");
  click(0,ClickType.RIGHT,InventoryAction.PICKUP_HALF);check(id(p.getItemOnCursor(),"blue_berry",3)&&id(item("input"),"blue_berry",2),"right-click splits station stack");p.setItemOnCursor(null);
  click(0,ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY);check(empty(item("input")),"shift-click removes input to player inventory");
  var drag=new InventoryDragEvent(p.getOpenInventory(),new ItemStack(Material.AIR),custom("blue_berry",2),false,Map.of(0,custom("blue_berry",2)));Bukkit.getPluginManager().callEvent(drag);check(id(item("input"),"blue_berry",2),"drag updates authoritative station input");
  var outputDrag=new InventoryDragEvent(p.getOpenInventory(),new ItemStack(Material.AIR),custom("blue_berry",1),false,Map.of(2,custom("blue_berry",1)));Bukkit.getPluginManager().callEvent(outputDrag);check(outputDrag.isCancelled()&&empty(item("output")),"drag cannot inject output");
  set(s,"progress",123);put(0,custom("holystone",1));check(number("progress")==0&&id(item("input"),"holystone",1),"changing recipe resets progress and swaps items without duplication");p.setItemOnCursor(null);
  m.breakStation(block);block.setType(Material.AIR,false);p.getInventory().clear();
  block=w.getBlockAt(10,200,8);block.setBlockData(Bukkit.createBlockData(a.content().block("freezer").carrier()),false);open(block,"freezer");put(0,custom("cold_aercloud",1));put(1,custom("icestone",1));
  later(400,this::freezerDone);
 }
 void freezerDone(){
  check(id(item("output"),"blue_aercloud",1)&&empty(item("input")),"freezer completes full 400-tick recipe while open");
  click(2,ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY);check(empty(item("output")),"freezer shift-extraction clears result");
  put(0,new ItemStack(Material.WATER_BUCKET));put(1,custom("icestone",1));later(200,this::bucketDone);
 }
 void bucketDone(){
  check(item("output")!=null&&item("output").getType()==Material.ICE,"water bucket freezes into ice");check(item("input")!=null&&item("input").getType()==Material.BUCKET,"freezing returns empty bucket instead of deleting container");
  m.breakStation(block);block.setType(Material.AIR,false);block=w.getBlockAt(12,200,8);block.setBlockData(Bukkit.createBlockData(a.content().block("altar").carrier()),false);open(block,"altar");
  ItemStack damaged=new ItemStack(Material.FLINT_AND_STEEL);var meta=(Damageable)damaged.getItemMeta();meta.setDamage(40);damaged.setItemMeta(meta);damaged.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING,2);
  put(0,damaged);put(1,custom("ambrosium_shard",2));later(300,this::repairDone);
 }
 void repairDone(){
  ItemStack output=item("output");check(output!=null&&output.getType()==Material.FLINT_AND_STEEL&&((Damageable)output.getItemMeta()).getDamage()==0,"altar repairs vanilla damageable items");check(output!=null&&output.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING)==2,"repair preserves enchantments");
  ItemStack damaged=new ItemStack(Material.FLINT_AND_STEEL);var meta=(Damageable)damaged.getItemMeta();meta.setDamage(10);damaged.setItemMeta(meta);put(0,damaged);set(s,"output",new ItemStack(Material.DIRT,64));set(s,"burn",0);int fuel=item("fuel")==null?0:item("fuel").getAmount();
  later(3,()->{check(item("input")!=null&&((Damageable)item("input").getItemMeta()).getDamage()==10&&number("progress")==0,"incompatible/full output blocks repair");check((item("fuel")==null?0:item("fuel").getAmount())==fuel,"blocked repair does not consume new fuel");hatchStart();});
 }
 void hatchStart(){
  p.setItemOnCursor(null);p.closeInventory();int x=16;
  for(String color:List.of("blue","white","black")){
   Block b=w.getBlockAt(x,200,8);x+=3;b.setBlockData(Bukkit.createBlockData(a.content().block("incubator").carrier()),false);b.getRelative(0,1,0).setType(Material.AIR,false);b.getRelative(0,2,0).setType(Material.AIR,false);incubators.add(b);
   open(b,"incubator");put(0,custom(color+"_moa_egg",1));put(1,custom("ambrosium_torch",2));check(!empty(item("input")),color+" moa egg accepted through GUI");
  }
  later(1000,this::hatchDone);
 }
 void hatchDone(){
  for(int i=0;i<incubators.size();i++){
   Block b=incubators.get(i);s=state(b);String color=List.of("blue","white","black").get(i);
   var moa=w.getEntitiesByClass(Horse.class).stream().filter(e->e.isTamed()&&e.getOwner()!=null&&p.getUniqueId().equals(e.getOwner().getUniqueId())&&color.equals(e.getPersistentDataContainer().get(new NamespacedKey(a,"moa_type"),PersistentDataType.STRING))).findFirst().orElse(null);
   check(moa!=null,color+" moa actually hatches after full 1000-tick cycle");check(moa!=null&&!moa.isAdult()&&moa.isTamed()&&p.getUniqueId().equals(moa.getOwner().getUniqueId()),color+" hatch is a baby owned by egg inserter");check(empty(item("input"))&&empty(item("fuel")),color+" hatching consumes one egg and two torches");
  }
  block=w.getBlockAt(28,200,8);block.setBlockData(Bukkit.createBlockData(a.content().block("altar").carrier()),false);open(block,"altar");put(0,custom("blue_berry",4));put(1,custom("ambrosium_shard",3));p.closeInventory();
  set(s,"progress",42);set(s,"burn",123);set(s,"burnTotal",250);
  try{Files.writeString(Path.of("qa19-restart"),"ready");}catch(Exception e){throw new RuntimeException(e);}finish();
 }
 void restartChecks(){
  block=w.getBlockAt(28,200,8);s=state(block);check(id(item("input"),"blue_berry",4),"station inventory survives real server restart");check(id(item("fuel"),"ambrosium_shard",3),"fuel stack survives real server restart");check(p.getUniqueId().equals(field(s,"lastUser")),"egg/input owner survives real server restart");check(number("burnTotal")==250,"fuel duration survives real server restart");
  open(block,"altar");check(p.getOpenInventory().getTopInventory().getSize()==3,"saved legacy-compatible station opens functional furnace menu");p.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE));p.breakBlock(block);check(block.getType()==Material.AIR&&!states().containsValue(s),"actual player block break closes station and removes state");finish();
 }
 ItemStack ref(String ref){return ref.startsWith("aether:")?custom(ref.substring(7),1):new ItemStack(Material.matchMaterial(ref.substring(10)));}
 void quickChecks(){try{
  Class<?> cls=states().values().stream().findFirst().orElseThrow().getClass();var constructor=cls.getDeclaredConstructor();constructor.setAccessible(true);var process=m.getClass().getDeclaredMethod("process",cls);process.setAccessible(true);
  for(String type:List.of("altar","freezer"))for(var cook:type.equals("altar")?m.recipes().enchanting():m.recipes().freezing())for(String ingredient:cook.in()){
   Object test=constructor.newInstance();set(test,"type",type);set(test,"loc",w.getBlockAt(30,200,8).getLocation());set(test,"input",ref(ingredient));set(test,"fuel",custom(type.equals("altar")?"ambrosium_shard":"icestone",1));set(test,"progress",cook.time()-1);process.invoke(m,test);
   ItemStack expected=ref(cook.out());expected.setAmount(cook.count());ItemStack actual=(ItemStack)field(test,"output");check(actual!=null&&actual.isSimilar(expected)&&actual.getAmount()==expected.getAmount(),"recipe matrix "+type+" "+ingredient+" -> "+cook.out());
  }
  for(var repair:m.recipes().repairing())for(String ingredient:repair.in()){
   ItemStack damaged=ref(ingredient);var meta=damaged.getItemMeta();if(!(meta instanceof Damageable d)){check(false,"repairable item supports durability: "+ingredient);continue;}d.setDamage(1);damaged.setItemMeta(meta);
   Object test=constructor.newInstance();set(test,"type","altar");set(test,"loc",w.getBlockAt(30,200,8).getLocation());set(test,"input",damaged);set(test,"fuel",custom("ambrosium_shard",1));set(test,"progress",repair.time()-1);process.invoke(m,test);ItemStack result=(ItemStack)field(test,"output");check(result!=null&&((Damageable)result.getItemMeta()).getDamage()==0,"repair matrix "+ingredient);
  }
  Block legacy=w.getBlockAt(31,200,8);m.breakStation(legacy);legacy.setBlockData(Bukkit.createBlockData(a.content().block("incubator").carrier()),false);legacy.getRelative(0,1,0).setType(Material.AIR,false);legacy.getRelative(0,2,0).setType(Material.AIR,false);open(legacy,"incubator");put(0,custom("blue_moa_egg",1));set(s,"lastUser",null);set(s,"progress",999);set(s,"burn",250);set(s,"burnTotal",500);
  Set<UUID> before=new HashSet<>();for(Horse horse:w.getEntitiesByClass(Horse.class))before.add(horse.getUniqueId());
  later(2,()->{
   var baby=w.getEntitiesByClass(Horse.class).stream().filter(h->!before.contains(h.getUniqueId())).findFirst().orElse(null);check(baby!=null&&!baby.isAdult(),"legacy egg without saved owner still hatches a baby");m.breakStation(legacy);legacy.setType(Material.AIR,false);
   block=w.getBlockAt(28,200,8);m.breakStation(block);block.setBlockData(Bukkit.createBlockData(a.content().block("altar").carrier()),false);open(block,"altar");put(0,custom("blue_berry",4));put(1,custom("ambrosium_shard",3));p.closeInventory();set(s,"progress",42);set(s,"burn",123);set(s,"burnTotal",250);finish();
  });
 }catch(Exception e){throw new RuntimeException(e);}}
 void finish(){try{Files.write(Path.of("revision19.txt"),report);}catch(Exception e){throw new RuntimeException(e);}getLogger().info("REVISION19_FINISHED "+report.stream().filter(s->s.startsWith("FAIL")).count()+" failures / "+report.size());}
}
