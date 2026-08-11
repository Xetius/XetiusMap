package dev.xetius.xetiusmap.client.render;

import dev.xetius.xetiusmap.client.MapClient;
import dev.xetius.xetiusmap.client.XetiusMapClient;
import dev.xetius.xetiusmap.client.config.ClientConfig;
import dev.xetius.xetiusmap.common.model.Waypoint;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Waypoint markers floating at their real position in the world.
 *
 * <p>Rather than submitting geometry into 26.2's reworked level pipeline, each waypoint is
 * projected through the camera's own view-rotation-projection matrix and drawn as a flat HUD
 * marker.
 *
 * <p>Markers shrink with distance towards a readable floor, so a horizon full of waypoints stays
 * legible without any one of them dominating the view. Each carries only its initials; the full
 * name appears for the single marker nearest the crosshair, which keeps a crowded view quiet while
 * still letting you identify anything by looking straight at it.
 */
public final class WorldWaypointHud implements HudElement {

    /** Markers this close are faded out so they stop covering what the player is looking at. */
    private static final float FADE_RANGE = 4.0F;

    /** Keeps a crowded horizon from turning into a wall of overlapping markers. */
    private static final int MAX_MARKERS = 32;

    /** The focused marker's name never shrinks below this, or looking at it would not help. */
    private static final float NAME_MIN_SCALE = 0.8F;

    private static final float FOCUS_MIN_PIXELS = 26.0F;

    private final Matrix4f projection = new Matrix4f();
    private final Vector4f scratch = new Vector4f();

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientConfig config = XetiusMapClient.config();
        MapClient client = XetiusMapClient.mapClient();

        if (client == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (!config.showWaypointsInWorld || !config.showWaypoints) {
            return;
        }
        if (minecraft.gui.screen() != null) {
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        if (camera == null || !camera.isInitialized()) {
            return;
        }
        Vec3 eye = camera.position();
        camera.getViewRotationProjectionMatrix(projection);

        float centreX = graphics.guiWidth() / 2.0F;
        float centreY = graphics.guiHeight() / 2.0F;
        float focusRadius = Math.max(FOCUS_MIN_PIXELS,
                graphics.guiHeight() * config.worldWaypointFocusPercent / 100.0F);

        List<Projected> visible = new ArrayList<>();
        Projected focused = null;
        float bestFocusDistance = Float.MAX_VALUE;

        for (Waypoint waypoint : client.waypoints().inDimension(client.dimension())) {
            double dx = waypoint.x() + 0.5 - eye.x;
            double dy = waypoint.y() + 0.5 - eye.y;
            double dz = waypoint.z() + 0.5 - eye.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (config.worldWaypointMaxDistance > 0 && distance > config.worldWaypointMaxDistance) {
                continue;
            }

            scratch.set((float) dx, (float) dy, (float) dz, 1.0F);
            projection.transform(scratch);
            if (scratch.w <= 0.0F) {
                continue;
            }

            float screenX = (scratch.x / scratch.w * 0.5F + 0.5F) * graphics.guiWidth();
            float screenY = (0.5F - scratch.y / scratch.w * 0.5F) * graphics.guiHeight();
            if (screenX < -48 || screenY < -48
                    || screenX > graphics.guiWidth() + 48 || screenY > graphics.guiHeight() + 48) {
                continue;
            }

            Projected marker = new Projected(waypoint, screenX, screenY, distance,
                    fade(config, distance), scale(config, distance));
            visible.add(marker);

            float fromCrosshair = (float) Math.hypot(screenX - centreX, screenY - centreY);
            if (fromCrosshair <= focusRadius && fromCrosshair < bestFocusDistance && marker.alpha() > 0.01F) {
                bestFocusDistance = fromCrosshair;
                focused = marker;
            }
        }

        // Furthest first, so nearer markers land on top of the ones behind them.
        visible.sort(Comparator.comparingDouble(Projected::distance).reversed());
        int from = Math.max(0, visible.size() - MAX_MARKERS);
        for (Projected marker : visible.subList(from, visible.size())) {
            draw(graphics, minecraft, config, marker, marker == focused);
        }
    }

    /**
     * Shrinks the whole marker — icon, initials and distance alike — with an ease-out cubic, so
     * most of the shrinking happens in the first stretch and markers settle to the floor rather
     * than creeping down over hundreds of blocks.
     */
    static float scale(ClientConfig config, double distance) {
        double near = config.worldWaypointFullSizeDistance;
        double far = config.worldWaypointMinSizeDistance;
        float floor = config.worldWaypointMinTextSize / ClientConfig.BASE_TEXT_SIZE;
        if (distance <= near) {
            return 1.0F;
        }
        if (distance >= far) {
            return floor;
        }
        double t = (distance - near) / (far - near);
        double remaining = 1.0 - t;
        double eased = 1.0 - remaining * remaining * remaining;
        return (float) (1.0 - eased * (1.0 - floor));
    }

    private static float fade(ClientConfig config, double distance) {
        if (config.worldWaypointFadeNear <= 0) {
            return 1.0F;
        }
        if (distance >= config.worldWaypointFadeNear + FADE_RANGE) {
            return 1.0F;
        }
        if (distance <= config.worldWaypointFadeNear) {
            return 0.0F;
        }
        return (float) ((distance - config.worldWaypointFadeNear) / FADE_RANGE);
    }

    private void draw(GuiGraphicsExtractor graphics, Minecraft minecraft, ClientConfig config,
                      Projected marker, boolean focused) {
        if (marker.alpha() <= 0.01F) {
            return;
        }

        int color = Shapes.withAlpha(0xFF000000 | marker.waypoint().color(), marker.alpha());
        String initials = initials(marker.waypoint().name());

        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(marker.screenX(), marker.screenY());
        pose.scale(marker.scale());

        // The pin's point sits on the waypoint itself, with the body carrying the initials above it.
        float halfWidth = config.worldWaypointIconSize;
        Shapes.pin(graphics, 0.0F, 0.0F, halfWidth, color);
        // Text sits on a coloured icon, so pick whichever of black or white reads against it.
        graphics.centeredText(minecraft.font, initials,
                0, Math.round(Shapes.pinBodyCentre(0.0F, halfWidth)) - 4,
                Shapes.withAlpha(contrastingText(marker.waypoint().color()), marker.alpha()));

        if (config.showWaypointDistance) {
            graphics.centeredText(minecraft.font, formatDistance(marker.distance()),
                    0, 3, Shapes.withAlpha(0xFFD6D6D6, marker.alpha()));
        }
        pose.popMatrix();

        if (focused) {
            // Drawn in its own transform so the name stays legible however far away it is.
            float nameScale = Math.max(NAME_MIN_SCALE, marker.scale());
            pose.pushMatrix();
            pose.translate(marker.screenX(),
                    marker.screenY() + Shapes.pinTop(0.0F, halfWidth) * marker.scale());
            pose.scale(nameScale);
            graphics.centeredText(minecraft.font, marker.waypoint().name(), 0, -11,
                    Shapes.withAlpha(0xFFFFFFFF, marker.alpha()));
            pose.popMatrix();
        }
    }

    private static String formatDistance(double distance) {
        long blocks = Math.round(distance);
        return blocks >= 1000 ? String.format(Locale.ROOT, "%.1fk", blocks / 1000.0) : blocks + "m";
    }

    /**
     * Up to two characters identifying a waypoint at a glance: the initials of the first two words,
     * or the first two letters when the name is a single word.
     */
    static String initials(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return "?";
        }
        String[] words = trimmed.split("[\\s_\\-]+");
        if (words.length >= 2 && !words[0].isEmpty() && !words[1].isEmpty()) {
            return ("" + Character.toUpperCase(words[0].charAt(0))
                    + Character.toUpperCase(words[1].charAt(0)));
        }
        String word = words[0];
        return word.length() == 1
                ? String.valueOf(Character.toUpperCase(word.charAt(0)))
                : "" + Character.toUpperCase(word.charAt(0)) + Character.toLowerCase(word.charAt(1));
    }

    /** Black on light markers, white on dark ones, by perceived luminance. */
    static int contrastingText(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.55 ? 0xFF101010 : 0xFFFFFFFF;
    }

    private record Projected(Waypoint waypoint, float screenX, float screenY,
                             double distance, float alpha, float scale) {
    }
}
