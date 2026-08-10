package dev.xetius.xetiusmap.paper;

import dev.xetius.xetiusmap.common.model.ServerPolicy;
import dev.xetius.xetiusmap.common.model.Waypoint;
import dev.xetius.xetiusmap.common.net.C2S;
import dev.xetius.xetiusmap.common.net.Protocol;
import dev.xetius.xetiusmap.common.net.S2C;
import dev.xetius.xetiusmap.common.store.TileStore;
import dev.xetius.xetiusmap.paper.command.XMapCommand;
import dev.xetius.xetiusmap.paper.net.MessageBus;
import dev.xetius.xetiusmap.paper.service.EntityService;
import dev.xetius.xetiusmap.paper.service.TeleportService;
import dev.xetius.xetiusmap.paper.service.TileService;
import dev.xetius.xetiusmap.paper.service.WaypointService;
import dev.xetius.xetiusmap.paper.session.PlayerSession;
import dev.xetius.xetiusmap.paper.util.RevisionCounter;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * XetiusMap's server half: the shared tile store, the global waypoint list, the live entity radar
 * and the teleport gate. Clients running the Fabric mod discover it through a handshake on the
 * {@code xetiusmap:main} plugin channel; anyone without the mod is unaffected.
 */
public final class XetiusMapPlugin extends JavaPlugin implements Listener {

    private final AtomicReference<PluginConfig> config = new AtomicReference<>();

    private ExecutorService io;
    private TileStore store;
    private RevisionCounter revisions;
    private MessageBus bus;
    private TileService tiles;
    private WaypointService waypoints;
    private EntityService entities;
    private TeleportService teleports;

    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config.set(PluginConfig.load(getConfig()));
        PluginConfig cfg = config.get();

        io = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "XetiusMap-IO");
            thread.setDaemon(true);
            return thread;
        });

        Path dataRoot = getDataFolder().toPath();
        Path mapRoot = dataRoot.resolve(cfg.storageDirectory());
        store = new TileStore(mapRoot, cfg.maxOpenRegions());

        try {
            Path counterFile = dataRoot.resolve("revision.dat");
            // Only pay for a full scan the first time, when there is no counter to resume from.
            long floor = java.nio.file.Files.exists(counterFile) ? 0L : store.scanMaxRevision();
            revisions = new RevisionCounter(counterFile, floor);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not initialise the map revision counter; disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        bus = new MessageBus(this, config::get);
        bus.register();

        tiles = new TileService(getLogger(), config::get, bus, store, revisions, io);
        waypoints = new WaypointService(getLogger(), dataRoot.resolve("waypoints.yml"), bus, io);
        waypoints.load();
        entities = new EntityService(this, config::get, bus);
        teleports = new TeleportService(this, config::get, bus, waypoints);

        bus.setHandler(this::handle);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(teleports, this);

        PluginCommand command = getCommand("xmap");
        if (command != null) {
            XMapCommand handler = new XMapCommand(this, config::get, bus, waypoints, teleports, tiles);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().warning("The /xmap command is missing from plugin.yml; commands will not work.");
        }

        scheduleTasks(cfg);

        // Players already online when the plugin is reloaded still need a session.
        for (Player player : getServer().getOnlinePlayers()) {
            bus.openSession(player);
        }

        getLogger().info("XetiusMap ready — protocol v" + Protocol.VERSION + ", map data in " + mapRoot);
    }

    private void scheduleTasks(PluginConfig cfg) {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        tasks.add(getServer().getScheduler().runTaskTimer(this, bus::flush, 1L, 1L));
        tasks.add(getServer().getScheduler().runTaskTimer(this, entities::tick,
                cfg.radarIntervalTicks(), cfg.radarIntervalTicks()));
        tasks.add(getServer().getScheduler().runTaskTimer(this, this::flushStore, 600L, 600L));
    }

    private void flushStore() {
        io.execute(() -> {
            try {
                store.flushAll();
                revisions.flush();
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Could not flush the map store", e);
            }
        });
    }

    @Override
    public void onDisable() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();

        if (teleports != null) {
            teleports.shutdown();
        }
        if (bus != null) {
            bus.unregister();
        }
        if (waypoints != null) {
            waypoints.saveNow();
        }
        if (io != null) {
            io.shutdown();
            try {
                if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                    getLogger().warning("Map storage did not finish writing within 10s; forcing shutdown.");
                    io.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                io.shutdownNow();
            }
        }
        if (revisions != null) {
            revisions.flush();
        }
        if (store != null) {
            try {
                store.close();
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Could not close the map store cleanly", e);
            }
        }
    }

    /** Rebuilds the config snapshot and re-times the scheduled work. */
    public void reload() {
        reloadConfig();
        PluginConfig updated = PluginConfig.load(getConfig());
        config.set(updated);
        bus.applyConfig(updated);
        waypoints.load();
        scheduleTasks(updated);
        for (PlayerSession session : bus.activeSessions()) {
            bus.send(session, new S2C.HelloOk(Protocol.VERSION, policyFor(session)));
            waypoints.syncTo(session);
        }
    }

    // --- Session lifecycle -------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        bus.openSession(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bus.closeSession(event.getPlayer().getUniqueId());
    }

    // --- Packet dispatch ---------------------------------------------------------------------

    private void handle(Player player, PlayerSession session, C2S packet) {
        if (packet instanceof C2S.Hello hello) {
            handleHello(player, session, hello);
            return;
        }
        if (!session.handshakeComplete()) {
            return;
        }

        switch (packet) {
            case C2S.TileUpload upload -> {
                if (player.hasPermission(Permissions.UPLOAD)) {
                    tiles.handleUpload(session, upload, player.getWorld().getKey().toString());
                }
            }
            case C2S.TileRequest request -> tiles.handleRequest(session, request);
            case C2S.RegionSubscribe subscribe -> tiles.handleSubscribe(session, subscribe);
            case C2S.WaypointCreate create -> handleWaypointCreate(player, session, create);
            case C2S.WaypointUpdate update -> handleWaypointUpdate(player, session, update);
            case C2S.WaypointDelete delete -> handleWaypointDelete(player, session, delete);
            case C2S.TeleportRequest request -> teleports.request(player, session, request.waypointId());
            case C2S.EntityView view -> session.setView(view.active()
                    ? new PlayerSession.ViewBounds(view.dimension(), view.minChunkX(), view.minChunkZ(),
                            view.maxChunkX(), view.maxChunkZ())
                    : null);
            case C2S.SetHidden hidden -> session.setHidden(hidden.hidden());
            case C2S.Hello ignored -> {
                // Already handled above; a repeat is harmless and deliberately ignored.
            }
        }
    }

    private void handleHello(Player player, PlayerSession session, C2S.Hello hello) {
        if (hello.protocolVersion() != Protocol.VERSION) {
            bus.send(session, new S2C.Notice(S2C.Notice.Level.ERROR,
                    "XetiusMap version mismatch: this server speaks protocol v" + Protocol.VERSION
                            + " but your mod speaks v" + hello.protocolVersion() + "."));
            return;
        }
        if (!player.hasPermission(Permissions.USE)) {
            bus.send(session, new S2C.Notice(S2C.Notice.Level.ERROR,
                    "You do not have permission to use the shared map on this server."));
            return;
        }

        session.completeHandshake(hello.modVersion());
        bus.send(session, new S2C.HelloOk(Protocol.VERSION, policyFor(session)));
        waypoints.syncTo(session);
        getLogger().info("XetiusMap client connected: " + player.getName() + " (mod " + hello.modVersion() + ")");
    }

    private ServerPolicy policyFor(PlayerSession session) {
        PluginConfig cfg = config.get();
        Player player = getServer().getPlayer(session.playerId());
        boolean mayTeleport = player != null && player.hasPermission(Permissions.TELEPORT);
        boolean mayUpload = cfg.uploadsEnabled() && player != null && player.hasPermission(Permissions.UPLOAD);

        List<String> dimensions = new ArrayList<>();
        for (World world : getServer().getWorlds()) {
            String id = world.getKey().toString();
            if (cfg.allowsDimension(id)) {
                dimensions.add(id);
            }
        }

        return new ServerPolicy(
                getServer().getName() + " " + getServer().getMinecraftVersion(),
                mayUpload,
                cfg.teleportEnabled(),
                mayTeleport,
                cfg.mobRadius(),
                cfg.radarIntervalTicks(),
                cfg.maxUploadsPerSecond(),
                List.copyOf(dimensions)
        );
    }

    private void handleWaypointCreate(Player player, PlayerSession session, C2S.WaypointCreate create) {
        if (!player.hasPermission(Permissions.WAYPOINT_CREATE)) {
            bus.send(session, new S2C.Notice(S2C.Notice.Level.ERROR,
                    "You do not have permission to create waypoints."));
            return;
        }
        Waypoint incoming = create.waypoint();
        // Ownership is assigned here, never taken from the client.
        Waypoint owned = new Waypoint(
                incoming.id(), incoming.name(), incoming.dimension(),
                incoming.x(), incoming.y(), incoming.z(),
                incoming.color(), incoming.icon(),
                player.getUniqueId(), player.getName(), System.currentTimeMillis());
        waypoints.create(owned, config.get()).ifPresent(error ->
                bus.send(session, new S2C.Notice(S2C.Notice.Level.ERROR, error)));
    }

    private void handleWaypointUpdate(Player player, PlayerSession session, C2S.WaypointUpdate update) {
        Waypoint existing = waypoints.byId(update.waypoint().id()).orElse(null);
        if (existing == null) {
            bus.send(session, new S2C.Notice(S2C.Notice.Level.ERROR, "That waypoint no longer exists."));
            return;
        }
        if (!mayModify(player, existing.owner(), Permissions.WAYPOINT_EDIT_OWN, Permissions.WAYPOINT_EDIT_OTHER)) {
            bus.send(session, new S2C.Notice(S2C.Notice.Level.ERROR,
                    "You may only edit waypoints you created."));
            return;
        }
        waypoints.update(update.waypoint()).ifPresent(error ->
                bus.send(session, new S2C.Notice(S2C.Notice.Level.ERROR, error)));
    }

    private void handleWaypointDelete(Player player, PlayerSession session, C2S.WaypointDelete delete) {
        Waypoint existing = waypoints.byId(delete.waypointId()).orElse(null);
        if (existing == null) {
            return;
        }
        if (!mayModify(player, existing.owner(), Permissions.WAYPOINT_DELETE_OWN, Permissions.WAYPOINT_DELETE_OTHER)) {
            bus.send(session, new S2C.Notice(S2C.Notice.Level.ERROR,
                    "You may only delete waypoints you created."));
            return;
        }
        waypoints.delete(delete.waypointId());
    }

    private static boolean mayModify(Player player, UUID owner, String ownPermission, String otherPermission) {
        if (player.hasPermission(otherPermission)) {
            return true;
        }
        return player.getUniqueId().equals(owner) && player.hasPermission(ownPermission);
    }

    // --- Accessors used by the command handler ------------------------------------------------

    public PluginConfig configSnapshot() {
        return config.get();
    }

    public MessageBus bus() {
        return bus;
    }
}
