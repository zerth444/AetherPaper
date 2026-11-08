import com.aetherport.aether.AetherPlugin;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Calls the real client level-data methods without starting Minecraft. */
public class HorizonRegression extends JavaPlugin implements Listener {
    final List<String> checks=new ArrayList<>();
    void check(boolean pass,String label){String line=(pass?"PASS ":"FAIL ")+label;checks.add(line);getLogger().info(line);}
    public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);getCommand("qahorizon").setExecutor((s,c,l,args)->{run();return true;});}
    @EventHandler public void join(PlayerJoinEvent e){if(e.getPlayer().getName().equals("AetherBot")){e.getPlayer().setGameMode(GameMode.SPECTATOR);Bukkit.getScheduler().runTaskLater(this,this::run,10);}}
    void run(){try{
        var a=AetherPlugin.get();World w=a.aetherWorldNoCreate();Player bot=Bukkit.getPlayerExact("AetherBot");
        Object handle=w.getClass().getMethod("getHandle").invoke(w);
        check(!(Boolean)handle.getClass().getMethod("isFlat").invoke(handle),"server island generator remains non-flat");
        try(URLClassLoader loader=new URLClassLoader(new URL[]{Path.of("C:/Users/Lemans PC/AppData/Local/Temp/opencode/client-26.2.jar").toUri().toURL()},getClass().getClassLoader())){
            Class<?> data=Class.forName("net.minecraft.client.multiplayer.ClientLevel$ClientLevelData",true,loader);
            Class<?> difficulty=Class.forName("net.minecraft.world.Difficulty"),height=Class.forName("net.minecraft.world.level.LevelHeightAccessor");
            Object old=data.getConstructor(difficulty,boolean.class,boolean.class).newInstance(difficulty.getField("NORMAL").get(null),false,false);
            Object updated=data.getConstructor(difficulty,boolean.class,boolean.class).newInstance(difficulty.getField("NORMAL").get(null),false,true);
            var horizon=data.getMethod("getHorizonHeight",height);var voidRange=data.getMethod("voidDarknessOnsetRange");
            double previous=((Number)horizon.invoke(old,handle)).doubleValue(),current=((Number)horizon.invoke(updated,handle)).doubleValue();
            check(previous==63,"real client baseline horizon Y63");check(current==w.getMinHeight(),"real client Aether horizon=minY="+current);
            check(((Number)voidRange.invoke(updated)).floatValue()==1,"real client void-darkening range is1 instead of32");
            for(int y:new int[]{128,64,62,61,60,30,1})check(y+1.62>=current,"dark-disc predicate false with feet at Y"+y);
            check(61+1.62<previous,"baseline reproduces the user's Y61 threshold");
        }
        bot.teleport(new Location(a.mainWorld(),400,100,450));
        Bukkit.getScheduler().runTaskLater(this,()->bot.teleport(new Location(w,3650,60,3650)),10);
        Bukkit.getScheduler().runTaskLater(this,()->bot.teleport(new Location(w,3650,30,3650)),20);
        Bukkit.getScheduler().runTaskLater(this,()->{
            try{Files.write(Path.of("../history/verification/horizon-r5.txt"),checks);}catch(Exception e){throw new RuntimeException(e);}
            getLogger().info("QA_HORIZON_FINISHED failures="+checks.stream().filter(s->s.startsWith("FAIL")).count());
        },30);
    }catch(Exception e){throw new RuntimeException(e);}}
}
