package dev.xetius.xetiusmap.client.screen;

import dev.xetius.xetiusmap.client.MapClient;
import dev.xetius.xetiusmap.client.XetiusMapClient;
import dev.xetius.xetiusmap.client.config.ClientConfig;
import dev.xetius.xetiusmap.client.map.EntityTracker;
import dev.xetius.xetiusmap.client.map.RegionRaster;
import dev.xetius.xetiusmap.client.render.MapMarkers;
import dev.xetius.xetiusmap.client.render.MarkerIcons;
import dev.xetius.xetiusmap.common.model.Markers;
import dev.xetius.xetiusmap.common.model.Waypoint;
import dev.xetius.xetiusmap.common.util.MapCoords;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The full-screen map: pan, zoom, switch dimension, and manage the shared waypoints.
 *
 * <p>Terrain is drawn by blitting each region's texture with the visible sub-rectangle, which keeps
 * a screen full of map to a few dozen draw calls no matter how far out it is zoomed. Region
 * boundaries are rounded with the same expression on both sides, so adjacent regions always land on
 * the same pixel and no seams appear.
 */
public final class WorldMapScreen extends Screen {

    private static final int PANEL_WIDTH = 172;
    private static final int BACKGROUND = 0xFF0B0E12;
    private static final int PANEL_BACKGROUND = 0xF0151A21;
    private static final int PANEL_LINE = 0xFF2A323D;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int TEXT_DIM = 0xFF9AA4B0;
    private static final int SELECTION = 0x60FFAA00;
    private static final int UNEXPLORED = 0xFF12161C;

    /** Safety valve: a hugely zoomed-out view must not try to draw thousands of regions. */
    private static final int MAX_REGIONS_PER_FRAME = 512;

    private static final int ROW_HEIGHT = 12;

    private static final int MOB_ICON_SIZE = 11;
    private static final int PLAYER_HEAD_SIZE = 10;

    private double centreX;
    private double centreZ;
    private String viewedDimension;

    private boolean dragging;
    private double dragAnchorX;
    private double dragAnchorZ;
    private double dragMouseX;
    private double dragMouseY;

    private UUID selectedWaypoint;
    private int listScroll;

    private Button teleportButton;
    private Button editButton;
    private Button deleteButton;

    public WorldMapScreen() {
        super(Component.literal("XetiusMap"));
    }

    @Override
    protected void init() {
        MapClient client = XetiusMapClient.mapClient();
        if (client == null) {
            return;
        }
        if (viewedDimension == null) {
            viewedDimension = client.dimension();
            centreOnPlayer();
        }

        int panelX = width - PANEL_WIDTH;
        int buttonWidth = PANEL_WIDTH - 16;
        int y = height - 96;

        addRenderableWidget(Button.builder(Component.literal("New waypoint here"), b -> createWaypointAtCentre())
                .bounds(panelX + 8, y, buttonWidth, 18).build());
        y += 21;

        teleportButton = addRenderableWidget(Button.builder(Component.literal("Teleport"), b -> teleportToSelection())
                .bounds(panelX + 8, y, buttonWidth, 18).build());
        y += 21;

        editButton = addRenderableWidget(Button.builder(Component.literal("Edit"), b -> editSelection())
                .bounds(panelX + 8, y, (buttonWidth - 4) / 2, 18).build());
        deleteButton = addRenderableWidget(Button.builder(Component.literal("Delete"), b -> deleteSelection())
                .bounds(panelX + 8 + (buttonWidth + 4) / 2, y, (buttonWidth - 4) / 2, 18).build());
        y += 21;

        addRenderableWidget(Button.builder(Component.literal("Dimension"), b -> cycleDimension())
                .bounds(panelX + 8, y, (buttonWidth - 4) / 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Centre on me"), b -> {
            viewedDimension = client.dimension();
            centreOnPlayer();
        }).bounds(panelX + 8 + (buttonWidth + 4) / 2, y, (buttonWidth - 4) / 2, 18).build());

        updateButtonState();
    }

    private void centreOnPlayer() {
        if (minecraft != null && minecraft.player != null) {
            centreX = minecraft.player.getX();
            centreZ = minecraft.player.getZ();
        }
    }

    private void updateButtonState() {
        MapClient client = XetiusMapClient.mapClient();
        boolean hasSelection = selectedWaypoint != null && client != null
                && client.waypoints().byId(selectedWaypoint).isPresent();
        if (teleportButton != null) {
            teleportButton.active = hasSelection && client.canTeleport();
        }
        if (editButton != null) {
            editButton.active = hasSelection;
        }
        if (deleteButton != null) {
            deleteButton.active = hasSelection;
        }
    }

    // --- Geometry ----------------------------------------------------------------------------

    private int mapWidth() {
        return width - PANEL_WIDTH;
    }

    private float pixelsPerBlock() {
        return ClientConfig.Zoom.scale(XetiusMapClient.config().worldMapZoom);
    }

    private double screenX(double worldX) {
        return mapWidth() / 2.0 + (worldX - centreX) * pixelsPerBlock();
    }

    private double screenY(double worldZ) {
        return height / 2.0 + (worldZ - centreZ) * pixelsPerBlock();
    }

    private double worldXAt(double screenX) {
        return centreX + (screenX - mapWidth() / 2.0) / pixelsPerBlock();
    }

    private double worldZAt(double screenY) {
        return centreZ + (screenY - height / 2.0) / pixelsPerBlock();
    }

    // --- Rendering ---------------------------------------------------------------------------

    /** The map covers every pixel, so the vanilla blurred backdrop would only be wasted work. */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Intentionally empty.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        MapClient client = XetiusMapClient.mapClient();
        if (client == null) {
            return;
        }
        client.store().pump();

        graphics.fill(0, 0, width, height, BACKGROUND);

        graphics.enableScissor(0, 0, mapWidth(), height);
        drawTerrain(graphics, client);
        drawMarkers(graphics, client);
        graphics.disableScissor();

        drawPanel(graphics, client, mouseX, mouseY);
        drawTopBar(graphics, client, mouseX, mouseY);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawTerrain(GuiGraphicsExtractor graphics, MapClient client) {
        float pixelsPerBlock = pixelsPerBlock();
        int blocksPerPixel = pixelsPerBlock >= 0.5F
                ? RegionRaster.DETAIL_BLOCKS_PER_PIXEL
                : RegionRaster.COARSE_BLOCKS_PER_PIXEL;

        int minRegionX = MapCoords.blockToRegion((int) Math.floor(worldXAt(0)));
        int maxRegionX = MapCoords.blockToRegion((int) Math.ceil(worldXAt(mapWidth())));
        int minRegionZ = MapCoords.blockToRegion((int) Math.floor(worldZAt(0)));
        int maxRegionZ = MapCoords.blockToRegion((int) Math.ceil(worldZAt(height)));

        Set<Long> visible = new HashSet<>();
        int drawn = 0;
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ && drawn < MAX_REGIONS_PER_FRAME; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX && drawn < MAX_REGIONS_PER_FRAME; regionX++) {
                drawn++;
                visible.add(MapCoords.key(regionX, regionZ));

                int x0 = (int) Math.round(screenX((double) regionX * MapCoords.REGION_BLOCKS));
                int x1 = (int) Math.round(screenX((double) (regionX + 1) * MapCoords.REGION_BLOCKS));
                int y0 = (int) Math.round(screenY((double) regionZ * MapCoords.REGION_BLOCKS));
                int y1 = (int) Math.round(screenY((double) (regionZ + 1) * MapCoords.REGION_BLOCKS));
                if (x1 <= 0 || y1 <= 0 || x0 >= mapWidth() || y0 >= height) {
                    continue;
                }

                RegionRaster raster = client.store().raster(viewedDimension, regionX, regionZ, blocksPerPixel);
                if (raster == null || raster.isEmpty()) {
                    graphics.fill(Math.max(0, x0), Math.max(0, y0),
                            Math.min(mapWidth(), x1), Math.min(height, y1), UNEXPLORED);
                    continue;
                }
                Identifier texture = raster.texture();
                if (texture != null) {
                    graphics.blit(texture, x0, y0, x1, y1, 0.0F, 1.0F, 0.0F, 1.0F);
                }
            }
        }

        // Keep the server pushing updates for whatever is actually on screen.
        client.setViewRegions(viewedDimension, visible);
    }

    private void drawMarkers(GuiGraphicsExtractor graphics, MapClient client) {
        ClientConfig config = XetiusMapClient.config();

        if (config.showMobs && viewedDimension.equals(client.entities().dimension())) {
            int viewerY = minecraft != null && minecraft.player != null ? minecraft.player.getBlockY() : 0;
            List<String> palette = client.entities().typePalette();
            for (Markers.MobMarker mob : client.entities().mobs()) {
                if (!config.showsMobAtHeight(mob.y(), viewerY)) {
                    continue;
                }
                float x = (float) screenX(mob.x() + 0.5);
                float y = (float) screenY(mob.z() + 0.5);
                boolean drawn = config.showMobIcons
                        && mob.typeIndex() >= 0 && mob.typeIndex() < palette.size()
                        && MarkerIcons.mobIcon(graphics, palette.get(mob.typeIndex()), x, y, MOB_ICON_SIZE);
                if (!drawn) {
                    int ix = Math.round(x);
                    int iy = Math.round(y);
                    graphics.fill(ix - 2, iy - 2, ix + 2, iy + 2, EntityTracker.colorFor(mob.category()));
                }
            }
        }

        if (config.showWaypoints) {
            for (Waypoint waypoint : client.waypoints().inDimension(viewedDimension)) {
                MapMarkers.drawWaypoint(graphics,
                        (float) screenX(waypoint.x() + 0.5),
                        (float) screenY(waypoint.z() + 0.5),
                        waypoint,
                        true);
                if (waypoint.id().equals(selectedWaypoint)) {
                    int x = (int) Math.round(screenX(waypoint.x() + 0.5));
                    int y = (int) Math.round(screenY(waypoint.z() + 0.5));
                    graphics.outline(x - 8, y - 8, 16, 16, 0xFFFFAA00);
                }
            }
        }

        if (config.showPlayers) {
            for (Markers.PlayerMarker player : client.entities().players()) {
                if (!player.dimension().equals(viewedDimension)) {
                    continue;
                }
                MapMarkers.drawPlayer(graphics,
                        (float) screenX(player.x() + 0.5),
                        (float) screenY(player.z() + 0.5),
                        player, config.showPlayerNames, font, config.showPlayerHeads, PLAYER_HEAD_SIZE);
            }
        }
    }

    private void drawTopBar(GuiGraphicsExtractor graphics, MapClient client, int mouseX, int mouseY) {
        graphics.fill(0, 0, mapWidth(), 20, PANEL_BACKGROUND);
        graphics.fill(0, 20, mapWidth(), 21, PANEL_LINE);

        String dimensionLabel = shortName(viewedDimension);
        graphics.text(font, Component.literal(dimensionLabel), 8, 6, TEXT);

        String zoomLabel = "Zoom " + ClientConfig.Zoom.label(XetiusMapClient.config().worldMapZoom);
        graphics.text(font, Component.literal(zoomLabel), 8 + font.width(dimensionLabel) + 16, 6, TEXT_DIM);

        if (mouseX < mapWidth() && mouseY > 20) {
            String under = (int) Math.floor(worldXAt(mouseX)) + ", " + (int) Math.floor(worldZAt(mouseY));
            graphics.text(font, Component.literal(under), mapWidth() - font.width(under) - 8, 6, TEXT_DIM);
        }
    }

    private void drawPanel(GuiGraphicsExtractor graphics, MapClient client, int mouseX, int mouseY) {
        int panelX = width - PANEL_WIDTH;
        graphics.fill(panelX, 0, width, height, PANEL_BACKGROUND);
        graphics.fill(panelX, 0, panelX + 1, height, PANEL_LINE);

        graphics.text(font, Component.literal("Waypoints"), panelX + 8, 8, TEXT);
        graphics.text(font, Component.literal(client.status()), panelX + 8, 20, TEXT_DIM);

        int listTop = 36;
        int listBottom = height - 100;
        graphics.enableScissor(panelX + 4, listTop, width - 4, listBottom);

        List<Waypoint> waypoints = client.waypoints().sortedByName();
        int y = listTop - listScroll;
        for (Waypoint waypoint : waypoints) {
            if (y + ROW_HEIGHT >= listTop && y <= listBottom) {
                boolean selected = waypoint.id().equals(selectedWaypoint);
                if (selected) {
                    graphics.fill(panelX + 4, y, width - 4, y + ROW_HEIGHT, SELECTION);
                }
                graphics.fill(panelX + 8, y + 3, panelX + 14, y + 9, 0xFF000000 | waypoint.color());
                boolean here = waypoint.dimension().equals(viewedDimension);
                graphics.text(font, Component.literal(trim(waypoint.name(), 22)),
                        panelX + 18, y + 2, here ? TEXT : TEXT_DIM);
            }
            y += ROW_HEIGHT;
        }
        graphics.disableScissor();

        if (waypoints.isEmpty()) {
            graphics.text(font, Component.literal("None yet"), panelX + 8, listTop + 2, TEXT_DIM);
        }

        Waypoint selected = selectedWaypoint == null
                ? null : client.waypoints().byId(selectedWaypoint).orElse(null);
        if (selected != null) {
            String coords = selected.x() + ", " + selected.y() + ", " + selected.z();
            graphics.text(font, Component.literal(coords), panelX + 8, listBottom + 4, TEXT_DIM);
            graphics.text(font, Component.literal(shortName(selected.dimension()) + " · " + selected.ownerName()),
                    panelX + 8, listBottom + 14, TEXT_DIM);
        }
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String shortName(String dimension) {
        int colon = dimension.indexOf(':');
        return colon >= 0 ? dimension.substring(colon + 1) : dimension;
    }

    // --- Input -------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MapClient client = XetiusMapClient.mapClient();
        if (client == null) {
            return super.mouseClicked(event, doubleClick);
        }

        double mouseX = event.x();
        double mouseY = event.y();

        if (mouseX >= width - PANEL_WIDTH) {
            if (selectWaypointFromList(client, mouseX, mouseY)) {
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            createWaypointAt((int) Math.floor(worldXAt(mouseX)), (int) Math.floor(worldZAt(mouseY)));
            return true;
        }

        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Waypoint hit = waypointAt(client, mouseX, mouseY);
            if (hit != null) {
                selectedWaypoint = hit.id();
                updateButtonState();
                return true;
            }
            dragging = true;
            dragMouseX = mouseX;
            dragMouseY = mouseY;
            dragAnchorX = centreX;
            dragAnchorZ = centreZ;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean selectWaypointFromList(MapClient client, double mouseX, double mouseY) {
        int listTop = 36;
        int listBottom = height - 100;
        if (mouseY < listTop || mouseY > listBottom) {
            return false;
        }
        int index = (int) ((mouseY - listTop + listScroll) / ROW_HEIGHT);
        List<Waypoint> waypoints = client.waypoints().sortedByName();
        if (index < 0 || index >= waypoints.size()) {
            return false;
        }
        Waypoint waypoint = waypoints.get(index);
        selectedWaypoint = waypoint.id();
        viewedDimension = waypoint.dimension();
        centreX = waypoint.x();
        centreZ = waypoint.z();
        updateButtonState();
        return true;
    }

    private Waypoint waypointAt(MapClient client, double mouseX, double mouseY) {
        for (Waypoint waypoint : client.waypoints().inDimension(viewedDimension)) {
            double dx = screenX(waypoint.x() + 0.5) - mouseX;
            double dy = screenY(waypoint.z() + 0.5) - mouseY;
            if (dx * dx + dy * dy <= 36) {
                return waypoint;
            }
        }
        return null;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging) {
            float pixelsPerBlock = pixelsPerBlock();
            centreX = dragAnchorX - (event.x() - dragMouseX) / pixelsPerBlock;
            centreZ = dragAnchorZ - (event.y() - dragMouseY) / pixelsPerBlock;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= width - PANEL_WIDTH) {
            listScroll = Math.max(0, listScroll - (int) (scrollY * ROW_HEIGHT * 2));
            return true;
        }
        if (scrollY == 0) {
            return false;
        }

        // Keep whatever is under the cursor under the cursor.
        double anchorWorldX = worldXAt(mouseX);
        double anchorWorldZ = worldZAt(mouseY);

        ClientConfig config = XetiusMapClient.config();
        config.worldMapZoom = scrollY > 0
                ? ClientConfig.Zoom.in(config.worldMapZoom)
                : ClientConfig.Zoom.out(config.worldMapZoom);

        centreX = anchorWorldX - (mouseX - mapWidth() / 2.0) / pixelsPerBlock();
        centreZ = anchorWorldZ - (mouseY - height / 2.0) / pixelsPerBlock();
        XetiusMapClient.saveConfig();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        ClientConfig config = XetiusMapClient.config();
        int pan = (int) (64 / pixelsPerBlock());
        switch (event.key()) {
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> {
                config.worldMapZoom = ClientConfig.Zoom.in(config.worldMapZoom);
                XetiusMapClient.saveConfig();
                return true;
            }
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> {
                config.worldMapZoom = ClientConfig.Zoom.out(config.worldMapZoom);
                XetiusMapClient.saveConfig();
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                centreX -= pan;
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                centreX += pan;
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                centreZ -= pan;
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                centreZ += pan;
                return true;
            }
            case GLFW.GLFW_KEY_C -> {
                centreOnPlayer();
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                cycleDimension();
                return true;
            }
            default -> {
                // Fall through to the default handling below.
            }
        }
        if (XetiusMapClient.WORLD_MAP_KEY.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    // --- Actions -----------------------------------------------------------------------------

    private void cycleDimension() {
        MapClient client = XetiusMapClient.mapClient();
        if (client == null) {
            return;
        }
        List<String> dimensions = new ArrayList<>(client.availableDimensions());
        if (!dimensions.contains(client.dimension())) {
            dimensions.add(client.dimension());
        }
        if (dimensions.isEmpty()) {
            return;
        }
        int index = dimensions.indexOf(viewedDimension);
        viewedDimension = dimensions.get((index + 1) % dimensions.size());
    }

    private void createWaypointAtCentre() {
        createWaypointAt((int) Math.floor(centreX), (int) Math.floor(centreZ));
    }

    private void createWaypointAt(int x, int z) {
        int y = minecraft != null && minecraft.player != null ? minecraft.player.getBlockY() : 64;
        if (minecraft != null) {
            minecraft.gui.setScreen(WaypointEditScreen.forNew(this, viewedDimension, x, y, z));
        }
    }

    private void editSelection() {
        MapClient client = XetiusMapClient.mapClient();
        if (client == null || selectedWaypoint == null || minecraft == null) {
            return;
        }
        client.waypoints().byId(selectedWaypoint).ifPresent(waypoint ->
                minecraft.gui.setScreen(WaypointEditScreen.forExisting(this, waypoint)));
    }

    private void deleteSelection() {
        MapClient client = XetiusMapClient.mapClient();
        if (client != null && selectedWaypoint != null) {
            client.deleteWaypoint(selectedWaypoint);
            selectedWaypoint = null;
            updateButtonState();
        }
    }

    private void teleportToSelection() {
        MapClient client = XetiusMapClient.mapClient();
        if (client != null && selectedWaypoint != null) {
            client.teleportTo(selectedWaypoint);
            onClose();
        }
    }

    @Override
    public void tick() {
        updateButtonState();
        MapClient client = XetiusMapClient.mapClient();
        if (client != null) {
            client.sendEntityView(true, viewedDimension,
                    MapCoords.blockToChunk((int) Math.floor(worldXAt(0))),
                    MapCoords.blockToChunk((int) Math.floor(worldZAt(0))),
                    MapCoords.blockToChunk((int) Math.ceil(worldXAt(mapWidth()))),
                    MapCoords.blockToChunk((int) Math.ceil(worldZAt(height))));
        }
    }

    @Override
    public void onClose() {
        MapClient client = XetiusMapClient.mapClient();
        if (client != null) {
            client.clearViewRegions();
            client.sendEntityView(false, viewedDimension, 0, 0, 0, 0);
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
