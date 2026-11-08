import com.aetherport.aether.*;
import com.aetherport.aether.listener.*;
import com.aetherport.aether.mob.SwetParticles;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.attribute.Attribute;
import java.util.*;
import java.nio.file.*;

public class Revision14 extends JavaPlugin implements Listener {
 final List<String> results=new ArrayList<>();AetherPlugin a;Player p;World w;Block custom,nativeBlock;MiningListener mining;
 void check(boolean ok,String s){String line=(ok?"PASS ":"FAIL ")+s;results.add(line);getLogger().info(line);}
 public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);}
 @EventHandler public void join(PlayerJoinEvent e){if(e.getPlayer().getName().equals("AetherBot"))Bukkit.getScheduler().runTaskLater(this,()->start(e.getPlayer()),30);}
 void start(Player player){try{
  a=AetherPlugin.get();p=player;w=a.mainWorld();p.setGameMode(GameMode.SURVIVAL);p.setAllowFlight(true);p.setFlying(false);
  for(int x=-2;x<=4;x++)for(int z=-2;z<=4;z++)w.loadChunk(x,z);
  for(int x=20;x<=76;x++)for(int y=200;y<=214;y++)for(int z=20;z<=28;z++)w.getBlockAt(x,y,z).setType(Material.AIR,false);
  custom=w.getBlockAt(8,200,8);nativeBlock=w.getBlockAt(9,200,8);
  var field=AetherPlugin.class.getDeclaredField("miningListener");field.setAccessible(true);mining=(MiningListener)field.get(a);
  p.teleport(new Location(w,8.5,201,8.5,0,90));
  for(String id:List.of("skyroot_sapling","golden_oak_sapling")){
   Block root=w.getBlockAt(id.startsWith("skyroot")?24:40,200,24);
   root.getRelative(BlockFace.DOWN).setBlockData(Bukkit.createBlockData(a.content().block("aether_grass_block").carrier()),false);
   root.setBlockData(Bukkit.createBlockData(a.content().block(id).carrier()),false);
   check(root.isPassable() && root.getCollisionShape().getBoundingBoxes().isEmpty(),id+" has no standing collision");
   check(root.getType()==Material.TRIPWIRE && a.content().blockIdForCarrier(root.getBlockData().getAsString()).equals(id),id+" non-occluding carrier roundtrip");
   a.saplings().track(root);check(a.saplings().grow(root,false,null),id+" grows tree");
   check((id.startsWith("skyroot")?"skyroot_log":"golden_oak_log").equals(a.content().blockIdForCarrier(root.getBlockData().getAsString())),id+" grows matching trunk");
   check("aether_grass_block".equals(a.content().blockIdForCarrier(root.getRelative(BlockFace.DOWN).getBlockData().getAsString())),"tree preserves soil");
  }
  Block blocked=w.getBlockAt(56,200,24);blocked.getRelative(BlockFace.DOWN).setType(Material.DIRT,false);blocked.setBlockData(Bukkit.createBlockData(a.content().block("skyroot_sapling").carrier()),false);blocked.getRelative(BlockFace.UP).setType(Material.STONE,false);
  check(!a.saplings().grow(blocked,false,null) && blocked.getRelative(BlockFace.UP).getType()==Material.STONE,"blocked growth preserves obstruction and sapling");
  for(Material glass:List.of(Material.WHITE_STAINED_GLASS,Material.LIGHT_BLUE_STAINED_GLASS,Material.YELLOW_STAINED_GLASS)){
   custom.setType(glass,false);check(a.content().blockIdForCarrier(custom.getBlockData().getAsString())==null,"ordinary "+glass+" is not cloud");
  }
  for(String berry:List.of("blue_berry","enchanted_berry"))check(!a.aetherItems().create(berry,1).getItemMeta().getFood().canAlwaysEat(),berry+" cannot always eat");
  ItemStack old=a.aetherItems().create("blue_berry",2);var meta=old.getItemMeta();var food=meta.getFood();food.setCanAlwaysEat(true);meta.setFood(food);old.setItemMeta(meta);p.getInventory().setItem(20,old);
  ItemStack oldTool=a.aetherItems().create("gravitite_pickaxe",1);oldTool.setType(Material.NETHERITE_PICKAXE);oldTool.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.EFFICIENCY,3);p.getInventory().setItem(21,oldTool);
  packets();drops();
  Bukkit.getScheduler().runTaskLater(this,()->{
   check(!p.getInventory().getItem(20).getItemMeta().getFood().canAlwaysEat(),"old berries migrated");
   var migrated=p.getInventory().getItem(21);check(migrated.getType()==Material.DIAMOND_PICKAXE && migrated.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.EFFICIENCY)==3,"old gravitite tool tier migrated, enchantment preserved");
   cases();
  },25);
 }catch(Throwable e){fail(e);}}
 void packets()throws Exception{
  UUID uuid=UUID.randomUUID();int entity=900000;SwetParticles.track(entity,uuid);
  for(String type:List.of("Pos","PosRot","Rot")){
   Class<?> cls=Class.forName("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$"+type);Object packet;
   if(type.equals("Pos"))packet=cls.getConstructor(int.class,short.class,short.class,short.class,boolean.class).newInstance(entity,(short)12,(short)34,(short)56,true);
   else if(type.equals("Rot"))packet=cls.getConstructor(int.class,byte.class,byte.class,boolean.class).newInstance(entity,(byte)23,(byte)45,true);
   else packet=cls.getConstructor(int.class,short.class,short.class,short.class,byte.class,byte.class,boolean.class).newInstance(entity,(short)12,(short)34,(short)56,(byte)23,(byte)45,true);
   Object changed=AetherVisuals.rewrite(packet);check(!(Boolean)cls.getMethod("isOnGround").invoke(changed),type+" hides Swet client landing");
   check(cls.getMethod("getXa").invoke(packet).equals(cls.getMethod("getXa").invoke(changed)),type+" movement preserved");
   SwetParticles.forget(uuid);check(AetherVisuals.rewrite(packet)==packet,type+" ordinary entity packet unchanged");SwetParticles.track(entity,uuid);
   Class<?> bundle=Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket");Object packed=bundle.getConstructor(Iterable.class).newInstance(List.of(packet));Object updated=AetherVisuals.rewrite(packed);
   Object inner=((Iterable<?>)bundle.getMethod("subPackets").invoke(updated)).iterator().next();check(!(Boolean)cls.getMethod("isOnGround").invoke(inner),type+" nested bundle filtered");
  }
  Class<?> vec=Class.forName("net.minecraft.world.phys.Vec3"),values=Class.forName("net.minecraft.world.entity.PositionMoveRotation");Object zero=vec.getField("ZERO").get(null);Object value=values.getConstructor(vec,vec,float.class,float.class).newInstance(zero,zero,0f,0f);
  for(String type:List.of("ClientboundTeleportEntityPacket","ClientboundEntityPositionSyncPacket")){
   Class<?> cls=Class.forName("net.minecraft.network.protocol.game."+type);Object packet=type.contains("Teleport")?cls.getConstructor(int.class,values,Set.class,boolean.class).newInstance(entity,value,Set.of(),true):cls.getConstructor(int.class,values,boolean.class).newInstance(entity,value,true);
   check(!(Boolean)cls.getMethod("onGround").invoke(AetherVisuals.rewrite(packet)),type+" landing filtered");
  }
  SwetParticles.forget(uuid);
 }
 void drops(){
  for(String tool:List.of("skyroot_pickaxe","holystone_pickaxe","zanite_pickaxe","gravitite_pickaxe","vanilla")){
   for(Entity entity:w.getNearbyEntities(custom.getLocation(),3,3,3))if(entity instanceof Item)entity.remove();
   custom.setBlockData(Bukkit.createBlockData(a.content().block("holystone").carrier()),false);
   p.getInventory().setItemInMainHand(tool.equals("vanilla")?new ItemStack(Material.DIAMOND_PICKAXE):a.aetherItems().create(tool,1));p.breakBlock(custom);
   int count=w.getNearbyEntities(custom.getLocation(),3,3,3).stream().filter(e->e instanceof Item).mapToInt(e->((Item)e).getItemStack().getAmount()).sum();
   check(count==1 && custom.getType()==Material.AIR,"one stone with "+tool+": "+count);
  }
 }
 record Case(String block,String tool,boolean enchanted){}
 final List<Case> cases=new ArrayList<>();int cursor;
 void cases(){
  for(String block:List.of("holystone","aether_dirt","aether_grass_block","skyroot_log","quicksoil","zanite_ore","skyroot_leaves"))for(String tool:List.of("hand","skyroot_pickaxe","zanite_pickaxe","gravitite_pickaxe","IRON_PICKAXE","IRON_SHOVEL","IRON_AXE","SHEARS"))cases.add(new Case(block,tool,false));
  cases.add(new Case("holystone","zanite_pickaxe",true));cases.add(new Case("holystone","IRON_PICKAXE",true));next();
 }
 void next(){try{
  if(cursor==cases.size()){finalChecks();return;}Case test=cases.get(cursor++);
  mining.clear(p);custom.setBlockData(Bukkit.createBlockData(a.content().block(test.block).carrier()),false);
  nativeBlock.setType(VanillaMining.analogue(a.content().block(test.block)),false);
  ItemStack held=test.tool.equals("hand")?new ItemStack(Material.AIR):Character.isUpperCase(test.tool.charAt(0))?new ItemStack(Material.valueOf(test.tool)):a.aetherItems().create(test.tool,1);
  if(test.enchanted)held.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.EFFICIENCY,4);p.getInventory().setItemInMainHand(held);
  Bukkit.getScheduler().runTaskLater(this,()->{
   try{
    double expected=nativeBlock.getBreakSpeed(p);if(a.aetherItems().idOf(held)==null && VanillaMining.isTool(held.getType()))expected*=.5;
    double actual=mining.rate(p,custom);check(Math.abs(actual-expected)<1e-7,test.block+" "+test.tool+(test.enchanted?" EffIV":"")+" custom="+actual+" expected="+expected);
    Bukkit.getPluginManager().callEvent(new BlockDamageEvent(p,custom,BlockFace.UP,held,false));
    if(VanillaMining.analogue(a.content().block(test.block)).getHardness()>0){check(p.getAttribute(Attribute.BLOCK_BREAK_SPEED).getValue()==0,"client prediction suppressed");check(Math.abs(mining.rate(p,custom)-expected)<1e-7,"rate ignores own prediction limiter");}
    mining.clear(p);check(Math.abs(p.getAttribute(Attribute.BLOCK_BREAK_SPEED).getValue()-1)<1e-7,"mining cleanup restores attribute");next();
   }catch(Throwable e){fail(e);}
  },3);
 }catch(Throwable e){fail(e);}}
 void finalChecks(){try{
  Block root=w.getBlockAt(72,200,24);root.getRelative(BlockFace.DOWN).setType(Material.DIRT,false);root.setBlockData(Bukkit.createBlockData(a.content().block("skyroot_sapling").carrier()),false);a.saplings().track(root);
  root.getRelative(BlockFace.EAST).setType(Material.STONE,true);
  Bukkit.getScheduler().runTaskLater(this,()->{try{
   check("skyroot_sapling".equals(a.content().blockIdForCarrier(root.getBlockData().getAsString())),"sapling identity survives adjacent block physics");
   root.getRelative(BlockFace.EAST).setType(Material.AIR,false);
   var random=com.aetherport.aether.worldgen.SaplingGrowth.class.getDeclaredField("random");random.setAccessible(true);((Random)random.get(a.saplings())).setSeed(42);
   ItemStack bone=new ItemStack(Material.BONE_MEAL,64);p.getInventory().setItemInMainHand(bone);
   for(int i=0;i<40 && root.getType()==Material.TRIPWIRE;i++)Bukkit.getPluginManager().callEvent(new PlayerInteractEvent(p,Action.RIGHT_CLICK_BLOCK,bone,root,BlockFace.UP,EquipmentSlot.HAND));
   check("skyroot_log".equals(a.content().blockIdForCarrier(root.getBlockData().getAsString())) && bone.getAmount()<64,"bonemeal interaction grows tree and consumes bonemeal");
   mining.clear(p);custom.setBlockData(Bukkit.createBlockData(a.content().block("holystone").carrier()),false);p.teleport(new Location(w,8.5,201,8.5,0,90));p.getInventory().setItemInMainHand(a.aetherItems().create("zanite_pickaxe",1));
   Bukkit.getScheduler().runTaskLater(this,()->{
    check(custom.equals(p.getTargetBlockExact(6)),"protocol client targets mining test block");
    Bukkit.getPluginManager().callEvent(new BlockDamageEvent(p,custom,BlockFace.UP,p.getInventory().getItemInMainHand(),false));
    Bukkit.getScheduler().runTaskLater(this,()->{
     check(custom.getType()==Material.AIR,"server completes mining without client finish packet");
     check(p.getAttribute(Attribute.BLOCK_BREAK_SPEED).getValue()==1,"completion clears prediction limiter");finish();
    },20);
   },3);
  }catch(Throwable e){fail(e);}},3);
 }catch(Throwable e){fail(e);}}
 void finish(){try{Files.write(Path.of("revision14.txt"),results);}catch(Exception e){throw new RuntimeException(e);}getLogger().info("REVISION14_FINISHED "+results.stream().filter(s->s.startsWith("FAIL")).count()+" failures / "+results.size());}
 void fail(Throwable e){check(false,e.toString());e.printStackTrace();finish();}
}
