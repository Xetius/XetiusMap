package dev.xetius.xetiusmap.client;

import dev.xetius.xetiusmap.client.config.ClientConfig;
import dev.xetius.xetiusmap.client.map.ChunkScanner;
import dev.xetius.xetiusmap.client.map.EntityTracker;
import dev.xetius.xetiusmap.client.map.MapDataStore;
import dev.xetius.xetiusmap.client.map.TileColorizer;
import dev.xetius.xetiusmap.client.net.ClientNetwork;
import dev.xetius.xetiusmap.client.waypoint.WaypointManager;
import dev.xetius.xetiusmap.common.model.ServerPolicy;
import dev.xetius.xetiusmap.common.model.Waypoint;
import dev.xetius.xetiusmap.common.net.C2S;
import dev.xetius.xetiusmap.common.net.Protocol;
import dev.xetius.xetiusmap.common.net.S2C;
import dev.xetius.xetiusmap.common.tile.ChunkTile;
import dev.xetius.xetiusmap.common.util.ChunkRef;
import dev.xetius.xetiusmap.common.util.MapCoords;
import dev.xetius.xetiusmap.common.util.RegionRef;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything tied to one connection: the tile cache, the scanner feeding it, the shared waypoint
 * list, live markers, and the handshake that decides whether any of it is shared at all.
 *
 * <p>If the server answers the handshake, the map is collective — tiles this client renders are
 * offered to everyone and tiles others rendered arrive here. If it does not answer within a few
 * seconds the mod falls into local mode and keeps a private map instead, which is also how single
 * player works.
 */
public final class MapClient implements AutoCloseable {

    /** How long to wait for a server to answer before assuming it has no plugin. */
    private static final int HANDSHAKE_TIMEOUT_TICKS = 60;

    private static final int HANDSHAKE_RETRY_TICKS = 20;

    /** Regions around the player kept subscribed when the world map is closed. */
    private static final int MINIMAP_REGION_RADIUS = 1;

    private static final int SUBSCRIPTION_INTERVAL_TICKS = 20;

    private final ClientConfig config;
    private final ExecutorService io;
    private final MapDataStore store;
    private final ChunkScanner scanner;
    private final WaypointManager waypoints;
    private final EntityTracker entities = new EntityTracker();
    private final String storageKey;

    private ServerPolicy policy = ServerPolicy.localMode();
    private boolean serverBacked;
    private boolean handshakeFailed;
    private int handshakeTicks;
    private int subscriptionTicks;

    private String dimension = "minecraft:overworld";
    private String subscribedDimension = "";
    private Set<Long> subscribedRegions = Set.of();

    /** Set while the world map is open so subscriptions follow what is on screen. */
    private String viewDimension;
    private Set<Long> viewRegions;

    private double uploadTokens;
    private long uploadTokensStamp = System.nanoTime();
    private String status = "Local map";

    public MapClient(Minecraft minecraft, ClientConfig config) {
        this.config = config;
        this.storageKey = storageKeyFor(minecraft);
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "XetiusMap-Client-IO");
            thread.setDaemon(true);
            return thread;
        });

        Path root = FabricLoader.getInstance().getGameDir()
                .resolve("xetiusmap")
                .resolve(MapCoords.dimensionToFolder(storageKey));
        this.store = new MapDataStore(root.resolve("map"), io);
        this.waypoints = new WaypointManager(root.resolve("waypoints.json"), true);
        this.scanner = new ChunkScanner(new TileColorizer(minecraft.getBlockColors()));
    }

    private static String storageKeyFor(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "server-" + server.ip;
        }
        if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
            return "local-" + minecraft.getSingleplayerServer().getWorldData().getLevelName();
        }
        return "unknown";
    }

    // --- Accessors ---------------------------------------------------------------------------

    public MapDataStore store() {
        return store;
    }

    public WaypointManager waypoints() {
        return waypoints;
    }

    public EntityTracker entities() {
        return entities;
    }

    public ServerPolicy policy() {
        return policy;
    }

    public boolean serverBacked() {
        return serverBacked;
    }

    public String status() {
        return status;
    }

    public String dimension() {
        return dimension;
    }

    /** Dimensions worth offering in the world map's selector. */
    public List<String> availableDimensions() {
        if (!policy.dimensions().isEmpty()) {
            return policy.dimensions();
        }
        return List.of(dimension);
    }

    // --- Tick --------------------------------------------------------------------------------

    public void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        dimension = minecraft.level.dimension().identifier().toString();

        handshakeTick();
        scanner.tick(minecraft, this, config);

        if (++subscriptionTicks >= SUBSCRIPTION_INTERVAL_TICKS) {
            subscriptionTicks = 0;
            refreshSubscriptions(minecraft);
        }

        if (!serverBacked || entities.stale()) {
            entities.refreshFromClient(minecraft);
        }
    }

    private void handshakeTick() {
        if (serverBacked || handshakeFailed) {
            return;
        }
        handshakeTicks++;
        if (handshakeTicks % HANDSHAKE_RETRY_TICKS == 0) {
            ClientNetwork.send(new C2S.Hello(Protocol.VERSION, XetiusMapClient.modVersion()));
        }
        if (handshakeTicks > HANDSHAKE_TIMEOUT_TICKS) {
            handshakeFailed = true;
            status = "Local map (no server plugin)";
            XetiusMap.LOGGER.info("No XetiusMap server plugin detected; using a local map.");
        }
    }

    /**
     * Tells the server which regions to keep us updated about: the ring around the player normally,
     * or whatever the world map is showing while it is open.
     */
    private void refreshSubscriptions(Minecraft minecraft) {
        if (!serverBacked) {
            return;
        }

        String wanted;
        Set<Long> regions;
        if (viewRegions != null && viewDimension != null) {
            wanted = viewDimension;
            regions = viewRegions;
        } else {
            wanted = dimension;
            regions = new HashSet<>();
            int regionX = MapCoords.blockToRegion(minecraft.player.getBlockX());
            int regionZ = MapCoords.blockToRegion(minecraft.player.getBlockZ());
            for (int dz = -MINIMAP_REGION_RADIUS; dz <= MINIMAP_REGION_RADIUS; dz++) {
                for (int dx = -MINIMAP_REGION_RADIUS; dx <= MINIMAP_REGION_RADIUS; dx++) {
                    regions.add(MapCoords.key(regionX + dx, regionZ + dz));
                }
            }
        }

        if (wanted.equals(subscribedDimension) && regions.equals(subscribedRegions)) {
            return;
        }
        subscribedDimension = wanted;
        subscribedRegions = Set.copyOf(regions);

        List<RegionRef> refs = new ArrayList<>(regions.size());
        for (long key : regions) {
            refs.add(RegionRef.of(key));
            if (refs.size() >= Protocol.MAX_REGION_SUBSCRIPTIONS) {
                break;
            }
        }
        ClientNetwork.send(new C2S.RegionSubscribe(wanted, refs));
    }

    /** Called by the world map screen as the view moves. */
    public void setViewRegions(String viewedDimension, Set<Long> regions) {
        this.viewDimension = viewedDimension;
        this.viewRegions = regions;
        this.subscriptionTicks = SUBSCRIPTION_INTERVAL_TICKS;
    }

    public void clearViewRegions() {
        this.viewDimension = null;
        this.viewRegions = null;
        this.subscriptionTicks = SUBSCRIPTION_INTERVAL_TICKS;
    }

    /** Queues a newly available chunk for colourising. */
    public void scannerEnqueue(net.minecraft.client.multiplayer.ClientLevel level,
                               net.minecraft.world.level.ChunkPos pos) {
        scanner.enqueue(level, pos);
    }

    public int pendingScans() {
        return scanner.pending();
    }

    /** Colourises every loaded chunk again, for when a setting changes how tiles are drawn. */
    public int rescanLoadedChunks(Minecraft minecraft) {
        return scanner.rescanLoaded(minecraft);
    }

    // --- Tiles -------------------------------------------------------------------------------

    /** Handed a freshly colourised chunk by the scanner. */
    public void storeScannedTile(String tileDimension, ChunkTile tile, long localRevision) {
        boolean uploading = serverBacked && policy.uploadsEnabled() && config.uploadEnabled;
        store.acceptLocalTile(tileDimension, tile, localRevision, !uploading, encoded -> {
            if (uploading && takeUploadToken()) {
                ClientNetwork.send(new C2S.TileUpload(
                        tileDimension, tile.chunkX(), tile.chunkZ(), encoded.hash(), encoded.blob()));
            }
        });
    }

    /** Paces uploads to whatever budget the server advertised, so we are never the one throttled. */
    private synchronized boolean takeUploadToken() {
        int perSecond = Math.max(1, policy.maxUploadsPerSecond());
        long now = System.nanoTime();
        uploadTokens = Math.min(perSecond, uploadTokens + (now - uploadTokensStamp) * perSecond / 1_000_000_000.0);
        uploadTokensStamp = now;
        if (uploadTokens >= 1.0) {
            uploadTokens -= 1.0;
            return true;
        }
        return false;
    }

    // --- Packet handling ---------------------------------------------------------------------

    public void handle(S2C packet) {
        switch (packet) {
            case S2C.HelloOk helloOk -> {
                serverBacked = true;
                handshakeFailed = false;
                policy = helloOk.policy();
                status = "Shared map — " + policy.serverBrand();
                subscribedDimension = "";
                subscribedRegions = Set.of();
                store.forgetServerIndex();
                XetiusMap.LOGGER.info("Connected to a XetiusMap server: {}", policy.serverBrand());
            }
            case S2C.TileData data -> store.acceptRemoteTile(
                    data.dimension(), data.chunkX(), data.chunkZ(), data.revision(), data.hash(), data.blob());
            case S2C.TileAccepted accepted -> store.confirmUpload(
                    accepted.dimension(), accepted.chunkX(), accepted.chunkZ(), accepted.revision());
            case S2C.RegionIndex index -> {
                store.acceptRegionIndex(index.dimension(), index.regionX(), index.regionZ(),
                        index.slots(), index.revisions());
                requestMissing(index.dimension(), index.regionX(), index.regionZ());
            }
            case S2C.TileMissing ignored -> {
                // Nothing to do: the gap simply stays unexplored until somebody walks it.
            }
            case S2C.WaypointSync sync -> waypoints.replaceAll(sync.waypoints());
            case S2C.WaypointDelta delta -> waypoints.apply(delta);
            case S2C.EntityUpdate update -> entities.accept(update);
            case S2C.TeleportResult result -> status = result.message();
            case S2C.PaletteRequest request -> sendBlockPalette(request.reason());
            case S2C.Notice notice -> {
                status = notice.message();
                XetiusMap.LOGGER.info("Server notice: {}", notice.message());
            }
        }
    }

    /**
     * Answers the server's request for a colour table. Built on the client thread from the live
     * registries, then handed to the network layer, which fragments it as needed.
     */
    private void sendBlockPalette(String reason) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            try {
                var palette = dev.xetius.xetiusmap.client.map.PaletteBuilder.build(minecraft);
                if (palette.isEmpty()) {
                    return;
                }
                ClientNetwork.send(new C2S.BlockPaletteUpload(palette));
                XetiusMap.LOGGER.info("Sent the server a colour palette ({}): {} blocks.",
                        reason, palette.size());
            } catch (RuntimeException e) {
                XetiusMap.LOGGER.warn("Could not build a colour palette for the server", e);
            }
        });
    }

    private void requestMissing(String indexDimension, int regionX, int regionZ) {
        store.findMissingTiles(indexDimension, regionX, regionZ, missing -> {
            List<ChunkRef> batch = new ArrayList<>(Protocol.MAX_TILE_REQUESTS_PER_PACKET);
            for (int[] chunk : missing) {
                batch.add(new ChunkRef(chunk[0], chunk[1]));
                if (batch.size() == Protocol.MAX_TILE_REQUESTS_PER_PACKET) {
                    ClientNetwork.send(new C2S.TileRequest(indexDimension, List.copyOf(batch)));
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                ClientNetwork.send(new C2S.TileRequest(indexDimension, List.copyOf(batch)));
            }
        });
    }

    // --- Waypoint actions --------------------------------------------------------------------

    /** Creates a waypoint, on the server when there is one and locally otherwise. */
    public void createWaypoint(Waypoint waypoint) {
        if (serverBacked) {
            ClientNetwork.send(new C2S.WaypointCreate(waypoint));
        } else {
            waypoints.putLocal(waypoint.sanitised());
        }
    }

    public void updateWaypoint(Waypoint waypoint) {
        if (serverBacked) {
            ClientNetwork.send(new C2S.WaypointUpdate(waypoint));
        } else {
            waypoints.putLocal(waypoint.sanitised());
        }
    }

    public void deleteWaypoint(UUID id) {
        if (serverBacked) {
            ClientNetwork.send(new C2S.WaypointDelete(id));
        } else {
            waypoints.removeLocal(id);
        }
    }

    public boolean canTeleport() {
        return serverBacked && policy.teleportEnabled() && policy.teleportPermitted();
    }

    public void teleportTo(UUID waypointId) {
        if (canTeleport()) {
            ClientNetwork.send(new C2S.TeleportRequest(waypointId));
        } else {
            status = serverBacked
                    ? "You do not have permission to teleport to waypoints."
                    : "Teleporting needs the XetiusMap server plugin.";
        }
    }

    /** Whether the server allows this player to teleport to any point on the map. */
    public boolean canTeleportAnywhere() {
        return serverBacked && policy.teleportEnabled() && policy.teleportAnywherePermitted();
    }

    /** Asks to be moved to a point picked off the map. The server chooses a safe height. */
    public void teleportTo(String targetDimension, int x, int z) {
        if (canTeleportAnywhere()) {
            ClientNetwork.send(new C2S.TeleportTo(targetDimension, x, z));
        } else {
            status = serverBacked
                    ? "You do not have permission to teleport to map locations."
                    : "Teleporting needs the XetiusMap server plugin.";
        }
    }

    public void setHidden(boolean hidden) {
        config.hiddenFromOthers = hidden;
        if (serverBacked) {
            ClientNetwork.send(new C2S.SetHidden(hidden));
        }
    }

    /** Tells the server which area the world map is showing, widening the entity radar to match. */
    public void sendEntityView(boolean active, String viewedDimension,
                               int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        if (serverBacked) {
            ClientNetwork.send(new C2S.EntityView(
                    active, viewedDimension, minChunkX, minChunkZ, maxChunkX, maxChunkZ));
        }
    }

    @Override
    public void close() {
        store.close();
        io.shutdown();
    }
}
