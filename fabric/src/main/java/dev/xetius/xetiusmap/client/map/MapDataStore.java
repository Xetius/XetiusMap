package dev.xetius.xetiusmap.client.map;

import dev.xetius.xetiusmap.client.XetiusMap;
import dev.xetius.xetiusmap.common.store.RegionFile;
import dev.xetius.xetiusmap.common.store.TileStore;
import dev.xetius.xetiusmap.common.tile.ChunkTile;
import dev.xetius.xetiusmap.common.tile.TileCodec;
import dev.xetius.xetiusmap.common.util.MapCoords;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * The client's view of the map: an on-disk cache in exactly the same format the server uses, plus
 * the rasters the renderers draw from.
 *
 * <p>Threading is deliberately one-way. Disk reads, decoding and encoding happen on the I/O
 * executor; finished work is posted to queues that the render thread drains. The raster caches
 * themselves are only ever touched from the render thread, which is also the only thread allowed to
 * free a GPU texture.
 */
public final class MapDataStore implements AutoCloseable {

    // A 1920x1080 world map at half a pixel per block needs about 41 detail regions, so a cap of
    // 32 guaranteed thrashing: regions still on screen were evicted, redrawn as unexplored, and
    // immediately reloaded. Eviction now refuses to touch anything drawn recently, and the caps
    // leave headroom above what a screen can actually demand.
    private static final int MAX_DETAIL_RASTERS = 64;
    private static final int MAX_COARSE_RASTERS = 320;
    /** Only 4 KiB each, and zooming right out needs thousands of them at once. */
    private static final int MAX_OVERVIEW_RASTERS = 4096;

    /** Rasters drawn within this many frames are never evicted, however full the cache is. */
    private static final long IN_USE_FRAMES = 2;

    /**
     * How long an evicted raster's GPU texture is kept alive before being freed.
     *
     * <p>26.2 draws the GUI in two phases: {@code blit} resolves a texture during extraction and
     * stores its view in the render state, and the draw happens afterwards. Freeing a texture
     * between those two points leaves the render state pointing at released GPU memory, and the
     * driver happily draws whatever has since been allocated there — which, just after changing
     * dimension, is a freshly created texture for the dimension you have arrived in. That is how
     * whole regions of one world appeared on the map of another.
     */
    private static final long DISPOSAL_DELAY_FRAMES = 3;

    /** Absolute ceiling, in case a pathological view somehow keeps everything in use. */
    private static final int HARD_CAP_MULTIPLIER = 2;

    /** Uploads awaiting acknowledgement. Generous, but bounded so a broken server cannot leak. */
    private static final int MAX_PENDING_UPLOADS = 1024;

    private final TileStore disk;
    private final ExecutorService io;

    private final Map<RegionRaster.Key, RegionRaster> detailCache = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<RegionRaster.Key, RegionRaster> coarseCache = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<RegionRaster.Key, RegionRaster> overviewCache = new LinkedHashMap<>(16, 0.75F, true);

    /** Region coordinates known to hold data, per dimension, so far zoom need not scan empty space. */
    private final Map<String, Set<Long>> knownRegions = new ConcurrentHashMap<>();
    private final Set<String> knownRegionScans = ConcurrentHashMap.newKeySet();
    private final Set<RegionRaster.Key> loading = ConcurrentHashMap.newKeySet();

    private final Queue<RegionRaster> loaded = new ConcurrentLinkedQueue<>();

    /** Evicted rasters awaiting disposal, oldest first. */
    private final Deque<Disposal> pendingDisposal = new ArrayDeque<>();
    private final Queue<PaintJob> paintQueue = new ConcurrentLinkedQueue<>();

    /** Revisions the server has told us about, so we only ask for tiles we actually lack. */
    private final Map<RegionKey, long[]> serverRevisions = new ConcurrentHashMap<>();

    /** Tiles uploaded but not yet acknowledged, keyed by dimension and chunk. */
    private final Map<String, Encoded> pendingUploads = new ConcurrentHashMap<>();

    private volatile boolean closed;
    private int generation;
    private long frame;

    public MapDataStore(Path root, ExecutorService io) {
        this.disk = new TileStore(root, 32);
        this.io = io;
    }

    public Path root() {
        return disk.root();
    }

    /**
     * The raster for a region, or {@code null} while it loads.
     *
     * <p>Render thread only.
     */
    public RegionRaster raster(String dimension, int regionX, int regionZ, int blocksPerPixel) {
        RegionRaster.Key key = new RegionRaster.Key(dimension, regionX, regionZ, blocksPerPixel);
        Map<RegionRaster.Key, RegionRaster> cache = cacheFor(blocksPerPixel);
        RegionRaster existing = cache.get(key);
        if (existing != null) {
            existing.markUsed(frame);
            return existing;
        }
        scheduleLoad(key);
        return null;
    }

    private Map<RegionRaster.Key, RegionRaster> cacheFor(int blocksPerPixel) {
        if (blocksPerPixel == RegionRaster.DETAIL_BLOCKS_PER_PIXEL) {
            return detailCache;
        }
        return blocksPerPixel == RegionRaster.COARSE_BLOCKS_PER_PIXEL ? coarseCache : overviewCache;
    }

    private static int limitFor(int blocksPerPixel) {
        if (blocksPerPixel == RegionRaster.DETAIL_BLOCKS_PER_PIXEL) {
            return MAX_DETAIL_RASTERS;
        }
        return blocksPerPixel == RegionRaster.COARSE_BLOCKS_PER_PIXEL ? MAX_COARSE_RASTERS : MAX_OVERVIEW_RASTERS;
    }

    /**
     * The regions that actually hold data, which is what makes zooming right out affordable: a
     * screen may span tens of thousands of regions, but only the explored ones are worth visiting.
     * Returns an empty set on first call and fills in from disk in the background.
     */
    public Set<Long> knownRegions(String dimension) {
        Set<Long> known = knownRegions.get(dimension);
        if (known == null && knownRegionScans.add(dimension)) {
            io.execute(() -> {
                Set<Long> found = ConcurrentHashMap.newKeySet();
                for (long[] region : disk.listRegions(dimension)) {
                    found.add(MapCoords.key((int) region[0], (int) region[1]));
                }
                knownRegions.put(dimension, found);
            });
        }
        return known == null ? Set.of() : known;
    }

    /** Keeps the known-region set current as new areas arrive. */
    private void noteRegion(String dimension, int chunkX, int chunkZ) {
        Set<Long> known = knownRegions.get(dimension);
        if (known != null) {
            known.add(MapCoords.key(MapCoords.chunkToRegion(chunkX), MapCoords.chunkToRegion(chunkZ)));
        }
    }

    private void scheduleLoad(RegionRaster.Key key) {
        if (closed || !loading.add(key)) {
            return;
        }
        io.execute(() -> {
            try {
                RegionRaster raster = new RegionRaster(key.dimension(), key.regionX(), key.regionZ(),
                        key.blocksPerPixel());
                long[] revisions = disk.regionRevisions(key.dimension(), key.regionX(), key.regionZ());
                if (revisions != null) {
                    for (int slot = 0; slot < revisions.length; slot++) {
                        if (revisions[slot] == 0) {
                            continue;
                        }
                        int chunkX = MapCoords.tileIndexToChunkX(key.regionX(), slot);
                        int chunkZ = MapCoords.tileIndexToChunkZ(key.regionZ(), slot);
                        byte[] blob = disk.read(key.dimension(), chunkX, chunkZ);
                        if (blob == null) {
                            continue;
                        }
                        raster.paint(ChunkTile.decode(chunkX, chunkZ, TileCodec.decompress(blob)), revisions[slot]);
                    }
                }
                loaded.add(raster);
            } catch (IOException | RuntimeException e) {
                XetiusMap.LOGGER.warn("Could not load map region {},{}: {}",
                        key.regionX(), key.regionZ(), e.toString());
                loading.remove(key);
            }
        });
    }

    /**
     * Moves finished background work into the caches. Called once per frame from the render thread,
     * which is the only place a raster may be created or freed.
     */
    public void pump() {
        frame++;
        disposeExpired();
        RegionRaster raster;
        while ((raster = loaded.poll()) != null) {
            RegionRaster.Key key = raster.key();
            loading.remove(key);
            Map<RegionRaster.Key, RegionRaster> cache = cacheFor(key.blocksPerPixel());
            RegionRaster previous = cache.put(key, raster);
            if (previous != null) {
                pendingDisposal.addLast(new Disposal(previous, frame));
            }
            raster.markUsed(frame);
            generation++;
            evict(cache, limitFor(key.blocksPerPixel()));
        }

        PaintJob job;
        while ((job = paintQueue.poll()) != null) {
            paintIntoLoaded(job);
        }
    }

    /**
     * Bumped whenever pixels change. Renderers compare it against the value they last drew with, so
     * a static view costs nothing to keep on screen.
     */
    public int generation() {
        return generation;
    }

    private void paintIntoLoaded(PaintJob job) {
        generation++;
        int regionX = MapCoords.chunkToRegion(job.tile.chunkX());
        int regionZ = MapCoords.chunkToRegion(job.tile.chunkZ());
        for (int blocksPerPixel : new int[]{RegionRaster.DETAIL_BLOCKS_PER_PIXEL,
                RegionRaster.COARSE_BLOCKS_PER_PIXEL, RegionRaster.OVERVIEW_BLOCKS_PER_PIXEL}) {
            RegionRaster raster = cacheFor(blocksPerPixel)
                    .get(new RegionRaster.Key(job.dimension, regionX, regionZ, blocksPerPixel));
            if (raster != null) {
                raster.paint(job.tile, job.revision);
            }
        }
    }

    /**
     * Trims the cache back to its limit, in least-recently-used order but skipping anything drawn
     * in the last couple of frames. Evicting a region that is still on screen is what caused
     * visible chunks to flicker: it would be dropped, redrawn as unexplored, reloaded, and in
     * turn evict its neighbours.
     */
    private void evict(Map<RegionRaster.Key, RegionRaster> cache, int limit) {
        int hardCap = limit * HARD_CAP_MULTIPLIER;
        Iterator<Map.Entry<RegionRaster.Key, RegionRaster>> it = cache.entrySet().iterator();
        while (cache.size() > limit && it.hasNext()) {
            Map.Entry<RegionRaster.Key, RegionRaster> eldest = it.next();
            boolean inUse = frame - eldest.getValue().lastUsedFrame() < IN_USE_FRAMES;
            if (inUse && cache.size() <= hardCap) {
                continue;
            }
            it.remove();
            // Deliberately not closed here: see DISPOSAL_DELAY_FRAMES.
            pendingDisposal.addLast(new Disposal(eldest.getValue(), frame));
        }
    }

    /** Frees textures whose last possible draw is now safely behind us. */
    private void disposeExpired() {
        while (!pendingDisposal.isEmpty()
                && frame - pendingDisposal.peekFirst().queuedFrame() >= DISPOSAL_DELAY_FRAMES) {
            pendingDisposal.removeFirst().raster().close();
        }
    }

    private record Disposal(RegionRaster raster, long queuedFrame) {
    }

    /** Stores a tile that arrived from the server and shows it. */
    public void acceptRemoteTile(String dimension, int chunkX, int chunkZ, long revision, long hash, byte[] blob) {
        if (closed) {
            return;
        }
        io.execute(() -> {
            try {
                ChunkTile tile = ChunkTile.decode(chunkX, chunkZ, TileCodec.decompress(blob));
                disk.write(dimension, chunkX, chunkZ, blob, revision, hash);
                noteRegion(dimension, chunkX, chunkZ);
                paintQueue.add(new PaintJob(dimension, tile, revision));
            } catch (IOException | RuntimeException e) {
                XetiusMap.LOGGER.warn("Rejected a map tile from the server at {},{}: {}",
                        chunkX, chunkZ, e.toString());
            }
        });
    }

    /**
     * Draws a tile without keeping it: the cave view.
     *
     * <p>It reaches the rasters already on screen and nothing else, so the surface map is untouched
     * on disk and comes back as soon as those chunks are drawn again — which the scanner arranges
     * the moment the player is above ground. A region evicted and reloaded mid-cave reappears as the
     * surface for the same reason, until the chunks around the player are scanned again.
     */
    public void showTile(String dimension, ChunkTile tile, long revision) {
        if (closed) {
            return;
        }
        paintQueue.add(new PaintJob(dimension, tile, revision));
    }

    /**
     * Stores a tile this client just rendered.
     *
     * <p>Against a server the tile is drawn straight away but held back from disk until the server
     * confirms which revision it was stored under — see {@link #confirmUpload}. Writing it
     * immediately with a made-up revision would make the cache disagree with the server and cause
     * the client to re-download its own work on the next login. In local mode there is no server to
     * ask, so the supplied revision is used directly.
     *
     * @param persistNow  true in local mode, false when an upload acknowledgement is expected
     * @param onEncoded   receives the compressed blob if the tile is new or changed; never called
     *                    for a chunk that is already stored unchanged
     */
    public void acceptLocalTile(String dimension, ChunkTile tile, long revision,
                                boolean persistNow, Consumer<Encoded> onEncoded) {
        if (closed) {
            return;
        }
        io.execute(() -> {
            try {
                byte[] body = tile.encode();
                long hash = TileCodec.hash(body);

                RegionFile.TileMeta existing = disk.meta(dimension, tile.chunkX(), tile.chunkZ());
                if (existing != null && existing.hash() == hash) {
                    return;
                }

                byte[] blob = TileCodec.compress(body);
                paintQueue.add(new PaintJob(dimension, tile, revision));

                if (persistNow) {
                    disk.write(dimension, tile.chunkX(), tile.chunkZ(), blob, revision, hash);
                } else {
                    rememberPending(dimension, tile.chunkX(), tile.chunkZ(), new Encoded(hash, blob));
                }
                onEncoded.accept(new Encoded(hash, blob));
            } catch (IOException | RuntimeException e) {
                XetiusMap.LOGGER.warn("Could not store a locally rendered tile at {},{}: {}",
                        tile.chunkX(), tile.chunkZ(), e.toString());
            }
        });
    }

    /** Writes an uploaded tile to the cache now that the server has given it a revision. */
    public void confirmUpload(String dimension, int chunkX, int chunkZ, long revision) {
        if (closed) {
            return;
        }
        io.execute(() -> {
            Encoded encoded = pendingUploads.remove(pendingKey(dimension, chunkX, chunkZ));
            if (encoded == null) {
                return;
            }
            try {
                disk.write(dimension, chunkX, chunkZ, encoded.blob(), revision, encoded.hash());
            } catch (IOException e) {
                XetiusMap.LOGGER.warn("Could not cache the acknowledged tile at {},{}: {}",
                        chunkX, chunkZ, e.toString());
            }
        });
    }

    private void rememberPending(String dimension, int chunkX, int chunkZ, Encoded encoded) {
        // A tile whose acknowledgement never arrives is simply dropped; it will be rendered again.
        if (pendingUploads.size() > MAX_PENDING_UPLOADS) {
            pendingUploads.clear();
        }
        pendingUploads.put(pendingKey(dimension, chunkX, chunkZ), encoded);
    }

    private static String pendingKey(String dimension, int chunkX, int chunkZ) {
        return dimension + '@' + chunkX + ',' + chunkZ;
    }

    /** Remembers what the server says it holds for a region. */
    public void acceptRegionIndex(String dimension, int regionX, int regionZ, int[] slots, long[] revisions) {
        long[] table = new long[MapCoords.TILES_PER_REGION];
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] >= 0 && slots[i] < table.length) {
                table[slots[i]] = revisions[i];
            }
        }
        serverRevisions.put(new RegionKey(dimension, regionX, regionZ), table);
    }

    /**
     * Chunks in a region the server has but we do not, newest first is unnecessary — order does not
     * matter because the caller batches them into one request.
     *
     * <p>Runs on the I/O executor because it reads slot metadata from disk.
     */
    public void findMissingTiles(String dimension, int regionX, int regionZ, Consumer<List<int[]>> onResult) {
        long[] server = serverRevisions.get(new RegionKey(dimension, regionX, regionZ));
        if (server == null || closed) {
            return;
        }
        io.execute(() -> {
            List<int[]> missing = new ArrayList<>();
            try {
                long[] local = disk.regionRevisions(dimension, regionX, regionZ);
                for (int slot = 0; slot < server.length; slot++) {
                    if (server[slot] == 0) {
                        continue;
                    }
                    long have = local == null ? 0 : local[slot];
                    if (have < server[slot]) {
                        missing.add(new int[]{
                                MapCoords.tileIndexToChunkX(regionX, slot),
                                MapCoords.tileIndexToChunkZ(regionZ, slot)
                        });
                    }
                }
            } catch (IOException e) {
                XetiusMap.LOGGER.warn("Could not compare region {},{} against the server: {}",
                        regionX, regionZ, e.toString());
            }
            if (!missing.isEmpty()) {
                onResult.accept(missing);
            }
        });
    }

    /** Drops the remembered server index, so the next subscription re-syncs from scratch. */
    public void forgetServerIndex() {
        serverRevisions.clear();
    }

    /** Identity of a region for the server-index table; a plain hash would risk collisions. */
    private record RegionKey(String dimension, int regionX, int regionZ) {
    }

    @Override
    public void close() {
        closed = true;
        pendingDisposal.forEach(pending -> pending.raster().close());
        pendingDisposal.clear();
        detailCache.values().forEach(RegionRaster::close);
        coarseCache.values().forEach(RegionRaster::close);
        overviewCache.values().forEach(RegionRaster::close);
        detailCache.clear();
        coarseCache.clear();
        overviewCache.clear();
        loaded.clear();
        paintQueue.clear();
        try {
            disk.close();
        } catch (IOException e) {
            XetiusMap.LOGGER.warn("Could not close the local map cache: {}", e.toString());
        }
    }

    /** A compressed tile ready to be offered to the server. */
    public record Encoded(long hash, byte[] blob) {
    }

    private record PaintJob(String dimension, ChunkTile tile, long revision) {
    }
}
