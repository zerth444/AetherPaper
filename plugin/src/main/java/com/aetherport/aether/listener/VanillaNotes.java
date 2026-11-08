package com.aetherport.aether.listener;
import com.aetherport.aether.AetherPlugin;
import org.bukkit.*;import org.bukkit.block.Block;import org.bukkit.event.*;import org.bukkit.event.block.*;import org.bukkit.event.player.PlayerInteractEvent;import org.bukkit.inventory.EquipmentSlot;import org.bukkit.persistence.PersistentDataType;
/** Reserve a vanilla model for native notes; store their musical pitch separately. */
public final class VanillaNotes implements Listener {
 private final AetherPlugin plugin;
 public VanillaNotes(AetherPlugin plugin){this.plugin=plugin;Bukkit.getPluginManager().registerEvents(this,plugin);}
 private NamespacedKey key(Block b){return new NamespacedKey(plugin,"vanilla_note_"+(b.getX()&15)+"_"+b.getY()+"_"+(b.getZ()&15));}
 private boolean nativeNote(Block b){return b.getType()==Material.NOTE_BLOCK && b.getBlockData().getAsString().contains("note=22,");}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){
  if(e.getBlock().getType()!=Material.NOTE_BLOCK || e.getItemInHand().getType()!=Material.NOTE_BLOCK || plugin.aetherItems().idOf(e.getItemInHand())!=null)return;
  e.getBlock().setBlockData(Bukkit.createBlockData("minecraft:note_block[instrument=harp,note=22,powered=false]"),false);
  e.getBlock().getChunk().getPersistentDataContainer().set(key(e.getBlock()),PersistentDataType.INTEGER,0);
 }
 @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void use(PlayerInteractEvent e){
  if(e.getHand()!=EquipmentSlot.HAND || e.getAction()!=Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null || !nativeNote(e.getClickedBlock()))return;
  if(e.getPlayer().isSneaking() && e.getItem()!=null && !e.getItem().getType().isAir())return;
  Block b=e.getClickedBlock();int pitch=(b.getChunk().getPersistentDataContainer().getOrDefault(key(b),PersistentDataType.INTEGER,0)+1)%25;
  b.getChunk().getPersistentDataContainer().set(key(b),PersistentDataType.INTEGER,pitch);e.setCancelled(true);
  b.getWorld().playNote(b.getLocation(),((org.bukkit.block.data.type.NoteBlock)b.getBlockData()).getInstrument(),new Note(pitch));
 }
 @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void play(NotePlayEvent e){if(nativeNote(e.getBlock()))e.setNote(new Note(e.getBlock().getChunk().getPersistentDataContainer().getOrDefault(key(e.getBlock()),PersistentDataType.INTEGER,0)));}
}
