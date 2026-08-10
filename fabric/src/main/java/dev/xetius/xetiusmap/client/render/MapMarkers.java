package dev.xetius.xetiusmap.client.render;

import dev.xetius.xetiusmap.common.model.Markers;
import dev.xetius.xetiusmap.common.model.Waypoint;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Marker drawing shared by the minimap and the world map, so a waypoint looks the same wherever it
 * appears.
 */
public final class MapMarkers {

    private static final int OUTLINE = 0xFF000000;
    private static final int LABEL_BACKDROP = 0xA0000000;

    private MapMarkers() {
    }

    /** A diamond with the waypoint's colour, and its initial when there is room. */
    public static void drawWaypoint(GuiGraphicsExtractor graphics, float screenX, float screenY,
                                    Waypoint waypoint, boolean withLabel) {
        int x = Math.round(screenX);
        int y = Math.round(screenY);
        int color = 0xFF000000 | waypoint.color();

        for (int row = -4; row <= 4; row++) {
            int halfWidth = 4 - Math.abs(row);
            graphics.fill(x - halfWidth - 1, y + row, x + halfWidth + 2, y + row + 1, OUTLINE);
        }
        for (int row = -3; row <= 3; row++) {
            int halfWidth = 3 - Math.abs(row);
            graphics.fill(x - halfWidth, y + row, x + halfWidth + 1, y + row + 1, color);
        }

        if (withLabel) {
            drawLabel(graphics, x, y - 14, waypoint.name(), 0xFFFFFFFF);
        }
    }

    /** A dot with a facing tick, plus the player's name if labels are on. */
    public static void drawPlayer(GuiGraphicsExtractor graphics, float screenX, float screenY,
                                  Markers.PlayerMarker player, boolean withLabel, Font font,
                                  boolean withHead, int headSize) {
        int x = Math.round(screenX);
        int y = Math.round(screenY);

        if (withHead) {
            MarkerIcons.playerHead(graphics, player.uuid(), screenX, screenY, headSize);
        } else {
            graphics.fill(x - 3, y - 3, x + 3, y + 3, OUTLINE);
            graphics.fill(x - 2, y - 2, x + 2, y + 2, 0xFFFFE070);
        }

        double radians = Math.toRadians(player.yaw());
        int reach = withHead ? headSize / 2 + 3 : 5;
        int tipX = x - (int) Math.round(Math.sin(radians) * reach);
        int tipY = y + (int) Math.round(Math.cos(radians) * reach);
        graphics.fill(tipX - 1, tipY - 1, tipX + 1, tipY + 1, 0xFFFFE070);

        if (withLabel && font != null) {
            int above = withHead ? headSize / 2 + 11 : 14;
            graphics.centeredText(font, Component.literal(player.name()), x, y - above, 0xFFFFFFFF);
        }
    }

    private static void drawLabel(GuiGraphicsExtractor graphics, int x, int y, String text, int color) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        int width = minecraft.font.width(text);
        graphics.fill(x - width / 2 - 2, y - 1, x + width / 2 + 2, y + 10, LABEL_BACKDROP);
        graphics.centeredText(minecraft.font, Component.literal(text), x, y, color);
    }
}
