package com.aetherport.aether.listener;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

final class VanillaBlockPlacement {
    private final Method handle;
    private final Method itemInHand;
    private final Method useOn;
    private final Constructor<?> vector;
    private final Constructor<?> position;
    private final Constructor<?> hit;
    private final Constructor<?> context;
    private final Class<? extends Enum> direction;
    private final Class<? extends Enum> hand;

    @SuppressWarnings("unchecked")
    VanillaBlockPlacement(Player player) throws ReflectiveOperationException {
        handle = player.getClass().getMethod("getHandle");
        Class<?> nmsPlayer = Class.forName("net.minecraft.world.entity.player.Player");
        hand = (Class<? extends Enum>) Class.forName("net.minecraft.world.InteractionHand");
        direction = (Class<? extends Enum>) Class.forName("net.minecraft.core.Direction");
        Class<?> vec = Class.forName("net.minecraft.world.phys.Vec3");
        Class<?> pos = Class.forName("net.minecraft.core.BlockPos");
        Class<?> hitType = Class.forName("net.minecraft.world.phys.BlockHitResult");
        Class<?> ctx = Class.forName("net.minecraft.world.item.context.UseOnContext");
        vector = vec.getConstructor(double.class, double.class, double.class);
        position = pos.getConstructor(int.class, int.class, int.class);
        hit = hitType.getConstructor(vec, direction, pos, boolean.class);
        context = ctx.getConstructor(nmsPlayer, hand, hitType);
        itemInHand = nmsPlayer.getMethod("getItemInHand", hand);
        useOn = Class.forName("net.minecraft.world.item.ItemStack").getMethod("useOn", ctx);
    }

    @SuppressWarnings("unchecked")
    void place(PlayerInteractEvent event) throws ReflectiveOperationException {
        Player player = event.getPlayer();
        Object nmsPlayer = handle.invoke(player);
        Object nmsHand = Enum.valueOf(hand,
                event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND ? "MAIN_HAND" : "OFF_HAND");
        Object stack = itemInHand.invoke(nmsPlayer, nmsHand);
        var block = event.getClickedBlock();
        var face = event.getBlockFace();
        var clickedPoint = event.getInteractionPoint();
        Object point = clickedPoint == null
                ? vector.newInstance(block.getX() + .5 + face.getModX() * .5,
                        block.getY() + .5 + face.getModY() * .5,
                        block.getZ() + .5 + face.getModZ() * .5)
                : vector.newInstance(clickedPoint.getX(), clickedPoint.getY(), clickedPoint.getZ());
        Object blockPos = position.newInstance(block.getX(), block.getY(), block.getZ());
        Object result = hit.newInstance(point, Enum.valueOf(direction, face.name()), blockPos, false);
        ItemStack held = player.getInventory().getItem(event.getHand());
        int amount = held.getAmount();
        try {
            useOn.invoke(stack, context.newInstance(nmsPlayer, nmsHand, result));
        } finally {
            if (player.getGameMode() == GameMode.CREATIVE) {
                held.setAmount(amount);
            }
        }
    }
}
