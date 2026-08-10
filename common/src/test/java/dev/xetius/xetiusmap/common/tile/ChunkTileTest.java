package dev.xetius.xetiusmap.common.tile;

import dev.xetius.xetiusmap.common.net.ProtocolException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkTileTest {

    private static ChunkTile tile(int distinctColors, boolean withWater, long seed) {
        Random random = new Random(seed);
        int[] colors = new int[ChunkTile.COLUMNS];
        short[] heights = new short[ChunkTile.COLUMNS];
        byte[] water = withWater ? new byte[ChunkTile.COLUMNS] : null;

        int[] palette = new int[distinctColors];
        for (int i = 0; i < distinctColors; i++) {
            palette[i] = 0xFF000000 | random.nextInt(0xFFFFFF);
        }
        for (int i = 0; i < ChunkTile.COLUMNS; i++) {
            colors[i] = palette[random.nextInt(distinctColors)];
            heights[i] = (short) (random.nextInt(384) - 64);
            if (water != null) {
                water[i] = (byte) random.nextInt(256);
            }
        }
        return new ChunkTile(3, -7, colors, heights, water);
    }

    private static void assertRoundTrips(ChunkTile original) {
        byte[] body = original.encode();
        ChunkTile decoded = ChunkTile.decode(original.chunkX(), original.chunkZ(), body);

        assertEquals(original.chunkX(), decoded.chunkX());
        assertEquals(original.chunkZ(), decoded.chunkZ());
        assertArrayEquals(original.colors(), decoded.colors());
        assertArrayEquals(original.heights(), decoded.heights());
        assertArrayEquals(original.waterDepth(), decoded.waterDepth());
    }

    @Test
    void roundTripsAcrossEveryPaletteWidth() {
        // 1 colour uses zero index bits; 2, 3, 5, 17 and 256 straddle each bit-width boundary.
        for (int distinct : new int[]{1, 2, 3, 4, 5, 16, 17, 255, 256}) {
            assertRoundTrips(tile(distinct, true, distinct));
        }
    }

    @Test
    void roundTripsWithoutWaterDepth() {
        ChunkTile original = tile(24, false, 99);
        byte[] body = original.encode();
        ChunkTile decoded = ChunkTile.decode(0, 0, body);
        assertNull(decoded.waterDepth());
        assertArrayEquals(original.colors(), decoded.colors());
    }

    @Test
    void survivesCompressionRoundTrip() {
        ChunkTile original = tile(48, true, 1234);
        byte[] body = original.encode();
        byte[] blob = TileCodec.compress(body);
        assertArrayEquals(body, TileCodec.decompress(blob));

        ChunkTile decoded = ChunkTile.decode(3, -7, TileCodec.decompress(blob));
        assertArrayEquals(original.colors(), decoded.colors());
    }

    @Test
    void realisticTileCompressesSmall() {
        // Terrain repeats heavily, so a real tile should land well under 1 KB on the wire.
        ChunkTile original = tile(12, true, 7);
        int compressed = TileCodec.compress(original.encode()).length;
        assertTrue(compressed < 1024, "expected a compact tile, got " + compressed + " bytes");
    }

    @Test
    void hashDetectsChange() {
        ChunkTile original = tile(16, true, 5);
        byte[] body = original.encode();
        long before = TileCodec.hash(body);

        int[] colors = Arrays.copyOf(original.colors(), ChunkTile.COLUMNS);
        colors[100] = ~colors[100];
        long after = TileCodec.hash(new ChunkTile(3, -7, colors, original.heights(), original.waterDepth()).encode());

        assertNotEquals(before, after);
        assertEquals(before, TileCodec.hash(original.encode()), "encoding must be deterministic");
    }

    @Test
    void rejectsTruncatedBody() {
        byte[] body = tile(20, true, 3).encode();
        byte[] truncated = Arrays.copyOf(body, body.length / 2);
        assertThrows(ProtocolException.class, () -> ChunkTile.decode(0, 0, truncated));
    }

    @Test
    void rejectsUnknownFormatVersion() {
        byte[] body = tile(4, false, 3).encode();
        body[0] = 99;
        assertThrows(ProtocolException.class, () -> ChunkTile.decode(0, 0, body));
    }

    @Test
    void rejectsDecompressionBomb() {
        byte[] blob = TileCodec.compress(new byte[1024]);
        // Overwrite the declared size with something absurd.
        blob[0] = 0x7F;
        blob[1] = (byte) 0xFF;
        assertThrows(ProtocolException.class, () -> TileCodec.decompress(blob));
    }

    @Test
    void rejectsWrongColumnCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkTile(0, 0, new int[10], new short[10], null));
    }
}
