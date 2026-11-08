package com.aetherport.aether;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

/**
 * Updates the managed datapack directory and asks for a full restart when its
 * dimension registry data changes. A data reload alone cannot rebuild dimensions.
 */
public final class ServerListener implements Listener {

    private final AetherPlugin plugin;

    public ServerListener(AetherPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        plugin.refreshServerRegistries();
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) {
            return;
        }
        plugin.portals().prune(plugin);
        if (plugin.getConfig().getBoolean("world.install-datapack", true)) {
            boolean installed = DatapackInstaller.install(plugin.getLogger());
            if (installed) {
                try {
                    Bukkit.reloadData();
                } catch (Throwable t) {
                    plugin.getLogger().warning("Data reload failed: " + t);
                }
                // A newly added datapack dimension is only available after a restart.
                plugin.getLogger().info("Aether datapack installed; restart the server to "
                        + "load the Aether dimension. Skipping world creation this session.");
                plugin.setPendingRestart(true);
                // reloadData() rebuilds registries; re-register our advancements after it
                if (plugin.advancements() != null) {
                    plugin.advancements().registerAll();
                }
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.ensureAetherSky(plugin.aetherWorld());
            String status = plugin.dimensionStatus();
            plugin.getLogger().info("Aether startup: " + status);
            try {
                java.nio.file.Files.writeString(plugin.getDataFolder().toPath().resolve("dimension-status.txt"),
                        "Server: " + Bukkit.getVersion() + "\n" + status + "\n",
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                plugin.getLogger().warning("Cannot write dimension status: " + e);
            }
        });
        if (plugin.getConfig().getBoolean("debug.dimension", false)) {
            Bukkit.getScheduler().runTaskLater(plugin, plugin::debugDimensions, 40L);
        }
    }
    @EventHandler
    public void onResourcesReloaded(io.papermc.paper.event.server.ServerResourcesReloadedEvent event){
        plugin.refreshServerRegistries();
    }
}
