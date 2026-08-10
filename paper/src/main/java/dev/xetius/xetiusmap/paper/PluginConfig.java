package dev.xetius.xetiusmap.paper;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of config.yml. Rebuilt wholesale on {@code /xmap reload} so a running tick
 * never sees a half-applied configuration.
 */
public record PluginConfig(
        String storageDirectory,
        int maxOpenRegions,
        boolean uploadsEnabled,
        Set<String> allowedDimensions,
        int maxUploadsPerSecond,
        int maxTileRequestsPerSecond,
        int maxFramesPerPlayerPerTick,
        boolean radarEnabled,
        int radarIntervalTicks,
        int mobRadius,
        int maxMobsPerUpdate,
        boolean showPlayers,
        boolean hideSpectators,
        boolean teleportEnabled,
        int teleportCooldownSeconds,
        int teleportWarmupSeconds,
        boolean teleportCancelOnMove,
        boolean teleportCancelOnDamage,
        boolean teleportAllowCrossDimension,
        boolean teleportSafeLanding,
        int maxWaypointsPerPlayer,
        int maxWaypointsTotal
) {

    public static PluginConfig load(FileConfiguration c) {
        List<String> dimensions = c.getStringList("map.dimensions");
        return new PluginConfig(
                c.getString("storage.directory", "mapdata"),
                clamp(c.getInt("storage.max-open-regions", 64), 4, 4096),
                c.getBoolean("map.uploads-enabled", true),
                Set.copyOf(dimensions),
                clamp(c.getInt("map.max-uploads-per-second", 30), 1, 10_000),
                clamp(c.getInt("map.max-tile-requests-per-second", 400), 1, 100_000),
                clamp(c.getInt("map.max-frames-per-player-per-tick", 8), 1, 256),
                c.getBoolean("radar.enabled", true),
                clamp(c.getInt("radar.interval-ticks", 10), 1, 200),
                clamp(c.getInt("radar.mob-radius", 192), 0, 2048),
                clamp(c.getInt("radar.max-mobs-per-update", 400), 0, 5000),
                c.getBoolean("radar.show-players", true),
                c.getBoolean("radar.hide-spectators", true),
                c.getBoolean("teleport.enabled", true),
                clamp(c.getInt("teleport.cooldown-seconds", 30), 0, 86_400),
                clamp(c.getInt("teleport.warmup-seconds", 3), 0, 60),
                c.getBoolean("teleport.cancel-on-move", true),
                c.getBoolean("teleport.cancel-on-damage", true),
                c.getBoolean("teleport.allow-cross-dimension", true),
                c.getBoolean("teleport.safe-landing", true),
                clamp(c.getInt("waypoints.max-per-player", 64), 0, 100_000),
                clamp(c.getInt("waypoints.max-total", 2000), 0, 1_000_000)
        );
    }

    /** An empty dimension list means "every dimension", which is the friendlier default. */
    public boolean allowsDimension(String dimensionId) {
        return allowedDimensions.isEmpty() || allowedDimensions.contains(dimensionId);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
