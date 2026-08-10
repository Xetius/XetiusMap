package dev.xetius.xetiusmap.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import dev.xetius.xetiusmap.client.XetiusMap;
import dev.xetius.xetiusmap.common.tile.ChunkTile;
import dev.xetius.xetiusmap.common.util.MapCoords;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Arrays;

/**
 * One region's worth of map pixels, at one of two resolutions.
 *
 * <p>Detail rasters are a pixel per block and are what you see when zoomed in; coarse rasters are a
 * pixel per four blocks and are what makes zooming right out affordable — at 1/8 of a pixel per
 * block a screen covers hundreds of regions, and holding those at full detail would cost a
 * gigabyte.
 *
 * <p>Pixels live in a plain {@code int[]} so they can be filled from the worker thread. The GPU
 * texture is created and refreshed lazily on the render thread the first time something asks to
 * draw it.
 */
public final class RegionRaster implements AutoCloseable {

    public static final int DETAIL_BLOCKS_PER_PIXEL = 1;
    public static final int COARSE_BLOCKS_PER_PIXEL = 4;

    private final String dimension;
    private final int regionX;
    private final int regionZ;
    private final int blocksPerPixel;
    private final int resolution;
    private final int[] pixels;
    private final long[] revisions = new long[MapCoords.TILES_PER_REGION];

    private NativeImage image;
    private DynamicTexture texture;
    private Identifier textureId;
    private volatile boolean dirty;
    private volatile boolean closed;
    private int populatedTiles;

    public RegionRaster(String dimension, int regionX, int regionZ, int blocksPerPixel) {
        this.dimension = dimension;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.blocksPerPixel = blocksPerPixel;
        this.resolution = MapCoords.REGION_BLOCKS / blocksPerPixel;
        this.pixels = new int[resolution * resolution];
    }

    public String dimension() {
        return dimension;
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    public int resolution() {
        return resolution;
    }

    public int blocksPerPixel() {
        return blocksPerPixel;
    }

    public boolean isEmpty() {
        return populatedTiles == 0;
    }

    public long revisionOf(int slot) {
        return revisions[slot];
    }

    /** Paints one chunk into the raster, averaging blocks together for a coarse raster. */
    public synchronized void paint(ChunkTile tile, long revision) {
        int slot = MapCoords.tileIndex(tile.chunkX(), tile.chunkZ());
        if (revisions[slot] == 0) {
            populatedTiles++;
        }
        revisions[slot] = revision;

        int chunkPixels = MapCoords.CHUNK_BLOCKS / blocksPerPixel;
        int baseX = (tile.chunkX() & 31) * chunkPixels;
        int baseZ = (tile.chunkZ() & 31) * chunkPixels;

        if (blocksPerPixel == 1) {
            for (int z = 0; z < 16; z++) {
                int row = (baseZ + z) * resolution + baseX;
                for (int x = 0; x < 16; x++) {
                    pixels[row + x] = tile.colorAt(x, z);
                }
            }
        } else {
            for (int pz = 0; pz < chunkPixels; pz++) {
                for (int px = 0; px < chunkPixels; px++) {
                    pixels[(baseZ + pz) * resolution + baseX + px] = average(tile, px, pz);
                }
            }
        }
        dirty = true;
    }

    private int average(ChunkTile tile, int pixelX, int pixelZ) {
        int r = 0;
        int g = 0;
        int b = 0;
        int counted = 0;
        for (int dz = 0; dz < blocksPerPixel; dz++) {
            for (int dx = 0; dx < blocksPerPixel; dx++) {
                int color = tile.colorAt(pixelX * blocksPerPixel + dx, pixelZ * blocksPerPixel + dz);
                if ((color >>> 24) == 0) {
                    continue;
                }
                r += (color >> 16) & 0xFF;
                g += (color >> 8) & 0xFF;
                b += color & 0xFF;
                counted++;
            }
        }
        if (counted == 0) {
            return 0;
        }
        return 0xFF000000 | ((r / counted) << 16) | ((g / counted) << 8) | (b / counted);
    }

    /** Colour at a world block position, or 0 if that spot has not been mapped. */
    public int colorAtBlock(int worldX, int worldZ) {
        int localX = Math.floorMod(worldX, MapCoords.REGION_BLOCKS) / blocksPerPixel;
        int localZ = Math.floorMod(worldZ, MapCoords.REGION_BLOCKS) / blocksPerPixel;
        return pixels[localZ * resolution + localX];
    }

    public boolean hasTileAtBlock(int worldX, int worldZ) {
        return revisions[MapCoords.tileIndex(worldX >> 4, worldZ >> 4)] != 0;
    }

    /**
     * The texture id to blit, uploading pending changes first.
     *
     * <p>Render thread only — it touches GPU resources.
     */
    public Identifier texture() {
        if (closed) {
            return null;
        }
        if (texture == null) {
            image = new NativeImage(resolution, resolution, false);
            texture = new DynamicTexture(() -> "xetiusmap/" + dimension + "/" + regionX + "_" + regionZ,
                    image);
            textureId = XetiusMap.id("region/"
                    + MapCoords.dimensionToFolder(dimension).toLowerCase(java.util.Locale.ROOT)
                    + "/" + (regionX < 0 ? "n" + (-regionX) : regionX)
                    + "_" + (regionZ < 0 ? "n" + (-regionZ) : regionZ)
                    + "_" + blocksPerPixel);
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            dirty = true;
        }
        if (dirty) {
            uploadPixels();
        }
        return textureId;
    }

    private synchronized void uploadPixels() {
        for (int y = 0; y < resolution; y++) {
            int row = y * resolution;
            for (int x = 0; x < resolution; x++) {
                image.setPixel(x, y, pixels[row + x]);
            }
        }
        texture.upload();
        dirty = false;
    }

    public void markDirty() {
        dirty = true;
    }

    @Override
    public void close() {
        closed = true;
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            texture.close();
            texture = null;
            image = null;
        }
        Arrays.fill(revisions, 0L);
    }

    /** Cache key: a raster is identified by dimension, region and resolution. */
    public record Key(String dimension, int regionX, int regionZ, int blocksPerPixel) {
    }

    public Key key() {
        return new Key(dimension, regionX, regionZ, blocksPerPixel);
    }
}
