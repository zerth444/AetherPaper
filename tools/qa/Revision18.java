import com.aetherport.aether.*;
import com.aetherport.aether.item.LoreMenu;
import com.google.gson.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class Revision18 extends JavaPlugin implements Listener {
 final List<String> report=new ArrayList<>();AetherPlugin a;Player p;LoreMenu menu;String testKey;ItemStack testItem;
 void check(boolean ok,String label){String s=(ok?"PASS ":"FAIL ")+label;report.add(s);getLogger().info(s);}
 public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);}
 @EventHandler public void join(PlayerJoinEvent e){if(e.getPlayer().getName().equals("AetherBot"))Bukkit.getScheduler().runTaskLater(this,()->start(e.getPlayer()),30);}
 void next(Runnable r){Bukkit.getScheduler().runTaskLater(this,()->{try{r.run();}catch(Throwable e){fail(e);}},3);}
 LoreMenu.Session session(){return (LoreMenu.Session)p.getOpenInventory().getTopInventory().getHolder();}
 void click(int slot,ClickType click,InventoryAction action){Bukkit.getPluginManager().callEvent(new InventoryClickEvent(p.getOpenInventory(),slot<54?InventoryType.SlotType.CONTAINER:InventoryType.SlotType.QUICKBAR,slot,click,action));}
 int amount(String id){return Arrays.stream(p.getInventory().getContents()).filter(Objects::nonNull).filter(i->id.equals(a.aetherItems().idOf(i))).mapToInt(ItemStack::getAmount).sum();}
 void start(Player player){try{
  a=AetherPlugin.get();p=player;menu=a.loreMenu();p.getInventory().clear();p.setGameMode(GameMode.SURVIVAL);p.setItemOnCursor(null);
  check(menu.entryCount()==144,"only 144 currently available entries");
  try(var in=a.getClass().getResourceAsStream("/content/lore_gui.json")){
   var entries=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("entries");
   for(String hidden:List.of("valkyrie_pickaxe","gold_dungeon_key","victory_medal","treasure_chest","present","phoenix_helmet"))check(menu.entryFor(a.aetherItems().create(hidden,1))==null,"unavailable item cannot open archived entry: "+hidden);
   for(var entry:entries.entrySet()){
    if(entry.getKey().startsWith("__"))continue;
    String id=entry.getKey().substring(entry.getKey().lastIndexOf('.')+1);var item=a.aetherItems().create(id,1);
    check(item!=null && entry.getKey().equals(menu.entryFor(item)),"available article resolves real item: "+id);
    check(!entry.getValue().getAsJsonObject().getAsJsonArray("ru").isEmpty(),"Russian pages exist: "+id);
   }
   for(var entry:entries.entrySet()){
    if(entry.getKey().startsWith("__"))continue;
    String id=entry.getKey().substring(entry.getKey().lastIndexOf('.')+1);var item=a.aetherItems().create(id,1);
    if(item!=null && entry.getValue().getAsJsonObject().getAsJsonArray(p.getLocale().startsWith("ru")?"ru":"en").size()>1){testKey=entry.getKey();testItem=item;break;}
   }
  }
  check(testItem!=null,"multi-page original entry selected for navigation test");
  var use=new PlayerInteractEvent(p,org.bukkit.event.block.Action.RIGHT_CLICK_AIR,a.aetherItems().create("book_of_lore",1),null,org.bukkit.block.BlockFace.SELF,EquipmentSlot.HAND);use.setCancelled(true);Bukkit.getPluginManager().callEvent(use);
  next(()->{
   check(p.getOpenInventory().getTopInventory().getHolder() instanceof LoreMenu.Session,"right-click book opens menu despite native PAPER cancellation");
   check(p.getOpenInventory().getTitle().contains("\ue100"),"menu uses original-art bitmap font, not written book");
   p.setItemOnCursor(a.aetherItems().create("holystone",9));click(LoreMenu.INPUT,ClickType.LEFT,InventoryAction.PLACE_ALL);
   next(this::split);
  });
 }catch(Throwable e){fail(e);}}
 void split(){
  check(session().input.getAmount()==9 && p.getItemOnCursor().getType().isAir(),"inserted full stack transferred exactly once");
  check("lore.block.aether.holystone".equals(session().selected),"inserted block resolves original block lore");
  var read=Bukkit.getAdvancement(NamespacedKey.fromString("aether:read_lore"));check(read!=null && p.getAdvancementProgress(read).isDone(),"reading real input grants original read_lore advancement");
  click(LoreMenu.INPUT,ClickType.RIGHT,InventoryAction.PICKUP_HALF);next(()->{
   check(session().input.getAmount()==4 && p.getItemOnCursor().getAmount()==5,"right-click takes half without duplication");
   click(LoreMenu.INPUT,ClickType.RIGHT,InventoryAction.PLACE_ONE);next(()->{
    check(session().input.getAmount()==5 && p.getItemOnCursor().getAmount()==4,"right-click places exactly one");
    click(LoreMenu.INPUT,ClickType.LEFT,InventoryAction.PLACE_ALL);next(()->{
     click(LoreMenu.INPUT,ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY);next(this::navigation);
    });
   });
  });
 }
 void navigation(){
  check(session().input==null && amount("holystone")==9,"shift-click returns input stack exactly once");
  p.setItemOnCursor(testItem.clone());click(LoreMenu.INPUT,ClickType.LEFT,InventoryAction.PLACE_ALL);next(()->{
   check(testKey.equals(session().selected) && menu.pageCount(session())>1,"multi-page item entry selected");
   click(LoreMenu.NEXT,ClickType.LEFT,InventoryAction.PICKUP_ALL);next(()->{
    check(session().page==1 && session().input!=null,"next button changes page and retains real input");
    click(LoreMenu.PREVIOUS,ClickType.LEFT,InventoryAction.PICKUP_ALL);next(()->{
     check(session().page==0,"previous button changes page");
     var drag=new InventoryDragEvent(p.getOpenInventory(),new ItemStack(Material.AIR),testItem.clone(),false,Map.of(0,testItem.clone()));Bukkit.getPluginManager().callEvent(drag);check(drag.isCancelled(),"drag cannot overwrite protected menu cells");
     click(LoreMenu.INDEX,ClickType.LEFT,InventoryAction.PICKUP_ALL);next(this::catalog);
    });
   });
  });
 }
 void catalog(){
  check(!(p.getOpenInventory().getTopInventory().getHolder() instanceof LoreMenu.Session),"index button opens catalogue");
  ItemStack first=p.getOpenInventory().getTopInventory().getItem(0).clone();
  click(LoreMenu.NEXT,ClickType.LEFT,InventoryAction.PICKUP_ALL);next(()->{
   check(!first.isSimilar(p.getOpenInventory().getTopInventory().getItem(0)),"catalogue next button changes entries");
   click(LoreMenu.PREVIOUS,ClickType.LEFT,InventoryAction.PICKUP_ALL);next(()->{
    check(first.isSimilar(p.getOpenInventory().getTopInventory().getItem(0)),"catalogue previous button restores page");
    click(0,ClickType.LEFT,InventoryAction.PICKUP_ALL);next(()->{
     check(session().selected!=null && session().input==null,"catalogue opens entry as virtual preview");
     check(amount(a.aetherItems().idOf(testItem))==1,"catalogue returns previously inserted item");
     click(LoreMenu.INPUT,ClickType.LEFT,InventoryAction.PICKUP_ALL);next(()->{
      check(p.getItemOnCursor().getType().isAir() && session().selected==null,"catalogue icon cannot be taken as a free item");
      p.setItemOnCursor(a.aetherItems().create("zanite_gemstone",3));click(LoreMenu.INPUT,ClickType.LEFT,InventoryAction.PLACE_ALL);next(()->{
       p.closeInventory();check(amount("zanite_gemstone")==3,"closing book returns real items");
       menu.open(p);p.setItemOnCursor(new ItemStack(Material.BEDROCK));click(LoreMenu.INPUT,ClickType.LEFT,InventoryAction.PLACE_ALL);next(this::unknown);
      });
     });
    });
   });
  });
 }
 void unknown(){
  check(session().selected==null && session().input.getType()==Material.BEDROCK && menu.pageCount(session())>0,"unknown item shows explanation without losing item");
  click(LoreMenu.INDEX,ClickType.LEFT,InventoryAction.PICKUP_ALL);next(()->{
   p.closeInventory();check(p.getInventory().contains(Material.BEDROCK),"closing catalogue returns held input");
   menu.open(p);p.setItemOnCursor(testItem.clone());click(LoreMenu.INPUT,ClickType.LEFT,InventoryAction.PLACE_ALL);next(()->{
    click(LoreMenu.NEXT,ClickType.LEFT,InventoryAction.PICKUP_ALL);p.closeInventory();next(()->{
     check(!(p.getOpenInventory().getTopInventory().getHolder() instanceof LoreMenu.Session),"pending navigation does not reopen after Escape");
     menu.open(p);p.setItemOnCursor(a.aetherItems().create("book_of_lore",1));click(LoreMenu.INPUT,ClickType.LEFT,InventoryAction.PLACE_ALL);next(()->{
      var loreception=Bukkit.getAdvancement(NamespacedKey.fromString("aether:loreception"));check(loreception!=null && p.getAdvancementProgress(loreception).isDone(),"putting book into book grants original loreception advancement");p.closeInventory();finish();
     });
    });
   });
  });
 }
 void fail(Throwable e){check(false,e.toString());e.printStackTrace();finish();}
 void finish(){try{Files.write(Path.of("revision18.txt"),report);}catch(Exception e){throw new RuntimeException(e);}getLogger().info("REVISION18_FINISHED "+report.stream().filter(s->s.startsWith("FAIL")).count()+" failures / "+report.size());}
}
