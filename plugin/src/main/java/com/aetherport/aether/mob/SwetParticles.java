package com.aetherport.aether.mob;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Suppress only custom Swet's client-generated landing particles.
 * Server onGround, physics and AI stay intact. Ordinary slimes are untouched. */
public final class SwetParticles {
    private static final Map<Integer,UUID> ids=new ConcurrentHashMap<>();
    public static void track(int id,UUID uuid){ids.put(id,uuid);}
    public static void forget(UUID uuid){ids.values().removeIf(uuid::equals);}
    public static void clear(){ids.clear();}
    private static Object field(Object packet,String key)throws ReflectiveOperationException{
        Field f=packet.getClass().getSuperclass().getDeclaredField(key);f.setAccessible(true);return f.get(packet);
    }
    public static Object rewrite(Object packet)throws ReflectiveOperationException{
        Class<?> type=packet.getClass();String name=type.getSimpleName();
        if(type.getSuperclass()!=null && type.getSuperclass().getSimpleName().equals("ClientboundMoveEntityPacket")){
            int id=(Integer)field(packet,"entityId");if(!ids.containsKey(id) || !(Boolean)field(packet,"onGround"))return packet;
            if(name.equals("Pos"))return type.getConstructor(int.class,short.class,short.class,short.class,boolean.class).newInstance(id,field(packet,"xa"),field(packet,"ya"),field(packet,"za"),false);
            if(name.equals("PosRot"))return type.getConstructor(int.class,short.class,short.class,short.class,byte.class,byte.class,boolean.class).newInstance(id,field(packet,"xa"),field(packet,"ya"),field(packet,"za"),field(packet,"yRot"),field(packet,"xRot"),false);
            if(name.equals("Rot"))return type.getConstructor(int.class,byte.class,byte.class,boolean.class).newInstance(id,field(packet,"yRot"),field(packet,"xRot"),false);
        }
        if(name.equals("ClientboundTeleportEntityPacket") || name.equals("ClientboundEntityPositionSyncPacket")){
            int id=(Integer)type.getMethod("id").invoke(packet);if(!ids.containsKey(id))return packet;
            var components=type.getRecordComponents();Class<?>[] types=new Class<?>[components.length];Object[] values=new Object[components.length];
            for(int i=0;i<components.length;i++){types[i]=components[i].getType();values[i]=components[i].getName().equals("onGround")?false:components[i].getAccessor().invoke(packet);}
            return type.getConstructor(types).newInstance(values);
        }
        if(name.equals("ClientboundBundlePacket")){
            List<Object> changed=new ArrayList<>();boolean modified=false;
            for(Object child:(Iterable<?>)type.getMethod("subPackets").invoke(packet)){Object result=rewrite(child);changed.add(result);modified|=result!=child;}
            if(modified)return type.getConstructor(Iterable.class).newInstance(changed);
        }
        return packet;
    }
}
