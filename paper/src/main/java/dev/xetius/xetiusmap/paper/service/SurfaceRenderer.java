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

    /** Matches the client's default; the server has no per-player setting to consult. */
    private static final int WATER_OPAQUE_DEPTH = 18;

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

                String blockId = snapshot.getBlockType(x, surface, z).getKey().toString();
                String biomeId = snapshot.getBiome(x, surface, z).getKey().toString();

                int rgb;
                int depth = 0;
                // Relief is measured from the seabed under water, matching what clients render, so
                // backfilled tiles sit alongside walked ones without a visible change of style.
                int reliefY = surface;

                if (palette.isWater(blockId)) {
                    int lowestWater = surface;
                    while (lowestWater - 1 >= minY
                            && palette.isWater(snapshot.getBlockType(x, lowestWater - 1, z).getKey().toString())) {
                        lowestWater--;
                    }
                    depth = Math.min(255, surface - lowestWater + 1);
                    int floorY = lowestWater - 1;
                    int waterRgb = palette.colorOf(blockId, biomeId, 0);

                    if (floorY >= minY) {
                        String floorId = snapshot.getBlockType(x, floorY, z).getKey().toString();
                        int floorRgb = palette.colorOf(floorId, snapshot.getBiome(x, floorY, z).getKey().toString(), 0);
                        rgb = floorRgb == 0 ? waterRgb : blend(floorRgb, waterRgb, waterOpacity(depth));
                        reliefY = floorY;
                    } else {
                        rgb = waterRgb;
                    }
                } else {
                    rgb = palette.colorOf(blockId, biomeId, 0);
                }

                if (rgb == 0) {
                    heights[index] = (short) surface;
                    currentRow[x] = surface;
                    continue;
                }
                anything = true;

                int north = previousRow != null ? previousRow[x] : reliefY;
                colors[index] = 0xFF000000 | scale(rgb, shade(reliefY, north, x, z));
                heights[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, reliefY));
                waterDepth[index] = (byte) depth;
                currentRow[x] = reliefY;
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

    /** Water's own contribution, matching the client so the two renderers agree. */
    private static float waterOpacity(int depth) {
        return 0.15F + 0.75F * Math.min(1.0F, depth / (float) WATER_OPAQUE_DEPTH);
    }

    private static int blend(int from, int to, float amount) {
        float a = Math.max(0.0F, Math.min(1.0F, amount));
        int r = Math.round(((from >> 16) & 0xFF) * (1 - a) + ((to >> 16) & 0xFF) * a);
        int g = Math.round(((from >> 8) & 0xFF) * (1 - a) + ((to >> 8) & 0xFF) * a);
        int b = Math.round((from & 0xFF) * (1 - a) + (to & 0xFF) * a);
        return (r << 16) | (g << 8) | b;
    }

    /** The same relief shading the client applies, so backfilled tiles sit alongside walked ones. */
    private static int shade(int y, int northY, int x, int z) {
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
