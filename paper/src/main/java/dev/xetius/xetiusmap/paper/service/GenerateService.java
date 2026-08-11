package dev.xetius.xetiusmap.paper.service;

import dev.xetius.xetiusmap.common.tile.ChunkTile;
import dev.xetius.xetiusmap.paper.PluginConfig;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Backfills the map from chunks the world has already generated, without anyone having to walk
 * there.
 *
 * <p>Chunks are loaded a few per tick with generation disabled, so nothing new is created and the
 * server stays responsive; each one is snapshotted on the main thread and rendered on the store
 * thread. Chunks that already have a tile are skipped, because a tile somebody actually walked past
 * is rendered from live client state and is the better of the two.
 */
public final class GenerateService {

    /** Rows of chunks are swept north to south so relief shading carries across chunk borders. */
    private final Map<Integer, int[]> lastRowHeights = new HashMap<>();

    private final Plugin plugin;
    private final Supplier<PluginConfig> config;
    private final TileService tiles;
    private final PaletteStore palettes;

    private Job job;

    public GenerateService(Plugin plugin, Supplier<PluginConfig> config, TileService tiles, PaletteStore palettes) {
        this.plugin = plugin;
        this.config = config;
        this.tiles = tiles;
        this.palettes = palettes;
    }

    /** Progress of a running sweep. */
    public record Progress(String dimension, int done, int total, int rendered, int skipped) {
        public int percent() {
            return total <= 0 ? 100 : (int) (100L * done / total);
        }
    }

    private static final class Job {
        final World world;
        final String dimension;
        final Deque<RegionScanner.ChunkRef> queue;
        final boolean force;
        final int total;
        int done;
        int inFlight;
        int rendered;
        int skipped;
        boolean cancelled;

        Job(World world, String dimension, Deque<RegionScanner.ChunkRef> queue, boolean force) {
            this.world = world;
            this.dimension = dimension;
            this.queue = queue;
            this.force = force;
            this.total = queue.size();
        }
    }

    public boolean isRunning() {
        return job != null && !job.cancelled;
    }

    public Progress progress() {
        Job current = job;
        return current == null ? null
                : new Progress(current.dimension, current.done, current.total, current.rendered, current.skipped);
    }

    public void cancel() {
        if (job != null) {
            job.cancelled = true;
            job = null;
            lastRowHeights.clear();
        }
    }

    /**
     * Starts a sweep of one world.
     *
     * @param onReady called on the main thread once the chunk list is known, or with a message when
     *                the sweep cannot start
     */
    public void start(World world, boolean force, Consumer<String> onReady) {
        if (isRunning()) {
            onReady.accept("A map generation is already running. Use /xmap generate stop first.");
            return;
        }
        if (palettes.isEmpty()) {
            onReady.accept("No colour palette yet. A player with the XetiusMap mod must connect once "
                    + "so the server can learn what colour each block is.");
            return;
        }

        String dimension = world.getKey().toString();
        if (!config.get().allowsDimension(dimension)) {
            onReady.accept("The dimension " + dimension + " is not enabled in the config.");
            return;
        }

        // Reading region headers is file I/O, so it happens off the main thread.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<RegionScanner.ChunkRef> chunks;
            try {
                chunks = RegionScanner.generatedChunks(world);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not scan region files for " + dimension, e);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> onReady.accept("Could not read the region files; see the server log."));
                return;
            }

            // North to south within each column, so a chunk's northern neighbour is rendered first.
            List<RegionScanner.ChunkRef> ordered = chunks.stream()
                    .sorted((a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()))
                    .toList();

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (ordered.isEmpty()) {
                    onReady.accept("No generated chunks found for " + dimension + ".");
                    return;
                }
                job = new Job(world, dimension, new ArrayDeque<>(ordered), force);
                lastRowHeights.clear();
                onReady.accept("Generating map data for " + ordered.size() + " chunk(s) in " + dimension + ".");
            });
        });
    }

    /** Main thread, once per tick. */
    public void tick() {
        Job current = job;
        if (current == null || current.cancelled) {
            return;
        }

        // Keep a few loads in flight so disk latency does not idle the sweep, but not so many that
        // a burst of chunk loads is felt by players.
        int budget = config.get().generateChunksPerTick();
        int maxInFlight = budget * 4;
        while (current.inFlight < maxInFlight) {
            RegionScanner.ChunkRef chunk = current.queue.poll();
            if (chunk == null) {
                break;
            }
            request(current, chunk);
        }

        if (current.queue.isEmpty() && current.inFlight == 0) {
            finish(current);
        }
    }

    private void request(Job current, RegionScanner.ChunkRef chunk) {
        if (!current.force && tiles.hasTile(current.dimension, chunk.x(), chunk.z())) {
            current.done++;
            current.skipped++;
            return;
        }

        boolean wasLoaded = current.world.isChunkLoaded(chunk.x(), chunk.z());
        current.inFlight++;
        // gen=false is the whole point: a chunk that was never finished comes back null rather
        // than being generated on the spot. Asking isChunkGenerated first was wrong — it reports
        // false for plenty of chunks that are perfectly readable.
        current.world.getChunkAtAsync(chunk.x(), chunk.z(), false, loaded -> {
            current.inFlight--;
            current.done++;
            if (current.cancelled) {
                return;
            }
            if (loaded == null) {
                current.skipped++;
                return;
            }
            try {
                render(current, chunk, loaded.getChunkSnapshot(true, true, false));
            } catch (RuntimeException e) {
                current.skipped++;
            } finally {
                if (!wasLoaded) {
                    // Leave the server's chunk cache as we found it.
                    current.world.unloadChunkRequest(chunk.x(), chunk.z());
                }
            }
        });
    }

    private void render(Job current, RegionScanner.ChunkRef chunk, ChunkSnapshot snapshot) {
        int minY = current.world.getMinHeight();
        // Completions can arrive slightly out of order, so the row to the north may not be known
        // yet; the renderer falls back to flat shading for that row when it is missing.
        int[] north = lastRowHeights.get(chunk.x());
        int[] out = new int[16];

        ChunkTile tile = new SurfaceRenderer(palettes.palette()).render(snapshot, minY, north, out);
        lastRowHeights.put(chunk.x(), out);
        if (tile == null) {
            current.skipped++;
            return;
        }
        current.rendered++;
        tiles.storeGenerated(current.dimension, tile);
    }

    private void finish(Job current) {
        job = null;
        lastRowHeights.clear();
        plugin.getLogger().info("Map generation for " + current.dimension + " finished: "
                + current.rendered + " rendered, " + current.skipped + " skipped.");
    }
}
