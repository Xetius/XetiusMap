package dev.xetius.xetiusmap.paper.service;

import dev.xetius.xetiusmap.common.model.Waypoint;
import dev.xetius.xetiusmap.common.model.WaypointIcon;
import dev.xetius.xetiusmap.common.net.S2C;
import dev.xetius.xetiusmap.paper.PluginConfig;
import dev.xetius.xetiusmap.paper.net.MessageBus;
import dev.xetius.xetiusmap.paper.session.PlayerSession;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The server-wide waypoint list. Every waypoint is visible to every client — that is the point of
 * putting them on the server rather than in each player's config — so creation and deletion are
 * broadcast as deltas and the whole list is synced on join.
 *
 * <p>State lives on the main thread; only the YAML write is pushed to the I/O executor.
 */
public final class WaypointService {

    private final Logger logger;
    private final Path file;
    private final MessageBus bus;
    private final ExecutorService io;

    private final Map<UUID, Waypoint> waypoints = new LinkedHashMap<>();

    public WaypointService(Logger logger, Path file, MessageBus bus, ExecutorService io) {
        this.logger = logger;
        this.file = file;
        this.bus = bus;
        this.io = io;
    }

    public void load() {
        waypoints.clear();
        if (!Files.exists(file)) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection root = yaml.getConfigurationSection("waypoints");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                UUID id = UUID.fromString(key);
                Waypoint waypoint = new Waypoint(
                        id,
                        section.getString("name", "Waypoint"),
                        section.getString("dimension", "minecraft:overworld"),
                        section.getInt("x"),
                        section.getInt("y"),
                        section.getInt("z"),
                        section.getInt("color", 0xFFFFFF),
                        WaypointIcon.normalise(section.getString("icon", WaypointIcon.DEFAULT)),
                        parseUuid(section.getString("owner"), Waypoint.CONSOLE_OWNER),
                        section.getString("owner-name", "?"),
                        section.getLong("created")
                ).sanitised();
                waypoints.put(id, waypoint);
            } catch (IllegalArgumentException e) {
                logger.warning("Skipping malformed waypoint '" + key + "' in " + file.getFileName());
            }
        }
        logger.info("Loaded " + waypoints.size() + " waypoint(s).");
    }

    private static UUID parseUuid(String raw, UUID fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public List<Waypoint> all() {
        return List.copyOf(waypoints.values());
    }

    public Optional<Waypoint> byId(UUID id) {
        return Optional.ofNullable(waypoints.get(id));
    }

    /** Case-insensitive lookup, so {@code /xmap tp base} works without copying a UUID. */
    public Optional<Waypoint> byName(String name) {
        return waypoints.values().stream()
                .filter(w -> w.name().equalsIgnoreCase(name))
                .findFirst();
    }

    public List<Waypoint> ownedBy(UUID owner) {
        return waypoints.values().stream().filter(w -> w.owner().equals(owner)).toList();
    }

    public List<String> names() {
        return waypoints.values().stream().map(Waypoint::name).sorted(Comparator.naturalOrder()).toList();
    }

    /**
     * Adds a waypoint, subject to the configured limits.
     *
     * @return an error message, or empty on success
     */
    public Optional<String> create(Waypoint incoming, PluginConfig config) {
        Waypoint waypoint = incoming.sanitised();
        if (waypoints.containsKey(waypoint.id())) {
            return Optional.of("That waypoint already exists.");
        }
        if (config.maxWaypointsTotal() > 0 && waypoints.size() >= config.maxWaypointsTotal()) {
            return Optional.of("The server has reached its waypoint limit ("
                    + config.maxWaypointsTotal() + ").");
        }
        if (config.maxWaypointsPerPlayer() > 0
                && !waypoint.owner().equals(Waypoint.CONSOLE_OWNER)
                && ownedBy(waypoint.owner()).size() >= config.maxWaypointsPerPlayer()) {
            return Optional.of("You already have the maximum of "
                    + config.maxWaypointsPerPlayer() + " waypoints.");
        }
        if (waypoints.values().stream().anyMatch(w -> w.name().equalsIgnoreCase(waypoint.name()))) {
            return Optional.of("A waypoint named '" + waypoint.name() + "' already exists.");
        }

        waypoints.put(waypoint.id(), waypoint);
        bus.sendAll(S2C.WaypointDelta.added(waypoint));
        save();
        return Optional.empty();
    }

    public Optional<String> update(Waypoint incoming) {
        Waypoint waypoint = incoming.sanitised();
        Waypoint existing = waypoints.get(waypoint.id());
        if (existing == null) {
            return Optional.of("That waypoint no longer exists.");
        }
        boolean nameClash = waypoints.values().stream()
                .anyMatch(w -> !w.id().equals(waypoint.id()) && w.name().equalsIgnoreCase(waypoint.name()));
        if (nameClash) {
            return Optional.of("A waypoint named '" + waypoint.name() + "' already exists.");
        }

        // Ownership and creation time are the server's to decide, not the client's.
        Waypoint merged = new Waypoint(
                existing.id(),
                waypoint.name(),
                waypoint.dimension(),
                waypoint.x(),
                waypoint.y(),
                waypoint.z(),
                waypoint.color(),
                WaypointIcon.normalise(waypoint.icon()),
                existing.owner(),
                existing.ownerName(),
                existing.createdAt()
        );
        waypoints.put(merged.id(), merged);
        bus.sendAll(S2C.WaypointDelta.updated(merged));
        save();
        return Optional.empty();
    }

    public boolean delete(UUID id) {
        if (waypoints.remove(id) == null) {
            return false;
        }
        bus.sendAll(S2C.WaypointDelta.removed(id));
        save();
        return true;
    }

    public void syncTo(PlayerSession session) {
        bus.send(session, new S2C.WaypointSync(all()));
    }

    /** Snapshots on the calling thread, then writes on the I/O executor. */
    public void save() {
        List<Waypoint> snapshot = all();
        io.execute(() -> writeYaml(snapshot));
    }

    /** Synchronous variant for shutdown, when the executor is about to go away. */
    public void saveNow() {
        writeYaml(all());
    }

    private void writeYaml(List<Waypoint> snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Waypoint waypoint : snapshot) {
            String base = "waypoints." + waypoint.id();
            yaml.set(base + ".name", waypoint.name());
            yaml.set(base + ".dimension", waypoint.dimension());
            yaml.set(base + ".x", waypoint.x());
            yaml.set(base + ".y", waypoint.y());
            yaml.set(base + ".z", waypoint.z());
            yaml.set(base + ".color", waypoint.color());
            yaml.set(base + ".icon", waypoint.icon());
            yaml.set(base + ".owner", waypoint.owner().toString());
            yaml.set(base + ".owner-name", waypoint.ownerName());
            yaml.set(base + ".created", waypoint.createdAt());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            yaml.save(tmp.toFile());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.SEVERE, e, () -> "Could not save waypoints to " + file);
        }
    }

    public int size() {
        return waypoints.size();
    }

    /** Names matching a prefix, for command tab completion. */
    public List<String> completeNames(String prefix) {
        String lower = prefix.toLowerCase(java.util.Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Waypoint waypoint : waypoints.values()) {
            if (waypoint.name().toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
                out.add(waypoint.name());
            }
        }
        return out;
    }
}
