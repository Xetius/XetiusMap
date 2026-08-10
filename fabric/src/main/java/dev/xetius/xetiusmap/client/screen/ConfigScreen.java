package dev.xetius.xetiusmap.client.screen;

import dev.xetius.xetiusmap.client.MapClient;
import dev.xetius.xetiusmap.client.XetiusMapClient;
import dev.xetius.xetiusmap.client.config.ClientConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Minimap and map settings. Deliberately self-contained — no Cloth Config or Mod Menu dependency,
 * so the mod is one jar with nothing else required.
 */
public final class ConfigScreen extends Screen {

    private static final int COLUMN_WIDTH = 200;
    private static final int ROW_HEIGHT = 22;

    private final Screen parent;
    private final List<Runnable> labelUpdates = new ArrayList<>();

    public ConfigScreen(Screen parent) {
        super(Component.literal("XetiusMap settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ClientConfig config = XetiusMapClient.config();
        labelUpdates.clear();

        int leftColumn = width / 2 - COLUMN_WIDTH - 4;
        int rightColumn = width / 2 + 4;
        int top = 44;

        int y = top;
        toggle(leftColumn, y, "Minimap", () -> config.minimapEnabled, v -> config.minimapEnabled = v);
        y += ROW_HEIGHT;
        cycle(leftColumn, y, "Position", () -> config.minimapAnchor.name(), () ->
                config.minimapAnchor = next(ClientConfig.Anchor.values(), config.minimapAnchor));
        y += ROW_HEIGHT;
        cycle(leftColumn, y, "Shape", () -> config.minimapShape.name(), () ->
                config.minimapShape = next(ClientConfig.Shape.values(), config.minimapShape));
        y += ROW_HEIGHT;
        cycle(leftColumn, y, "Size", () -> config.minimapSize + " px", () ->
                config.minimapSize = nextSize(config.minimapSize));
        y += ROW_HEIGHT;
        cycle(leftColumn, y, "Zoom", () -> ClientConfig.Zoom.label(config.minimapZoom), () ->
                config.minimapZoom = (config.minimapZoom + 1) % ClientConfig.Zoom.LEVELS.length);
        y += ROW_HEIGHT;
        toggle(leftColumn, y, "Rotate with player", () -> config.minimapRotate, v -> config.minimapRotate = v);
        y += ROW_HEIGHT;
        toggle(leftColumn, y, "Frame", () -> config.minimapFrame, v -> config.minimapFrame = v);
        y += ROW_HEIGHT;
        cycle(leftColumn, y, "Offset", () -> config.minimapOffsetX + " px", () -> {
            config.minimapOffsetX = (config.minimapOffsetX + 4) % 64;
            config.minimapOffsetY = config.minimapOffsetX;
        });

        y = top;
        toggle(rightColumn, y, "Coordinates", () -> config.showCoordinates, v -> config.showCoordinates = v);
        y += ROW_HEIGHT;
        toggle(rightColumn, y, "Biome name", () -> config.showBiome, v -> config.showBiome = v);
        y += ROW_HEIGHT;
        toggle(rightColumn, y, "Compass letters", () -> config.showDirections, v -> config.showDirections = v);
        y += ROW_HEIGHT;
        toggle(rightColumn, y, "Players", () -> config.showPlayers, v -> config.showPlayers = v);
        y += ROW_HEIGHT;
        toggle(rightColumn, y, "Player names", () -> config.showPlayerNames, v -> config.showPlayerNames = v);
        y += ROW_HEIGHT;
        toggle(rightColumn, y, "Mobs", () -> config.showMobs, v -> config.showMobs = v);
        y += ROW_HEIGHT;
        toggle(rightColumn, y, "Waypoints", () -> config.showWaypoints, v -> config.showWaypoints = v);
        y += ROW_HEIGHT;
        cycle(rightColumn, y, "Colours", () -> config.colorStyle == ClientConfig.ColorStyle.TINTED
                ? "biome tinted" : "vanilla map", () ->
                config.colorStyle = next(ClientConfig.ColorStyle.values(), config.colorStyle));

        y += ROW_HEIGHT;
        toggle(rightColumn, y, "Waypoints in world", () -> config.showWaypointsInWorld,
                v -> config.showWaypointsInWorld = v);

        y = top + ROW_HEIGHT * 8;
        toggle(leftColumn, y, "Edge arrows: waypoints", () -> config.edgeIndicatorWaypoints,
                v -> config.edgeIndicatorWaypoints = v);
        y += ROW_HEIGHT;
        toggle(leftColumn, y, "Edge arrows: players", () -> config.edgeIndicatorPlayers,
                v -> config.edgeIndicatorPlayers = v);
        y += ROW_HEIGHT;
        toggle(leftColumn, y, "Waypoint distance", () -> config.showWaypointDistance,
                v -> config.showWaypointDistance = v);

        int bottom = height - 52;
        toggle(leftColumn, bottom, "Cave mode", () -> config.caveMode, v -> config.caveMode = v);
        toggle(rightColumn, bottom, "Share what I explore", () -> config.uploadEnabled,
                v -> config.uploadEnabled = v);

        addRenderableWidget(Button.builder(Component.literal("Hide me from other players' maps"), button -> {
            MapClient client = XetiusMapClient.mapClient();
            boolean hidden = !config.hiddenFromOthers;
            if (client != null) {
                client.setHidden(hidden);
            } else {
                config.hiddenFromOthers = hidden;
            }
            button.setMessage(Component.literal(hidden
                    ? "Hidden from other players' maps"
                    : "Hide me from other players' maps"));
            XetiusMapClient.saveConfig();
        }).bounds(width / 2 - COLUMN_WIDTH, bottom + ROW_HEIGHT, COLUMN_WIDTH * 2, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 60, height - 26, 120, 20).build());
    }

    private void toggle(int x, int y, String label, Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
        Button button = addRenderableWidget(Button.builder(
                        Component.literal(label + ": " + (get.get() ? "on" : "off")), b -> {
                            set.accept(!get.get());
                            XetiusMapClient.saveConfig();
                            b.setMessage(Component.literal(label + ": " + (get.get() ? "on" : "off")));
                        })
                .bounds(x, y, COLUMN_WIDTH, 20).build());
        labelUpdates.add(() -> button.setMessage(Component.literal(label + ": " + (get.get() ? "on" : "off"))));
    }

    private void cycle(int x, int y, String label, Supplier<String> describe, Runnable advance) {
        Button button = addRenderableWidget(Button.builder(
                        Component.literal(label + ": " + describe.get()), b -> {
                            advance.run();
                            XetiusMapClient.config().validate();
                            XetiusMapClient.saveConfig();
                            b.setMessage(Component.literal(label + ": " + describe.get()));
                        })
                .bounds(x, y, COLUMN_WIDTH, 20).build());
        labelUpdates.add(() -> button.setMessage(Component.literal(label + ": " + describe.get())));
    }

    private static <T extends Enum<T>> T next(T[] values, T current) {
        return values[(current.ordinal() + 1) % values.length];
    }

    private static int nextSize(int current) {
        int[] sizes = {64, 96, 128, 160, 192, 256};
        for (int size : sizes) {
            if (size > current) {
                return size;
            }
        }
        return sizes[0];
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC0101418);
        graphics.centeredText(font, getTitle(), width / 2, 18, 0xFFFFFFFF);

        MapClient client = XetiusMapClient.mapClient();
        if (client != null) {
            graphics.centeredText(font, Component.literal(client.status()), width / 2, 30, 0xFF9AA4B0);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void tick() {
        labelUpdates.forEach(Runnable::run);
    }

    @Override
    public void onClose() {
        XetiusMapClient.saveConfig();
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
