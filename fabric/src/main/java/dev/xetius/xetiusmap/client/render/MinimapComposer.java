package dev.xetius.xetiusmap.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.xetius.xetiusmap.client.XetiusMap;
import dev.xetius.xetiusmap.client.config.ClientConfig;
import dev.xetius.xetiusmap.client.map.MapDataStore;
import dev.xetius.xetiusmap.client.map.RegionRaster;
import dev.xetius.xetiusmap.common.util.MapCoords;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Builds the minimap image a pixel at a time on the CPU.
 *
 * <p>Doing it this way rather than blitting region textures buys two things the GPU path cannot
 * easily give: the map can rotate with the player without fighting the scissor rectangle, and a
 * round minimap is a per-pixel test rather than an impossible clip shape. At the default 128 pixels
 * square that is sixteen thousand array lookups, and the result is only rebuilt when the view has
 * actually moved.
 */
public final class MinimapComposer implements AutoCloseable {

    /** Shown where nothing has been mapped yet. */
    private static final int UNEXPLORED = 0x30101418;

    private final Identifier textureId = XetiusMap.id("minimap");

    private NativeImage image;
    private DynamicTexture texture;
    private int size;

    // What the current image was built from, so an unchanged view costs nothing.
    private double lastCentreX = Double.NaN;
    private double lastCentreZ = Double.NaN;
    private float lastYaw = Float.NaN;
    private float lastScale = -1.0F;
    private String lastDimension = "";
    private ClientConfig.Shape lastShape;
    private int lastGeneration = -1;

    /**
     * Redraws if anything has changed and returns the texture to blit.
     *
     * <p>Render thread only.
     */
    public Identifier update(MapDataStore store, String dimension, ClientConfig config,
                             double centreX, double centreZ, float yaw, int requestedSize) {
        float scale = ClientConfig.Zoom.scale(config.minimapZoom);
        float effectiveYaw = config.minimapRotate ? yaw : 0.0F;

        ensureSize(requestedSize);
        if (image == null) {
            return null;
        }

        boolean unchanged = dimension.equals(lastDimension)
                && config.minimapShape == lastShape
                && scale == lastScale
                && store.generation() == lastGeneration
                && Math.abs(centreX - lastCentreX) * scale < 0.5
                && Math.abs(centreZ - lastCentreZ) * scale < 0.5
                && Math.abs(angleDelta(effectiveYaw, lastYaw)) < 0.75F;
        if (unchanged) {
            return textureId;
        }

        compose(store, dimension, config, centreX, centreZ, effectiveYaw, scale);

        lastDimension = dimension;
        lastShape = config.minimapShape;
        lastScale = scale;
        lastGeneration = store.generation();
        lastCentreX = centreX;
        lastCentreZ = centreZ;
        lastYaw = effectiveYaw;
        return textureId;
    }

    private static float angleDelta(float a, float b) {
        if (Float.isNaN(b)) {
            return Float.MAX_VALUE;
        }
        float delta = (a - b) % 360.0F;
        if (delta > 180.0F) {
            delta -= 360.0F;
        }
        if (delta < -180.0F) {
            delta += 360.0F;
        }
        return delta;
    }

    private void ensureSize(int requestedSize) {
        if (texture != null && size == requestedSize) {
            return;
        }
        close();
        size = requestedSize;
        image = new NativeImage(size, size, false);
        texture = new DynamicTexture(() -> "xetiusmap/minimap", image);
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
        lastGeneration = -1;
        lastScale = -1.0F;
    }

    private void compose(MapDataStore store, String dimension, ClientConfig config,
                         double centreX, double centreZ, float yaw, float scale) {
        double half = size / 2.0;
        double radiusSquared = half * half;

        // The player's facing becomes screen-up when the map rotates; north-up is the identity.
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);

        RegionRaster cached = null;
        int cachedRegionX = Integer.MIN_VALUE;
        int cachedRegionZ = Integer.MIN_VALUE;
        int blocksPerPixel = scale >= 0.5F
                ? RegionRaster.DETAIL_BLOCKS_PER_PIXEL
                : RegionRaster.COARSE_BLOCKS_PER_PIXEL;

        for (int py = 0; py < size; py++) {
            double dy = py - half + 0.5;
            for (int px = 0; px < size; px++) {
                double dx = px - half + 0.5;

                if (config.minimapShape == ClientConfig.Shape.CIRCLE
                        && dx * dx + dy * dy > radiusSquared) {
                    image.setPixel(px, py, 0);
                    continue;
                }

                double offsetX;
                double offsetZ;
                if (yaw == 0.0F) {
                    offsetX = dx / scale;
                    offsetZ = dy / scale;
                } else {
                    offsetX = (-cos * dx + sin * dy) / scale;
                    offsetZ = (-sin * dx - cos * dy) / scale;
                }

                int worldX = (int) Math.floor(centreX + offsetX);
                int worldZ = (int) Math.floor(centreZ + offsetZ);
                int regionX = MapCoords.blockToRegion(worldX);
                int regionZ = MapCoords.blockToRegion(worldZ);

                if (cached == null || regionX != cachedRegionX || regionZ != cachedRegionZ) {
                    cached = store.raster(dimension, regionX, regionZ, blocksPerPixel);
                    cachedRegionX = regionX;
                    cachedRegionZ = regionZ;
                }

                int color = cached == null ? 0 : cached.colorAtBlock(worldX, worldZ);
                image.setPixel(px, py, (color >>> 24) == 0 ? UNEXPLORED : color);
            }
        }
        texture.upload();
    }

    /** Maps a world position onto minimap pixel coordinates, matching {@link #compose}. */
    public static float[] project(double worldX, double worldZ, double centreX, double centreZ,
                                  float yaw, float scale, int size) {
        double offsetX = worldX - centreX;
        double offsetZ = worldZ - centreZ;
        double half = size / 2.0;
        if (yaw == 0.0F) {
            return new float[]{(float) (half + offsetX * scale), (float) (half + offsetZ * scale)};
        }
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        // Inverse of the sampling rotation above.
        double dx = -cos * offsetX - sin * offsetZ;
        double dy = sin * offsetX - cos * offsetZ;
        return new float[]{(float) (half + dx * scale), (float) (half + dy * scale)};
    }

    @Override
    public void close() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            texture.close();
            texture = null;
            image = null;
        }
        lastGeneration = -1;
    }
}
