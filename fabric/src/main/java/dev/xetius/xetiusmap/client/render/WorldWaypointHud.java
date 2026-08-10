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
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Waypoint markers floating at their real position in the world.
 *
 * <p>Rather than submitting geometry into 26.2's reworked level pipeline, each waypoint is
 * projected through the camera's own view-rotation-projection matrix and drawn as a flat HUD
 * marker. That is both simpler and steadier than world-space geometry: the marker keeps a constant
 * size at any distance, so a base a thousand blocks away is still a readable label rather than a
 * sub-pixel speck.
 */
public final class WorldWaypointHud implements HudElement {

    /** Markers this close are faded out so they stop covering what the player is looking at. */
    private static final float FADE_RANGE = 4.0F;

    /** Keeps a crowded horizon from turning into a wall of overlapping labels. */
    private static final int MAX_MARKERS = 24;

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
        // A screen of its own already shows this information; drawing under it is just clutter.
        if (minecraft.gui.screen() != null) {
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        if (camera == null || !camera.isInitialized()) {
            return;
        }
        Vec3 eye = camera.position();
        camera.getViewRotationProjectionMatrix(projection);

        List<Projected> visible = new ArrayList<>();
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
            if (screenX < -32 || screenY < -32
                    || screenX > graphics.guiWidth() + 32 || screenY > graphics.guiHeight() + 32) {
                continue;
            }

            visible.add(new Projected(waypoint, screenX, screenY, distance, fade(config, distance)));
        }

        // Furthest first, so nearer markers land on top of the ones behind them.
        visible.sort(Comparator.comparingDouble(Projected::distance).reversed());
        int from = Math.max(0, visible.size() - MAX_MARKERS);
        for (Projected marker : visible.subList(from, visible.size())) {
            draw(graphics, minecraft, config, marker);
        }
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

    private void draw(GuiGraphicsExtractor graphics, Minecraft minecraft, ClientConfig config, Projected marker) {
        if (marker.alpha() <= 0.01F) {
            return;
        }
        int x = Math.round(marker.screenX());
        int y = Math.round(marker.screenY());
        int color = Shapes.withAlpha(0xFF000000 | marker.waypoint().color(), marker.alpha());
        int outline = Shapes.withAlpha(0xFF000000, marker.alpha());

        Shapes.diamond(graphics, x, y, 4, color, outline);

        String name = marker.waypoint().name();
        int nameWidth = minecraft.font.width(name);
        int textY = y - 18;
        graphics.fill(x - nameWidth / 2 - 2, textY - 1, x + nameWidth / 2 + 2, textY + 9,
                Shapes.withAlpha(0xA0000000, marker.alpha()));
        graphics.centeredText(minecraft.font, Component.literal(name), x, textY,
                Shapes.withAlpha(0xFFFFFFFF, marker.alpha()));

        if (config.showWaypointDistance) {
            String distance = Math.round(marker.distance()) + "m";
            int distanceWidth = minecraft.font.width(distance);
            int distanceY = y + 8;
            graphics.fill(x - distanceWidth / 2 - 2, distanceY - 1, x + distanceWidth / 2 + 2, distanceY + 9,
                    Shapes.withAlpha(0xA0000000, marker.alpha()));
            graphics.centeredText(minecraft.font, Component.literal(distance), x, distanceY,
                    Shapes.withAlpha(0xFFD0D0D0, marker.alpha()));
        }
    }

    private record Projected(Waypoint waypoint, float screenX, float screenY, double distance, float alpha) {
    }
}
