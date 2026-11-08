package com.aetherport.aether;

import io.netty.channel.*;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import org.bukkit.event.*;
import java.lang.reflect.*;
import java.util.*;

/** Supply the visual contract with the dimension registry, including on the
 * first boot after a datapack update. Server terrain/registry objects are untouched. */
public final class AetherVisuals implements Listener {
    private final AetherPlugin plugin;
    private final Set<Channel> channels=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public AetherVisuals(AetherPlugin plugin){this.plugin=plugin;}
    @EventHandler public void configure(PlayerConnectionInitialConfigureEvent event) {
        try {
            Object api=event.getConnection();Class<?> type=api.getClass();Field field=null;
            while(type!=null){try{field=type.getDeclaredField("packetListener");break;}catch(NoSuchFieldException e){type=type.getSuperclass();}}
            if(field==null)throw new NoSuchFieldException("packetListener");field.setAccessible(true);
            Object listener=field.get(api),connection=listener.getClass().getField("connection").get(listener);
            Channel channel=(Channel)connection.getClass().getField("channel").get(connection);
            channel.eventLoop().execute(()->{
                if(channel.pipeline().get("aether_visuals")!=null)return;
                channels.add(channel);channel.closeFuture().addListener(future->channels.remove(channel));
                channel.pipeline().addBefore("packet_handler","aether_visuals",new ChannelDuplexHandler(){
                    @Override public void write(ChannelHandlerContext context,Object packet,ChannelPromise promise)throws Exception {
                        Object outgoing=packet;
                        try{outgoing=rewrite(packet);}catch(ReflectiveOperationException e){plugin.getLogger().log(java.util.logging.Level.SEVERE,"Aether visual registry synchronization failed",e);}
                        super.write(context,outgoing,promise);
                    }
                });
            });
        }catch(ReflectiveOperationException e){plugin.getLogger().log(java.util.logging.Level.SEVERE,"Cannot attach Aether visual registry adapter",e);}
    }
    public static Object rewrite(Object packet)throws ReflectiveOperationException {
        packet=com.aetherport.aether.mob.SwetParticles.rewrite(packet);
        String name=packet.getClass().getSimpleName();
        if(name.equals("ClientboundLoginPacket") || name.equals("ClientboundRespawnPacket")) {
            Object info=packet.getClass().getMethod("commonPlayerSpawnInfo").invoke(packet);
            Object dimension=info.getClass().getMethod("dimension").invoke(info);
            if(!dimension.getClass().getMethod("identifier").invoke(dimension).toString().equals("aether:the_aether"))return packet;
            // The client's isFlat flag controls its hardcoded Y63 dark-disc
            // threshold and void fog. Combine this with the r4 registry colours:
            // colouring a shader alone does not suppress other renderers' disc.
            Object adjusted=copy(info,Map.of("isFlat",true));
            return copy(packet,Map.of("commonPlayerSpawnInfo",adjusted));
        }
        if(!name.equals("ClientboundRegistryDataPacket"))return packet;
        Object registry=packet.getClass().getMethod("registry").invoke(packet);
        if(!registry.getClass().getMethod("identifier").invoke(registry).toString().equals("minecraft:dimension_type"))return packet;
        List<?> entries=(List<?>)packet.getClass().getMethod("entries").invoke(packet);List<Object> changed=new ArrayList<>();boolean patched=false;
        Class<?> compound=Class.forName("net.minecraft.nbt.CompoundTag"),tag=Class.forName("net.minecraft.nbt.Tag");
        for(Object entry:entries) {
            if(!entry.getClass().getMethod("id").invoke(entry).toString().equals("aether:the_aether")){changed.add(entry);continue;}
            Optional<?> data=(Optional<?>)entry.getClass().getMethod("data").invoke(entry);
            if(data.isEmpty())throw new ReflectiveOperationException("Aether dimension registry entry has no data");
            Object root=compound.getMethod("copy").invoke(data.get());
            Object attributes=compound.getMethod("getCompoundOrEmpty",String.class).invoke(root,"attributes");
            attributes=compound.getMethod("copy").invoke(attributes);
            var put=compound.getMethod("putString",String.class,String.class);
            put.invoke(attributes,"minecraft:visual/sky_color","#b7d9ff");
            put.invoke(attributes,"minecraft:visual/fog_color","#c0d8ff");
            put.invoke(attributes,"minecraft:visual/ambient_light_color","#181c24");
            put.invoke(attributes,"minecraft:visual/night_vision_color","#ffffff");
            compound.getMethod("putFloat",String.class,float.class).invoke(attributes,"minecraft:visual/fog_end_distance",8191.25f);
            compound.getMethod("put",String.class,tag).invoke(root,"attributes",attributes);
            changed.add(copy(entry,Map.of("data",Optional.of(root))));patched=true;
        }
        return patched?copy(packet,Map.of("entries",changed)):packet;
    }
    private static Object copy(Object record,Map<String,Object> values)throws ReflectiveOperationException {
        var fields=record.getClass().getRecordComponents();Class<?>[] types=new Class<?>[fields.length];Object[] args=new Object[fields.length];
        for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();args[i]=values.containsKey(fields[i].getName())?values.get(fields[i].getName()):fields[i].getAccessor().invoke(record);}
        return record.getClass().getConstructor(types).newInstance(args);
    }
    public void close(){for(Channel channel:channels)channel.eventLoop().execute(()->{if(channel.pipeline().get("aether_visuals")!=null)channel.pipeline().remove("aether_visuals");});channels.clear();com.aetherport.aether.mob.SwetParticles.clear();}
}
