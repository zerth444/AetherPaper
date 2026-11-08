package com.aetherport.aether.listener;

import com.aetherport.aether.AetherPlugin;
import com.aetherport.aether.world.PortalManager;
import com.aetherport.aether.world.PortalRect;
import com.aetherport.aether.world.PortalShape;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Aether portal behaviour: glowstone frame + water lights a vertical portal plane
 * (nether_portal carrier rendered as the original plane by the resource pack).
 * Entering teleports between the overworld and {@code aether:the_aether}, creating
 * a matching exit portal when needed, like the original.
 */
public final class PortalListener implements Listener {

    public static final String SOUND_TRIGGER = "aether:block.aether_portal.trigger";
    public static final String SOUND_TRAVEL = "aether:block.aether_portal.travel";

    private static final long TRAVEL_COOLDOWN_MS = 4000L;
    private static final double SEARCH_RADIUS = 128.0;

    private final AetherPlugin plugin;
    private final Logger logger;
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> portalTicks = new java.util.HashMap<>();
    private final java.util.Set<UUID> attempted = new java.util.HashSet<>();

    public PortalListener(AetherPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickTravel, 1L, 1L);
    }

    // ---------------------------------------------------------------- ignition

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.WATER) {
            tryIgnite(event.getBlockPlaced().getWorld(),
                    event.getBlockPlaced().getX(),
                    event.getBlockPlaced().getY(),
                    event.getBlockPlaced().getZ());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (event.getToBlock().getType() == Material.WATER || event.getBlock().getType() == Material.WATER) {
            Block to = event.getToBlock();
            // Ignition replaces the water source with portal blocks. Do not let
            // the original water tick overwrite the newly created portal plane.
            if (tryIgnite(to.getWorld(), to.getX(), to.getY(), to.getZ())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        org.bukkit.block.Block target =
                event.getBlockClicked().getRelative(event.getBlockFace());
        org.bukkit.World world = target.getWorld();
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (world.getBlockAt(x, y, z).getType() == org.bukkit.Material.WATER) {
                tryIgnite(world, x, y, z);
            }
        });
    }

    /** Called when water is applied to a frame by any means (bucket, flow, commands). */
    public boolean tryIgnite(World world, int x, int y, int z) {
        String key = world.getKey().toString();
        if (!plugin.isPortalWorld(key)) {
            return false;
        }
        Optional<PortalRect> found = PortalShape.find(world, x, y, z);
        if (found.isEmpty()) {
            return false;
        }
        PortalRect rect = found.get();
        if (plugin.portals().at(key, x, y, z) != null) {
            return true;
        }
        try {
            org.bukkit.block.data.BlockData data =
                    Bukkit.createBlockData("minecraft:nether_portal[axis=" + rect.axis() + "]");
            for (int level = 0; level < rect.h(); level++) {
                for (int i = 0; i < rect.w(); i++) {
                    int cx = rect.axis() == 'x' ? rect.x0() + i : rect.x0();
                    int cz = rect.axis() == 'z' ? rect.z0() + i : rect.z0();
                    world.getBlockAt(cx, rect.y0() + level, cz).setBlockData(data, false);
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to place portal blocks: " + e);
            return false;
        }
        plugin.portals().register(rect);
        plugin.portals().saveAsync(plugin);
        Location center = spot(world, rect);
        world.playSound(center, SOUND_TRIGGER, 1.0f, 1.0f);
        world.spawnParticle(Particle.END_ROD, center, 24, 1.0, 1.5, 1.0, 0.05);
        logger.info("Lit Aether portal " + rect.key() + " (" + rect.w() + "x" + rect.h() + ")");
        return true;
    }

    // -------------------------------------------------------------- protection

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalPhysics(org.bukkit.event.block.BlockPhysicsEvent event) {
        Block b = event.getBlock();
        if (b.getType() == Material.NETHER_PORTAL
                && plugin.portals().at(b.getWorld().getKey().toString(), b.getX(), b.getY(), b.getZ()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.NETHER_PORTAL) {
            return;
        }
        if (plugin.portals().at(block.getWorld().getKey().toString(),
                block.getX(), block.getY(), block.getZ()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String worldKey = block.getWorld().getKey().toString();
        if (block.getType() == Material.NETHER_PORTAL) {
            PortalRect rect = plugin.portals().at(worldKey, block.getX(), block.getY(), block.getZ());
            if (rect != null) {
                clearRect(block.getWorld(), rect);
                logger.info("Broke Aether portal " + rect.key());
            }
            return;
        }
        if (block.getType() == Material.GLOWSTONE) {
            for (PortalRect rect : plugin.portals().inWorld(worldKey)) {
                if (Math.abs(block.getX() - rect.x0()) > rect.w() + 2
                        || Math.abs(block.getZ() - rect.z0()) > rect.w() + 2
                        || Math.abs(block.getY() - rect.y0()) > rect.h() + 2) {
                    continue;
                }
                if (!PortalShape.validate(block.getWorld(), rect)) {
                    clearRect(block.getWorld(), rect);
                    logger.info("Aether portal collapsed " + rect.key());
                }
            }
        }
    }

    private void clearRect(World world, PortalRect rect) {
        PortalShape.clearVolume(world, rect);
        plugin.portals().remove(rect);
        plugin.portals().saveAsync(plugin);
    }

    // ------------------------------------------------------------------ travel

    private PortalRect touchingPortal(Player player) {
        var box = player.getBoundingBox();
        World world = player.getWorld();
        String key = world.getKey().toString();
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                    PortalRect rect = plugin.portals().at(key, x, y, z);
                    if (rect != null && world.getBlockAt(x, y, z).getType() == Material.NETHER_PORTAL) {
                        return rect;
                    }
                }
            }
        }
        return null;
    }

    /** Vanilla may never fire PlayerPortalEvent when Nether travel is disabled or unavailable. */
    private void tickTravel() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (player.isDead() || touchingPortal(player) == null) {
                portalTicks.remove(id);
                attempted.remove(id);
                continue;
            }
            if (attempted.contains(id)) continue;
            Long last = cooldown.get(id);
            if (last != null && System.currentTimeMillis() - last < TRAVEL_COOLDOWN_MS) continue;
            int ticks = portalTicks.merge(id, 1, Integer::sum);
            int required = player.getGameMode() == org.bukkit.GameMode.CREATIVE ? 1 : 80;
            if (ticks < required) continue;
            attempted.add(id);
            portalTicks.remove(id);
            try {
                travel(player);
            } catch (Exception e) {
                player.sendMessage("Не удалось пройти через портал Aether. Подробности в консоли сервера.");
                logger.log(java.util.logging.Level.WARNING, "Aether portal travel failed for " + player.getName(), e);
            }
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        portalTicks.remove(id);
        attempted.remove(id);
        cooldown.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerPortalEvent.TeleportCause.NETHER_PORTAL) {
            return;
        }
        // Registered Aether portals use their own timer and destination. Leave ordinary
        // Nether portals entirely to vanilla and other plugins.
        if (event.getFrom().getWorld().getKey().toString().equals(plugin.aetherDimensionKey())
                || touchingPortal(event.getPlayer()) != null
                || plugin.portals().at(event.getFrom().getWorld().getKey().toString(),event.getFrom().getBlockX(),event.getFrom().getBlockY(),event.getFrom().getBlockZ())!=null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onVanillaTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if(event.getCause()!=org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.NETHER_PORTAL)return;
        Long last=cooldown.get(event.getPlayer().getUniqueId());
        if(event.getFrom().getWorld().getKey().toString().equals(plugin.aetherDimensionKey())
                || last!=null && System.currentTimeMillis()-last<TRAVEL_COOLDOWN_MS) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOrdinaryPortal(PlayerPortalEvent event) {
        if (event.getCause() == PlayerPortalEvent.TeleportCause.NETHER_PORTAL && touchingPortal(event.getPlayer())==null)
            event.getPlayer().playSound(event.getFrom(),"aether:carrier_original.block.portal.travel",.5f,1);
    }

    /** Public entry used by /aether tpaether and tpover. */
    public boolean travel(Player player) {
        long now = System.currentTimeMillis();
        Long last = cooldown.get(player.getUniqueId());
        if (last != null && now - last < TRAVEL_COOLDOWN_MS) {
            return false;
        }
        Location to = computeDestination(player);
        if (to == null) {
            player.sendMessage("Мир Aether недоступен. Если плагин установлен впервые, полностью перезапусти сервер после установки датапака.");
            logger.warning("Aether portal: no destination for " + player.getName()
                    + "; " + plugin.dimensionStatus());
            return false;
        }
        cooldown.put(player.getUniqueId(), now);
        Location from = player.getLocation();
        if (!player.teleport(to, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            player.sendMessage("Телепортация Aether отменена. Проверь плагины защиты и ограничения телепортации.");
            logger.warning("Aether portal teleport cancelled for " + player.getName());
            return false;
        }
        from.getWorld().playSound(from, SOUND_TRAVEL, 0.5f, 1.0f);
        to.getWorld().playSound(to, SOUND_TRAVEL, 0.5f, 1.0f);
        logger.info("Aether portal: " + player.getName() + " travelled from "
                + from.getWorld().getKey() + " to " + to.getWorld().getKey());
        return true;
    }

    private Location computeDestination(Player player) {
        Location from = player.getLocation();
        String fromKey = from.getWorld().getKey().toString();
        boolean inAether = fromKey.equals(plugin.aetherDimensionKey());
        World target = inAether ? plugin.mainWorld() : plugin.aetherWorld();
        if (target == null) {
            return null;
        }
        PortalManager portals = plugin.portals();
        PortalRect rect = portals.nearest(target.getKey().toString(),
                from.getX(), from.getY(), from.getZ(), SEARCH_RADIUS);
        if (rect == null) {
            rect = createExit(target, (int) from.getX(), (int) from.getY(), (int) from.getZ(),
                    sourceAxis(from));
            if (rect == null) {
                throw new IllegalStateException("Cannot build exit portal in " + target.getKey());
            }
        }
        return spot(target, rect);
    }

    private char sourceAxis(Location from) {
        PortalRect current = plugin.portals().at(from.getWorld().getKey().toString(),
                from.getBlockX(), from.getBlockY(), from.getBlockZ());
        return current != null ? current.axis() : 'x';
    }

    public static Location spot(World world, PortalRect rect) {
        double cx = rect.axis() == 'x' ? rect.x0() + rect.w() / 2.0 : rect.x0() + 0.5;
        double cz = rect.axis() == 'z' ? rect.z0() + rect.w() / 2.0 : rect.z0() + 0.5;
        float yaw = rect.axis() == 'x' ? 180.0f : 90.0f;
        return new Location(world, cx, rect.y0(), cz, yaw, 0.0f);
    }

    private PortalRect createExit(World world, int x, int y, int z, char axis) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int[][] offsets = {{0, 0}, {4, 0}, {-4, 0}, {0, 4}, {0, -4}, {8, 0}, {-8, 0}, {0, 8},
                {0, -8}, {12, 0}, {-12, 0}, {0, 12}, {0, -12}};
        for (int[] o : offsets) {
            int hx = x + o[0];
            int hz = z + o[1];
            int ground = world.getHighestBlockYAt(hx, hz);
            if (ground <= minY) {
                continue;
            }
            int x0 = axis == 'x' ? hx - 1 : hx;
            int z0 = axis == 'z' ? hz - 1 : hz;
            int y0 = ground + 1;
            if (y0 + 3 >= maxY) {
                continue;
            }
            if (volumeReplaceable(world, x0, y0, z0, axis, 2, 3)
                    && PortalShape.buildRect(world, x0, y0, z0, axis, 2, 3, Material.NETHER_PORTAL)) {
                PortalRect rect = new PortalRect(world.getKey().toString(), axis, x0, y0, z0, 2, 3);
                plugin.portals().register(rect);
                plugin.portals().saveAsync(plugin);
                logger.info("Created exit portal " + rect.key());
                return rect;
            }
        }
        // floating platform fallback (void / open sky)
        int highest = minY;
        // Include the complete frame and a landing platform. The previous
        // fallback cleared only the two interior columns at the source Y,
        // leaving solid island blocks where the frame had to go.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                highest = Math.max(highest, world.getHighestBlockYAt(x + dx, z + dz));
            }
        }
        int py = Math.max(minY + 2, Math.max(y, highest + 2));
        if (py + 3 >= maxY) return null;
        int x0 = axis == 'x' ? x - 1 : x;
        int z0 = axis == 'z' ? z - 1 : z;
        PortalRect probe = new PortalRect(world.getKey().toString(), axis, x0, py, z0, 2, 3);
        if (!volumeReplaceable(world, x0, py, z0, axis, 2, 3)) return null;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.getBlockAt(x + dx, py - 1, z + dz).setType(Material.GLOWSTONE, false);
            }
        }
        if (PortalShape.buildRect(world, x0, py, z0, axis, 2, 3, Material.NETHER_PORTAL)) {
            plugin.portals().register(probe);
            plugin.portals().saveAsync(plugin);
            logger.info("Created floating exit portal " + probe.key());
            return probe;
        }
        return null;
    }

    private static boolean volumeReplaceable(World world, int x0, int y0, int z0, char axis, int w, int h) {
        for (int level = -1; level <= h; level++) {
            for (int i = -1; i <= w; i++) {
                int cx = axis == 'x' ? x0 + i : x0;
                int cz = axis == 'z' ? z0 + i : z0;
                Material m = world.getBlockAt(cx, y0 + level, cz).getType();
                boolean edge = level == -1 || level == h || i == -1 || i == w;
                if (edge) {
                    // The bottom frame replaces existing ground. Requiring air
                    // there rejected every ordinary surface and forced a platform.
                    if (level == -1 && m!=Material.BEDROCK && !(world.getBlockAt(cx,y0+level,cz).getState() instanceof org.bukkit.block.TileState)) continue;
                    if (!PortalShape.isReplaceable(m) && m != Material.GLOWSTONE) {
                        return false;
                    }
                } else if (!PortalShape.isReplaceable(m)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ ambience

    /** Budgeted server-side approximation of the portal ambient particles. */
    public void tickAmbient() {
        int budget = 120;
        for (String worldKey : List.of(plugin.aetherDimensionKey(), "minecraft:overworld")) {
            World world = Bukkit.getWorld(org.bukkit.NamespacedKey.fromString(worldKey));
            if (world == null || world.getPlayers().isEmpty()) {
                continue;
            }
            for (PortalRect rect : plugin.portals().inWorld(worldKey)) {
                if (budget <= 0) {
                    return;
                }
                Location center = spot(world, rect);
                world.spawnParticle(Particle.END_ROD, center, 2, 0.8, 1.2, 0.8, 0.02);
                if (Bukkit.getCurrentTick() % 100 == 0) world.playSound(center,"aether:block.aether_portal.ambient",.5f,1);
                budget -= 2;
            }
        }
        // The pack suppresses client-side portal effects globally. Restore the
        // ordinary portal's original effects only where no Aether portal exists.
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location at = player.getLocation();
            World world = player.getWorld();
            for (int dx=-4; dx<=4; dx++) for (int dy=-3; dy<=3; dy++) for (int dz=-4; dz<=4; dz++) {
                int x=at.getBlockX()+dx, y=at.getBlockY()+dy, z=at.getBlockZ()+dz;
                if (y<world.getMinHeight() || y>=world.getMaxHeight() || !world.isChunkLoaded(x>>4,z>>4)) continue;
                if (world.getBlockAt(x,y,z).getType()!=Material.NETHER_PORTAL
                        || plugin.portals().at(world.getKey().toString(),x,y,z)!=null) continue;
                Location center=new Location(world,x+.5,y+.5,z+.5);
                world.spawnParticle(Particle.REVERSE_PORTAL,center,1,.4,.4,.4,.02);
                if (java.util.concurrent.ThreadLocalRandom.current().nextInt(100)==0)
                    world.playSound(center,"aether:carrier_original.block.portal.ambient",.5f,1);
            }
        }
    }

    // ---------------------------------------------------------------- self-test

    public void portalSelfTest() {
        if (!plugin.getConfig().getBoolean("debug.portal-test", false)) {
            return;
        }
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return;
        }
        World world = worlds.get(0);
        int fx = 8, fy = 200, fz = 8;
        if (!PortalShape.buildRect(world, fx, fy, fz, 'x', 2, 3, Material.WATER)) {
            logger.warning("Portal self-test: could not build a test frame.");
            return;
        }
        Optional<PortalRect> found = PortalShape.find(world, fx + 1, fy + 1, fz);
        if (found.isEmpty()) {
            cleanup(world, fx, fy, fz);
            logger.warning("Portal self-test: FAIL - shape not detected.");
            return;
        }
        PortalRect rect = found.get();
        if (rect.axis() != 'x' || rect.w() != 2 || rect.h() != 3) {
            cleanup(world, fx, fy, fz);
            logger.warning("Portal self-test: FAIL - wrong geometry " + rect);
            return;
        }
        if (!tryIgnite(world, fx + 1, fy + 1, fz)) {
            cleanup(world, fx, fy, fz);
            logger.warning("Portal self-test: FAIL - ignition failed.");
            return;
        }
        int planes = 0;
        for (int level = 0; level < 3; level++) {
            for (int i = 0; i < 2; i++) {
                if (world.getBlockAt(fx + i, fy + level, fz).getType() == Material.NETHER_PORTAL) {
                    planes++;
                }
            }
        }
        boolean ok = planes == 6 && plugin.portals().at(world.getKey().toString(),
                fx + 1, fy + 1, fz) != null;
        logger.info("Portal self-test: " + (ok ? "PASS" : "FAIL")
                + " - lit " + planes + "/6 planes, rect=" + rect.key());
        cleanup(world, fx, fy, fz);
    }

    private void cleanup(World world, int fx, int fy, int fz) {
        PortalRect interior = new PortalRect(world.getKey().toString(), 'x', fx, fy, fz, 2, 3);
        plugin.portals().remove(interior);
        plugin.portals().saveAsync(plugin);
        for (int level = -1; level <= 3; level++) {
            for (int i = -1; i <= 2; i++) {
                world.getBlockAt(fx + i, fy + level, fz).setType(Material.AIR, false);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEvent event) {
        // reserved: frame diagnostics; bucket lightning is handled via BlockPlaceEvent
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getHand() == EquipmentSlot.HAND) {
            Block clicked = event.getClickedBlock();
            if (clicked == null || clicked.getType() != Material.GLOWSTONE) {
                return;
            }
        }
    }
}
