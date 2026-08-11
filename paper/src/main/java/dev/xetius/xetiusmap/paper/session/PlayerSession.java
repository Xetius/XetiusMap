package dev.xetius.xetiusmap.paper.session;

import dev.xetius.xetiusmap.common.net.Framing;
import dev.xetius.xetiusmap.paper.PluginConfig;
import dev.xetius.xetiusmap.paper.util.RateLimiter;

import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Everything the server tracks about one modded client for the lifetime of its connection.
 *
 * <p>Fields touched from more than one thread are concurrent or volatile: packets arrive on the
 * main thread, tile work happens on the store thread, and the outgoing queue is drained on the main
 * thread once per tick.
 */
public final class PlayerSession {

    private final UUID playerId;
    private final String playerName;

    private final Framing.Reassembler reassembler = new Framing.Reassembler();
    private final Queue<byte[]> outgoing = new ConcurrentLinkedQueue<>();
    private final AtomicInteger streamIds = new AtomicInteger();

    private final RateLimiter uploadLimiter;

    /** Tiles this client has asked for and not yet been sent, drained at the configured rate. */
    private final Queue<PendingTile> pendingTiles = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingTileCount = new AtomicInteger();

    /** Regions the client is currently watching, as {@code MapCoords.key} values. */
    private final Set<Long> subscribedRegions = ConcurrentHashMap.newKeySet();

    private final AtomicLong tilesUploaded = new AtomicLong();
    private final AtomicLong tilesRejected = new AtomicLong();
    private final AtomicLong tilesSent = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();

    private volatile boolean handshakeComplete;
    private volatile String modVersion = "unknown";
    private volatile String subscribedDimension = "";
    private volatile boolean hidden;
    private volatile ViewBounds view;
    private volatile long lastTeleportMillis;

    public PlayerSession(UUID playerId, String playerName, PluginConfig config) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.uploadLimiter = new RateLimiter(config.maxUploadsPerSecond());
    }

    public void applyConfig(PluginConfig config) {
        uploadLimiter.setRate(config.maxUploadsPerSecond());
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public Framing.Reassembler reassembler() {
        return reassembler;
    }

    public Queue<byte[]> outgoing() {
        return outgoing;
    }

    public AtomicInteger streamIds() {
        return streamIds;
    }

    public boolean tryUpload() {
        return uploadLimiter.tryAcquire();
    }

    /**
     * Queues a tile to send later.
     *
     * @return false if the backlog is already at {@code max}, so the caller can stop adding
     */
    public boolean queueTile(PendingTile tile, int max) {
        if (pendingTileCount.get() >= max) {
            return false;
        }
        pendingTiles.add(tile);
        pendingTileCount.incrementAndGet();
        return true;
    }

    public PendingTile pollTile() {
        PendingTile tile = pendingTiles.poll();
        if (tile != null) {
            pendingTileCount.decrementAndGet();
        }
        return tile;
    }

    public int pendingTileCount() {
        return pendingTileCount.get();
    }

    public void clearPendingTiles() {
        pendingTiles.clear();
        pendingTileCount.set(0);
    }

    /** A tile owed to a client. */
    public record PendingTile(String dimension, int chunkX, int chunkZ) {
    }

    public boolean handshakeComplete() {
        return handshakeComplete;
    }

    public void completeHandshake(String modVersion) {
        this.modVersion = modVersion;
        this.handshakeComplete = true;
    }

    public String modVersion() {
        return modVersion;
    }

    public boolean hidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public ViewBounds view() {
        return view;
    }

    public void setView(ViewBounds view) {
        this.view = view;
    }

    public String subscribedDimension() {
        return subscribedDimension;
    }

    public Set<Long> subscribedRegions() {
        return subscribedRegions;
    }

    public void replaceSubscriptions(String dimension, Set<Long> regions) {
        this.subscribedDimension = dimension;
        subscribedRegions.clear();
        subscribedRegions.addAll(regions);
    }

    public boolean watches(String dimension, long regionKey) {
        return subscribedDimension.equals(dimension) && subscribedRegions.contains(regionKey);
    }

    public long lastTeleportMillis() {
        return lastTeleportMillis;
    }

    public void markTeleported() {
        this.lastTeleportMillis = System.currentTimeMillis();
    }

    public AtomicLong tilesUploaded() {
        return tilesUploaded;
    }

    public AtomicLong tilesRejected() {
        return tilesRejected;
    }

    public AtomicLong tilesSent() {
        return tilesSent;
    }

    public AtomicLong bytesSent() {
        return bytesSent;
    }

    public AtomicLong framesDropped() {
        return framesDropped;
    }

    /** The chunk rectangle a client's world map is showing, used to widen entity updates. */
    public record ViewBounds(String dimension, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {

        public int chunkArea() {
            long width = (long) maxChunkX - minChunkX + 1;
            long height = (long) maxChunkZ - minChunkZ + 1;
            long area = width * height;
            return area > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, area);
        }

        public boolean containsBlock(int x, int z) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            return chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
        }
    }
}
