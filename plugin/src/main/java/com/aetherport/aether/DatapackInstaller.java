package com.aetherport.aether;

import org.bukkit.Bukkit;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Writes the bundled Aether datapack into the main world's datapacks folder so the
 * server loads the custom dimension / worldgen on the next startup.
 */
public final class DatapackInstaller {

    private DatapackInstaller() {
    }

    /** @return true if the pack was newly installed (a data reload is required). */
    public static boolean install(Logger logger) {
        if (Bukkit.getWorlds().isEmpty()) {
            logger.warning("No worlds loaded yet; cannot install datapack.");
            return false;
        }
        // In current Paper the main world folder points at the per-dimension storage
        // (world/dimensions/minecraft/overworld); the level root that holds
        // datapacks/ is <world-container>/<level-name>.
        File root = new File(Bukkit.getWorldContainer(), Bukkit.getWorlds().get(0).getName());
        File pack = new File(root, "datapacks" + File.separator + "aether-port");
        try (InputStream in = AetherPlugin.class.getResourceAsStream("/aether-datapack.zip")) {
            if (in == null) {
                logger.warning("Bundled datapack resource is missing from the jar.");
                return false;
            }
            byte[] bundled = in.readAllBytes();
            // A loaded ZIP is locked on Windows. A managed directory can be
            // updated while the old registry is alive and loaded on next boot.
            Path target=pack.toPath().toAbsolutePath().normalize();
            Files.createDirectories(target);
            Path manifest=root.toPath().resolve("aether-datapack-files.json");
            java.util.Set<String> previous=new java.util.HashSet<>(), current=new java.util.TreeSet<>();
            if(Files.isRegularFile(manifest)) for(var item:com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonArray()) previous.add(item.getAsString());
            boolean changed=false;
            Path backup=root.toPath().resolve("aether-datapack-backups").resolve(Long.toString(System.currentTimeMillis()));
            try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bundled))) {
                java.util.zip.ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) {
                    if(entry.isDirectory()) continue;
                    String name=entry.getName().replace('\\','/');
                    Path file=target.resolve(name).normalize();
                    if(!file.startsWith(target)) throw new java.io.IOException("Invalid datapack entry: "+name);
                    current.add(name); byte[] bytes=zip.readAllBytes();
                    if(Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file),bytes)) continue;
                    if(Files.isRegularFile(file)) {
                        Path copy=backup.resolve(name);Files.createDirectories(copy.getParent());Files.copy(file,copy);
                    }
                    Files.createDirectories(file.getParent()); Files.write(file,bytes);changed=true;
                }
            }
            for(String old:previous) if(!current.contains(old)) {
                Path file=target.resolve(old).normalize();
                if(!file.startsWith(target)) throw new java.io.IOException("Invalid manifest entry");
                if(Files.isRegularFile(file)) {
                    Path copy=backup.resolve(old);Files.createDirectories(copy.getParent());Files.copy(file,copy);
                    Files.delete(file);changed=true;
                }
            }
            Files.writeString(manifest,new com.google.gson.Gson().toJson(current));
            var manager=Bukkit.getServer().getDatapackManager();manager.refreshPacks();
            var generated=manager.getPack("file/aether-port");
            if(generated!=null && !generated.isEnabled()){generated.setEnabled(true);changed=true;}
            for(String legacy:new String[]{"file/aether.zip","file/aether"}) {
                var old=manager.getPack(legacy);
                if(old!=null && old.isEnabled()){old.setEnabled(false);changed=true;}
            }
            if(changed) logger.info("Updated managed Aether datapack at "+target+"; full restart required. Legacy packs retained and disabled.");
            return changed;
        } catch (Exception e) {
            logger.warning("Failed to install Aether datapack: " + e);
            return false;
        }
    }
}
