package dev.xetius.xetiusmap.client.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Small drawing helpers.
 *
 * <p>{@code GuiGraphicsExtractor} only fills axis-aligned rectangles, so anything rotated — the
 * arrows pinned to the minimap edge, for instance — has to be scanline-filled by hand.
 */
public final class Shapes {

    private Shapes() {
    }

    /**
     * A triangle pointing along {@code angleRadians}, measured clockwise from screen-up, which is
     * the same convention the rest of the map uses for bearings.
     *
     * @param length distance from the centre to the tip
     * @param halfWidth half the width of the base
     */
    public static void arrow(GuiGraphicsExtractor graphics, float centreX, float centreY,
                             double angleRadians, float length, float halfWidth, int color) {
        double dirX = Math.sin(angleRadians);
        double dirY = -Math.cos(angleRadians);
        // Perpendicular, for the two base corners.
        double sideX = -dirY;
        double sideY = dirX;

        float tipX = (float) (centreX + dirX * length);
        float tipY = (float) (centreY + dirY * length);
        float baseX = (float) (centreX - dirX * length * 0.4);
        float baseY = (float) (centreY - dirY * length * 0.4);

        triangle(graphics,
                tipX, tipY,
                (float) (baseX + sideX * halfWidth), (float) (baseY + sideY * halfWidth),
                (float) (baseX - sideX * halfWidth), (float) (baseY - sideY * halfWidth),
                color);
    }

    /** Scanline-fills a triangle, one single-pixel-tall rectangle per row. */
    public static void triangle(GuiGraphicsExtractor graphics,
                                float x1, float y1, float x2, float y2, float x3, float y3, int color) {
        int top = (int) Math.floor(Math.min(y1, Math.min(y2, y3)));
        int bottom = (int) Math.ceil(Math.max(y1, Math.max(y2, y3)));
        if (bottom - top > 64) {
            // Not a shape this helper is meant for; refuse rather than burn a frame on it.
            return;
        }

        for (int y = top; y <= bottom; y++) {
            float rowCentre = y + 0.5F;
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;

            for (int edge = 0; edge < 3; edge++) {
                float ax = edge == 0 ? x1 : edge == 1 ? x2 : x3;
                float ay = edge == 0 ? y1 : edge == 1 ? y2 : y3;
                float bx = edge == 0 ? x2 : edge == 1 ? x3 : x1;
                float by = edge == 0 ? y2 : edge == 1 ? y3 : y1;

                if ((ay <= rowCentre && by > rowCentre) || (by <= rowCentre && ay > rowCentre)) {
                    float t = (rowCentre - ay) / (by - ay);
                    float x = ax + t * (bx - ax);
                    min = Math.min(min, x);
                    max = Math.max(max, x);
                }
            }

            if (min <= max) {
                graphics.fill((int) Math.floor(min), y, (int) Math.ceil(max), y + 1, color);
            }
        }
    }

    /** Outlined diamond, the shape used for every waypoint marker. */
    public static void diamond(GuiGraphicsExtractor graphics, int x, int y, int radius, int color, int outline) {
        for (int row = -radius - 1; row <= radius + 1; row++) {
            int half = radius + 1 - Math.abs(row);
            if (half >= 0) {
                graphics.fill(x - half, y + row, x + half + 1, y + row + 1, outline);
            }
        }
        for (int row = -radius; row <= radius; row++) {
            int half = radius - Math.abs(row);
            if (half >= 0) {
                graphics.fill(x - half, y + row, x + half + 1, y + row + 1, color);
            }
        }
    }

    /** Multiplies a packed ARGB colour's alpha by {@code factor}. */
    public static int withAlpha(int argb, float factor) {
        int alpha = (int) (((argb >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, factor)));
        return (alpha << 24) | (argb & 0xFFFFFF);
    }
}
