package dev.xetius.xetiusmap.paper.service;

import dev.xetius.xetiusmap.common.net.C2S;
import dev.xetius.xetiusmap.common.net.Protocol;
import dev.xetius.xetiusmap.common.net.ProtocolException;
import dev.xetius.xetiusmap.common.net.S2C;
import dev.xetius.xetiusmap.common.store.RegionFile;
import dev.xetius.xetiusmap.common.store.TileStore;
import dev.xetius.xetiusmap.common.tile.ChunkTile;
import dev.xetius.xetiusmap.common.tile.TileCodec;
import dev.xetius.xetiusmap.common.util.ChunkRef;
import dev.xetius.xetiusmap.common.util.MapCoords;
import dev.xetius.xetiusmap.common.util.RegionRef;
import dev.xetius.xetiusmap.paper.PluginConfig;
import dev.xetius.xetiusmap.paper.net.MessageBus;
import dev.xetius.xetiusmap.paper.session.PlayerSession;
import dev.xetius.xetiusmap.paper.util.RevisionCounter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The shared map: accepts tiles rendered by clients, stores them, and hands them out to everyone
 * else. This is what makes discovery collective — a chunk any player has walked through is
 * afterwards visible to every player, in every dimension the server allows.
 *
 * <p>All store access runs on a single-threaded executor. Nothing here touches the main thread
 * except through {@link MessageBus}, whose outgoing queue is thread-safe by design.
 */
public final class TileService {

    private final Logger logger;
    private final Supplier<PluginConfig> config;
    private final MessageBus bus;
    private final TileStore store;
    private final RevisionCounter revisions;
    private final ExecutorService io;

    private final AtomicLong acceptedUploads = new AtomicLong();
    private final AtomicLong duplicateUploads = new AtomicLong();
    private final AtomicLong rejectedUploads = new AtomicLong();
    private final AtomicLong tilesServed = new AtomicLong();

    public TileService(Logger logger,
                       Supplier<PluginConfig> config,
                       MessageBus bus,
                       TileStore store,
                       RevisionCounter revisions,
                       ExecutorService io) {
        this.logger = logger;
        this.config = config;
        this.bus = bus;
        this.store = store;
        this.revisions = revisions;
        this.io = io;
    }

    /**
     * Handles a tile offered by a client.
     *
     * @param actualDimension the dimension the player is genuinely in, resolved on the main thread;
     *                        an upload claiming any other dimension is dropped, so a client cannot
     *                        scribble on a world it is not in
     */
    public void handleUpload(PlayerSession session, C2S.TileUpload upload, String actualDimension) {
        PluginConfig cfg = config.get();
        if (!cfg.uploadsEnabled() || !cfg.allowsDimension(upload.dimension())) {
            return;
        }
        if (!upload.dimension().equals(actualDimension)) {
            session.tilesRejected().incrementAndGet();
            rejectedUploads.incrementAndGet();
            return;
        }
        if (!session.tryUpload()) {
            session.tilesRejected().incrementAndGet();
            rejectedUploads.incrementAndGet();
            return;
        }

        io.execute(() -> {
            try {
                byte[] body;
                try {
                    body = TileCodec.decompress(upload.blob());
                    // Decoding proves the tile is structurally sound before it enters the store;
                    // otherwise one bad client could poison a chunk for everybody.
                    ChunkTile.decode(upload.chunkX(), upload.chunkZ(), body);
                } catch (ProtocolException e) {
                    session.tilesRejected().incrementAndGet();
                    rejectedUploads.incrementAndGet();
                    return;
                }

                long hash = TileCodec.hash(body);
                if (hash != upload.hash()) {
                    session.tilesRejected().incrementAndGet();
                    rejectedUploads.incrementAndGet();
                    return;
                }

                RegionFile.TileMeta existing = store.meta(upload.dimension(), upload.chunkX(), upload.chunkZ());
                if (existing != null && existing.hash() == hash) {
                    // Identical to what is already stored: no revision bump, no broadcast.
                    duplicateUploads.incrementAndGet();
                    return;
                }

                long revision = revisions.next();
                store.write(upload.dimension(), upload.chunkX(), upload.chunkZ(), upload.blob(), revision, hash);
                session.tilesUploaded().incrementAndGet();
                acceptedUploads.incrementAndGet();

                // Tell the uploader which revision it got, so its local cache matches ours.
                bus.send(session, new S2C.TileAccepted(
                        upload.dimension(), upload.chunkX(), upload.chunkZ(), revision));

                broadcast(session, new S2C.TileData(
                        upload.dimension(), upload.chunkX(), upload.chunkZ(), revision, hash, upload.blob()));
            } catch (IOException e) {
                logger.log(Level.WARNING, e, () -> "Failed to store a map tile from " + session.playerName());
            }
        });
    }

    /** Pushes a freshly stored tile to every other client currently watching that region. */
    private void broadcast(PlayerSession source, S2C.TileData data) {
        long regionKey = RegionRef.ofChunk(data.chunkX(), data.chunkZ()).key();
        for (PlayerSession other : bus.activeSessions()) {
            if (other == source || !other.watches(data.dimension(), regionKey)) {
                continue;
            }
            bus.send(other, data);
            other.tilesSent().incrementAndGet();
            tilesServed.incrementAndGet();
        }
    }

    /**
     * Queues the requested tiles. They are sent by {@link #drainRequests()} at the configured rate
     * rather than all at once.
     *
     * <p>This used to serve the batch immediately and reject it outright when it exceeded the
     * per-second budget — which a full-sized request always did, because a client may ask for more
     * chunks in one packet than the budget allows per second. The rejected tiles were never
     * re-requested, so the map kept permanent holes. Queueing means a large request is slow, never
     * lost.
     */
    public void handleRequest(PlayerSession session, C2S.TileRequest request) {
        PluginConfig cfg = config.get();
        if (!cfg.allowsDimension(request.dimension()) || request.chunks().isEmpty()) {
            return;
        }
        for (ChunkRef chunk : request.chunks()) {
            if (!session.queueTile(
                    new PlayerSession.PendingTile(request.dimension(), chunk.x(), chunk.z()),
                    MAX_PENDING_TILES)) {
                break;
            }
        }
    }

    /** Main thread, once per tick: hands each client its share of the tile budget. */
    public void drainRequests() {
        int perTick = Math.max(1, config.get().maxTileRequestsPerSecond() / 20);
        for (PlayerSession session : bus.activeSessions()) {
            if (session.pendingTileCount() == 0) {
                continue;
            }
            List<PlayerSession.PendingTile> batch = new ArrayList<>(perTick);
            for (int i = 0; i < perTick; i++) {
                PlayerSession.PendingTile tile = session.pollTile();
                if (tile == null) {
                    break;
                }
                batch.add(tile);
            }
            if (!batch.isEmpty()) {
                serve(session, batch);
            }
        }
    }

    private void serve(PlayerSession session, List<PlayerSession.PendingTile> batch) {
        io.execute(() -> {
            for (PlayerSession.PendingTile tile : batch) {
                try {
                    RegionFile.TileMeta meta = store.meta(tile.dimension(), tile.chunkX(), tile.chunkZ());
                    if (meta == null) {
                        continue;
                    }
                    byte[] blob = store.read(tile.dimension(), tile.chunkX(), tile.chunkZ());
                    if (blob == null) {
                        continue;
                    }
                    bus.send(session, new S2C.TileData(
                            tile.dimension(), tile.chunkX(), tile.chunkZ(), meta.revision(), meta.hash(), blob));
                    session.tilesSent().incrementAndGet();
                    tilesServed.incrementAndGet();
                } catch (IOException e) {
                    logger.log(Level.WARNING, e, () -> "Failed to read tile "
                            + tile.chunkX() + "," + tile.chunkZ() + " of " + tile.dimension());
                }
            }
        });
    }


    /**
     * Records what the client is watching and replies with an index per region, so the client can
     * work out exactly which tiles it is missing in one round trip instead of 1024 probes.
     */
    public void handleSubscribe(PlayerSession session, C2S.RegionSubscribe subscribe) {
        PluginConfig cfg = config.get();
        if (!cfg.allowsDimension(subscribe.dimension())) {
            session.replaceSubscriptions(subscribe.dimension(), Set.of());
            return;
        }

        Set<Long> keys = new HashSet<>();
        for (RegionRef region : subscribe.regions()) {
            keys.add(region.key());
        }
        session.replaceSubscriptions(subscribe.dimension(), keys);

        List<RegionRef> regions = List.copyOf(subscribe.regions());
        io.execute(() -> {
            for (RegionRef region : regions) {
                try {
                    long[] all = store.regionRevisions(subscribe.dimension(), region.x(), region.z());
                    if (all == null) {
                        bus.send(session, new S2C.RegionIndex(
                                subscribe.dimension(), region.x(), region.z(), new int[0], new long[0]));
                        continue;
                    }
                    int populated = 0;
                    for (long revision : all) {
                        if (revision != 0) {
                            populated++;
                        }
                    }
                    int[] slots = new int[populated];
                    long[] revisionList = new long[populated];
                    int at = 0;
                    for (int slot = 0; slot < all.length; slot++) {
                        if (all[slot] != 0) {
                            slots[at] = slot;
                            revisionList[at] = all[slot];
                            at++;
                        }
                    }
                    bus.send(session, new S2C.RegionIndex(
                            subscribe.dimension(), region.x(), region.z(), slots, revisionList));
                } catch (IOException e) {
                    logger.log(Level.WARNING, e,
                            () -> "Failed to index region " + region.x() + "," + region.z());
                }
            }
        });
    }

    /** Wipes a dimension's stored map. Runs on the store thread; the callback fires there too. */
    public void purge(String dimension, java.util.function.IntConsumer onDone) {
        io.execute(() -> {
            try {
                onDone.accept(store.purgeDimension(dimension));
            } catch (IOException e) {
                logger.log(Level.WARNING, e, () -> "Failed to purge map data for " + dimension);
                onDone.accept(-1);
            }
        });
    }

    public Stats stats() {
        return new Stats(
                acceptedUploads.get(),
                duplicateUploads.get(),
                rejectedUploads.get(),
                tilesServed.get(),
                revisions.current(),
                store.dimensionFolders().size()
        );
    }

    /** Counts a server owner can read off {@code /xmap stats} to tune the rate limits. */
    public record Stats(long accepted, long duplicates, long rejected, long served, long revision, int dimensions) {
    }

    /** Cap on a client's outstanding tile backlog, so a flood of requests cannot grow without end. */
    private static final int MAX_PENDING_TILES = 16_384;

    /** Total tiles stored for a dimension, for {@code /xmap stats}. Runs on the store thread. */
    public void countTiles(String dimension, java.util.function.LongConsumer onDone) {
        io.execute(() -> {
            long count = 0;
            for (long[] region : store.listRegions(dimension)) {
                try {
                    long[] revisions = store.regionRevisions(dimension, (int) region[0], (int) region[1]);
                    if (revisions == null) {
                        continue;
                    }
                    for (long revision : revisions) {
                        if (revision != 0) {
                            count++;
                        }
                    }
                } catch (IOException e) {
                    logger.log(Level.FINE, e, () -> "Could not count tiles in a region of " + dimension);
                }
            }
            onDone.accept(count);
        });
    }

    /** Region count on disk, used by {@code /xmap stats}. */
    public int regionCount(String dimension) {
        return store.listRegions(dimension).size();
    }

    public List<String> storedDimensionFolders() {
        return store.dimensionFolders();
    }

    /** Exposed so the plugin can flush and close the store during shutdown. */
    public TileStore store() {
        return store;
    }

    /** Chunk radius a client is expected to subscribe to; used only for logging sanity checks. */
    public static int regionsForRadius(int chunkRadius) {
        return (2 * (chunkRadius / MapCoords.REGION_CHUNKS) + 2) * (2 * (chunkRadius / MapCoords.REGION_CHUNKS) + 2);
    }
}
