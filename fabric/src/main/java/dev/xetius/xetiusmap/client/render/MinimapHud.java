package dev.xetius.xetiusmap.client.render;

import dev.xetius.xetiusmap.client.MapClient;
import dev.xetius.xetiusmap.client.XetiusMapClient;
import dev.xetius.xetiusmap.client.config.ClientConfig;
import dev.xetius.xetiusmap.client.map.EntityTracker;
import dev.xetius.xetiusmap.client.screen.WorldMapScreen;
import dev.xetius.xetiusmap.common.model.Markers;
import dev.xetius.xetiusmap.common.model.Waypoint;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/** The always-on minimap overlay. */
public final class MinimapHud implements HudElement {

    private static final int FRAME_COLOR = 0xFF1B1B1F;
    private static final int FRAME_HIGHLIGHT = 0xFF55555F;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int TEXT_SHADOW_BACKDROP = 0x90000000;

    /** How far inside the rim an edge arrow sits, so it is not clipped by the scissor. */
    private static final float EDGE_INSET = 5.0F;
    private static final float EDGE_ARROW_LENGTH = 4.0F;
    private static final float EDGE_ARROW_HALF_WIDTH = 3.0F;

    private static final int MOB_ICON_SIZE = 9;
    private static final int PLAYER_HEAD_SIZE = 8;

    private final MinimapComposer composer = new MinimapComposer();

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientConfig config = XetiusMapClient.config();
        MapClient client = XetiusMapClient.mapClient();

        if (client == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        // The full-screen map already shows everything the minimap would.
        if (!config.minimapEnabled || minecraft.gui.screen() instanceof WorldMapScreen) {
            return;
        }

        client.store().pump();

        int size = config.minimapSize;
        int left = anchorX(graphics.guiWidth(), size, config);
        int top = anchorY(graphics.guiHeight(), size, config);

        double centreX = minecraft.player.getX();
        double centreZ = minecraft.player.getZ();
        float yaw = minecraft.player.getYRot();

        Identifier texture = composer.update(client.store(), client.dimension(), config, centreX, centreZ, yaw, size);
        if (texture == null) {
            return;
        }

        if (config.minimapFrame) {
            graphics.fill(left - 2, top - 2, left + size + 2, top + size + 2, FRAME_COLOR);
            graphics.fill(left - 1, top - 1, left + size + 1, top + size + 1, FRAME_HIGHLIGHT);
        }
        graphics.blit(texture, left, top, left + size, top + size, 0.0F, 1.0F, 0.0F, 1.0F);

        graphics.enableScissor(left, top, left + size, top + size);
        drawMarkers(graphics, client, config, left, top, size, centreX, centreZ, yaw);
        graphics.disableScissor();

        drawPlayerArrow(graphics, left + size / 2, top + size / 2, config.minimapRotate ? 0.0F : yaw);

        if (config.showDirections) {
            drawCompass(graphics, minecraft, left, top, size, config.minimapRotate ? yaw : 0.0F);
        }
        drawLabels(graphics, minecraft, client, config, left, top, size);
    }

    private void drawMarkers(GuiGraphicsExtractor graphics, MapClient client, ClientConfig config,
                             int left, int top, int size, double centreX, double centreZ, float yaw) {
        float scale = ClientConfig.Zoom.scale(config.minimapZoom);
        float effectiveYaw = config.minimapRotate ? yaw : 0.0F;
        String dimension = client.dimension();

        // Mobs get no edge indicator: they move constantly and are not things you navigate towards,
        // so pinning them to the rim would be a ring of noise.
        if (config.showMobs) {
            int viewerY = Minecraft.getInstance().player == null
                    ? 0 : Minecraft.getInstance().player.getBlockY();
            List<String> palette = client.entities().typePalette();
            for (Markers.MobMarker mob : client.entities().mobs()) {
                if (!config.showsMob(mob.skyVisible(), mob.y(), viewerY)) {
                    continue;
                }
                float[] at = MinimapComposer.project(mob.x() + 0.5, mob.z() + 0.5,
                        centreX, centreZ, effectiveYaw, scale, size);
                if (!inside(at, size)) {
                    continue;
                }
                float markerX = left + at[0];
                float markerY = top + at[1];
                boolean drawn = config.showMobIcons
                        && mob.typeIndex() >= 0 && mob.typeIndex() < palette.size()
                        && MarkerIcons.mobIcon(graphics, palette.get(mob.typeIndex()),
                                markerX, markerY, MOB_ICON_SIZE);
                if (!drawn) {
                    dot(graphics, markerX, markerY, 2, EntityTracker.colorFor(mob.category()));
                }
            }
        }

        if (config.showWaypoints) {
            for (Waypoint waypoint : client.waypoints().inDimension(dimension)) {
                float[] at = MinimapComposer.project(waypoint.x() + 0.5, waypoint.z() + 0.5,
                        centreX, centreZ, effectiveYaw, scale, size);
                if (inside(at, size)) {
                    MapMarkers.drawWaypoint(graphics, left + at[0], top + at[1], waypoint, false);
                } else if (config.edgeIndicatorWaypoints) {
                    drawEdgeIndicator(graphics, left, top, size, config, at, 0xFF000000 | waypoint.color());
                }
            }
        }

        if (config.showPlayers) {
            java.util.UUID self = Minecraft.getInstance().player == null
                    ? null : Minecraft.getInstance().player.getUUID();
            for (Markers.PlayerMarker player : client.entities().players()) {
                if (player.uuid().equals(self) || !player.dimension().equals(dimension)) {
                    continue;
                }
                float[] at = MinimapComposer.project(player.x() + 0.5, player.z() + 0.5,
                        centreX, centreZ, effectiveYaw, scale, size);
                if (inside(at, size)) {
                    MapMarkers.drawPlayer(graphics, left + at[0], top + at[1], player,
                            config.showPlayerNames, Minecraft.getInstance().font, config.showPlayerHeads,
                            PLAYER_HEAD_SIZE);
                } else if (config.edgeIndicatorPlayers) {
                    drawEdgeIndicator(graphics, left, top, size, config, at, 0xFFFFE070);
                }
            }
        }
    }

    private static boolean inside(float[] at, int size) {
        return at[0] >= 0 && at[1] >= 0 && at[0] < size && at[1] < size;
    }

    /**
     * Pins an off-map marker to the rim with an arrow pointing the way to it, so you can still walk
     * towards something that has scrolled out of the minimap's range.
     */
    private static void drawEdgeIndicator(GuiGraphicsExtractor graphics, int left, int top, int size,
                                          ClientConfig config, float[] at, int color) {
        float half = size / 2.0F;
        float dx = at[0] - half;
        float dy = at[1] - half;
        if (dx == 0 && dy == 0) {
            return;
        }

        float limit = half - EDGE_INSET;
        float scale = config.minimapShape == ClientConfig.Shape.CIRCLE
                ? limit / (float) Math.sqrt(dx * dx + dy * dy)
                : limit / Math.max(Math.abs(dx), Math.abs(dy));

        float x = left + half + dx * scale;
        float y = top + half + dy * scale;
        double angle = Math.atan2(dx, -dy);

        Shapes.arrow(graphics, x, y, angle, EDGE_ARROW_LENGTH + 1.0F, EDGE_ARROW_HALF_WIDTH + 1.0F, 0xFF000000);
        Shapes.arrow(graphics, x, y, angle, EDGE_ARROW_LENGTH, EDGE_ARROW_HALF_WIDTH, color);
    }

    private static void dot(GuiGraphicsExtractor graphics, float x, float y, int radius, int color) {
        int cx = Math.round(x);
        int cy = Math.round(y);
        graphics.fill(cx - radius, cy - radius, cx + radius, cy + radius, color);
        graphics.fill(cx - radius, cy - radius, cx + radius, cy - radius + 1, 0x60000000);
    }

    /** A small arrow at the centre, pointing the way the player faces. */
    private static void drawPlayerArrow(GuiGraphicsExtractor graphics, int x, int y, float yaw) {
        int color = 0xFFFFFFFF;
        int outline = 0xFF000000;
        graphics.fill(x - 3, y - 3, x + 3, y + 3, outline);
        graphics.fill(x - 2, y - 2, x + 2, y + 2, color);
        // With north-up the arrow needs to indicate facing; with rotation the map already does.
        if (yaw != 0.0F) {
            double radians = Math.toRadians(yaw);
            int tipX = x - (int) Math.round(Math.sin(radians) * 5);
            int tipY = y + (int) Math.round(Math.cos(radians) * 5);
            graphics.fill(tipX - 1, tipY - 1, tipX + 1, tipY + 1, color);
        }
    }

    private static void drawCompass(GuiGraphicsExtractor graphics, Minecraft minecraft,
                                    int left, int top, int size, float yaw) {
        String[] letters = {"N", "E", "S", "W"};
        // North is -Z, which is up on an unrotated map.
        for (int i = 0; i < letters.length; i++) {
            double bearing = Math.toRadians(i * 90.0 - yaw);
            double radius = size / 2.0 - 7;
            int x = left + size / 2 + (int) Math.round(Math.sin(bearing) * radius);
            int y = top + size / 2 - (int) Math.round(Math.cos(bearing) * radius);
            graphics.centeredText(minecraft.font, letters[i], x, y - 4, 0xFFFFFFFF);
        }
    }

    private static void drawLabels(GuiGraphicsExtractor graphics, Minecraft minecraft, MapClient client,
                                   ClientConfig config, int left, int top, int size) {
        int y = top + size + 3;
        if (config.showCoordinates && minecraft.player != null) {
            String text = minecraft.player.getBlockX() + ", "
                    + minecraft.player.getBlockY() + ", "
                    + minecraft.player.getBlockZ();
            label(graphics, minecraft, text, left, y, size);
            y += 10;
        }
        if (config.showBiome && minecraft.level != null && minecraft.player != null) {
            String biome = minecraft.level.getBiome(minecraft.player.blockPosition())
                    .unwrapKey().<String>map(key -> key.identifier().getPath()).orElse("unknown");
            label(graphics, minecraft, biome, left, y, size);
            y += 10;
        }
        if (!client.serverBacked()) {
            label(graphics, minecraft, "local map", left, y, size);
        }
    }

    private static void label(GuiGraphicsExtractor graphics, Minecraft minecraft,
                              String text, int left, int y, int size) {
        int width = minecraft.font.width(text);
        int x = left + (size - width) / 2;
        graphics.fill(x - 2, y - 1, x + width + 2, y + 9, TEXT_SHADOW_BACKDROP);
        graphics.text(minecraft.font, Component.literal(text), x, y, TEXT_COLOR);
    }

    private static int anchorX(int screenWidth, int size, ClientConfig config) {
        return switch (config.minimapAnchor) {
            case TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT -> config.minimapOffsetX;
            case TOP_CENTER, BOTTOM_CENTER -> (screenWidth - size) / 2;
            case TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT -> screenWidth - size - config.minimapOffsetX;
        };
    }

    private static int anchorY(int screenHeight, int size, ClientConfig config) {
        return switch (config.minimapAnchor) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> config.minimapOffsetY;
            case MIDDLE_LEFT, MIDDLE_RIGHT -> (screenHeight - size) / 2;
            case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> screenHeight - size - config.minimapOffsetY;
        };
    }

    public void close() {
        composer.close();
    }
}
