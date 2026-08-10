package dev.xetius.xetiusmap.common.store;

import dev.xetius.xetiusmap.common.util.MapCoords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionFileTest {

    private static byte[] blob(int length, int seed) {
        byte[] out = new byte[length];
        new Random(seed).nextBytes(out);
        return out;
    }

    @Test
    void writesAndReadsBack(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.xmr");
        byte[] payload = blob(300, 1);
        try (RegionFile region = new RegionFile(path)) {
            assertNull(region.read(5));
            assertFalse(region.has(5));

            region.write(5, payload, 42L, 0xABCDL);

            assertTrue(region.has(5));
            assertArrayEquals(payload, region.read(5));
            RegionFile.TileMeta meta = region.meta(5);
            assertNotNull(meta);
            assertEquals(42L, meta.revision());
            assertEquals(0xABCDL, meta.hash());
            assertEquals(payload.length, meta.length());
        }
    }

    @Test
    void survivesReopen(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.1.-2.xmr");
        byte[] first = blob(128, 2);
        byte[] second = blob(700, 3);

        try (RegionFile region = new RegionFile(path)) {
            region.write(0, first, 1L, 11L);
            region.write(MapCoords.TILES_PER_REGION - 1, second, 2L, 22L);
        }
        try (RegionFile region = new RegionFile(path)) {
            assertArrayEquals(first, region.read(0));
            assertArrayEquals(second, region.read(MapCoords.TILES_PER_REGION - 1));
            assertEquals(2L, region.meta(MapCoords.TILES_PER_REGION - 1).revision());
        }
    }

    @Test
    void overwriteKeepsLatestRevision(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.xmr");
        try (RegionFile region = new RegionFile(path)) {
            region.write(7, blob(100, 4), 1L, 1L);
            byte[] replacement = blob(250, 5);
            region.write(7, replacement, 2L, 2L);

            assertArrayEquals(replacement, region.read(7));
            assertEquals(2L, region.meta(7).revision());
        }
    }

    @Test
    void compactionReclaimsSpaceAndPreservesData(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.xmr");
        byte[] keep = blob(400, 6);
        try (RegionFile region = new RegionFile(path)) {
            // Rewrite one slot repeatedly to build up dead blobs, then verify compaction shrinks
            // the file while every live slot survives.
            for (int i = 1; i <= 200; i++) {
                region.write(3, blob(4096, i), i, i);
            }
            region.write(9, keep, 999L, 999L);
            byte[] latest = region.read(3);

            long before = Files.size(path);
            region.compact();
            long after = Files.size(path);

            assertTrue(after < before, "compaction should shrink the file: " + before + " -> " + after);
            assertArrayEquals(latest, region.read(3));
            assertArrayEquals(keep, region.read(9));
            assertEquals(999L, region.meta(9).revision());
        }
    }

    @Test
    void automaticCompactionTriggersOnWaste(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.xmr");
        try (RegionFile region = new RegionFile(path)) {
            boolean compacted = false;
            for (int i = 1; i <= 400 && !compacted; i++) {
                region.write(1, blob(8192, i), i, i);
                compacted = region.compactIfWasteful();
            }
            assertTrue(compacted, "repeated rewrites should eventually trigger compaction");
        }
    }

    @Test
    void dropsSlotsPointingOutsideTheFile(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.xmr");
        try (RegionFile region = new RegionFile(path)) {
            region.write(2, blob(200, 7), 1L, 1L);
        }
        // Simulate a torn write: the slot record survived but the data did not.
        try (RandomAccessFile raw = new RandomAccessFile(path.toFile(), "rw")) {
            raw.setLength(raw.length() - 100);
        }
        try (RegionFile region = new RegionFile(path)) {
            assertFalse(region.has(2), "a slot pointing past the end of the file must be dropped");
        }
    }

    @Test
    void rejectsForeignFile(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.xmr");
        Files.write(path, new byte[64 * 1024]);
        assertThrows(IOException.class, () -> new RegionFile(path));
    }

    @Test
    void rejectsInvalidWrites(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.xmr");
        try (RegionFile region = new RegionFile(path)) {
            assertThrows(IllegalArgumentException.class, () -> region.write(0, new byte[0], 1L, 0L));
            assertThrows(IllegalArgumentException.class, () -> region.write(0, blob(10, 1), 0L, 0L));
            assertThrows(IndexOutOfBoundsException.class, () -> region.write(9999, blob(10, 1), 1L, 0L));
        }
    }
}
