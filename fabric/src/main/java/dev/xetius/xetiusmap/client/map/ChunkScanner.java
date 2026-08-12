package dev.xetius.xetiusmap.client.map;

import dev.xetius.xetiusmap.client.MapClient;
import dev.xetius.xetiusmap.client.config.ClientConfig;
import dev.xetius.xetiusmap.common.tile.ChunkTile;
import dev.xetius.xetiusmap.common.util.MapCoords;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides which chunks get turned into map tiles, and when.
 *
 * <p>Chunks are queued as they load, and the ring around the player is re-queued periodically to
 * pick up building and terraforming. Re-scanning is cheap to get wrong in the safe direction: a
 * chunk whose appearance has not changed hashes identically and is dropped before it reaches disk
 * or the network, so a sweep over unchanged terrain costs nothing but the colourising.
 *
 * <p>Colourising reads live chunk state and therefore runs on the client thread, limited to a
 * couple of chunks per tick. Encoding, compression and storage happen on the I/O executor.
 */
public final class ChunkScanner {

    /** Chunks colourised per client tick. Two is imperceptible and keeps up with normal travel. */
    private static final int CHUNKS_PER_TICK = 2;

    /** How often the chunks around the player are re-queued, in ticks. */
    private static final int SWEEP_INTERVAL_TICKS = 100;

    /** Radius in chunks of that periodic sweep. */
    private static final int SWEEP_RADIUS = 2;

    private static final int MAX_QUEUE = 4096;

    private final TileColorizer colorizer;
    private final Deque<Long> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();

    private String queuedDimension = "";
    private int tickCounter;
    private long localRevision;

    public ChunkScanner(TileColorizer colorizer) {
        this.colorizer = colorizer;
    }

    /** Queues a chunk that has just become available. */
    public void enqueue(ClientLevel level, ChunkPos pos) {
        String dimension = level.dimension().identifier().toString();
        if (!dimension.equals(queuedDimension)) {
            clear();
            queuedDimension = dimension;
        }
        offer(MapCoords.key(pos.x(), pos.z()));
    }

    private void offer(long key) {
        if (queue.size() >= MAX_QUEUE || !queued.add(key)) {
            return;
        }
        queue.addLast(key);
    }

    public void clear() {
        queue.clear();
        queued.clear();
    }

    public int pending() {
        return queue.size();
    }

    public void tick(Minecraft minecraft, MapClient client, ClientConfig config) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }

        String dimension = level.dimension().identifier().toString();
        if (!dimension.equals(queuedDimension)) {
            clear();
            queuedDimension = dimension;
        }

        if (++tickCounter >= SWEEP_INTERVAL_TICKS) {
            tickCounter = 0;
            sweepAroundPlayer(minecraft);
        }

        int viewY = (int) minecraft.player.getEyeY();
        for (int done = 0; done < CHUNKS_PER_TICK; done++) {
            Long key = queue.pollFirst();
            if (key == null) {
                return;
            }
            queued.remove(key);

            int chunkX = MapCoords.keyX(key);
            int chunkZ = MapCoords.keyZ(key);
            if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                continue;
            }

            ChunkTile tile;
            try {
                tile = colorizer.render(level, chunkX, chunkZ, viewY, config);
            } catch (RuntimeException e) {
                // A chunk that vanished mid-scan is not worth interrupting the game for.
                continue;
            }
            if (tile != null) {
                client.storeScannedTile(dimension, tile, ++localRevision);
            }
        }
    }

    /**
     * Queues every chunk the client currently has loaded, so they are colourised again.
     *
     * <p>Needed when a setting changes what a chunk should look like: tiles are only redrawn as you
     * walk past them, so without this a rendering change would appear not to work at all on ground
     * you had already covered.
     *
     * @return how many chunks were queued
     */
    public int rescanLoaded(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return 0;
        }
        int radius = minecraft.options.renderDistance().get();
        ChunkPos centre = minecraft.player.chunkPosition();
        int queuedNow = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = centre.x() + dx;
                int chunkZ = centre.z() + dz;
                if (!minecraft.level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                int before = queue.size();
                offer(MapCoords.key(chunkX, chunkZ));
                if (queue.size() != before) {
                    queuedNow++;
                }
            }
        }
        return queuedNow;
    }

    private void sweepAroundPlayer(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        ChunkPos centre = minecraft.player.chunkPosition();
        for (int dz = -SWEEP_RADIUS; dz <= SWEEP_RADIUS; dz++) {
            for (int dx = -SWEEP_RADIUS; dx <= SWEEP_RADIUS; dx++) {
                offer(MapCoords.key(centre.x() + dx, centre.z() + dz));
            }
        }
    }
}
