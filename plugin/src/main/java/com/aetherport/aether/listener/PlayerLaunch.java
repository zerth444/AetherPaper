package com.aetherport.aether.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.lang.reflect.*;
import java.util.Set;

/** A position-confirmed launch carries velocity in PlayerPosition rather than
 * EntityMotion. This also avoids client knockback filters eating a cloud launch. */
final class PlayerLaunch {
    static boolean launch(Player player,double y)throws ReflectiveOperationException {
        var location=player.getLocation();var velocity=player.getVelocity();
        Class<?> vector=Class.forName("net.minecraft.world.phys.Vec3"),movement=Class.forName("net.minecraft.world.entity.PositionMoveRotation");
        Constructor<?> vec=vector.getConstructor(double.class,double.class,double.class);
        Object state=movement.getConstructor(vector,vector,float.class,float.class).newInstance(
                vec.newInstance(location.getX(),location.getY(),location.getZ()),
                vec.newInstance(velocity.getX(),y,velocity.getZ()),location.getYaw(),location.getPitch());
        Object handle=player.getClass().getMethod("getHandle").invoke(player);
        Object connection=handle.getClass().getField("connection").get(handle);
        return (Boolean)connection.getClass().getMethod("teleport",movement,Set.class,PlayerTeleportEvent.TeleportCause.class)
                .invoke(connection,state,Set.of(),PlayerTeleportEvent.TeleportCause.PLUGIN);
    }
}
