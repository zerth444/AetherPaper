package com.aetherport.aether.listener;

import com.aetherport.aether.content.BlockDef;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import java.lang.reflect.*;
import java.util.*;

/** Ask the pinned vanilla server to evaluate a real analogue state. No world edits. */
public final class VanillaMining {
    private final Map<Material,Object> states=new EnumMap<>(Material.class);
    private final Method progress;
    private final Constructor<?> position;
    private final Method eyeInFluid;
    private final Object water;
    public VanillaMining() {
        try {
            progress=Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase").getMethod("getDestroyProgress",Class.forName("net.minecraft.world.entity.player.Player"),Class.forName("net.minecraft.world.level.BlockGetter"),Class.forName("net.minecraft.core.BlockPos"));
            position=Class.forName("net.minecraft.core.BlockPos").getConstructor(int.class,int.class,int.class);
            eyeInFluid=Class.forName("net.minecraft.world.entity.Entity").getMethod("isEyeInFluid",Class.forName("net.minecraft.tags.TagKey"));
            water=Class.forName("net.minecraft.tags.FluidTags").getField("WATER").get(null);
        } catch(ReflectiveOperationException e){throw new IllegalStateException("Paper 26.2 vanilla mining adapter unavailable",e);}
    }
    public boolean eyesInWater(Player player){
        try{return (Boolean)eyeInFluid.invoke(player.getClass().getMethod("getHandle").invoke(player),water);}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot evaluate underwater mining",e);}
    }
    public double rate(BlockDef def,Block block,Player player) {
        try {
            Material material=analogue(def);
            Object state=states.get(material);
            if(state==null){var data=Bukkit.createBlockData(material);state=data.getClass().getMethod("getState").invoke(data);states.put(material,state);}
            Object handle=player.getClass().getMethod("getHandle").invoke(player);
            Object level=block.getWorld().getClass().getMethod("getHandle").invoke(block.getWorld());
            double rate=((Number)progress.invoke(state,handle,level,position.newInstance(block.getX(),block.getY(),block.getZ()))).doubleValue();
            return Double.isFinite(rate)?Math.max(0,rate):0;
        } catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot evaluate vanilla mining progress",e);}
    }
    public static boolean isTool(Material material){String n=material.name();return n.endsWith("_PICKAXE")||n.endsWith("_AXE")||n.endsWith("_SHOVEL")||n.endsWith("_HOE")||n.endsWith("_SWORD")||material==Material.SHEARS;}
    public static Material analogue(BlockDef def){
        String id=def.id();
        if(id.endsWith("_sapling"))return Material.OAK_SAPLING;
        if(id.startsWith("potted_"))return Material.FLOWER_POT;
        if(id.endsWith("_flower"))return Material.DANDELION;
        if(id.endsWith("_leaves"))return Material.OAK_LEAVES;
        if(id.endsWith("_aercloud"))return Material.SNOW_BLOCK;
        if(id.contains("glass_pane"))return Material.GLASS_PANE;
        if(id.contains("glass"))return Material.GLASS;
        if(id.contains("_torch"))return Material.TORCH;
        if(id.contains("grass_block"))return Material.GRASS_BLOCK;
        if(id.equals("aether_dirt"))return Material.DIRT;
        if(id.equals("aether_dirt_path"))return Material.DIRT_PATH;
        if(id.equals("aether_farmland"))return Material.FARMLAND;
        if(id.equals("quicksoil"))return Material.SAND;
        if(id.equals("frosted_ice"))return Material.ICE;
        if(id.equals("unstable_obsidian"))return Material.OBSIDIAN;
        if(id.equals("ambrosium_ore"))return Material.COAL_ORE;
        if(id.equals("zanite_ore"))return Material.IRON_ORE;
        if(id.equals("gravitite_ore"))return Material.DIAMOND_ORE;
        if(id.equals("ambrosium_block"))return Material.COAL_BLOCK;
        if(id.equals("zanite_block"))return Material.IRON_BLOCK;
        if(id.equals("enchanted_gravitite"))return Material.DIAMOND_BLOCK;
        if(id.contains("berry_bush"))return Material.SWEET_BERRY_BUSH;
        if(id.contains("chest"))return Material.CHEST;
        if(Set.of("altar","freezer","incubator","sun_altar").contains(id))return Material.FURNACE;
        if(id.contains("skyroot") || id.contains("golden_oak")){
            String suffix=id.replace("stripped_","").replace("skyroot_","").replace("golden_oak_","");
            Material oak=Material.matchMaterial("OAK_"+suffix.toUpperCase(Locale.ROOT));
            if(oak!=null && oak.isBlock())return oak;
            if(suffix.equals("bookshelf"))return Material.BOOKSHELF;
            if(suffix.equals("bed"))return Material.RED_BED;
            return Material.OAK_PLANKS;
        }
        if(id.endsWith("_button"))return Material.STONE_BUTTON;
        if(id.endsWith("_pressure_plate"))return Material.STONE_PRESSURE_PLATE;
        if(id.endsWith("_wall"))return Material.COBBLESTONE_WALL;
        if(id.endsWith("_stairs"))return Material.STONE_STAIRS;
        if(id.endsWith("_slab"))return Material.STONE_SLAB;
        if(id.equals("holystone_bricks"))return Material.STONE_BRICKS;
        if("axe".equals(def.tool()))return Material.OAK_PLANKS;
        if("shovel".equals(def.tool()))return Material.DIRT;
        return Material.STONE;
    }
}
