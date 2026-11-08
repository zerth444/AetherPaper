package com.aetherport.aether.item;

import com.aetherport.aether.AetherPlugin;
import com.google.gson.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Original item-inspection book, with server-owned navigation and real input.
 * The client uses the resource pack's original artwork and baked lore text. */
public final class LoreMenu implements Listener {
 public static final int INPUT=29,PREVIOUS=45,INDEX=49,NEXT=53;
 private static final Key FONT=Key.key("aether:lore_gui");
 private final AetherPlugin plugin;
 private final JsonObject manifest;
 private final List<String> entries;
 private final Map<UUID,Session> sessions=new HashMap<>();
 public static final class Session implements InventoryHolder {
  public ItemStack input;public String selected;public int page;public Inventory inventory;boolean transitioning;String locale;
  public Inventory getInventory(){return inventory;}
 }
 private record Catalog(Session session,int page) implements InventoryHolder {public Inventory getInventory(){return null;}}
 public LoreMenu(AetherPlugin plugin){
  this.plugin=plugin;
  try(var in=getClass().getResourceAsStream("/content/lore_gui.json")){
   manifest=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in),StandardCharsets.UTF_8)).getAsJsonObject();
  }catch(Exception e){throw new IllegalStateException("Book of Lore layout unavailable",e);}
  entries=manifest.getAsJsonObject("entries").keySet().stream().filter(k->!k.startsWith("__")).toList();
  Bukkit.getPluginManager().registerEvents(this,plugin);
 }
 public int entryCount(){return entries.size();}
 public String entryFor(ItemStack item){
  if(item==null || item.getType().isAir())return null;
  String id=plugin.aetherItems().idOf(item),key;
  if(id!=null){
   if(id.equals("hammer_of_kingbdogz") && item.getItemMeta().hasDisplayName() && PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()).equalsIgnoreCase("hammer of jeb"))id="hammer_of_jeb";
   key="lore."+(plugin.content().isBlock(id)?"block":"item")+".aether."+id;
  }else key="lore."+(item.getType().isBlock()?"block":"item")+".minecraft."+item.getType().getKey().getKey();
  if(manifest.getAsJsonObject("entries").has(key))return key;
  return null;
 }
 public void open(Player player){
  if(sessions.containsKey(player.getUniqueId()))player.closeInventory();
  Session session=new Session();session.locale=player.getLocale().toLowerCase(Locale.ROOT).startsWith("ru")?"ru":"en";
  sessions.put(player.getUniqueId(),session);render(player,session);
 }
 public int pageCount(Session s){String key=s.selected!=null?s.selected:s.input==null?"__empty":"__unknown";var entry=manifest.getAsJsonObject("entries").getAsJsonObject(key);return entry==null?0:entry.getAsJsonArray(s.locale).size();}
 private Component title(Session s){
  int count=pageCount(s);s.page=Math.max(0,Math.min(s.page,Math.max(0,count-1)));
  String background=manifest.getAsJsonObject("backgrounds").get(s.locale+"_"+(s.page>0?1:0)+"_"+(s.page+1<count?1:0)).getAsString();
  Component result=Component.text("\ue100"+background).font(FONT).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC,false);
  String key=s.selected!=null?s.selected:s.input==null?"__empty":"__unknown";
  var entry=manifest.getAsJsonObject("entries").getAsJsonObject(key);
  if(entry!=null && count>0)result=result.append(Component.text("\ue101"+entry.getAsJsonArray(s.locale).get(s.page).getAsString()).font(FONT));
  return result;
 }
 private ItemStack button(String text){ItemStack item=new ItemStack(Material.PAPER);var meta=item.getItemMeta();meta.setItemModel(NamespacedKey.fromString("aether:gui/lore_invisible"));meta.displayName(Component.text(text).decoration(TextDecoration.ITALIC,false));item.setItemMeta(meta);return item;}
 private ItemStack icon(String key){
  String id=key.substring(key.lastIndexOf('.')+1);ItemStack item=plugin.aetherItems().create(id,1);
  if(item==null){Material mat=Material.matchMaterial(id);item=new ItemStack(mat!=null && mat.isItem() && !mat.isAir()?mat:Material.BOOK);}
  return item;
 }
 private void show(Player player,Session s,Inventory inv){
  // Native menu replacement returns its carried stack to the player's bag.
  // Temporarily lift it out, then restore it on the new menu exactly once.
  ItemStack cursor=player.getItemOnCursor().clone();player.setItemOnCursor(null);
  s.transitioning=true;try{player.openInventory(inv);}finally{player.setItemOnCursor(cursor);s.transitioning=false;}
 }
 private void later(Player p,Session s,Runnable action){Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline() && sessions.get(p.getUniqueId())==s)action.run();});}
 private void render(Player player,Session s){
  Inventory inv=Bukkit.createInventory(s,54,title(s));
  inv.setItem(INPUT,s.input!=null?s.input.clone():s.selected!=null?icon(s.selected):null);
  if(s.page>0)inv.setItem(PREVIOUS,button(s.locale.equals("ru")?"Предыдущая страница":"Previous page"));
  if(s.page+1<pageCount(s))inv.setItem(NEXT,button(s.locale.equals("ru")?"Следующая страница":"Next page"));
  inv.setItem(INDEX,button(s.locale.equals("ru")?"Каталог всех записей":"All lore entries"));
  show(player,s,inv);s.inventory=inv;
 }
 private void catalog(Player p,Session s,int page){
  int count=(entries.size()+44)/45;page=Math.max(0,Math.min(page,count-1));
  Inventory inv=Bukkit.createInventory(new Catalog(s,page),54,Component.text((s.locale.equals("ru")?"Книга Эфира — каталог ":"Book of Lore — index ")+(page+1)+" / "+count));
  for(int i=0;i<45 && page*45+i<entries.size();i++){
   String key=entries.get(page*45+i);ItemStack item=icon(key);var meta=item.getItemMeta();
   meta.displayName(Component.text(manifest.getAsJsonObject("titles").getAsJsonObject(key).get(s.locale).getAsString()).decoration(TextDecoration.ITALIC,false));
   meta.lore(List.of(Component.text(s.locale.equals("ru")?"Нажмите, чтобы прочитать":"Click to read")));item.setItemMeta(meta);inv.setItem(i,item);
  }
  if(page>0)inv.setItem(PREVIOUS,plain(Material.ARROW,s.locale.equals("ru")?"Назад":"Previous"));
  inv.setItem(INDEX,plain(Material.BOOK,s.locale.equals("ru")?"Вернуться к книге":"Return to book"));
  if(page+1<count)inv.setItem(NEXT,plain(Material.ARROW,s.locale.equals("ru")?"Далее":"Next"));show(p,s,inv);
 }
 private ItemStack plain(Material material,String title){ItemStack item=new ItemStack(material);var meta=item.getItemMeta();meta.displayName(Component.text(title).decoration(TextDecoration.ITALIC,false));item.setItemMeta(meta);return item;}
 private void update(Player p,Session s){
  s.selected=entryFor(s.input);s.page=0;
  if(s.input!=null && s.selected!=null && plugin.advancements()!=null){
   plugin.advancements().grant(p,"aether:read_lore","lore_book_entry");
   if("book_of_lore".equals(plugin.aetherItems().idOf(s.input)))plugin.advancements().grant(p,"aether:loreception","lore_book_entry");
  }
  later(p,s,()->render(p,s));
 }
 @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){
  InventoryHolder holder=e.getView().getTopInventory().getHolder();Session s=holder instanceof Session session?session:holder instanceof Catalog catalog?catalog.session():null;
  if(s==null || !(e.getWhoClicked() instanceof Player p))return;
  if(holder instanceof Catalog c){
   e.setCancelled(true);int slot=e.getRawSlot();
   if(slot>=0 && slot<45 && c.page()*45+slot<entries.size()){
    if(s.input!=null){for(ItemStack rest:p.getInventory().addItem(s.input).values())p.getWorld().dropItemNaturally(p.getLocation(),rest);s.input=null;}
    s.selected=entries.get(c.page()*45+slot);s.page=0;later(p,s,()->render(p,s));
   }
   else if(slot==INDEX)later(p,s,()->render(p,s));
   else if(slot==PREVIOUS && c.page()>0)later(p,s,()->catalog(p,s,c.page()-1));
   else if(slot==NEXT && (c.page()+1)*45<entries.size())later(p,s,()->catalog(p,s,c.page()+1));
   return;
  }
  e.setCancelled(true);int slot=e.getRawSlot();
  if(slot>=54){
   if(e.isShiftClick()){
    ItemStack from=e.getCurrentItem();if(from==null || from.getType().isAir())return;
    if(s.input==null){s.input=from.clone();e.setCurrentItem(null);update(p,s);}
    else if(s.input.isSimilar(from)){int take=Math.min(from.getAmount(),s.input.getMaxStackSize()-s.input.getAmount());s.input.setAmount(s.input.getAmount()+take);from.setAmount(from.getAmount()-take);e.setCurrentItem(from);update(p,s);}
   }else if(e.getAction()!=InventoryAction.COLLECT_TO_CURSOR && e.getClick()!=ClickType.DOUBLE_CLICK)e.setCancelled(false);
   return;
  }
  if(slot==INPUT){
   if(e.isShiftClick()){
    if(s.input!=null){var rest=p.getInventory().addItem(s.input.clone());s.input=rest.isEmpty()?null:rest.values().iterator().next();}update(p,s);return;
   }
   if(e.getClick()!=ClickType.LEFT && e.getClick()!=ClickType.RIGHT)return;
   ItemStack cursor=p.getItemOnCursor();boolean empty=cursor==null || cursor.getType().isAir();
   if(empty && s.input!=null){int take=e.isRightClick()?(s.input.getAmount()+1)/2:s.input.getAmount();ItemStack out=s.input.clone();out.setAmount(take);s.input.setAmount(s.input.getAmount()-take);if(s.input.getAmount()==0)s.input=null;p.setItemOnCursor(out);}
   else if(!empty && s.input==null){int take=e.isRightClick()?1:cursor.getAmount();s.input=cursor.clone();s.input.setAmount(take);cursor.setAmount(cursor.getAmount()-take);p.setItemOnCursor(cursor);}
   else if(!empty && s.input.isSimilar(cursor)){int take=Math.min(e.isRightClick()?1:cursor.getAmount(),s.input.getMaxStackSize()-s.input.getAmount());s.input.setAmount(s.input.getAmount()+take);cursor.setAmount(cursor.getAmount()-take);p.setItemOnCursor(cursor);}
   else if(!empty && !e.isRightClick()){ItemStack old=s.input;s.input=cursor.clone();p.setItemOnCursor(old);}
   update(p,s);return;
  }
  if(slot==PREVIOUS && s.page>0){s.page--;later(p,s,()->render(p,s));}
  else if(slot==NEXT && s.page+1<pageCount(s)){s.page++;later(p,s,()->render(p,s));}
  else if(slot==INDEX)later(p,s,()->catalog(p,s,0));
 }
 @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){InventoryHolder h=e.getView().getTopInventory().getHolder();if((h instanceof Session || h instanceof Catalog)&&e.getRawSlots().stream().anyMatch(slot->slot<54))e.setCancelled(true);}
 @EventHandler public void close(InventoryCloseEvent e){InventoryHolder h=e.getInventory().getHolder();Session s=h instanceof Session session?session:h instanceof Catalog c?c.session():null;
  if(s==null || s.transitioning)return;sessions.remove(e.getPlayer().getUniqueId(),s);
  if(s.input!=null){ItemStack item=s.input;s.input=null;
   if(e.getPlayer().isDead())e.getPlayer().getWorld().dropItemNaturally(e.getPlayer().getLocation(),item);
   else for(ItemStack rest:e.getPlayer().getInventory().addItem(item).values())e.getPlayer().getWorld().dropItemNaturally(e.getPlayer().getLocation(),rest);
  }
 }
 public void close(){for(UUID id:List.copyOf(sessions.keySet())){Player p=Bukkit.getPlayer(id);if(p!=null)p.closeInventory();}}
}
