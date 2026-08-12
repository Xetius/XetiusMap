package dev.xetius.xetiusmap.client.screen;

import dev.xetius.xetiusmap.client.MapClient;
import dev.xetius.xetiusmap.client.XetiusMapClient;
import dev.xetius.xetiusmap.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * Settings for the map, in tabbed pages.
 *
 * <p>Self-contained on purpose — no Cloth Config dependency — but it registers with Mod Menu when
 * that is installed, so it turns up where people expect to find it.
 */
public final class ConfigScreen extends Screen {

    private enum Tab {
        MINIMAP("Minimap"),
        MARKERS("Markers"),
        IN_WORLD("In world"),
        MAP("Map");

        private final String title;

        Tab(String title) {
            this.title = title;
        }
    }

    private static final int COLUMN_WIDTH = 190;
    private static final int COLUMN_GAP = 8;
    private static final int ROW_HEIGHT = 22;
    private static final int WIDGET_HEIGHT = 20;

    private final Screen parent;
    private final List<Runnable> refreshers = new ArrayList<>();
    private Tab tab = Tab.MINIMAP;

    private int leftColumn;
    private int rightColumn;
    private int contentTop;
    private int[] nextRow = new int[2];

    public ConfigScreen(Screen parent) {
        super(Component.literal("XetiusMap settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ClientConfig config = XetiusMapClient.config();
        refreshers.clear();

        int totalWidth = COLUMN_WIDTH * 2 + COLUMN_GAP;
        leftColumn = (width - totalWidth) / 2;
        rightColumn = leftColumn + COLUMN_WIDTH + COLUMN_GAP;

        // Tab strip.
        int tabWidth = Math.min(96, totalWidth / Tab.values().length);
        int tabX = (width - tabWidth * Tab.values().length) / 2;
        for (Tab value : Tab.values()) {
            Button button = Button.builder(Component.literal(value.title), b -> {
                tab = value;
                rebuildWidgets();
            }).bounds(tabX, 28, tabWidth, WIDGET_HEIGHT).build();
            button.active = value != tab;
            addRenderableWidget(button);
            tabX += tabWidth;
        }

        contentTop = 58;
        nextRow = new int[]{contentTop, contentTop};

        switch (tab) {
            case MINIMAP -> buildMinimapTab(config);
            case MARKERS -> buildMarkersTab(config);
            case IN_WORLD -> buildInWorldTab(config);
            case MAP -> buildMapTab(config);
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 60, height - 28, 120, WIDGET_HEIGHT).build());
    }

    // --- Tabs --------------------------------------------------------------------------------

    private void buildMinimapTab(ClientConfig config) {
        toggle(0, "Minimap", () -> config.minimapEnabled, v -> config.minimapEnabled = v);
        cycle(0, "Position", () -> pretty(config.minimapAnchor.name()),
                () -> config.minimapAnchor = next(ClientConfig.Anchor.values(), config.minimapAnchor));
        cycle(0, "Shape", () -> pretty(config.minimapShape.name()),
                () -> config.minimapShape = next(ClientConfig.Shape.values(), config.minimapShape));
        toggle(0, "Rotate with player", () -> config.minimapRotate, v -> config.minimapRotate = v);
        toggle(0, "Frame", () -> config.minimapFrame, v -> config.minimapFrame = v);

        slider(1, "Size", 48, 512, 8, config.minimapSize,
                v -> String.format(Locale.ROOT, "%.0f px", v), v -> config.minimapSize = (int) v);
        cycle(1, "Zoom", () -> ClientConfig.Zoom.label(config.minimapZoom),
                () -> config.minimapZoom = (config.minimapZoom + 1) % ClientConfig.Zoom.LEVELS.length);
        slider(1, "Offset X", 0, 200, 1, config.minimapOffsetX,
                v -> String.format(Locale.ROOT, "%.0f px", v), v -> config.minimapOffsetX = (int) v);
        slider(1, "Offset Y", 0, 200, 1, config.minimapOffsetY,
                v -> String.format(Locale.ROOT, "%.0f px", v), v -> config.minimapOffsetY = (int) v);
        slider(1, "Opacity", 0.1, 1.0, 0.05, config.minimapOpacity,
                v -> String.format(Locale.ROOT, "%.0f%%", v * 100), v -> config.minimapOpacity = (float) v);
    }

    private void buildMarkersTab(ClientConfig config) {
        toggle(0, "Players", () -> config.showPlayers, v -> config.showPlayers = v);
        toggle(0, "Player names", () -> config.showPlayerNames, v -> config.showPlayerNames = v);
        toggle(0, "Mobs", () -> config.showMobs, v -> config.showMobs = v);
        toggle(0, "Waypoints", () -> config.showWaypoints, v -> config.showWaypoints = v);
        toggle(0, "Player faces", () -> config.showPlayerHeads, v -> config.showPlayerHeads = v);
        toggle(0, "Mob icons", () -> config.showMobIcons, v -> config.showMobIcons = v);
        toggle(0, "Names on hover", () -> config.showHoverNames, v -> config.showHoverNames = v);

        toggle(1, "Edge arrows: waypoints", () -> config.edgeIndicatorWaypoints,
                v -> config.edgeIndicatorWaypoints = v);
        toggle(1, "Edge arrows: players", () -> config.edgeIndicatorPlayers,
                v -> config.edgeIndicatorPlayers = v);
        toggle(1, "Coordinates", () -> config.showCoordinates, v -> config.showCoordinates = v);
        toggle(1, "Biome name", () -> config.showBiome, v -> config.showBiome = v);
        toggle(1, "Compass letters", () -> config.showDirections, v -> config.showDirections = v);
        cycle(1, "Show mobs", () -> switch (config.mobVisibility) {
                    case SURFACE -> "on the surface";
                    case SURFACE_AND_NEARBY -> "surface + nearby";
                    case NEARBY -> "near my level";
                    case ALL -> "everywhere";
                },
                () -> config.mobVisibility =
                        next(ClientConfig.MobVisibility.values(), config.mobVisibility));
        slider(1, "Nearby means", 0, 128, 4, config.mobVerticalRange,
                v -> v <= 0 ? "any height" : String.format(Locale.ROOT, "\u00b1%.0f m", v),
                v -> config.mobVerticalRange = (int) v);
    }

    private void buildInWorldTab(ClientConfig config) {
        toggle(0, "Show in world", () -> config.showWaypointsInWorld,
                v -> config.showWaypointsInWorld = v);
        toggle(0, "Show distance", () -> config.showWaypointDistance,
                v -> config.showWaypointDistance = v);
        slider(0, "Smallest size", 2, 9, 0.5, config.worldWaypointMinTextSize,
                v -> String.format(Locale.ROOT, "%.1f pt", v),
                v -> config.worldWaypointMinTextSize = (float) v);
        slider(0, "Icon size", 3, 24, 1, config.worldWaypointIconSize,
                v -> String.format(Locale.ROOT, "%.0f px", v),
                v -> config.worldWaypointIconSize = (int) v);
        slider(0, "Look-at area", 1, 30, 1, config.worldWaypointFocusPercent,
                v -> String.format(Locale.ROOT, "%.0f%%", v),
                v -> config.worldWaypointFocusPercent = (int) v);

        slider(1, "Full size within", 1, 128, 1, config.worldWaypointFullSizeDistance,
                v -> String.format(Locale.ROOT, "%.0f m", v),
                v -> config.worldWaypointFullSizeDistance = (int) v);
        slider(1, "Smallest by", 16, 1000, 8, config.worldWaypointMinSizeDistance,
                v -> String.format(Locale.ROOT, "%.0f m", v),
                v -> config.worldWaypointMinSizeDistance = (int) v);
        slider(1, "Fade out within", 0, 64, 1, config.worldWaypointFadeNear,
                v -> v <= 0 ? "never" : String.format(Locale.ROOT, "%.0f m", v),
                v -> config.worldWaypointFadeNear = (int) v);
        slider(1, "Hide beyond", 0, 4000, 50, config.worldWaypointMaxDistance,
                v -> v <= 0 ? "no limit" : String.format(Locale.ROOT, "%.0f m", v),
                v -> config.worldWaypointMaxDistance = (int) v);
    }

    private void buildMapTab(ClientConfig config) {
        cycle(0, "Colours", () -> config.colorStyle == ClientConfig.ColorStyle.TINTED
                        ? "biome tinted" : "vanilla map",
                () -> config.colorStyle = next(ClientConfig.ColorStyle.values(), config.colorStyle));
        toggle(0, "Cave view underground", () -> config.caveMode, v -> config.caveMode = v);
        toggle(0, "Seabed through water", () -> config.showUnderwaterTerrain,
                v -> config.showUnderwaterTerrain = v);
        slider(0, "Water opaque at", 2, 64, 1, config.waterOpaqueDepth,
                v -> String.format(Locale.ROOT, "%.0f m", v),
                v -> config.waterOpaqueDepth = (int) v);
        cycle(0, "World map zoom", () -> ClientConfig.Zoom.label(config.worldMapZoom),
                () -> config.worldMapZoom = (config.worldMapZoom + 1) % ClientConfig.Zoom.LEVELS.length);
        // Colour settings only affect chunks as they are drawn, so offer a way to redraw the ones
        // already on the map rather than making people walk the ground again.
        int redrawY = nextRow[0];
        place(0, Button.builder(Component.literal("Redraw nearby chunks"), button -> {
            MapClient client = XetiusMapClient.mapClient();
            int queued = client == null ? 0 : client.rescanLoadedChunks(Minecraft.getInstance());
            button.setMessage(Component.literal(queued == 0
                    ? "Nothing loaded to redraw"
                    : "Redrawing " + queued + " chunks"));
        }).bounds(columnX(0), redrawY, COLUMN_WIDTH, WIDGET_HEIGHT).build());

        toggle(1, "Share what I explore", () -> config.uploadEnabled, v -> config.uploadEnabled = v);
        Button hide = Button.builder(Component.literal(hiddenLabel(config)), button -> {
            MapClient client = XetiusMapClient.mapClient();
            boolean hidden = !config.hiddenFromOthers;
            if (client != null) {
                client.setHidden(hidden);
            } else {
                config.hiddenFromOthers = hidden;
            }
            XetiusMapClient.saveConfig();
            button.setMessage(Component.literal(hiddenLabel(config)));
        }).bounds(rightColumn, nextRow[1], COLUMN_WIDTH, WIDGET_HEIGHT).build();
        nextRow[1] += ROW_HEIGHT;
        addRenderableWidget(hide);
        refreshers.add(() -> hide.setMessage(Component.literal(hiddenLabel(config))));
    }

    private static String hiddenLabel(ClientConfig config) {
        return config.hiddenFromOthers ? "Hidden from others: on" : "Hidden from others: off";
    }

    // --- Widget helpers ----------------------------------------------------------------------

    private <T extends AbstractWidget> T place(int column, T widget) {
        nextRow[column] += ROW_HEIGHT;
        return addRenderableWidget(widget);
    }

    private int columnX(int column) {
        return column == 0 ? leftColumn : rightColumn;
    }

    private void toggle(int column, String label, BooleanSupplier get, Consumer<Boolean> set) {
        int y = nextRow[column];
        Button button = place(column, Button.builder(
                        Component.literal(label + ": " + (get.getAsBoolean() ? "on" : "off")), b -> {
                            set.accept(!get.getAsBoolean());
                            XetiusMapClient.saveConfig();
                            b.setMessage(Component.literal(label + ": " + (get.getAsBoolean() ? "on" : "off")));
                        })
                .bounds(columnX(column), y, COLUMN_WIDTH, WIDGET_HEIGHT).build());
        refreshers.add(() ->
                button.setMessage(Component.literal(label + ": " + (get.getAsBoolean() ? "on" : "off"))));
    }

    private void cycle(int column, String label, Supplier<String> describe, Runnable advance) {
        int y = nextRow[column];
        Button button = place(column, Button.builder(
                        Component.literal(label + ": " + describe.get()), b -> {
                            advance.run();
                            XetiusMapClient.config().validate();
                            XetiusMapClient.saveConfig();
                            b.setMessage(Component.literal(label + ": " + describe.get()));
                        })
                .bounds(columnX(column), y, COLUMN_WIDTH, WIDGET_HEIGHT).build());
        refreshers.add(() -> button.setMessage(Component.literal(label + ": " + describe.get())));
    }

    private void slider(int column, String label, double min, double max, double step, double initial,
                        java.util.function.DoubleFunction<String> format, DoubleConsumer apply) {
        int y = nextRow[column];
        place(column, new ConfigSlider(columnX(column), y, COLUMN_WIDTH, WIDGET_HEIGHT,
                label, min, max, step, initial, format, value -> {
            apply.accept(value);
            XetiusMapClient.config().validate();
            XetiusMapClient.saveConfig();
        }));
    }

    private static <T extends Enum<T>> T next(T[] values, T current) {
        return values[(current.ordinal() + 1) % values.length];
    }

    /** {@code TOP_RIGHT} reads better as {@code Top right}. */
    private static String pretty(String constant) {
        String spaced = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    // --- Screen ------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC0101418);
        graphics.centeredText(font, getTitle(), width / 2, 12, 0xFFFFFFFF);

        MapClient client = XetiusMapClient.mapClient();
        if (client != null) {
            graphics.centeredText(font, Component.literal(client.status()), width / 2, height - 44, 0xFF9AA4B0);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void tick() {
        refreshers.forEach(Runnable::run);
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
