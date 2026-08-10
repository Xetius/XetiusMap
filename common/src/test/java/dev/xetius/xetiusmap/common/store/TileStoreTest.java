package dev.xetius.xetiusmap.common.store;

import dev.xetius.xetiusmap.common.util.MapCoords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileStoreTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static byte[] blob(int seed) {
        byte[] out = new byte[64];
        new Random(seed).nextBytes(out);
        return out;
    }

    @Test
    void keepsDimensionsSeparate(@TempDir Path dir) throws IOException {
        try (TileStore store = new TileStore(dir)) {
            store.write(OVERWORLD, 10, 20, blob(1), 1L, 1L);
            store.write(NETHER, 10, 20, blob(2), 1L, 2L);

            assertArrayEquals(blob(1), store.read(OVERWORLD, 10, 20));
            assertArrayEquals(blob(2), store.read(NETHER, 10, 20));
            assertNull(store.read("minecraft:the_end", 10, 20));
        }
    }

    @Test
    void readsBackAcrossNegativeCoordinates(@TempDir Path dir) throws IOException {
        try (TileStore store = new TileStore(dir)) {
            for (int[] chunk : new int[][]{{-1, -1}, {-33, 5}, {0, -64}, {1000, -1000}}) {
                store.write(OVERWORLD, chunk[0], chunk[1], blob(chunk[0] ^ chunk[1]), 3L, 4L);
            }
            for (int[] chunk : new int[][]{{-1, -1}, {-33, 5}, {0, -64}, {1000, -1000}}) {
                assertArrayEquals(blob(chunk[0] ^ chunk[1]), store.read(OVERWORLD, chunk[0], chunk[1]),
                        "chunk " + chunk[0] + "," + chunk[1]);
            }
        }
    }

    @Test
    void regionRevisionsReportPopulatedSlotsOnly(@TempDir Path dir) throws IOException {
        try (TileStore store = new TileStore(dir)) {
            store.write(OVERWORLD, 0, 0, blob(1), 5L, 1L);
            store.write(OVERWORLD, 31, 31, blob(2), 6L, 2L);

            long[] revisions = store.regionRevisions(OVERWORLD, 0, 0);
            assertNotNull(revisions);
            assertEquals(MapCoords.TILES_PER_REGION, revisions.length);
            assertEquals(5L, revisions[MapCoords.tileIndex(0, 0)]);
            assertEquals(6L, revisions[MapCoords.tileIndex(31, 31)]);
            assertEquals(0L, revisions[MapCoords.tileIndex(4, 4)]);

            assertNull(store.regionRevisions(OVERWORLD, 9, 9), "an untouched region has no file");
        }
    }

    @Test
    void survivesMoreRegionsThanOpenHandles(@TempDir Path dir) throws IOException {
        // maxOpenRegions is deliberately tiny so the LRU eviction path is exercised.
        try (TileStore store = new TileStore(dir, 4)) {
            for (int i = 0; i < 40; i++) {
                store.write(OVERWORLD, i * 32, 0, blob(i), i + 1, i);
            }
            for (int i = 0; i < 40; i++) {
                assertArrayEquals(blob(i), store.read(OVERWORLD, i * 32, 0), "region " + i);
            }
        }
    }

    @Test
    void listsRegionsAndScansMaxRevision(@TempDir Path dir) throws IOException {
        try (TileStore store = new TileStore(dir)) {
            store.write(OVERWORLD, 0, 0, blob(1), 10L, 1L);
            store.write(OVERWORLD, 64, -32, blob(2), 77L, 2L);
            store.write(NETHER, 0, 0, blob(3), 12L, 3L);

            List<long[]> regions = store.listRegions(OVERWORLD);
            assertEquals(2, regions.size());
            assertTrue(regions.stream().anyMatch(r -> r[0] == 0 && r[1] == 0));
            assertTrue(regions.stream().anyMatch(r -> r[0] == 2 && r[1] == -1));

            assertEquals(77L, store.scanMaxRevision());
        }
    }

    @Test
    void purgeRemovesOnlyTheNamedDimension(@TempDir Path dir) throws IOException {
        try (TileStore store = new TileStore(dir)) {
            store.write(OVERWORLD, 0, 0, blob(1), 1L, 1L);
            store.write(NETHER, 0, 0, blob(2), 1L, 2L);

            assertEquals(1, store.purgeDimension(NETHER));

            assertNull(store.read(NETHER, 0, 0));
            assertArrayEquals(blob(1), store.read(OVERWORLD, 0, 0));
        }
    }
}
