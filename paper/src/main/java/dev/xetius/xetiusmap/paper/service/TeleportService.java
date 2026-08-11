package dev.xetius.xetiusmap.paper.service;

import dev.xetius.xetiusmap.common.model.Waypoint;
import dev.xetius.xetiusmap.common.net.S2C;
import dev.xetius.xetiusmap.paper.PluginConfig;
import dev.xetius.xetiusmap.paper.Permissions;
import dev.xetius.xetiusmap.paper.net.MessageBus;
import dev.xetius.xetiusmap.paper.session.PlayerSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Waypoint teleporting, gated behind {@code xetiusmap.teleport}, which is granted to nobody by
 * default. On a survival server free fast travel is a balance decision the operator has to opt into
 * deliberately, so the plugin ships with the door shut.
 *
 * <p>All of this runs on the main thread; the warmup is a scheduled task that a move or a hit will
 * cancel.
 */
public final class TeleportService implements Listener {

    private final Plugin plugin;
    private final Supplier<PluginConfig> config;
    private final MessageBus bus;
    private final WaypointService waypoints;

    private final Map<UUID, Pending> pending = new HashMap<>();

    public TeleportService(Plugin plugin, Supplier<PluginConfig> config, MessageBus bus, WaypointService waypoints) {
        this.plugin = plugin;
        this.config = config;
        this.bus = bus;
        this.waypoints = waypoints;
    }

    /**
     * Starts a teleport for a player, reporting the outcome both in chat and (when present) over
     * the map channel so the client UI can show it.
     */
    public void request(Player player, PlayerSession session, UUID waypointId) {
        Optional<String> refusal = validate(player, waypointId);
        if (refusal.isPresent()) {
            reply(player, session, false, refusal.get());
            return;
        }

        Waypoint waypoint = waypoints.byId(waypointId).orElseThrow();
        PluginConfig cfg = config.get();
        int warmup = cfg.teleportWarmupSeconds();
        if (warmup <= 0) {
            perform(player, session, waypoint);
            return;
        }

        cancel(player.getUniqueId(), null);
        Location origin = player.getLocation();
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (player.isOnline()) {
                perform(player, session, waypoint);
            }
        }, warmup * 20L);

        pending.put(player.getUniqueId(), new Pending(task, origin, session));
        reply(player, session, true, "Teleporting to " + waypoint.name() + " in " + warmup + "s — stand still.");
    }

    /**
     * Teleports to a point picked off the map. No height is supplied, so one is chosen here: the
     * surface, or in a roofed dimension the first standable gap below the ceiling.
     */
    public void requestLocation(Player player, PlayerSession session, String dimension, int x, int z) {
        PluginConfig cfg = config.get();
        if (!cfg.teleportEnabled()) {
            reply(player, session, false, "Waypoint teleporting is disabled on this server.");
            return;
        }
        if (!player.hasPermission(Permissions.TELEPORT_ANYWHERE)) {
            reply(player, session, false, "You do not have permission to teleport to arbitrary map locations.");
            return;
        }

        World world = worldFor(dimension);
        if (world == null) {
            reply(player, session, false, "The dimension '" + dimension + "' is not loaded.");
            return;
        }
        if (!cfg.teleportAllowCrossDimension() && !world.equals(player.getWorld())) {
            reply(player, session, false, "You can only teleport within your current dimension.");
            return;
        }
        long remaining = cooldownRemainingSeconds(player);
        if (remaining > 0) {
            reply(player, session, false, "You must wait " + remaining + "s before teleporting again.");
            return;
        }

        Location target = landingSpot(world, x, z);
        int warmup = cfg.teleportWarmupSeconds();
        if (warmup <= 0) {
            move(player, session, target, describe(dimension, x, z));
            return;
        }

        cancel(player.getUniqueId(), null);
        Location origin = player.getLocation();
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (player.isOnline()) {
                move(player, session, target, describe(dimension, x, z));
            }
        }, warmup * 20L);
        pending.put(player.getUniqueId(), new Pending(task, origin, session));
        reply(player, session, true, "Teleporting to " + describe(dimension, x, z)
                + " in " + warmup + "s — stand still.");
    }

    private static String describe(String dimension, int x, int z) {
        int colon = dimension.indexOf(':');
        return x + ", " + z + " in " + (colon >= 0 ? dimension.substring(colon + 1) : dimension);
    }

    /**
     * Picks somewhere to stand at an x/z with no height given. Under a ceiling the highest block is
     * the roof, so the search starts below it and works down for the first standable gap.
     */
    private Location landingSpot(World world, int x, int z) {
        int start = world.hasCeiling()
                ? Math.min(world.getMaxHeight() - 2, 100)
                : world.getHighestBlockYAt(x, z) + 1;
        Location candidate = new Location(world, x + 0.5, start, z + 0.5,
                0.0F, 0.0F);
        return config.get().teleportSafeLanding() ? safeLanding(candidate) : candidate;
    }

    private void move(Player player, PlayerSession session, Location target, String description) {
        Location destination = target.clone();
        destination.setYaw(player.getLocation().getYaw());
        destination.setPitch(player.getLocation().getPitch());
        player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                if (session != null) {
                    session.markTeleported();
                }
                reply(player, session, true, "Teleported to " + description + ".");
            } else {
                reply(player, session, false, "The teleport was cancelled.");
            }
        });
    }

    private Optional<String> validate(Player player, UUID waypointId) {
        PluginConfig cfg = config.get();
        if (!cfg.teleportEnabled()) {
            return Optional.of("Waypoint teleporting is disabled on this server.");
        }
        if (!player.hasPermission(Permissions.TELEPORT)) {
            return Optional.of("You do not have permission to teleport to waypoints.");
        }

        Optional<Waypoint> found = waypoints.byId(waypointId);
        if (found.isEmpty()) {
            return Optional.of("That waypoint no longer exists.");
        }
        Waypoint waypoint = found.get();

        World world = worldFor(waypoint.dimension());
        if (world == null) {
            return Optional.of("The dimension '" + waypoint.dimension() + "' is not loaded.");
        }
        if (!cfg.teleportAllowCrossDimension() && !world.equals(player.getWorld())) {
            return Optional.of("You can only teleport to waypoints in your current dimension.");
        }

        long remaining = cooldownRemainingSeconds(player);
        if (remaining > 0) {
            return Optional.of("You must wait " + remaining + "s before teleporting again.");
        }
        return Optional.empty();
    }

    public long cooldownRemainingSeconds(Player player) {
        PluginConfig cfg = config.get();
        if (cfg.teleportCooldownSeconds() <= 0 || player.hasPermission(Permissions.ADMIN)) {
            return 0;
        }
        PlayerSession session = bus.session(player.getUniqueId());
        long last = session == null ? 0 : session.lastTeleportMillis();
        if (last == 0) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - last;
        long cooldown = cfg.teleportCooldownSeconds() * 1000L;
        return elapsed >= cooldown ? 0 : (cooldown - elapsed + 999) / 1000;
    }

    private void perform(Player player, PlayerSession session, Waypoint waypoint) {
        World world = worldFor(waypoint.dimension());
        if (world == null) {
            reply(player, session, false, "The dimension '" + waypoint.dimension() + "' is not loaded.");
            return;
        }

        Location target = new Location(world, waypoint.x() + 0.5, waypoint.y(), waypoint.z() + 0.5,
                player.getLocation().getYaw(), player.getLocation().getPitch());
        if (config.get().teleportSafeLanding()) {
            target = safeLanding(target);
        }

        Location destination = target;
        player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                if (session != null) {
                    session.markTeleported();
                }
                reply(player, session, true, "Teleported to " + waypoint.name() + ".");
            } else {
                reply(player, session, false, "The teleport was cancelled.");
            }
        });
    }

    /**
     * Nudges the destination up out of solid blocks, then drops it onto the surface if the stored
     * Y is buried. Deliberately conservative: it never moves horizontally.
     */
    private Location safeLanding(Location target) {
        World world = target.getWorld();
        if (world.hasCeiling()) {
            // Searching upward would walk into the bedrock roof, so look down for a gap instead.
            int x = target.getBlockX();
            int z = target.getBlockZ();
            for (int y = target.getBlockY(); y > world.getMinHeight() + 1; y--) {
                boolean feetClear = world.getBlockAt(x, y, z).isPassable();
                boolean headClear = world.getBlockAt(x, y + 1, z).isPassable();
                boolean groundSolid = !world.getBlockAt(x, y - 1, z).isPassable();
                if (feetClear && headClear && groundSolid) {
                    return new Location(world, x + 0.5, y, z + 0.5, target.getYaw(), target.getPitch());
                }
            }
            return target;
        }
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int x = target.getBlockX();
        int z = target.getBlockZ();

        for (int y = Math.max(minY, target.getBlockY()); y < maxY - 1; y++) {
            boolean feetClear = world.getBlockAt(x, y, z).isPassable();
            boolean headClear = world.getBlockAt(x, y + 1, z).isPassable();
            boolean groundSolid = y > minY && !world.getBlockAt(x, y - 1, z).isPassable();
            if (feetClear && headClear && groundSolid) {
                return new Location(world, x + 0.5, y, z + 0.5, target.getYaw(), target.getPitch());
            }
        }
        // Nothing suitable at or above the stored height: fall back to the surface.
        int surface = world.getHighestBlockYAt(x, z) + 1;
        return new Location(world, x + 0.5, surface, z + 0.5, target.getYaw(), target.getPitch());
    }

    private World worldFor(String dimensionId) {
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getKey().toString().equals(dimensionId)) {
                return world;
            }
        }
        return null;
    }

    private void reply(Player player, PlayerSession session, boolean accepted, String message) {
        player.sendMessage(Component.text(message, accepted ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (session != null) {
            bus.send(session, new S2C.TeleportResult(accepted, message));
        }
    }

    private void cancel(UUID playerId, String reason) {
        Pending existing = pending.remove(playerId);
        if (existing == null) {
            return;
        }
        existing.task.cancel();
        if (reason != null) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                reply(player, existing.session, false, reason);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!config.get().teleportCancelOnMove() || pending.isEmpty()) {
            return;
        }
        Pending waiting = pending.get(event.getPlayer().getUniqueId());
        if (waiting == null) {
            return;
        }
        Location from = waiting.origin;
        Location to = event.getTo();
        // Looking around is fine; stepping off the block is not.
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ()) {
            cancel(event.getPlayer().getUniqueId(), "Teleport cancelled because you moved.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!config.get().teleportCancelOnDamage() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (pending.containsKey(player.getUniqueId())) {
            cancel(player.getUniqueId(), "Teleport cancelled because you took damage.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), null);
    }

    public void shutdown() {
        for (Pending waiting : pending.values()) {
            waiting.task.cancel();
        }
        pending.clear();
    }

    private record Pending(BukkitTask task, Location origin, PlayerSession session) {
    }
}
