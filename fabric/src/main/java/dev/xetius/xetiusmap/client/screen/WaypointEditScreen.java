package dev.xetius.xetiusmap.client.screen;

import dev.xetius.xetiusmap.client.MapClient;
import dev.xetius.xetiusmap.client.XetiusMapClient;
import dev.xetius.xetiusmap.common.model.Waypoint;
import dev.xetius.xetiusmap.common.model.WaypointIcon;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/** Create or edit one waypoint. Changes go to the server when there is one, so everybody sees them. */
public final class WaypointEditScreen extends Screen {

    /** A readable spread that stays distinguishable against terrain. */
    private static final int[] PALETTE = {
            0xFFAA00, 0xFF5555, 0xFF55FF, 0x55FF55, 0x55FFFF, 0x5555FF,
            0xFFFF55, 0xFFFFFF, 0xAAAAAA, 0x00AA00, 0x00AAAA, 0xAA00AA
    };

    private final Screen parent;
    private final UUID id;
    private final String dimension;
    private final int x;
    private final int y;
    private final int z;
    private final boolean creating;
    private final UUID owner;
    private final String ownerName;
    private final long createdAt;

    private EditBox nameBox;
    private int color;
    private String icon;

    private WaypointEditScreen(Screen parent, UUID id, String name, String dimension,
                               int x, int y, int z, int color, String icon,
                               UUID owner, String ownerName, long createdAt, boolean creating) {
        super(Component.literal(creating ? "New waypoint" : "Edit waypoint"));
        this.parent = parent;
        this.id = id;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
        this.icon = icon;
        this.owner = owner;
        this.ownerName = ownerName;
        this.createdAt = createdAt;
        this.creating = creating;
        this.pendingName = name;
    }

    private final String pendingName;

    public static WaypointEditScreen forNew(Screen parent, String dimension, int x, int y, int z) {
        return new WaypointEditScreen(parent, UUID.randomUUID(),
                "Waypoint " + x + "," + z, dimension, x, y, z,
                PALETTE[0], WaypointIcon.DEFAULT,
                Waypoint.CONSOLE_OWNER, "", System.currentTimeMillis(), true);
    }

    public static WaypointEditScreen forExisting(Screen parent, Waypoint waypoint) {
        return new WaypointEditScreen(parent, waypoint.id(), waypoint.name(), waypoint.dimension(),
                waypoint.x(), waypoint.y(), waypoint.z(), waypoint.color(), waypoint.icon(),
                waypoint.owner(), waypoint.ownerName(), waypoint.createdAt(), false);
    }

    @Override
    protected void init() {
        int centre = width / 2;
        int top = height / 2 - 70;

        nameBox = new EditBox(font, centre - 100, top + 14, 200, 20, Component.literal("Name"));
        nameBox.setMaxLength(Waypoint.MAX_NAME_LENGTH);
        nameBox.setValue(pendingName);
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        int iconY = top + 96;
        addRenderableWidget(Button.builder(Component.literal("Icon: " + icon), button -> {
            List<String> icons = WaypointIcon.BUILT_IN;
            icon = icons.get((icons.indexOf(icon) + 1 + icons.size()) % icons.size());
            button.setMessage(Component.literal("Icon: " + icon));
        }).bounds(centre - 100, iconY, 200, 20).build());

        addRenderableWidget(Button.builder(Component.literal(creating ? "Create" : "Save"), b -> save())
                .bounds(centre - 100, iconY + 26, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(centre + 2, iconY + 26, 98, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC0101418);

        int centre = width / 2;
        int top = height / 2 - 70;

        graphics.centeredText(font, getTitle(), centre, top - 14, 0xFFFFFFFF);
        graphics.text(font, Component.literal("Name"), centre - 100, top + 3, 0xFFB0B0B0);

        String coords = x + ", " + y + ", " + z + "  (" + shortName(dimension) + ")";
        graphics.text(font, Component.literal(coords), centre - 100, top + 40, 0xFF9AA4B0);

        graphics.text(font, Component.literal("Colour"), centre - 100, top + 56, 0xFFB0B0B0);
        drawPalette(graphics, centre - 100, top + 68);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawPalette(GuiGraphicsExtractor graphics, int left, int top) {
        for (int i = 0; i < PALETTE.length; i++) {
            int swatchX = left + (i % 12) * 17;
            graphics.fill(swatchX, top, swatchX + 15, top + 15, 0xFF000000);
            graphics.fill(swatchX + 1, top + 1, swatchX + 14, top + 14, 0xFF000000 | PALETTE[i]);
            if (PALETTE[i] == color) {
                graphics.outline(swatchX - 1, top - 1, 17, 17, 0xFFFFFFFF);
            }
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        int left = width / 2 - 100;
        int top = height / 2 - 70 + 68;
        if (event.y() >= top && event.y() <= top + 15) {
            int index = (int) ((event.x() - left) / 17);
            if (index >= 0 && index < PALETTE.length && event.x() >= left) {
                color = PALETTE[index];
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void save() {
        MapClient client = XetiusMapClient.mapClient();
        if (client == null) {
            onClose();
            return;
        }
        String name = nameBox.getValue().isBlank() ? "Waypoint" : nameBox.getValue();
        Waypoint waypoint = new Waypoint(id, name, dimension, x, y, z, color,
                WaypointIcon.normalise(icon), owner, ownerName, createdAt);

        if (creating) {
            client.createWaypoint(waypoint);
        } else {
            client.updateWaypoint(waypoint);
        }
        onClose();
    }

    private static String shortName(String dimension) {
        int colon = dimension.indexOf(':');
        return colon >= 0 ? dimension.substring(colon + 1) : dimension;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
