import com.aetherport.aether.*;
import com.aetherport.aether.dungeon.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;
import java.nio.file.*;
import java.util.*;

public class RevisionRegression extends JavaPlugin {
    private final List<String> checks=new ArrayList<>();
    private void check(boolean ok,String text){String line=(ok?"PASS ":"FAIL ")+text;checks.add(line);getLogger().info(line);}
    private Object get(Object record,String field)throws Exception{var m=record.getClass().getDeclaredMethod(field);m.setAccessible(true);return m.invoke(record);}
    public void onEnable(){getCommand("qarevision").setExecutor((s,c,l,args)->{run();return true;});}
    private void run(){
        var a=AetherPlugin.get();World w=a.aetherWorldNoCreate();Player bot=Bukkit.getPlayerExact("AetherBot");
        if(w==null||bot==null)throw new IllegalStateException("Missing QA client/world");
        try{
            Object handle=w.getClass().getMethod("getHandle").invoke(w);
            check(!(Boolean)handle.getClass().getMethod("isFlat").invoke(handle),"server still uses original non-flat island generator");
            check(a.mainWorld().getKey().toString().equals("minecraft:overworld"),"return world explicitly overworld");
            var nether=Bukkit.getWorld(NamespacedKey.minecraft("the_nether"));
            var ordinary=new PlayerPortalEvent(bot,new Location(a.mainWorld(),3000,100,3000),new Location(nether,3000,100,3000),PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
            Bukkit.getPluginManager().callEvent(ordinary);check(!ordinary.isCancelled(),"ordinary Nether portal outside Aether unaffected");
            var race=new PlayerPortalEvent(bot,new Location(w,3000,100,3000),new Location(nether,3000,100,3000),PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
            Bukkit.getPluginManager().callEvent(race);check(race.isCancelled(),"Aether vanilla Nether event blocked without touching registered plane");
            var direct=new PlayerTeleportEvent(bot,new Location(w,3000,100,3000),new Location(nether,3000,100,3000),PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
            Bukkit.getPluginManager().callEvent(direct);check(direct.isCancelled(),"Aether direct Nether teleport blocked");
            LivingEntity moa=a.mobs().spawnMob(w,new Location(w,400,231,400),"moa",false);
            check(Math.abs(moa.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED).getBaseValue()-.2)<.0001,"new moa speed=.2");
            moa.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED).setBaseValue(1);
            moa.getPersistentDataContainer().remove(new NamespacedKey("aether","moa_speed_v2"));
            a.mobs().migrateCarrier(moa,"moa");
            check(Math.abs(moa.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED).getBaseValue()-.2)<.0001,"existing moa speed migrated");moa.remove();
            var pieces=new DungeonPieces();pieces.load(getLogger());
            pieces.place(w,"bronze_dungeon","boss_room",2400,180,2400,0);
            int voids=0,notes=0;
            var template=pieces.piece("bronze_dungeon","boss_room");
            for(var node:template.getAsJsonArray("blocks")){
                var b=node.getAsJsonObject();if(!b.get("block").getAsString().equals("minecraft:structure_void"))continue;
                var block=w.getBlockAt(2400+b.get("x").getAsInt(),180+b.get("y").getAsInt(),2400+b.get("z").getAsInt());
                if(block.getType()==Material.STRUCTURE_VOID&&!block.getBlockData().isOccluding()&&block.isPassable())voids++;
            }
            check(voids==16,"all16 invisible doorway blocks passable/non-occluding");
            check(!a.dungeons().bronzeFits(w,2000,180,2000,4),"bronze plan rejected above terrain/void");
            var planMethod=DungeonManager.class.getDeclaredMethod("bronzePlan",long.class,int.class,int.class,int.class,int.class);planMethod.setAccessible(true);
            var plan=(List<?>)planMethod.invoke(a.dungeons(),w.getSeed(),2000,180,2000,4);
            int minX=99999,maxX=-99999,minZ=99999,maxZ=-99999,maxY=0;
            for(Object part:plan){
                int x=(Integer)get(part,"x"),z=(Integer)get(part,"z"),y=(Integer)get(part,"y");
                int[] size=pieces.sizeOf("bronze_dungeon",(String)get(part,"piece"));
                minX=Math.min(minX,x);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x+size[0]-1);maxZ=Math.max(maxZ,z+size[2]-1);maxY=Math.max(maxY,y+size[1]);
            }
            var stone=Bukkit.createBlockData(a.content().block("holystone").carrier());
            for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)for(int y=179;y<=maxY;y++)w.getBlockAt(x,y,z).setBlockData(stone,false);
            check(a.dungeons().bronzeFits(w,2000,180,2000,4),"complete bronze plan accepted inside solid island");
            w.getBlockAt(2000,179,2000).setType(Material.AIR,false);
            check(!a.dungeons().bronzeFits(w,2000,180,2000,4),"bronze rejected when one floor support is missing");
            w.getBlockAt(2000,179,2000).setBlockData(stone,false);
            a.dungeons().buildBronze(w,2000,180,2000,4);
            a.dungeons().buildSilver(w,2600,180,2600);
            check("cold_aercloud".equals(a.content().blockIdForCarrier(w.getBlockAt(2600,179,2600).getBlockData().getAsString())),"silver temple cloud foundation present");
            bot.teleport(new Location(a.mainWorld(),400,100,400));
            Bukkit.getScheduler().runTaskLater(this,()->{
                bot.teleport(new Location(w,400,30,400));
                getLogger().info("QA_HORIZON_TELEPORT_Y30");
            },20);
            Bukkit.getScheduler().runTaskLater(this,()->{
                try{
                    var field=AetherPlugin.class.getDeclaredField("portalListener");field.setAccessible(true);
                    var portal=(com.aetherport.aether.listener.PortalListener)field.get(a);
                    check(portal.travel(bot),"real Aether travel succeeded");
                    check(bot.getWorld().equals(a.mainWorld()),"real Aether travel returned to overworld");
                    var delayed=new PlayerPortalEvent(bot,bot.getLocation(),new Location(nether,0,80,0),PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
                    Bukkit.getPluginManager().callEvent(delayed);check(delayed.isCancelled(),"delayed vanilla event blocked during arrival cooldown");
                    Files.write(Path.of("../history/verification/revision-r2.txt"),checks);
                    getLogger().info("QA_REVISION_FINISHED failures="+checks.stream().filter(s->s.startsWith("FAIL")).count());
                }catch(Exception e){throw new RuntimeException(e);}
            },40);
        }catch(Exception e){throw new RuntimeException(e);}
    }
}
