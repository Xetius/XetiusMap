package dev.xetius.xetiusmap.common.util;

/**
 * Coordinate arithmetic shared by client and server. A "tile" is one chunk (16x16 blocks) and a
 * "region" is 32x32 tiles, i.e. 512x512 blocks — the same shape as a vanilla region file, which
 * keeps the on-disk layout intuitive and gives a 512x512 pixel texture at one pixel per block.
 */
public final class MapCoords {

    public static final int CHUNK_BLOCKS = 16;
    public static final int REGION_CHUNKS = 32;
    public static final int REGION_BLOCKS = REGION_CHUNKS * CHUNK_BLOCKS;
    public static final int TILES_PER_REGION = REGION_CHUNKS * REGION_CHUNKS;

    private MapCoords() {
    }

    public static int blockToChunk(int block) {
        return block >> 4;
    }

    public static int chunkToRegion(int chunk) {
        return chunk >> 5;
    }

    public static int blockToRegion(int block) {
        return block >> 9;
    }

    /** Index of a chunk within its region file, 0..1023. */
    public static int tileIndex(int chunkX, int chunkZ) {
        return ((chunkZ & 31) << 5) | (chunkX & 31);
    }

    public static int tileIndexToChunkX(int regionX, int index) {
        return (regionX << 5) + (index & 31);
    }

    public static int tileIndexToChunkZ(int regionZ, int index) {
        return (regionZ << 5) + ((index >> 5) & 31);
    }

    /** Packs a pair of 32-bit coordinates into a map key. Used for both chunk and region keys. */
    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    /**
     * Maps a dimension id onto a directory name that is safe on every filesystem we care about.
     * {@code minecraft:the_nether} becomes {@code minecraft.the_nether}.
     */
    public static String dimensionToFolder(String dimensionId) {
        StringBuilder sb = new StringBuilder(dimensionId.length());
        for (int i = 0; i < dimensionId.length(); i++) {
            char c = dimensionId.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            sb.append(safe ? c : '.');
        }
        String out = sb.toString();
        return out.isEmpty() ? "unknown" : out;
    }
}
