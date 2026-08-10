package dev.xetius.xetiusmap.paper.service;

import dev.xetius.xetiusmap.common.model.Markers;
import dev.xetius.xetiusmap.common.net.S2C;
import dev.xetius.xetiusmap.paper.Permissions;
import dev.xetius.xetiusmap.paper.PluginConfig;
import dev.xetius.xetiusmap.paper.net.MessageBus;
import dev.xetius.xetiusmap.paper.session.PlayerSession;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.WaterMob;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Pushes live positions to every modded client.
 *
 * <p>Players are reported globally, across all dimensions, because "where is everyone" is a big
 * part of what the world map is for. Mobs are reported only within a configurable radius of the
 * recipient — the difference matters, because sending every loaded mob on a busy server would be
 * both a bandwidth problem and a server-wide entity radar.
 *
 * <p>{@link Player#canSee} is the visibility test, so vanish plugins are respected for free.
 * Runs entirely on the main thread.
 */
public final class EntityService {

    private final Plugin plugin;
    private final Supplier<PluginConfig> config;
    private final MessageBus bus;

    /** Beyond this many chunks in view, the world map gets players only — mobs would be a flood. */
    private static final int MAX_VIEW_CHUNKS_FOR_MOBS = 32 * 32;

    public EntityService(Plugin plugin, Supplier<PluginConfig> config, MessageBus bus) {
        this.plugin = plugin;
        this.config = config;
        this.bus = bus;
    }

    public void tick() {
        PluginConfig cfg = config.get();
        if (!cfg.radarEnabled()) {
            return;
        }
        List<PlayerSession> sessions = bus.activeSessions();
        if (sessions.isEmpty()) {
            return;
        }

        for (PlayerSession session : sessions) {
            Player viewer = plugin.getServer().getPlayer(session.playerId());
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            bus.send(session, build(viewer, session, cfg));
        }
    }

    private S2C.EntityUpdate build(Player viewer, PlayerSession session, PluginConfig cfg) {
        String viewerDimension = viewer.getWorld().getKey().toString();

        List<Markers.PlayerMarker> players = new ArrayList<>();
        if (cfg.showPlayers()) {
            for (Player other : plugin.getServer().getOnlinePlayers()) {
                if (!isVisibleTo(other, viewer, cfg)) {
                    continue;
                }
                Location location = other.getLocation();
                players.add(new Markers.PlayerMarker(
                        other.getUniqueId(),
                        other.getName(),
                        other.getWorld().getKey().toString(),
                        location.getBlockX(),
                        clampY(location.getBlockY()),
                        location.getBlockZ(),
                        location.getYaw()
                ));
            }
        }

        List<String> palette = new ArrayList<>();
        Map<String, Integer> paletteIndex = new HashMap<>();
        List<Markers.MobMarker> mobs = new ArrayList<>();

        if (cfg.mobRadius() > 0 && cfg.maxMobsPerUpdate() > 0) {
            for (Entity entity : nearbyMobs(viewer, session, cfg)) {
                if (mobs.size() >= cfg.maxMobsPerUpdate()) {
                    break;
                }
                if (entity instanceof Player || !entity.isValid() || !viewer.canSee(entity)) {
                    continue;
                }
                if (!isLivingCreature(entity)) {
                    continue;
                }
                String type = entity.getType().getKey().toString();
                int index = paletteIndex.computeIfAbsent(type, key -> {
                    palette.add(key);
                    return palette.size() - 1;
                });
                Location location = entity.getLocation();
                mobs.add(new Markers.MobMarker(
                        index,
                        categorise(entity),
                        location.getBlockX(),
                        clampY(location.getBlockY()),
                        location.getBlockZ(),
                        location.getYaw()
                ));
            }
        }

        return new S2C.EntityUpdate(viewerDimension, palette, players, mobs);
    }

    /**
     * The mobs a viewer should be told about: normally a sphere around them, but widened to the
     * visible rectangle when their world map is open and showing a modest area.
     */
    private List<Entity> nearbyMobs(Player viewer, PlayerSession session, PluginConfig cfg) {
        PlayerSession.ViewBounds view = session.view();
        String viewerDimension = viewer.getWorld().getKey().toString();

        if (view != null
                && view.dimension().equals(viewerDimension)
                && view.chunkArea() > 0
                && view.chunkArea() <= MAX_VIEW_CHUNKS_FOR_MOBS) {
            double centreX = ((double) view.minChunkX() + view.maxChunkX() + 1) * 8.0;
            double centreZ = ((double) view.minChunkZ() + view.maxChunkZ() + 1) * 8.0;
            double halfX = ((double) view.maxChunkX() - view.minChunkX() + 1) * 8.0;
            double halfZ = ((double) view.maxChunkZ() - view.minChunkZ() + 1) * 8.0;
            Location centre = new Location(viewer.getWorld(), centreX, viewer.getLocation().getY(), centreZ);
            return new ArrayList<>(viewer.getWorld().getNearbyEntities(centre, halfX, 512.0, halfZ));
        }

        double radius = cfg.mobRadius();
        return new ArrayList<>(viewer.getWorld().getNearbyEntities(viewer.getLocation(), radius, radius, radius));
    }

    private boolean isVisibleTo(Player target, Player viewer, PluginConfig cfg) {
        if (!viewer.canSee(target)) {
            return false;
        }
        if (target.hasPermission(Permissions.HIDDEN)) {
            return false;
        }
        if (cfg.hideSpectators() && target.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        PlayerSession theirSession = bus.session(target.getUniqueId());
        if (theirSession != null && theirSession.hidden() && !target.equals(viewer)) {
            return false;
        }
        return true;
    }

    /** Items, projectiles and armour stands are noise on a map; only creatures are reported. */
    private static boolean isLivingCreature(Entity entity) {
        return entity instanceof org.bukkit.entity.LivingEntity && !(entity instanceof org.bukkit.entity.ArmorStand);
    }

    private static byte categorise(Entity entity) {
        if (entity instanceof Boss) {
            return Markers.MobCategory.BOSS;
        }
        if (entity instanceof Tameable tameable && tameable.isTamed()) {
            return Markers.MobCategory.TAMED;
        }
        if (entity instanceof Monster) {
            return Markers.MobCategory.HOSTILE;
        }
        if (entity instanceof WaterMob) {
            return Markers.MobCategory.WATER;
        }
        if (entity instanceof Animals || entity instanceof Ambient) {
            return Markers.MobCategory.PASSIVE;
        }
        return Markers.MobCategory.OTHER;
    }

    /** Marker Y travels as a short, which is ample for every vanilla world height. */
    private static int clampY(int y) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, y));
    }
}
