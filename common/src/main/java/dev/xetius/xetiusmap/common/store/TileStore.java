package dev.xetius.xetiusmap.common.store;

import dev.xetius.xetiusmap.common.util.MapCoords;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A directory of {@link RegionFile}s, one subdirectory per dimension, with a bounded pool of open
 * file handles. Used unchanged by the Paper plugin (as the shared authoritative store) and by the
 * Fabric client (as its local cache), which is what guarantees the two can never disagree about the
 * on-disk format.
 *
 * <p>Synchronised throughout and intentionally blocking: callers run it on a dedicated I/O thread.
 */
public final class TileStore implements Closeable {

    private static final int DEFAULT_MAX_OPEN_REGIONS = 64;

    private final Path root;
    private final int maxOpenRegions;
    private final LinkedHashMap<String, RegionFile> openRegions;
    private boolean closed;

    public TileStore(Path root) {
        this(root, DEFAULT_MAX_OPEN_REGIONS);
    }

    public TileStore(Path root, int maxOpenRegions) {
        this.root = root;
        this.maxOpenRegions = Math.max(4, maxOpenRegions);
        this.openRegions = new LinkedHashMap<>(16, 0.75f, true);
    }

    public Path root() {
        return root;
    }

    public synchronized byte[] read(String dimension, int chunkX, int chunkZ) throws IOException {
        RegionFile region = region(dimension, MapCoords.chunkToRegion(chunkX), MapCoords.chunkToRegion(chunkZ), false);
        return region == null ? null : region.read(MapCoords.tileIndex(chunkX, chunkZ));
    }

    public synchronized RegionFile.TileMeta meta(String dimension, int chunkX, int chunkZ) throws IOException {
        RegionFile region = region(dimension, MapCoords.chunkToRegion(chunkX), MapCoords.chunkToRegion(chunkZ), false);
        return region == null ? null : region.meta(MapCoords.tileIndex(chunkX, chunkZ));
    }

    public synchronized void write(String dimension, int chunkX, int chunkZ, byte[] blob, long revision, long hash)
            throws IOException {
        RegionFile region = region(dimension, MapCoords.chunkToRegion(chunkX), MapCoords.chunkToRegion(chunkZ), true);
        region.write(MapCoords.tileIndex(chunkX, chunkZ), blob, revision, hash);
        region.compactIfWasteful();
    }

    /**
     * Per-slot revisions for a whole region, or {@code null} if the region has never been written.
     * This is what lets a client ask "what do you have around here?" in one round trip instead of
     * 1024 individual probes.
     */
    public synchronized long[] regionRevisions(String dimension, int regionX, int regionZ) throws IOException {
        RegionFile region = region(dimension, regionX, regionZ, false);
        return region == null ? null : region.revisionSnapshot();
    }

    public synchronized List<String> dimensionFolders() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Region coordinates that exist on disk for a dimension, cheapest possible discovery. */
    public synchronized List<long[]> listRegions(String dimension) {
        Path dir = root.resolve(MapCoords.dimensionToFolder(dimension));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<long[]> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path p : children.toList()) {
                String name = p.getFileName().toString();
                if (!name.startsWith("r.") || !name.endsWith(".xmr")) {
                    continue;
                }
                String[] parts = name.substring(2, name.length() - 4).split("\\.");
                if (parts.length != 2) {
                    continue;
                }
                try {
                    out.add(new long[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
                } catch (NumberFormatException ignored) {
                    // Not one of ours; leave it alone.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.<long[]>comparingLong(a -> a[0]).thenComparingLong(a -> a[1]));
        return out;
    }

    /** Highest revision present anywhere, used to resume a revision counter after a restart. */
    public synchronized long scanMaxRevision() throws IOException {
        long max = 0;
        for (String folder : dimensionFolders()) {
            Path dir = root.resolve(folder);
            try (Stream<Path> children = Files.list(dir)) {
                for (Path p : children.toList()) {
                    if (!p.getFileName().toString().endsWith(".xmr")) {
                        continue;
                    }
                    try (RegionFile rf = new RegionFile(p)) {
                        for (long revision : rf.revisionSnapshot()) {
                            max = Math.max(max, revision);
                        }
                    }
                }
            }
        }
        return max;
    }

    /** Deletes every tile of a dimension. Returns the number of region files removed. */
    public synchronized int purgeDimension(String dimension) throws IOException {
        String folder = MapCoords.dimensionToFolder(dimension);
        closeMatching(folder);
        Path dir = root.resolve(folder);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> children = Files.list(dir)) {
            for (Path p : children.toList()) {
                if (Files.deleteIfExists(p)) {
                    removed++;
                }
            }
        }
        Files.deleteIfExists(dir);
        return removed;
    }

    public synchronized void flushAll() throws IOException {
        for (RegionFile region : openRegions.values()) {
            region.flush();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException first = null;
        for (RegionFile region : openRegions.values()) {
            try {
                region.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        openRegions.clear();
        if (first != null) {
            throw first;
        }
    }

    private RegionFile region(String dimension, int regionX, int regionZ, boolean create) throws IOException {
        if (closed) {
            throw new IOException("tile store is closed");
        }
        String folder = MapCoords.dimensionToFolder(dimension);
        String key = folder + '/' + regionX + ',' + regionZ;
        RegionFile existing = openRegions.get(key);
        if (existing != null) {
            return existing;
        }

        Path path = root.resolve(folder).resolve("r." + regionX + '.' + regionZ + ".xmr");
        if (!create && !Files.exists(path)) {
            return null;
        }
        evictIfNeeded();
        RegionFile region = new RegionFile(path);
        openRegions.put(key, region);
        return region;
    }

    private void evictIfNeeded() throws IOException {
        while (openRegions.size() >= maxOpenRegions) {
            Iterator<Map.Entry<String, RegionFile>> it = openRegions.entrySet().iterator();
            if (!it.hasNext()) {
                return;
            }
            Map.Entry<String, RegionFile> eldest = it.next();
            it.remove();
            eldest.getValue().close();
        }
    }

    private void closeMatching(String folderPrefix) throws IOException {
        Iterator<Map.Entry<String, RegionFile>> it = openRegions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RegionFile> entry = it.next();
            if (entry.getKey().startsWith(folderPrefix + '/')) {
                it.remove();
                entry.getValue().close();
            }
        }
    }
}
