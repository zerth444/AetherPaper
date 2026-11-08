import com.aetherport.aether.*;
import com.aetherport.aether.advancement.AdvancementLoader;
import com.aetherport.aether.listener.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.enchantments.Enchantment;
import io.netty.channel.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Revision16 extends JavaPlugin implements Listener {
 final List<String> report=new ArrayList<>();AetherPlugin a;Player p;World w;Block block;MiningListener mining;
 final Set<Location> furnaces=new HashSet<>();final List<Runnable> cookingChecks=new ArrayList<>();
 final Queue<Integer> cracks=new ConcurrentLinkedQueue<>();Channel channel;List<NamespacedKey> recipeKeys;
 void check(boolean ok,String s){String line=(ok?"PASS ":"FAIL ")+s;report.add(line);getLogger().info(line);}
 void later(long ticks,Runnable task){Bukkit.getScheduler().runTaskLater(this,()->{try{task.run();}catch(Throwable e){fail(e);}},ticks);}
 public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);}
 @EventHandler public void join(PlayerJoinEvent e){if(e.getPlayer().getName().equals("AetherBot"))later(30,()->start(e.getPlayer()));}
 public static class Legacy {boolean called;public org.bukkit.advancement.Advancement loadAdvancement(NamespacedKey key,String json){called=true;return null;}}
 public static class Modern {boolean called;public org.bukkit.advancement.Advancement loadAdvancement(net.kyori.adventure.key.Key key,String json,boolean save){called=save;return null;}}
 void start(Player player){try{
  a=AetherPlugin.get();p=player;w=a.mainWorld();p.setGameMode(GameMode.SURVIVAL);p.setAllowFlight(true);p.getInventory().clear();
  for(int x=-1;x<=4;x++)for(int z=-1;z<=2;z++)w.getChunkAt(x,z).setForceLoaded(true);
  block=w.getBlockAt(8,200,8);p.teleport(new Location(w,8.5,201,8.5,0,90));
  var field=AetherPlugin.class.getDeclaredField("miningListener");field.setAccessible(true);mining=(MiningListener)field.get(a);
  Legacy legacy=new Legacy();Modern modern=new Modern();AdvancementLoader.load(legacy,new NamespacedKey("aether","qa"),"{}");AdvancementLoader.load(modern,new NamespacedKey("aether","qa"),"{}");check(legacy.called && modern.called,"advancement adapter supports old and new API signatures");
  harvest();ores();recipes();stations();setupCooking();capture();later(30,this::finishCooking);
 }catch(Throwable e){fail(e);}}
 void clearDrops(){for(Entity e:w.getNearbyEntities(block.getLocation(),3,3,3))if(e instanceof Item)e.remove();}
 List<ItemStack> drops(){return w.getNearbyEntities(block.getLocation(),3,3,3).stream().filter(e->e instanceof Item).map(e->((Item)e).getItemStack()).toList();}
 List<ItemStack> mine(String id,ItemStack tool){mining.clear(p);clearDrops();block.setBlockData(Bukkit.createBlockData(a.content().block(id).carrier()),false);p.getInventory().setItemInMainHand(tool.clone());p.breakBlock(block);return drops();}
 boolean exact(List<ItemStack> result,String id,int count){return result.stream().allMatch(i->id.equals(a.aetherItems().idOf(i))) && result.stream().mapToInt(ItemStack::getAmount).sum()==count;}
 void harvest(){
  ItemStack silk=new ItemStack(Material.DIAMOND_PICKAXE);silk.addUnsafeEnchantment(Enchantment.SILK_TOUCH,1);
  for(String id:a.content().blocks().keySet())if(id.endsWith("_leaves")){
   check(exact(mine(id,new ItemStack(Material.SHEARS)),id,1),id+" shears gives exactly one leaf");
   check(exact(mine(id,silk),id,1),id+" Silk Touch gives exactly one leaf");
   boolean leafDropped=false;for(int n=0;n<48;n++)for(ItemStack item:mine(id,new ItemStack(Material.AIR)))if(id.equals(a.aetherItems().idOf(item)))leafDropped=true;
   check(!leafDropped,id+" hand never drops full leaf");
  }
  check(exact(mine("berry_bush",new ItemStack(Material.SHEARS)),"berry_bush",1),"ripe bush shears: one whole bush, no berries or loose stem");
  check(exact(mine("berry_bush",silk),"berry_bush",1),"ripe bush Silk Touch: one whole bush");
  check(exact(mine("berry_bush_stem",new ItemStack(Material.SHEARS)),"berry_bush_stem",1),"empty bush shears: one stem");
  var hand=mine("berry_bush",new ItemStack(Material.AIR));int count=hand.stream().mapToInt(ItemStack::getAmount).sum();
  check(count>=1 && count<=3 && hand.stream().allMatch(i->"blue_berry".equals(a.aetherItems().idOf(i))) && "berry_bush_stem".equals(a.content().blockIdForCarrier(block.getBlockData().getAsString())),"hand breaks ripe bush into berries and rooted stem only");
 }
 void ores(){
  for(String id:List.of("ambrosium_ore","zanite_ore","gravitite_ore","icestone")){
   int tier=Map.of("ambrosium_ore",0,"zanite_ore",1,"gravitite_ore",2,"icestone",1).get(id);
   check(a.content().block(id).requiredTier()==tier,id+" expected minimum tier from original tool tags="+tier);
   String drop=Map.of("ambrosium_ore","ambrosium_shard","zanite_ore","zanite_gemstone","gravitite_ore","gravitite_ore","icestone","icestone").get(id);
   for(String type:List.of("WOODEN","GOLDEN","STONE","IRON","DIAMOND","NETHERITE")){
    ItemStack tool=new ItemStack(Material.valueOf(type+"_PICKAXE"));var result=mine(id,tool);boolean allowed=Map.of("WOODEN",0,"GOLDEN",0,"STONE",1,"IRON",2,"DIAMOND",3,"NETHERITE",4).get(type)>=tier;
    check(allowed?exact(result,drop,1):result.isEmpty(),id+" vanilla "+type+" tier gate "+tier);
   }
   for(String type:List.of("skyroot","holystone","zanite","gravitite")){
    ItemStack tool=a.aetherItems().create(type+"_pickaxe",1);var result=mine(id,tool);boolean allowed=Map.of("skyroot",0,"holystone",1,"zanite",2,"gravitite",3).get(type)>=tier;
    check(allowed?exact(result,drop,1):result.isEmpty(),id+" custom "+type+" tier gate "+tier);
   }
   check(mine(id,new ItemStack(Material.DIAMOND_AXE)).isEmpty(),id+" wrong tool gives no resource");
  }
 }
 List<ItemStack> choices(RecipeChoice choice){if(choice==null)return List.of(new ItemStack(Material.AIR));if(choice instanceof RecipeChoice.ExactChoice e)return e.getChoices();if(choice instanceof RecipeChoice.MaterialChoice m)return m.getChoices().stream().map(ItemStack::new).toList();return List.of(choice.getItemStack());}
 void recipes(){
  int matrices=0,failed=0;recipeKeys=new ArrayList<>();var iterator=Bukkit.recipeIterator();
  while(iterator.hasNext()){
   Recipe recipe=iterator.next();if(!(recipe instanceof Keyed keyed)||!keyed.getKey().getNamespace().equals("aether"))continue;recipeKeys.add(keyed.getKey());
   List<List<ItemStack>> options=new ArrayList<>();for(int i=0;i<9;i++)options.add(List.of(new ItemStack(Material.AIR)));
   if(recipe instanceof ShapedRecipe shaped){var rows=shaped.getShape();for(int row=0;row<rows.length;row++)for(int col=0;col<rows[row].length();col++)options.set(row*3+col,choices(shaped.getChoiceMap().get(rows[row].charAt(col))));}
   else if(recipe instanceof ShapelessRecipe shapeless){for(int i=0;i<shapeless.getChoiceList().size();i++)options.set(i,choices(shapeless.getChoiceList().get(i)));}
   else continue;
   for(int slot=0;slot<9;slot++)for(ItemStack option:options.get(slot)){
    ItemStack[] matrix=new ItemStack[9];for(int i=0;i<9;i++)matrix[i]=options.get(i).get(0).clone();matrix[slot]=option.clone();
    Recipe found=Bukkit.getCraftingRecipe(matrix,w);matrices++;
    if(found==null || !found.getResult().isSimilar(recipe.getResult()) || found.getResult().getAmount()!=recipe.getResult().getAmount()){
     if(failed++<15)getLogger().warning("MATRIX "+keyed.getKey()+" slot="+slot+" option="+a.aetherItems().idOf(option)+" got="+(found==null?"null":found.getResult()));
    }
   }
  }
  check(recipeKeys.size()==194,"all 194 supported recipes registered");check(failed==0,"all ingredient alternatives: "+matrices+" real crafting matrices, mismatches="+failed);
  NamespacedKey planks=NamespacedKey.fromString("aether:skyroot_planks");p.undiscoverRecipe(planks);p.getInventory().clear();p.getInventory().addItem(a.aetherItems().create("skyroot_log",1));
  try{var method=a.advancements().getClass().getDeclaredMethod("checkInventory",Player.class);method.setAccessible(true);method.invoke(a.advancements(),p);}catch(Exception e){throw new RuntimeException(e);}
  check(p.hasDiscoveredRecipe(planks),"recipe book unlocks with one ingredient alternative, without already owning output");
  if(Files.exists(Path.of("persisted-recipes.marker")))check(p.hasDiscoveredRecipe(NamespacedKey.fromString("aether:holystone_sword")),"saved custom recipe survives server restart");
 }
 @EventHandler public void accelerate(FurnaceStartSmeltEvent e){if(furnaces.contains(e.getBlock().getLocation()))e.setTotalCookTime(4);}
 void stations()throws Exception{
  var manager=a.stations();Class<?> stateType=Arrays.stream(manager.getClass().getDeclaredClasses()).filter(c->c.getSimpleName().equals("State")).findFirst().orElseThrow();
  var constructor=stateType.getDeclaredConstructor();constructor.setAccessible(true);var process=manager.getClass().getDeclaredMethod("process",stateType);process.setAccessible(true);
  for(String type:List.of("altar","freezer"))for(var cook:type.equals("altar")?manager.recipes().enchanting():manager.recipes().freezing())for(String ref:cook.in()){
   Object state=constructor.newInstance();ItemStack input=ref.startsWith("aether:")?a.aetherItems().create(ref.substring(7),1):new ItemStack(Material.matchMaterial(ref.substring(10)));
   Map<String,Object> values=new HashMap<>();values.put("type",type);values.put("loc",w.getBlockAt(62,200,12).getLocation());values.put("input",input);values.put("fuel",a.aetherItems().create(type.equals("altar")?"ambrosium_shard":"icestone",1));values.put("progress",cook.time()-1);
   for(var value:values.entrySet()){var f=stateType.getDeclaredField(value.getKey());f.setAccessible(true);f.set(state,value.getValue());}process.invoke(manager,state);
   var output=stateType.getDeclaredField("output");output.setAccessible(true);ItemStack result=(ItemStack)output.get(state);
   ItemStack expected=cook.out().startsWith("aether:")?a.aetherItems().create(cook.out().substring(7),cook.count()):new ItemStack(Material.matchMaterial(cook.out().substring(10)),cook.count());
   var remaining=stateType.getDeclaredField("input");remaining.setAccessible(true);ItemStack rest=(ItemStack)remaining.get(state);
   check(result!=null && result.isSimilar(expected) && result.getAmount()==expected.getAmount() && (rest==null || rest.getAmount()==0),"station completes recipe "+type+" "+ref+" -> "+cook.out());
  }
 }
 void setupCooking(){
  var it=Bukkit.recipeIterator();int index=0;
  while(it.hasNext()){
   Recipe recipe=it.next();if(!(recipe instanceof Keyed key)||!key.getKey().getNamespace().equals("aether") || !(recipe instanceof CookingRecipe<?> cook))continue;
   for(ItemStack input:choices(cook.getInputChoice())){
    Block at=w.getBlockAt(20+(index%8)*2,200,8+(index/8)*2);index++;at.setType(cook instanceof BlastingRecipe?Material.BLAST_FURNACE:Material.FURNACE,false);furnaces.add(at.getLocation());
    // getInventory() is live; applying the old snapshot afterwards clears it.
    Furnace state=(Furnace)at.getState();state.getInventory().clear();state.getInventory().setSmelting(input.clone());state.getInventory().setFuel(a.aetherItems().create("ambrosium_shard",1));
    cookingChecks.add(()->{var inventory=((Furnace)at.getState()).getInventory();ItemStack actual=inventory.getResult(),remaining=inventory.getSmelting();check(actual!=null && actual.isSimilar(cook.getResult()) && actual.getAmount()==cook.getResult().getAmount() && (remaining==null || remaining.getType().isAir()),"actual furnace "+key.getKey()+" input="+Objects.toString(a.aetherItems().idOf(input),input.getType().name()));});
   }
  }
 }
 final List<StonecuttingRecipe> cuts=new ArrayList<>();int cutIndex;
 void finishCooking(){
  for(Runnable check:cookingChecks)check.run();var iterator=Bukkit.recipeIterator();while(iterator.hasNext()){Recipe recipe=iterator.next();if(recipe instanceof StonecuttingRecipe cut && cut.getKey().getNamespace().equals("aether"))cuts.add(cut);}nextCut();
 }
 void nextCut(){
  if(cutIndex==cuts.size()){smith();return;}var cut=cuts.get(cutIndex++);
  var view=(org.bukkit.inventory.view.StonecutterView)p.openStonecutter(w.getBlockAt(60,200,8).getLocation(),true);view.getTopInventory().setItem(0,choices(cut.getInputChoice()).get(0).clone());
  later(2,()->{try{
   int index=-1;for(int i=0;i<view.getRecipes().size();i++)if(view.getRecipes().get(i).getKey().equals(cut.getKey()))index=i;
   if(index<0){check(false,"stonecutter offers "+cut.getKey());view.getTopInventory().clear();p.closeInventory();nextCut();return;}
   Object handle=p.getClass().getMethod("getHandle").invoke(p),menu=handle.getClass().getField("containerMenu").get(handle);
   menu.getClass().getMethod("clickMenuButton",Class.forName("net.minecraft.world.entity.player.Player"),int.class).invoke(menu,handle,index);
   ItemStack result=view.getTopInventory().getItem(1);check(result!=null && result.isSimilar(cut.getResult()) && result.getAmount()==cut.getResult().getAmount(),"real stonecutter result "+cut.getKey());
   view.getTopInventory().clear();p.closeInventory();nextCut();
  }catch(Exception e){fail(e);}});
 }
 void smith(){
  var recipe=(SmithingTransformRecipe)Bukkit.getRecipe(NamespacedKey.fromString("aether:netherite_gloves_smithing"));
  var view=p.openSmithingTable(w.getBlockAt(60,200,10).getLocation(),true);var inv=(SmithingInventory)view.getTopInventory();
  inv.setInputTemplate(choices(recipe.getTemplate()).get(0).clone());inv.setInputEquipment(choices(recipe.getBase()).get(0).clone());inv.setInputMineral(choices(recipe.getAddition()).get(0).clone());
  later(3,()->{
   ItemStack result=inv.getResult();check(result!=null && result.isSimilar(recipe.getResult()),"real smithing produces netherite glove identity/model, got="+(result==null?"null":a.aetherItems().idOf(result)));
   inv.clear();p.closeInventory();p.discoverRecipes(recipeKeys);p.saveData();Bukkit.reloadData();later(50,this::afterReload);
  });
 }
 void afterReload(){
  check(recipeKeys.stream().allMatch(k->Bukkit.getRecipe(k)!=null),"all custom recipes restored after real data reload");
  check(Bukkit.getAdvancement(NamespacedKey.fromString("aether:read_lore"))!=null,"custom advancement restored after data reload");
  p.discoverRecipes(recipeKeys);p.saveData();try{Files.writeString(Path.of("persisted-recipes.marker"),"194");}catch(Exception e){throw new RuntimeException(e);}
  p.getInventory().clear();p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));block.setBlockData(Bukkit.createBlockData(a.content().block("holystone").carrier()),false);p.teleport(new Location(w,8.5,201,8.5,0,90));later(5,this::startMining);
 }
 void capture(){try{
  Object handle=p.getClass().getMethod("getHandle").invoke(p),listener=handle.getClass().getField("connection").get(handle),connection=listener.getClass().getField("connection").get(listener);channel=(Channel)connection.getClass().getField("channel").get(connection);
  channel.eventLoop().execute(()->channel.pipeline().addBefore("packet_handler","qa_cracks",new ChannelDuplexHandler(){@Override public void write(ChannelHandlerContext ctx,Object packet,ChannelPromise promise)throws Exception{
   if(packet.getClass().getSimpleName().equals("ClientboundBlockDestructionPacket") && (Integer)packet.getClass().getMethod("getId").invoke(packet)==MiningListener.crackSource(p))cracks.add((Integer)packet.getClass().getMethod("getProgress").invoke(packet));
   super.write(ctx,packet,promise);
  }}));
 }catch(Exception e){throw new RuntimeException(e);}}
 void damage(){Bukkit.getPluginManager().callEvent(new BlockDamageEvent(p,block,BlockFace.UP,p.getInventory().getItemInMainHand(),false));}
 void startMining(){cracks.clear();damage();later(25,this::damage);later(50,this::damage);later(180,()->{
  List<Integer> values=new ArrayList<>(cracks);boolean monotonic=true;int previous=-1,clears=0;
  for(int value:values){if(value<0){clears++;continue;}if(value<previous)monotonic=false;previous=value;}
  check(MiningListener.crackSource(p)!=p.getEntityId(),"custom crack source distinct from vanilla/client source");
  check(monotonic && previous>=8 && clears==1 && values.size()<=11,"one monotonic crack animation with repeated START packets: "+values);
  check(block.getType()==Material.AIR,"repeated START does not delay mining completion");finish();
 });}
 void finish(){try{Files.write(Path.of("revision16.txt"),report);}catch(Exception e){throw new RuntimeException(e);}getLogger().info("REVISION16_FINISHED "+report.stream().filter(s->s.startsWith("FAIL")).count()+" failures / "+report.size());}
 void fail(Throwable e){check(false,e.toString());e.printStackTrace();finish();}
}
