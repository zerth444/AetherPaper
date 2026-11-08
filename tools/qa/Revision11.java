import com.aetherport.aether.AetherPlugin;
import org.bukkit.*;import org.bukkit.entity.*;import org.bukkit.event.*;import org.bukkit.event.player.*;import org.bukkit.event.block.*;import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;import java.util.*;
public class Revision11 extends JavaPlugin implements Listener {
 final List<String> results=new ArrayList<>();
 void check(boolean ok,String label){String s=(ok?"PASS ":"FAIL ")+label;results.add(s);getLogger().info(s);}
 public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);}
 @EventHandler public void join(PlayerJoinEvent event){if(!event.getPlayer().getName().equals("AetherBot"))return;Bukkit.getScheduler().runTaskLater(this,()->run(event.getPlayer()),20);}
 void run(Player player){try{
  var a=AetherPlugin.get();World w=a.mainWorld();var block=w.getBlockAt(4120,200,4120);
  block.setBlockData(Bukkit.createBlockData(a.content().block("cold_aercloud").carrier()),false);
  check("cold_aercloud".equals(a.content().blockIdForCarrier(block.getBlockData().getAsString())),"cloud state recognized");
  double top=block.getCollisionShape().getBoundingBoxes().stream().mapToDouble(org.bukkit.util.BoundingBox::getMaxY).max().orElse(1);
  check(top<1 && top>0,"real cloud collision below full cube top="+top);
  var support=new com.aetherport.aether.worldgen.PlantSupport(a,a.content(),a.aetherItems());
  var method=support.getClass().getDeclaredMethod("check",org.bukkit.block.Block.class,boolean.class);method.setAccessible(true);
  block.setType(Material.WHITE_STAINED_GLASS,false);method.invoke(support,block,false);
  check(block.getType()==Material.WHITE_STAINED_GLASS,"r14 ordinary white glass no longer migrates to cloud");
  block.setBlockData(Bukkit.createBlockData(a.content().block("holystone").carrier()),false);
  block.getRelative(0,-1,0).setType(Material.BEDROCK,false);
  player.setGameMode(GameMode.SURVIVAL);player.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.IRON_PICKAXE));
  player.teleport(new Location(w,4120.5,201,4120.5,0,90));
  Bukkit.getScheduler().runTaskLater(this,()->{
   check(block.equals(player.getTargetBlockExact(6)),"headless player targets test stone");
   Bukkit.getPluginManager().callEvent(new BlockDamageEvent(player,block,player.getInventory().getItemInMainHand(),false));
   check(player.getAttribute(org.bukkit.attribute.Attribute.BLOCK_BREAK_SPEED).getValue()==0,"client mining prediction disabled during server mining");
   Bukkit.getScheduler().runTaskLater(this,()->{
    check(block.isEmpty(),"server completes mining without client finish-dig packet");
    check(player.getAttribute(org.bukkit.attribute.Attribute.BLOCK_BREAK_SPEED).getValue()>0,"mining attribute restored");
    try{Files.write(Path.of("../history/verification/revision-r11.txt"),results);}catch(Exception e){throw new RuntimeException(e);}
    getLogger().info("REVISION11_FINISHED");
   },40);
  },10);
 }catch(Exception e){throw new RuntimeException(e);}}
}
