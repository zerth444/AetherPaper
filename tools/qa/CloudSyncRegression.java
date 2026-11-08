import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.AetherVisuals;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;

public class CloudSyncRegression extends JavaPlugin implements Listener {
    private double maxY;
    private int moves,cancelled;
    private boolean running;
    private final List<String> report=new ArrayList<>();
    void check(boolean pass,String message){String line=(pass?"PASS ":"FAIL ")+message;report.add(line);getLogger().info(line);}
    public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);getCommand("qacloudsync").setExecutor((s,c,l,args)->{run();return true;});}
    @EventHandler public void onJoin(PlayerJoinEvent e){if(e.getPlayer().getName().equals("AetherBot")){e.getPlayer().setGameMode(GameMode.CREATIVE);e.getPlayer().setAllowFlight(true);e.getPlayer().setFlying(true);}}
    @EventHandler(priority=EventPriority.MONITOR) public void move(PlayerMoveEvent e){if(running&&e.getPlayer().getName().equals("AetherBot")){if(e.isCancelled())cancelled++;else{maxY=Math.max(maxY,e.getTo().getY());moves++;}}}
    void registryCheck()throws Exception {
        Class<?> identifier=Class.forName("net.minecraft.resources.Identifier"),compound=Class.forName("net.minecraft.nbt.CompoundTag"),tag=Class.forName("net.minecraft.nbt.Tag"),resource=Class.forName("net.minecraft.resources.ResourceKey"),entry=Class.forName("net.minecraft.core.RegistrySynchronization$PackedRegistryEntry"),packet=Class.forName("net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket");
        var parse=identifier.getMethod("parse",String.class);
        Object key=resource.getMethod("createRegistryKey",identifier).invoke(null,parse.invoke(null,"minecraft:dimension_type"));
        Object old=compound.getConstructor().newInstance();compound.getMethod("putInt",String.class,int.class).invoke(old,"height",256);
        Object aether=entry.getConstructor(identifier,Optional.class).newInstance(parse.invoke(null,"aether:the_aether"),Optional.of(old));
        Object vanilla=entry.getConstructor(identifier,Optional.class).newInstance(parse.invoke(null,"minecraft:overworld"),Optional.of(old));
        Object original=packet.getConstructor(resource,List.class).newInstance(key,List.of(aether,vanilla));
        Object updated=AetherVisuals.rewrite(original);List<?> entries=(List<?>)packet.getMethod("entries").invoke(updated);
        Object patched=((Optional<?>)entry.getMethod("data").invoke(entries.getFirst())).orElseThrow();
        Object attrs=compound.getMethod("getCompoundOrEmpty",String.class).invoke(patched,"attributes");
        check(compound.getMethod("getStringOr",String.class,String.class).invoke(attrs,"minecraft:visual/ambient_light_color","").equals("#181c24"),"old black ambient fixed in client registry without world restart");
        check(((Number)compound.getMethod("getFloatOr",String.class,float.class).invoke(attrs,"minecraft:visual/fog_end_distance",0f)).floatValue()==8191.25f,"missing sky marker supplied to old dimension data");
        check(entries.get(1)==vanilla,"vanilla dimension registry entry preserved by identity");
        check(!((Boolean)compound.getMethod("contains",String.class).invoke(old,"attributes")),"original server registry tag not mutated");
        // Validate the actual NETWORK_CODEC, not just the presence of NBT keys.
        Object world=AetherPlugin.get().aetherWorldNoCreate();Object handle=world.getClass().getMethod("getHandle").invoke(world);
        Object registryAccess=handle.getClass().getMethod("registryAccess").invoke(handle);
        Class<?> opsType=Class.forName("com.mojang.serialization.DynamicOps"),provider=Class.forName("net.minecraft.core.HolderLookup$Provider"),codecType=Class.forName("com.mojang.serialization.Codec"),dimension=Class.forName("net.minecraft.world.level.dimension.DimensionType");
        Object ops=Class.forName("net.minecraft.resources.RegistryOps").getMethod("create",opsType,provider).invoke(null,Class.forName("net.minecraft.nbt.NbtOps").getField("INSTANCE").get(null),registryAccess);
        Object codec=dimension.getField("NETWORK_CODEC").get(null),type=handle.getClass().getMethod("dimensionType").invoke(handle);
        var getOrThrow=Class.forName("com.mojang.serialization.DataResult").getMethod("getOrThrow");
        Object encoded=getOrThrow.invoke(codecType.getMethod("encodeStart",opsType,Object.class).invoke(codec,ops,type));
        Object oldAttributes=compound.getConstructor().newInstance();compound.getMethod("putString",String.class,String.class).invoke(oldAttributes,"minecraft:visual/ambient_light_color","#000000");
        compound.getMethod("put",String.class,tag).invoke(encoded,"attributes",oldAttributes);
        Object fullEntry=entry.getConstructor(identifier,Optional.class).newInstance(parse.invoke(null,"aether:the_aether"),Optional.of(encoded));
        Object fullPacket=packet.getConstructor(resource,List.class).newInstance(key,List.of(fullEntry));
        Object fullUpdated=AetherVisuals.rewrite(fullPacket);
        Object fullTag=((Optional<?>)entry.getMethod("data").invoke(((List<?>)packet.getMethod("entries").invoke(fullUpdated)).getFirst())).orElseThrow();
        Object decoded=getOrThrow.invoke(codecType.getMethod("parse",opsType,Object.class).invoke(codec,ops,fullTag));
        check(dimension.isInstance(decoded),"patched old dimension data decoded by real NETWORK_CODEC");
    }
    void run(){try{
        registryCheck();var a=AetherPlugin.get();World w=a.aetherWorldNoCreate();Player bot=Bukkit.getPlayerExact("AetherBot");
        if(bot==null)throw new IllegalStateException("No QA client");
        for(int x=3599;x<=3602;x++)for(int z=3599;z<=3602;z++){
            for(int y=151;y<=235;y++)w.getBlockAt(x,y,z).setType(Material.AIR,false);
            w.getBlockAt(x,150,z).setBlockData(Bukkit.createBlockData(a.content().block("blue_aercloud").carrier()),false);
        }
        bot.teleport(new Location(w,3600.5,151,3600.5));bot.setGameMode(GameMode.SURVIVAL);bot.setAllowFlight(false);bot.setFlying(false);
        maxY=151;moves=0;cancelled=0;running=true;
        getLogger().info("QA_CLOUD_READY: client must move onto blue cloud at 3600.65,151,3600.5");
        Bukkit.getScheduler().runTaskLater(this,()->{
            running=false;
            check(maxY>163,"server accepted actual cloud jump height="+(maxY-151));
            check(moves>=20,"server accepted movement packets="+moves);
            check(cancelled==0,"cancelled cloud movements="+cancelled);
            bot.setGameMode(GameMode.CREATIVE);bot.setAllowFlight(true);bot.setFlying(true);bot.teleport(new Location(w,3600.5,200,3600.5));
            try{Files.write(Path.of("../history/verification/cloud-sync-r4.txt"),report);}catch(Exception e){throw new RuntimeException(e);}
            getLogger().info("QA_CLOUD_SYNC_FINISHED failures="+report.stream().filter(s->s.startsWith("FAIL")).count());
        },220);
    }catch(Exception e){throw new RuntimeException(e);}}
}
