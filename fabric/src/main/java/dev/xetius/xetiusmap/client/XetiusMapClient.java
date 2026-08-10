package dev.xetius.xetiusmap.client;

import dev.xetius.xetiusmap.client.config.ClientConfig;
import dev.xetius.xetiusmap.client.config.ConfigManager;
import dev.xetius.xetiusmap.client.net.ClientNetwork;
import dev.xetius.xetiusmap.client.render.MinimapHud;
import dev.xetius.xetiusmap.client.render.WorldWaypointHud;
import dev.xetius.xetiusmap.client.screen.ConfigScreen;
import dev.xetius.xetiusmap.client.screen.WaypointEditScreen;
import dev.xetius.xetiusmap.client.screen.WorldMapScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Mod entry point. Owns the config, the key bindings, and the {@link MapClient} for the connection
 * currently in progress.
 */
public final class XetiusMapClient implements ClientModInitializer {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(XetiusMap.id("main"));

    public static final KeyMapping WORLD_MAP_KEY = new KeyMapping(
            "key.xetiusmap.world_map", GLFW.GLFW_KEY_M, CATEGORY);
    public static final KeyMapping ZOOM_IN_KEY = new KeyMapping(
            "key.xetiusmap.zoom_in", GLFW.GLFW_KEY_PAGE_UP, CATEGORY);
    public static final KeyMapping ZOOM_OUT_KEY = new KeyMapping(
            "key.xetiusmap.zoom_out", GLFW.GLFW_KEY_PAGE_DOWN, CATEGORY);
    public static final KeyMapping NEW_WAYPOINT_KEY = new KeyMapping(
            "key.xetiusmap.new_waypoint", GLFW.GLFW_KEY_B, CATEGORY);
    public static final KeyMapping TOGGLE_MINIMAP_KEY = new KeyMapping(
            "key.xetiusmap.toggle_minimap", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping SETTINGS_KEY = new KeyMapping(
            "key.xetiusmap.settings", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    private static final ConfigManager CONFIG = new ConfigManager();
    private static MapClient mapClient;
    private static MinimapHud minimapHud;
    private static String modVersion = "unknown";

    public static ClientConfig config() {
        return CONFIG.get();
    }

    public static void saveConfig() {
        CONFIG.save();
    }

    public static MapClient mapClient() {
        return mapClient;
    }

    public static String modVersion() {
        return modVersion;
    }

    @Override
    public void onInitializeClient() {
        modVersion = FabricLoader.getInstance().getModContainer(XetiusMap.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        CONFIG.load();
        ClientNetwork.registerPayloadTypes();
        ClientNetwork.setHandler(packet -> {
            if (mapClient != null) {
                mapClient.handle(packet);
            }
        });

        for (KeyMapping key : new KeyMapping[]{
                WORLD_MAP_KEY, ZOOM_IN_KEY, ZOOM_OUT_KEY, NEW_WAYPOINT_KEY, TOGGLE_MINIMAP_KEY, SETTINGS_KEY}) {
            KeyMappingHelper.registerKeyMapping(key);
        }

        // In-world waypoint markers sit behind the minimap so the minimap always wins an overlap.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                XetiusMap.id("world_waypoints"), new WorldWaypointHud());

        minimapHud = new MinimapHud();
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, XetiusMap.id("minimap"), minimapHud);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientNetwork.reset();
            closeMapClient();
            mapClient = new MapClient(client, CONFIG.get());
            XetiusMap.LOGGER.info("XetiusMap ready; map cache at {}", mapClient.store().root());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientNetwork.reset();
            closeMapClient();
        });

        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (mapClient != null) {
                mapClient.scannerEnqueue(level, chunk.getPos());
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft minecraft) {
        handleKeys(minecraft);
        if (mapClient != null && minecraft.level != null) {
            mapClient.tick(minecraft);
        }
    }

    private void handleKeys(Minecraft minecraft) {
        ClientConfig config = CONFIG.get();

        while (WORLD_MAP_KEY.consumeClick()) {
            if (minecraft.gui.screen() == null) {
                minecraft.gui.setScreen(new WorldMapScreen());
            }
        }
        while (SETTINGS_KEY.consumeClick()) {
            if (minecraft.gui.screen() == null) {
                minecraft.gui.setScreen(new ConfigScreen(null));
            }
        }
        while (TOGGLE_MINIMAP_KEY.consumeClick()) {
            config.minimapEnabled = !config.minimapEnabled;
            CONFIG.save();
        }
        while (ZOOM_IN_KEY.consumeClick()) {
            config.minimapZoom = ClientConfig.Zoom.in(config.minimapZoom);
            CONFIG.save();
        }
        while (ZOOM_OUT_KEY.consumeClick()) {
            config.minimapZoom = ClientConfig.Zoom.out(config.minimapZoom);
            CONFIG.save();
        }
        while (NEW_WAYPOINT_KEY.consumeClick()) {
            if (minecraft.gui.screen() == null && minecraft.player != null && mapClient != null) {
                minecraft.gui.setScreen(WaypointEditScreen.forNew(null,
                        mapClient.dimension(),
                        minecraft.player.getBlockX(),
                        minecraft.player.getBlockY(),
                        minecraft.player.getBlockZ()));
            }
        }
    }

    /**
     * Tears down the connection's map state. Both the raster cache and the minimap own GPU
     * textures, so the actual disposal is queued onto the render thread rather than run wherever
     * the disconnect happened to be delivered.
     */
    private static void closeMapClient() {
        MapClient closing = mapClient;
        mapClient = null;
        MinimapHud hud = minimapHud;
        Minecraft.getInstance().execute(() -> {
            if (closing != null) {
                closing.close();
            }
            if (hud != null) {
                hud.close();
            }
        });
    }
}
