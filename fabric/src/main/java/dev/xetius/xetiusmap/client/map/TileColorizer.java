package dev.xetius.xetiusmap.client.map;

import dev.xetius.xetiusmap.client.config.ClientConfig;
import dev.xetius.xetiusmap.common.tile.ChunkTile;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.List;

/**
 * Turns a loaded chunk into the colours the map draws.
 *
 * <p>The look follows vanilla's own map rendering: a block's {@link MapColor} shaded by how it sits
 * relative to the column to its north, which is what gives in-game maps their sense of relief.
 * Where a block is biome-tinted — grass, foliage, water — the biome colour is used instead of the
 * flat map colour, so a swamp reads as a swamp and a badlands as a badlands rather than everything
 * green being the same green.
 *
 * <p>Runs on the client thread: it reads live chunk state, and the budget in {@link ChunkScanner}
 * keeps it to a couple of chunks per tick.
 */
public final class TileColorizer {

    /** Below the surface in the Nether, and in cave mode, scanning starts this far above the eye. */
    private static final int CEILING_SCAN_OFFSET = 8;

    private final BlockColors blockColors;

    public TileColorizer(BlockColors blockColors) {
        this.blockColors = blockColors;
    }

    /**
     * @param viewY the player's eye height, used only when there is no sky to look down from
     * @return the rendered tile, or {@code null} if the chunk turned out to be empty
     */
    public ChunkTile render(ClientLevel level, int chunkX, int chunkZ, int viewY, ClientConfig config) {
        boolean roofed = level.dimensionType().hasCeiling() || config.caveMode;
        int minY = level.getMinY();

        int[] colors = new int[ChunkTile.COLUMNS];
        short[] heights = new short[ChunkTile.COLUMNS];
        byte[] waterDepth = new byte[ChunkTile.COLUMNS];

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // One extra row to the north so the first row of the chunk can be shaded properly.
        int[] northHeights = new int[16];
        for (int x = 0; x < 16; x++) {
            int worldX = (chunkX << 4) + x;
            int worldZ = (chunkZ << 4) - 1;
            northHeights[x] = surfaceY(level, pos, worldX, worldZ, viewY, roofed, minY);
        }

        boolean anything = false;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                int index = ChunkTile.index(x, z);

                int y = surfaceY(level, pos, worldX, worldZ, viewY, roofed, minY);
                if (y == Integer.MIN_VALUE) {
                    colors[index] = 0;
                    heights[index] = (short) minY;
                    continue;
                }
                anything = true;

                pos.set(worldX, y, worldZ);
                BlockState state = level.getBlockState(pos);

                int depth = 0;
                if (!state.getFluidState().isEmpty()) {
                    depth = fluidDepth(level, pos, worldX, worldZ, y, minY);
                }

                int rgb = baseColor(level, pos, state, config);
                int shade = shadeModifier(y, northHeights[x], x, z, depth);
                colors[index] = ARGB.opaque(ARGB.scaleRGB(rgb, shade));
                heights[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, y));
                waterDepth[index] = (byte) Math.min(255, depth);

                northHeights[x] = y;
            }
        }

        return anything ? new ChunkTile(chunkX, chunkZ, colors, heights, waterDepth) : null;
    }

    /**
     * Highest block worth drawing in a column.
     *
     * <p>With a sky overhead the heightmap gives the answer directly, minus any invisible blocks
     * sitting on top. Under a ceiling — the Nether, or cave mode anywhere — there is no meaningful
     * "surface", so the scan starts just above the player and finds the first solid block with a
     * gap above it, which is the floor they are actually standing on.
     */
    private int surfaceY(ClientLevel level, BlockPos.MutableBlockPos pos,
                         int worldX, int worldZ, int viewY, boolean roofed, int minY) {
        if (roofed) {
            int start = Math.min(level.getMaxY(), viewY + CEILING_SCAN_OFFSET);
            boolean sawGap = false;
            for (int y = start; y >= minY; y--) {
                pos.set(worldX, y, worldZ);
                BlockState state = level.getBlockState(pos);
                boolean empty = state.isAir() && state.getFluidState().isEmpty();
                if (empty) {
                    sawGap = true;
                } else if (sawGap && state.getMapColor(level, pos) != MapColor.NONE) {
                    return y;
                }
            }
            return Integer.MIN_VALUE;
        }

        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
        while (y >= minY) {
            pos.set(worldX, y, worldZ);
            if (level.getBlockState(pos).getMapColor(level, pos) != MapColor.NONE) {
                return y;
            }
            y--;
        }
        return Integer.MIN_VALUE;
    }

    /** How deep the fluid at this column runs, so shallow water reads lighter than an ocean. */
    private int fluidDepth(ClientLevel level, BlockPos.MutableBlockPos pos,
                           int worldX, int worldZ, int surfaceY, int minY) {
        int depth = 0;
        for (int y = surfaceY; y >= minY && depth < 255; y--) {
            pos.set(worldX, y, worldZ);
            if (level.getBlockState(pos).getFluidState().isEmpty()) {
                break;
            }
            depth++;
        }
        pos.set(worldX, surfaceY, worldZ);
        return depth;
    }

    /**
     * The untinted colour for a block. Biome-tinted blocks use their tint directly: a grass or
     * leaf texture is close to greyscale and gets multiplied by exactly this value when the world
     * is drawn, so it is a far better match for the real thing than the flat map colour.
     */
    private int baseColor(ClientLevel level, BlockPos pos, BlockState state, ClientConfig config) {
        MapColor mapColor = state.getMapColor(level, pos);
        int flat = mapColor == MapColor.NONE ? 0x000000 : mapColor.col;

        if (config.colorStyle != ClientConfig.ColorStyle.TINTED) {
            return flat;
        }

        List<BlockTintSource> tints = blockColors.getTintSources(state);
        if (tints.isEmpty()) {
            return flat;
        }
        try {
            int tint = tints.getFirst().colorInWorld(state, level, pos) & 0xFFFFFF;
            return tint == 0 ? flat : tint;
        } catch (RuntimeException e) {
            // A modded tint source that dislikes being asked out of context must not break the map.
            return flat;
        }
    }

    /**
     * Vanilla's relief shading: compare this column against the one to its north, with a slight
     * checkerboard dither so flat ground is not a single dead flat colour.
     */
    private static int shadeModifier(int y, int northY, int x, int z, int waterDepth) {
        if (waterDepth > 0) {
            if (waterDepth <= 2) {
                return MapColor.Brightness.HIGH.modifier;
            }
            return waterDepth <= 6 ? MapColor.Brightness.NORMAL.modifier : MapColor.Brightness.LOW.modifier;
        }

        double delta = (y - northY) * 4.0 / 5.0 + (((x + z) & 1) - 0.5) * 0.4;
        if (delta > 0.6) {
            return MapColor.Brightness.HIGH.modifier;
        }
        if (delta < -0.6) {
            return MapColor.Brightness.LOW.modifier;
        }
        return MapColor.Brightness.NORMAL.modifier;
    }
}
