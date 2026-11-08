import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.dungeon.DungeonPieces;
import com.aetherport.aether.listener.PortalListener;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.nio.file.*;

/** Integration checks for actual blocks, fluid ticks and scheduled natural spawns. */
public class ServerRegression extends JavaPlugin {
    private final List<String> report=new ArrayList<>();
    private void check(boolean ok,String message){String s=(ok?"PASS ":"FAIL ")+message;report.add(s);getLogger().info(s);}
    public void onEnable(){getCommand("qaserver").setExecutor((s,c,l,a)->{run();return true;});}
    private void run(){
        var a=AetherPlugin.get(); World w=a.aetherWorldNoCreate(); Player bot=Bukkit.getPlayerExact("AetherBot");
        if(bot==null||w==null)throw new IllegalStateException("QA client/world missing");
        try{
            Object handle=w.getClass().getMethod("getHandle").invoke(w);
            Object env=handle.getClass().getMethod("environmentAttributes").invoke(handle);
            Class<?> attribute=Class.forName("net.minecraft.world.attribute.EnvironmentAttribute");
            Class<?> attributes=Class.forName("net.minecraft.world.attribute.EnvironmentAttributes");
            var value=env.getClass().getMethod("getDimensionValue",attribute);
            int color=(Integer)value.invoke(env,attributes.getField("SKY_COLOR").get(null));
            float light=(Float)value.invoke(env,attributes.getField("SKY_LIGHT_FACTOR").get(null));
            check((color&0xffffff)!=0,"actual sky color="+Integer.toHexString(color));
            check(light>.9,"actual sky light factor="+light);
            var manager=Bukkit.getServer().getDatapackManager();
            check(manager.getPack("file/aether-port")!=null&&manager.getPack("file/aether-port").isEnabled(),"managed datapack enabled");
            check(manager.getPack("file/aether.zip")==null||!manager.getPack("file/aether.zip").isEnabled(),"legacy zip disabled");
        }catch(Exception e){throw new RuntimeException(e);}
        var pieces=new DungeonPieces();pieces.load(getLogger());
        int base=1024;
        for(String dungeon:List.of("bronze_dungeon","silver_dungeon","gold_dungeon")){
            var template=pieces.piece(dungeon,"boss_room");
            pieces.place(w,dungeon,"boss_room",base,190,1024,1);
            int solid=0,mismatches=0;int sx=template.getAsJsonArray("size").get(0).getAsInt();int sz=template.getAsJsonArray("size").get(2).getAsInt();
            for(var node:template.getAsJsonArray("blocks")){
                var b=node.getAsJsonObject();int x=b.get("x").getAsInt(),y=b.get("y").getAsInt(),z=b.get("z").getAsInt();
                var expected=Bukkit.createBlockData(b.get("block").getAsString());
                expected.rotate(org.bukkit.block.structure.StructureRotation.CLOCKWISE_90);
                var actual=w.getBlockAt(base+sz-1-z,190+y,1024+x).getBlockData();
                if(!expected.matches(actual))mismatches++;
                if(!actual.getMaterial().isAir())solid++;
            }
            check(mismatches==0,dungeon+" every template block matches; mismatch="+mismatches);
            check(solid>1000,dungeon+" actual solid blocks="+solid);
            base+=48;
        }
        pieces.place(w,"gold_dungeon","island",base,170,1024,0);
        int islandSolid=0,islandMismatch=0;
        for(var node:pieces.piece("gold_dungeon","island").getAsJsonArray("blocks")){
            var b=node.getAsJsonObject();var expected=Bukkit.createBlockData(b.get("block").getAsString());
            var actual=w.getBlockAt(base+b.get("x").getAsInt(),170+b.get("y").getAsInt(),1024+b.get("z").getAsInt()).getBlockData();
            if(!expected.matches(actual))islandMismatch++;
            if(!actual.getMaterial().isAir())islandSolid++;
        }
        check(islandMismatch==0&&islandSolid>30000,"gold island complete; solid="+islandSolid+" mismatches="+islandMismatch);
        for(int x=352;x<=448;x++)for(int z=352;z<=448;z++){
            w.getBlockAt(x,230,z).setBlockData(Bukkit.createBlockData(a.content().block("aether_grass_block").carrier()),false);
            for(int y=231;y<=234;y++)w.getBlockAt(x,y,z).setType(Material.AIR,false);
        }
        bot.setGameMode(GameMode.CREATIVE);bot.teleport(new Location(w,400.5,231,400.5));
        w.getBlockAt(410,231,410).setType(Material.WATER,true);
        var quick=w.getBlockAt(405,230,405);quick.setBlockData(Bukkit.createBlockData(a.content().block("quicksoil").carrier()),false);
        try{
            Object state=quick.getBlockData().getClass().getMethod("getState").invoke(quick.getBlockData());
            Object block=state.getClass().getMethod("getBlock").invoke(state);
            float friction=(Float)block.getClass().getMethod("getFriction").invoke(block);
            check(Math.abs(friction-.98)<.0001,"native quicksoil friction="+friction);
            check(quick.getType()==Material.FROSTED_ICE,"client receives native slippery block");
            var melted=quick.getState();melted.setType(Material.WATER);
            var fade=new org.bukkit.event.block.BlockFadeEvent(quick,melted);
            Bukkit.getPluginManager().callEvent(fade);check(fade.isCancelled(),"quicksoil melting prevented");
        }catch(Exception e){throw new RuntimeException(e);}
        for(int x=458;x<=466;x++)for(int z=458;z<=466;z++){
            w.getBlockAt(x,230,z).setType(Material.STONE,false);
            for(int y=231;y<=236;y++)w.getBlockAt(x,y,z).setType(Material.AIR,false);
        }
        try{
            var field=AetherPlugin.class.getDeclaredField("portalListener");field.setAccessible(true);
            PortalListener listener=(PortalListener)field.get(a);
            var method=PortalListener.class.getDeclaredMethod("createExit",World.class,int.class,int.class,int.class,char.class);method.setAccessible(true);
            Object rect=method.invoke(listener,w,462,231,462,'x');
            int glow=0;for(int x=459;x<=465;x++)for(int z=459;z<=465;z++)for(int y=229;y<=236;y++)if(w.getBlockAt(x,y,z).getType()==Material.GLOWSTONE)glow++;
            check(rect!=null&&glow==14,"surface portal without platform; glowstone="+glow);
        }catch(Exception e){throw new RuntimeException(e);}
        Bukkit.getScheduler().runTaskLater(this,()->{
            check(w.getBlockAt(411,231,410).getType()==Material.WATER,"water flows over Aether floor");
            check(w.getBlockAt(405,230,405).getType()==Material.FROSTED_ICE,"quicksoil persists after fluid/random ticks");
        },60);
        Bukkit.getScheduler().runTaskLater(this,()->{
            Map<String,Integer> counts=new TreeMap<>();
            for(Entity e:w.getNearbyEntities(bot.getLocation(),64,32,64)){
                String id=a.mobs().mobOf(e);var def=a.mobs().defs().get(id);
                if(def!=null&&!def.boss()&&!def.spawns().isEmpty())counts.merge(id,1,Integer::sum);
            }
            int total=counts.values().stream().mapToInt(Integer::intValue).sum();
            check(total>0&&total<=10,"scheduled natural population="+counts);
            try{Files.write(Path.of("../history/verification/server-regression.txt"),report);}catch(Exception e){throw new RuntimeException(e);}
            getLogger().info("QA_SERVER_FINISHED failures="+report.stream().filter(s->s.startsWith("FAIL")).count());
        },430);
    }
}
