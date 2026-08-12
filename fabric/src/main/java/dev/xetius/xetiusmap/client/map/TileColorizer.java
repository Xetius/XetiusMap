package dev.xetius.xetiusmap.client.map;

import dev.xetius.xetiusmap.client.config.ClientConfig;
import dev.xetius.xetiusmap.common.tile.ChunkTile;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.tags.FluidTags;
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
 * <p>Water is the exception to "colour the topmost block". Vanilla maps paint the surface and shade
 * it by depth, which turns every ocean into a flat blue slab; here the column is followed down to
 * the seabed, the floor block is coloured, and the water is mixed over it in proportion to its
 * depth. Relief is measured from the sea floor as well, so drop-offs and ravines show through.
 *
 * <p>Runs on the client thread: it reads live chunk state, and the budget in {@link ChunkScanner}
 * keeps it to a couple of chunks per tick.
 */
public final class TileColorizer {

    /** Below the surface in the Nether, and in the cave view, scanning starts this far above the eye. */
    private static final int CEILING_SCAN_OFFSET = 8;

    private final BlockColors blockColors;

    public TileColorizer(BlockColors blockColors) {
        this.blockColors = blockColors;
    }

    /**
     * @param viewY  the player's eye height, used only when there is no sky to look down from
     * @param roofed true when there is no surface to draw: a ceilinged dimension, or the cave view
     *               of {@link CaveView} while the player is underground
     * @return the rendered tile, or {@code null} if the chunk turned out to be empty
     */
    public ChunkTile render(ClientLevel level, int chunkX, int chunkZ, int viewY,
                            boolean roofed, ClientConfig config) {
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
            northHeights[x] = reliefHeight(level, pos, worldX, worldZ, viewY, roofed, minY, config);
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

                int rgb;
                int shade;
                int depth = 0;
                // The height relief is measured from is the seabed under water, so underwater
                // topography — ravines, drop-offs, sand against gravel — reads on the map.
                int reliefY = y;

                if (state.getFluidState().is(FluidTags.WATER)) {
                    int lowestWater = y;
                    while (lowestWater - 1 >= minY) {
                        pos.set(worldX, lowestWater - 1, worldZ);
                        if (!level.getBlockState(pos).getFluidState().is(FluidTags.WATER)) {
                            break;
                        }
                        lowestWater--;
                    }
                    depth = Math.min(255, y - lowestWater + 1);
                    int floorY = lowestWater - 1;

                    pos.set(worldX, y, worldZ);
                    int waterRgb = baseColor(level, pos, state, config);

                    if (config.showUnderwaterTerrain && floorY >= minY) {
                        pos.set(worldX, floorY, worldZ);
                        int floorRgb = baseColor(level, pos, level.getBlockState(pos), config);
                        rgb = blend(floorRgb, waterRgb, waterOpacity(depth, config.waterOpaqueDepth));
                        reliefY = floorY;
                        shade = reliefShade(reliefY, northHeights[x], x, z);
                    } else {
                        rgb = waterRgb;
                        shade = depthShade(depth);
                    }
                } else {
                    rgb = baseColor(level, pos, state, config);
                    shade = reliefShade(y, northHeights[x], x, z);
                }

                colors[index] = ARGB.opaque(ARGB.scaleRGB(rgb, shade));
                heights[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, reliefY));
                waterDepth[index] = (byte) depth;

                northHeights[x] = reliefY;
            }
        }

        return anything ? new ChunkTile(chunkX, chunkZ, colors, heights, waterDepth) : null;
    }

    /**
     * The height relief is measured from: the seabed under water, the surface otherwise. Used for
     * the row north of the chunk so shading is continuous across the boundary.
     */
    private int reliefHeight(ClientLevel level, BlockPos.MutableBlockPos pos, int worldX, int worldZ,
                             int viewY, boolean roofed, int minY, ClientConfig config) {
        int y = surfaceY(level, pos, worldX, worldZ, viewY, roofed, minY);
        if (y == Integer.MIN_VALUE || !config.showUnderwaterTerrain) {
            return y;
        }
        pos.set(worldX, y, worldZ);
        if (!level.getBlockState(pos).getFluidState().is(FluidTags.WATER)) {
            return y;
        }
        int lowest = y;
        while (lowest - 1 >= minY) {
            pos.set(worldX, lowest - 1, worldZ);
            if (!level.getBlockState(pos).getFluidState().is(FluidTags.WATER)) {
                break;
            }
            lowest--;
        }
        return lowest - 1;
    }

    /**
     * Highest block worth drawing in a column.
     *
     * <p>With a sky overhead the heightmap gives the answer directly, minus any invisible blocks
     * sitting on top. Under a roof — the Nether, or a cave — there is no meaningful "surface", so
     * the scan starts just above the player and finds the first solid block with a gap above it,
     * which is the floor they are actually standing on.
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

        // Water never tints through BlockColors: Blocks.WATER is registered with a source that only
        // implements colorAsTerrainParticle, so colorInWorld falls back to the untinted sentinel.
        // Asking the biome directly is what gives warm seas their green cast and cold ones their
        // deep blue.
        if (state.getFluidState().is(FluidTags.WATER)) {
            return usableTint(BiomeColors.getAverageWaterColor(level, pos), flat);
        }

        List<BlockTintSource> tints = blockColors.getTintSources(state);
        if (tints.isEmpty()) {
            return flat;
        }
        try {
            return usableTint(tints.getFirst().colorInWorld(state, level, pos), flat);
        } catch (RuntimeException e) {
            // A modded tint source that dislikes being asked out of context must not break the map.
            return flat;
        }
    }

    /**
     * A tint of white is the "no tint" sentinel. Taking it literally is what turned water grey:
     * white multiplied by the depth shading is exactly mid-grey.
     */
    private static int usableTint(int tint, int fallback) {
        int rgb = tint & 0xFFFFFF;
        return rgb == 0 || rgb == 0xFFFFFF ? fallback : rgb;
    }

    /**
     * How much the water itself contributes, from a hint in the shallows to fully opaque at depth.
     * Shallow water keeps most of the seabed's colour, which is what makes the sea floor visible.
     */
    static float waterOpacity(int depth, int opaqueDepth) {
        float t = Math.min(1.0F, depth / (float) Math.max(1, opaqueDepth));
        return 0.15F + 0.75F * t;
    }

    /** Mixes two packed RGB colours. */
    static int blend(int from, int to, float amount) {
        float a = Math.max(0.0F, Math.min(1.0F, amount));
        int r = Math.round(((from >> 16) & 0xFF) * (1 - a) + ((to >> 16) & 0xFF) * a);
        int g = Math.round(((from >> 8) & 0xFF) * (1 - a) + ((to >> 8) & 0xFF) * a);
        int b = Math.round((from & 0xFF) * (1 - a) + (to & 0xFF) * a);
        return (r << 16) | (g << 8) | b;
    }

    /** Used only when underwater detail is switched off: vanilla's three depth bands. */
    private static int depthShade(int depth) {
        if (depth <= 2) {
            return MapColor.Brightness.HIGH.modifier;
        }
        return depth <= 6 ? MapColor.Brightness.NORMAL.modifier : MapColor.Brightness.LOW.modifier;
    }

    /**
     * Vanilla's relief shading: compare this column against the one to its north, with a slight
     * checkerboard dither so flat ground is not a single dead flat colour.
     */
    private static int reliefShade(int y, int northY, int x, int z) {
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
