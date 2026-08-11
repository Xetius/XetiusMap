package dev.xetius.xetiusmap.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.xetius.xetiusmap.client.XetiusMap;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Loads and saves {@link ClientConfig} as {@code config/xetiusmap.json}. */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private ClientConfig config = new ClientConfig();

    public ConfigManager() {
        this(FabricLoader.getInstance().getConfigDir().resolve("xetiusmap.json"));
    }

    ConfigManager(Path file) {
        this.file = file;
    }

    public ClientConfig get() {
        return config;
    }

    public void load() {
        if (!Files.exists(file)) {
            // A fresh config is already current; only an existing file can need migrating.
            config.configVersion = ClientConfig.CURRENT_VERSION;
            save();
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            ClientConfig loaded = GSON.fromJson(json, ClientConfig.class);
            config = loaded != null ? loaded : new ClientConfig();
        } catch (IOException | JsonSyntaxException e) {
            // A corrupt config should not stop the mod loading; fall back and rewrite on save.
            XetiusMap.LOGGER.warn("Could not read {}, using defaults: {}", file, e.toString());
            config = new ClientConfig();
        }
        config.migrate();
        config.validate();
        save();
    }

    public void save() {
        config.validate();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, GSON.toJson(config), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            XetiusMap.LOGGER.warn("Could not save {}: {}", file, e.toString());
        }
    }
}
