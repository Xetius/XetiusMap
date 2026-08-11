package dev.xetius.xetiusmap.paper.service;

import dev.xetius.xetiusmap.common.model.BlockPalette;
import dev.xetius.xetiusmap.common.tile.ChunkTile;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

/**
 * Renders a chunk the server already has on disk into the same tile format clients produce.
 *
 * <p>It works from a {@link ChunkSnapshot}, which is a detached copy of the chunk, so the expensive
 * part runs off the main thread. Colours come from the {@link BlockPalette} a client uploaded,
 * because {@code paper-api} has no idea what colour a block is.
 *
 * <p>The result is close to a client-rendered tile but not identical: colours are per-block
 * defaults rather than resolved per position, so a handful of state-dependent blocks differ
 * slightly. Anywhere a player has actually been keeps its client-rendered tile.
 */
public final class SurfaceRenderer {

    /** Vanilla's map shading: LOW, NORMAL and HIGH as brightness multipliers out of 255. */
    private static final int SHADE_LOW = 180;
    private static final int SHADE_NORMAL = 220;
    private static final int SHADE_HIGH = 255;

    private final BlockPalette palette;

    public SurfaceRenderer(BlockPalette palette) {
        this.palette = palette;
    }

    /**
     * Renders one chunk.
     *
     * @param northHeights surface heights of the row immediately north of this chunk, so relief
     *                     shading is continuous across the boundary; null if unknown
     * @param outHeights   receives this chunk's southernmost row of heights, to pass into the next
     * @return the tile, or null if the chunk held nothing worth drawing
     */
    public ChunkTile render(ChunkSnapshot snapshot, int minY, int[] northHeights, int[] outHeights) {
        int[] colors = new int[ChunkTile.COLUMNS];
        short[] heights = new short[ChunkTile.COLUMNS];
        byte[] waterDepth = new byte[ChunkTile.COLUMNS];

        int[] previousRow = northHeights;
        int[] currentRow = new int[16];
        boolean anything = false;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = ChunkTile.index(x, z);
                int surface = surfaceY(snapshot, x, z, minY);
                if (surface == Integer.MIN_VALUE) {
                    heights[index] = (short) minY;
                    currentRow[x] = minY;
                    continue;
                }

                Material material = snapshot.getBlockType(x, surface, z);
                String blockId = material.getKey().toString();
                String biomeId = snapshot.getBiome(x, surface, z).getKey().toString();

                int depth = palette.isWater(blockId) ? waterDepth(snapshot, x, surface, z, minY) : 0;
                int rgb = palette.colorOf(blockId, biomeId, 0);
                if (rgb == 0) {
                    heights[index] = (short) surface;
                    currentRow[x] = surface;
                    continue;
                }
                anything = true;

                int north = previousRow != null ? previousRow[x] : surface;
                colors[index] = 0xFF000000 | scale(rgb, shade(surface, north, x, z, depth));
                heights[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, surface));
                waterDepth[index] = (byte) Math.min(255, depth);
                currentRow[x] = surface;
            }
            previousRow = currentRow.clone();
        }

        if (outHeights != null) {
            System.arraycopy(currentRow, 0, outHeights, 0, 16);
        }
        return anything ? new ChunkTile(snapshot.getX(), snapshot.getZ(), colors, heights, waterDepth) : null;
    }

    /** Highest block with a colour, skipping anything the palette says is invisible. */
    private int surfaceY(ChunkSnapshot snapshot, int x, int z, int minY) {
        int y = snapshot.getHighestBlockYAt(x, z);
        while (y >= minY) {
            Material material = snapshot.getBlockType(x, y, z);
            if (material != Material.AIR && material != Material.CAVE_AIR && material != Material.VOID_AIR) {
                return y;
            }
            y--;
        }
        return Integer.MIN_VALUE;
    }

    private int waterDepth(ChunkSnapshot snapshot, int x, int surfaceY, int z, int minY) {
        int depth = 0;
        for (int y = surfaceY; y >= minY && depth < 255; y--) {
            if (!palette.isWater(snapshot.getBlockType(x, y, z).getKey().toString())) {
                break;
            }
            depth++;
        }
        return depth;
    }

    /** The same relief shading the client applies, so backfilled tiles sit alongside walked ones. */
    private static int shade(int y, int northY, int x, int z, int waterDepth) {
        if (waterDepth > 0) {
            if (waterDepth <= 2) {
                return SHADE_HIGH;
            }
            return waterDepth <= 6 ? SHADE_NORMAL : SHADE_LOW;
        }
        double delta = (y - northY) * 4.0 / 5.0 + (((x + z) & 1) - 0.5) * 0.4;
        if (delta > 0.6) {
            return SHADE_HIGH;
        }
        return delta < -0.6 ? SHADE_LOW : SHADE_NORMAL;
    }

    private static int scale(int rgb, int brightness) {
        int r = ((rgb >> 16) & 0xFF) * brightness / 255;
        int g = ((rgb >> 8) & 0xFF) * brightness / 255;
        int b = (rgb & 0xFF) * brightness / 255;
        return (r << 16) | (g << 8) | b;
    }
}
