import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.listener.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.attribute.Attribute;
import java.nio.file.*;
import java.util.*;

public class MechanicsRegression extends JavaPlugin implements Listener {
    final List<String> report=new ArrayList<>();
    void check(boolean ok,String s){String line=(ok?"PASS ":"FAIL ")+s;report.add(line);getLogger().info(line);}
    public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);getCommand("qamechanics").setExecutor((s,c,l,args)->{run();return true;});}
    @EventHandler public void onJoin(PlayerJoinEvent event){
        if(!event.getPlayer().getName().equals("AetherBot"))return;
        event.getPlayer().setGameMode(GameMode.CREATIVE);event.getPlayer().setAllowFlight(true);event.getPlayer().setFlying(true);
        Bukkit.getScheduler().runTaskLater(this,this::run,10);
    }
    ItemStack choice(RecipeChoice c){return c==null?new ItemStack(Material.AIR):c.getItemStack().clone();}
    void clearItems(Location at){for(Entity e:at.getWorld().getNearbyEntities(at,3,3,3))if(e instanceof Item)e.remove();}
    List<ItemStack> drops(Location at){return at.getWorld().getNearbyEntities(at,3,3,3).stream().filter(e->e instanceof Item).map(e->((Item)e).getItemStack()).toList();}
    double mining(Player bot,Block block,String id,Material tool){
        var a=AetherPlugin.get();block.setBlockData(Bukkit.createBlockData(a.content().block(id).carrier()),false);
        ItemStack stack=new ItemStack(tool);bot.getInventory().setItemInMainHand(stack);
        Bukkit.getPluginManager().callEvent(new BlockDamageEvent(bot,block,BlockFace.UP,stack,false));
        double speed=block.getBreakSpeed(bot);
        Bukkit.getPluginManager().callEvent(new BlockDamageAbortEvent(bot,block,stack));return speed;
    }
    void environment(World w)throws Exception{
        Object handle=w.getClass().getMethod("getHandle").invoke(w), env=handle.getClass().getMethod("environmentAttributes").invoke(handle);
        Class<?> attr=Class.forName("net.minecraft.world.attribute.EnvironmentAttribute"),attrs=Class.forName("net.minecraft.world.attribute.EnvironmentAttributes"),vec=Class.forName("net.minecraft.world.phys.Vec3"),interp=Class.forName("net.minecraft.world.attribute.SpatialAttributeInterpolator");
        for(String name:List.of("SKY_COLOR","FOG_COLOR","SKY_LIGHT_FACTOR","SKY_LIGHT_COLOR","AMBIENT_LIGHT_COLOR","NIGHT_VISION_COLOR","FOG_END_DISTANCE")){
            Object key=attrs.getField(name).get(null),value=env.getClass().getMethod("getDimensionValue",attr).invoke(env,key);
            Object pos=env.getClass().getMethod("getValue",attr,vec,interp).invoke(env,key,vec.getConstructor(double.class,double.class,double.class).newInstance(400d,30d,400d),null);
            report.add("ENV "+name+" dimension="+value+" position="+pos);getLogger().info(report.getLast());
            if(name.equals("AMBIENT_LIGHT_COLOR"))check((((Integer)pos)&0xffffff)!=0,"shadow lightmap has non-zero ambient, no gamma 0/0");
            if(name.equals("NIGHT_VISION_COLOR"))check((((Integer)pos)&0xffffff)==0xffffff,"night vision lightmap has white base");
            if(name.equals("FOG_END_DISTANCE"))check(Math.abs(((Number)pos).floatValue()-8191.25)<.001,"Aether sky shader marker active");
        }
    }
    void run(){try{
        var a=AetherPlugin.get();World w=a.aetherWorldNoCreate();Player bot=Bukkit.getPlayerExact("AetherBot");if(bot==null)throw new IllegalStateException("missing client");
        environment(w);bot.setGameMode(GameMode.SURVIVAL);bot.setAllowFlight(true);bot.setFlying(false);bot.teleport(new Location(w,3200.5,201,3200.5));
        Block b=w.getBlockAt(3200,200,3200);
        double logAxe=mining(bot,b,"skyroot_log",Material.IRON_AXE),logPick=mining(bot,b,"skyroot_log",Material.IRON_PICKAXE);
        check(logAxe>logPick*5,"wood axe faster than pickaxe: "+logAxe+" / "+logPick);
        double stonePick=mining(bot,b,"holystone",Material.IRON_PICKAXE),stoneAxe=mining(bot,b,"holystone",Material.IRON_AXE);
        check(stonePick>stoneAxe*10,"stone pickaxe faster than axe: "+stonePick+" / "+stoneAxe);
        check(Math.abs(bot.getAttribute(Attribute.BLOCK_BREAK_SPEED).getValue()-1)<.00001,"mining modifier removed after abort");
        check(a.content().block("skyroot_log").hardness()==2,"copied log hardness=2");
        for(String id:List.of("skyroot_log","golden_oak_log","skyroot_planks","holystone","mossy_holystone","ambrosium_ore","zanite_ore","gravitite_ore","icestone")){
            clearItems(b.getLocation());b.setBlockData(Bukkit.createBlockData(a.content().block(id).carrier()),false);
            ItemStack held=new ItemStack(id.contains("log")||id.contains("planks")?Material.IRON_AXE:Material.NETHERITE_PICKAXE);bot.getInventory().setItemInMainHand(held);
            bot.breakBlock(b);var list=drops(b.getLocation());
            check(b.getType()==Material.AIR && !list.isEmpty() && list.stream().noneMatch(s->s.getType()==Material.NOTE_BLOCK),"mine resource "+id+" -> "+list.stream().map(a.aetherItems()::idOf).toList());
            check(bot.getInventory().getItemInMainHand().getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d && d.getDamage()==1,"durability consumed "+id);
        }
        clearItems(b.getLocation());b.setBlockData(Bukkit.createBlockData(a.content().block("gravitite_ore").carrier()),false);bot.getInventory().setItemInMainHand(new ItemStack(Material.STONE_PICKAXE));bot.breakBlock(b);
        check(drops(b.getLocation()).isEmpty(),"gravitite rejects stone tier");
        clearItems(b.getLocation());b.setBlockData(Bukkit.createBlockData(a.content().block("zanite_ore").carrier()),false);
        ItemStack silk=new ItemStack(Material.DIAMOND_PICKAXE);silk.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH,1);bot.getInventory().setItemInMainHand(silk);bot.breakBlock(b);
        var silkDrops=drops(b.getLocation());check(silkDrops.size()==1 && "zanite_ore".equals(a.aetherItems().idOf(silkDrops.getFirst())),"silk ore excludes gemstone alternative");
        int fortuneTotal=0;boolean fortuneValid=true;
        ItemStack fortune=new ItemStack(Material.DIAMOND_PICKAXE);fortune.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.FORTUNE,3);
        for(int attempt=0;attempt<32;attempt++){
            clearItems(b.getLocation());b.setBlockData(Bukkit.createBlockData(a.content().block("zanite_ore").carrier()),false);bot.getInventory().setItemInMainHand(fortune.clone());bot.breakBlock(b);
            var outputs=drops(b.getLocation());int count=outputs.stream().mapToInt(ItemStack::getAmount).sum();fortuneTotal+=count;
            fortuneValid &= count>=1 && count<=4 && outputs.stream().allMatch(s->"zanite_gemstone".equals(a.aetherItems().idOf(s)));
        }
        check(fortuneValid && fortuneTotal>32,"Fortune III original ore bonus, total="+fortuneTotal+" /32 ores");
        for(boolean entity:new boolean[]{true,false}){
            clearItems(b.getLocation());List<Block> blocks=new ArrayList<>();
            for(int i=0;i<3;i++){Block p=b.getRelative(i,0,0);p.setBlockData(Bukkit.createBlockData(a.content().block(List.of("skyroot_log","holystone","zanite_ore").get(i)).carrier()),false);blocks.add(p);}
            if(entity){TNTPrimed t=w.spawn(b.getLocation().add(0,2,0),TNTPrimed.class);t.setFuseTicks(100);Bukkit.getPluginManager().callEvent(new EntityExplodeEvent(t,t.getLocation(),blocks,1,ExplosionResult.DESTROY));t.remove();}
            else {BlockExplodeEvent event=new BlockExplodeEvent(b,b.getState(),blocks,1,ExplosionResult.DESTROY);Bukkit.getPluginManager().callEvent(event);}
            var list=drops(b.getLocation());check(blocks.isEmpty()&&list.size()==3&&list.stream().allMatch(s->a.aetherItems().idOf(s)!=null),"custom drops for "+(entity?"entity":"block")+" explosion");
        }
        int invalid=0;for(String id:a.content().items().keySet()) {
            try {a.aetherItems().create(id,1).serializeAsBytes();}catch(Exception e){invalid++;report.add("INVALID_ITEM "+id+" "+e);}
        }
        check(invalid==0,"all "+a.content().items().size()+" item stacks serialize with valid components");
        int recipes=0,failed=0;var iterator=Bukkit.recipeIterator();
        while(iterator.hasNext()){
            Recipe recipe;try{recipe=iterator.next();}catch(Exception e){failed++;report.add("INVALID_RECIPE "+e);continue;}
            if(!(recipe instanceof Keyed keyed)||!keyed.getKey().getNamespace().equals("aether"))continue;
            ItemStack[] matrix=new ItemStack[9];Arrays.fill(matrix,new ItemStack(Material.AIR));
            if(recipe instanceof ShapedRecipe shaped){String[] rows=shaped.getShape();var choices=shaped.getChoiceMap();for(int row=0;row<rows.length;row++)for(int col=0;col<rows[row].length();col++)matrix[row*3+col]=choice(choices.get(rows[row].charAt(col)));}
            else if(recipe instanceof ShapelessRecipe shapeless){int i=0;for(var c:shapeless.getChoiceList())matrix[i++]=choice(c);}else continue;
            var found=Bukkit.getCraftingRecipe(matrix,w);recipes++;
            if(found==null||!found.getResult().isSimilar(recipe.getResult())){failed++;report.add("RECIPE_MISMATCH "+keyed.getKey()+" got="+(found==null?"null":found.getResult()));}
        }
        check(failed==0,"real crafting matrices: "+recipes+" checked, "+failed+" mismatches");
        var cloud=new CloudListener(a);
        for(String id:List.of("cold_aercloud","blue_aercloud","golden_aercloud")){
            b.setBlockData(Bukkit.createBlockData(a.content().block(id).carrier()),false);bot.teleport(b.getLocation().add(.5,1,.5));
            var fall=new EntityDamageEvent(bot,EntityDamageEvent.DamageCause.FALL,30);Bukkit.getPluginManager().callEvent(fall);check(fall.isCancelled(),"no fall damage on "+id);
            check(id.equals(cloud.cloudAt(b.getLocation().add(1.1,1,.5),.29)),"edge landing detected "+id);
        }
        b.setBlockData(Bukkit.createBlockData(a.content().block("blue_aercloud").carrier()),false);bot.teleport(b.getLocation().add(.5,1,.5));
        Bukkit.getPluginManager().callEvent(new PlayerMoveEvent(bot,bot.getLocation().add(0,.1,0),bot.getLocation()));
        Bukkit.getScheduler().runTaskLater(this,()->{
            check(bot.getVelocity().getY()>1.7,"blue cloud launch impulse "+bot.getVelocity().getY());
            try{Files.write(Path.of("../history/verification/mechanics-r3.txt"),report);}catch(Exception e){throw new RuntimeException(e);}
            getLogger().info("QA_MECHANICS_FINISHED failures="+report.stream().filter(s->s.startsWith("FAIL")).count());
            bot.setGameMode(GameMode.CREATIVE);bot.setFlying(true);bot.teleport(b.getLocation().add(0,20,0));
        },2);
    }catch(Exception e){throw new RuntimeException(e);}}
}
