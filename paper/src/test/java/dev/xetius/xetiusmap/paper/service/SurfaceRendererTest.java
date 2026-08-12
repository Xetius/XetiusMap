package dev.xetius.xetiusmap.paper.service;

import dev.xetius.xetiusmap.common.model.BlockPalette;
import dev.xetius.xetiusmap.common.tile.ChunkTile;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the part clients see most: what the map looks like where there is water.
 *
 * <p>Bukkit's own {@code Biome} constants resolve through a registry that only exists inside a
 * running server; {@code TestRegistryAccess} stands in for it.
 */
class SurfaceRendererTest {

    private static final Biome OCEAN = Biome.OCEAN;

    private static final int SAND = 0xDBD3A0;
    private static final int GRAVEL = 0x8F8F8F;
    private static final int WATER = 0x4040FF;
    private static final int GRASS = 0x7FB238;

    private static final BlockPalette PALETTE = new BlockPalette(
            Map.of(
                    "minecraft:water", new BlockPalette.Entry(WATER, BlockPalette.TINT_WATER),
                    "minecraft:sand", new BlockPalette.Entry(SAND, BlockPalette.TINT_NONE),
                    "minecraft:gravel", new BlockPalette.Entry(GRAVEL, BlockPalette.TINT_NONE),
                    "minecraft:grass_block", new BlockPalette.Entry(GRASS, BlockPalette.TINT_NONE)),
            Map.of("minecraft:ocean", new BlockPalette.BiomeTint(0x8EB971, 0x71A74D, WATER)));

    @Test
    void shallowWaterShowsTheSeabedThroughIt() {
        // One block of water over sand: mostly sand, only lightly tinted blue.
        ChunkTile tile = render(seabedAt(62, Material.SAND, 63));
        int color = tile.colorAt(0, 0);

        assertTrue(red(color) > blue(color),
                "sand under a single block of water should still read as sand, got " + hex(color));
    }

    @Test
    void deepWaterHidesTheSeabed() {
        // Far below the depth where water goes opaque, the floor should barely register.
        ChunkTile tile = render(seabedAt(20, Material.SAND, 63));
        int color = tile.colorAt(0, 0);

        assertTrue(blue(color) > red(color),
                "deep water should read as water, not sand, got " + hex(color));
    }

    @Test
    void sameDepthOverDifferentFloorsGivesDifferentColours() {
        // The whole point of the feature: seabed composition is legible through the water.
        int sand = render(seabedAt(58, Material.SAND, 63)).colorAt(0, 0);
        int gravel = render(seabedAt(58, Material.GRAVEL, 63)).colorAt(0, 0);

        assertTrue(sand != gravel,
                "sand and gravel seabeds under equal water should differ, both were " + hex(sand));
    }

    @Test
    void seabedReliefIsShadedFromTheFloorNotTheSurface() {
        // A flat sea over a sloping floor: with surface-based relief every pixel would be
        // identical, because the water surface itself is flat.
        ChunkTile tile = render(slopingSeabed(63));

        int flat = tile.colorAt(0, 4);
        boolean anyDifferent = false;
        for (int z = 0; z < 16; z++) {
            if (tile.colorAt(0, z) != flat) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "a sloping seabed under flat water should still show relief");
    }

    @Test
    void recordedDepthAndHeightDescribeTheWaterColumn() {
        ChunkTile tile = render(seabedAt(53, Material.SAND, 63));

        assertEquals(10, tile.waterDepth()[ChunkTile.index(0, 0)], "water depth is the column above the floor");
        assertEquals(53, tile.heightAt(0, 0), "height is the seabed, so relief joins up across chunks");
    }

    @Test
    void dryLandIsUnaffected() {
        ChunkTile tile = render(dryLand(70));

        assertEquals(0, tile.waterDepth()[ChunkTile.index(0, 0)]);
        assertEquals(70, tile.heightAt(0, 0));
        assertEquals(shaded(GRASS, 220), tile.colorAt(0, 0) & 0xFFFFFF,
                "flat land keeps its palette colour, at vanilla's NORMAL relief shade");
    }

    private static ChunkTile render(Column[][] columns) {
        ChunkTile tile = new SurfaceRenderer(PALETTE).render(snapshot(columns), -64, null, null);
        assertNotNull(tile, "renderer produced no tile");
        return tile;
    }

    /** Water from {@code seaLevel} down to just above {@code floorY}, floor of the given block. */
    private static Column[][] seabedAt(int floorY, Material floor, int seaLevel) {
        return fill((x, z) -> new Column(floorY, floor, seaLevel));
    }

    private static Column[][] dryLand(int y) {
        return fill((x, z) -> new Column(y, Material.GRASS_BLOCK, y));
    }

    /** Floor descending one block per row south, under a level sea. */
    private static Column[][] slopingSeabed(int seaLevel) {
        return fill((x, z) -> new Column(58 - z, Material.SAND, seaLevel));
    }

    private static Column[][] fill(ColumnFactory factory) {
        Column[][] columns = new Column[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                columns[x][z] = factory.at(x, z);
            }
        }
        return columns;
    }

    private interface ColumnFactory {
        Column at(int x, int z);
    }

    /** Solid up to and including {@code floorY}, then water up to and including {@code topY}. */
    private record Column(int floorY, Material floor, int topY) {

        Material blockAt(int y) {
            if (y <= floorY) {
                return floor;
            }
            return y <= topY ? Material.WATER : Material.AIR;
        }
    }

    private static ChunkSnapshot snapshot(Column[][] columns) {
        return (ChunkSnapshot) Proxy.newProxyInstance(
                SurfaceRendererTest.class.getClassLoader(),
                new Class<?>[] {ChunkSnapshot.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getX", "getZ" -> 0;
                    case "getBlockType" -> columns[(int) args[0]][(int) args[2]].blockAt((int) args[1]);
                    case "getBiome" -> OCEAN;
                    case "getHighestBlockYAt" -> columns[(int) args[0]][(int) args[1]].topY();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Vanilla darkens every map pixel by a relief multiplier; flat ground gets the middle one. */
    private static int shaded(int rgb, int brightness) {
        return ((((rgb >> 16) & 0xFF) * brightness / 255) << 16)
                | ((((rgb >> 8) & 0xFF) * brightness / 255) << 8)
                | ((rgb & 0xFF) * brightness / 255);
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }
}
