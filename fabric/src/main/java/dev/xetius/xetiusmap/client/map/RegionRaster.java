package dev.xetius.xetiusmap.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import dev.xetius.xetiusmap.client.XetiusMap;
import dev.xetius.xetiusmap.common.tile.ChunkTile;
import dev.xetius.xetiusmap.common.util.MapCoords;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.Locale;

/**
 * One region's worth of map pixels, at one of two resolutions.
 *
 * <p>Detail rasters are a pixel per block and are what you see when zoomed in; coarse rasters are a
 * pixel per four blocks and are what makes zooming right out affordable — at 1/8 of a pixel per
 * block a screen covers hundreds of regions, and holding those at full detail would cost a
 * gigabyte.
 *
 * <p>Pixels live directly in a {@link NativeImage}, which doubles as the upload source: allocating
 * one is just memory, so it can be filled on the worker thread, while the GPU texture wrapping it
 * is created lazily on the render thread the first time something asks to draw it.
 */
public final class RegionRaster implements AutoCloseable {

    public static final int DETAIL_BLOCKS_PER_PIXEL = 1;
    public static final int COARSE_BLOCKS_PER_PIXEL = 4;

    /**
     * A pixel per sixteen blocks, so a whole region is 32x32 and costs 4 KiB. Zoomed right out a
     * screen can span tens of thousands of regions, which is only affordable at this size.
     */
    public static final int OVERVIEW_BLOCKS_PER_PIXEL = 16;

    private final String dimension;
    private final int regionX;
    private final int regionZ;
    private final int blocksPerPixel;
    private final int resolution;
    private final long[] revisions = new long[MapCoords.TILES_PER_REGION];
    private final Identifier textureId;

    private NativeImage image;
    private DynamicTexture texture;
    private volatile boolean dirty = true;
    private volatile boolean closed;
    private int populatedTiles;

    /** The frame this raster was last drawn on, so eviction can leave on-screen regions alone. */
    private long lastUsedFrame;

    public RegionRaster(String dimension, int regionX, int regionZ, int blocksPerPixel) {
        this.dimension = dimension;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.blocksPerPixel = blocksPerPixel;
        this.resolution = MapCoords.REGION_BLOCKS / blocksPerPixel;
        this.image = new NativeImage(resolution, resolution, false);
        this.textureId = XetiusMap.id("region/"
                + MapCoords.dimensionToFolder(dimension).toLowerCase(Locale.ROOT)
                + "/" + (regionX < 0 ? "n" + (-regionX) : regionX)
                + "_" + (regionZ < 0 ? "n" + (-regionZ) : regionZ)
                + "_" + blocksPerPixel);
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

    public long lastUsedFrame() {
        return lastUsedFrame;
    }

    public void markUsed(long frame) {
        this.lastUsedFrame = frame;
    }

    /** Paints one chunk into the raster, averaging blocks together for a coarse raster. */
    public synchronized void paint(ChunkTile tile, long revision) {
        if (closed) {
            return;
        }
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
                for (int x = 0; x < 16; x++) {
                    image.setPixel(baseX + x, baseZ + z, tile.colorAt(x, z));
                }
            }
        } else {
            for (int pz = 0; pz < chunkPixels; pz++) {
                for (int px = 0; px < chunkPixels; px++) {
                    image.setPixel(baseX + px, baseZ + pz, average(tile, px, pz));
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
        if (closed) {
            return 0;
        }
        int localX = Math.floorMod(worldX, MapCoords.REGION_BLOCKS) / blocksPerPixel;
        int localZ = Math.floorMod(worldZ, MapCoords.REGION_BLOCKS) / blocksPerPixel;
        return image.getPixel(localX, localZ);
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
            texture = new DynamicTexture(() -> "xetiusmap/" + dimension + "/" + regionX + "_" + regionZ, image);
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            dirty = false;
        } else if (dirty) {
            texture.upload();
            dirty = false;
        }
        return textureId;
    }

    public void markDirty() {
        dirty = true;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            // DynamicTexture#close also frees the NativeImage backing it.
            texture.close();
            texture = null;
        } else if (image != null) {
            image.close();
        }
        image = null;
        Arrays.fill(revisions, 0L);
    }

    /** Cache key: a raster is identified by dimension, region and resolution. */
    public record Key(String dimension, int regionX, int regionZ, int blocksPerPixel) {
    }

    public Key key() {
        return new Key(dimension, regionX, regionZ, blocksPerPixel);
    }
}
