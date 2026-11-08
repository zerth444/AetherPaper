package com.aetherport.aether;

import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Offers the Aether resource pack to players when they join. The plugin degrades
 * gracefully when a player declines it (visuals only; gameplay is unaffected).
 */
public final class PlayerListener implements Listener {

    private final AetherPlugin plugin;

    public PlayerListener(AetherPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPackStatus(
            org.bukkit.event.player.PlayerResourcePackStatusEvent event) {
        plugin.getLogger().info("Resource pack " + event.getPlayer().getName()
                + ": " + event.getStatus());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {        String url = plugin.resourcePackUrl();
        if (url == null || url.isEmpty()) {
            return;
        }
        String hash = plugin.resourcePackHash();
        boolean force = plugin.getConfig().getBoolean("resource-pack.force", false);
        Component prompt = Component.text(plugin.getConfig().getString("resource-pack.prompt",
                "The Aether resource pack is required for the full experience."));
        try {
            event.getPlayer().setResourcePack(url, hash, force, prompt);
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to send resource pack to " + event.getPlayer().getName() + ": " + t);
        }
    }
}
