package dev.xetius.xetiusmap.client.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.xetius.xetiusmap.client.XetiusMap;
import dev.xetius.xetiusmap.common.model.Waypoint;
import dev.xetius.xetiusmap.common.net.S2C;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
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

/**
 * The client's copy of the waypoint list.
 *
 * <p>Against a server this simply mirrors what the server sends, which is why everyone sees the
 * same waypoints. In local mode the same list is kept on disk beside the map cache, so single
 * player still gets waypoints without any of the sharing machinery.
 */
public final class WaypointManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Waypoint>>() {
    }.getType();

    private final Path localFile;
    private final boolean local;
    private final Map<UUID, Waypoint> waypoints = new LinkedHashMap<>();

    public WaypointManager(Path localFile, boolean local) {
        this.localFile = localFile;
        this.local = local;
        if (local) {
            load();
        }
    }

    public boolean isLocal() {
        return local;
    }

    public List<Waypoint> all() {
        return List.copyOf(waypoints.values());
    }

    /** Waypoints in one dimension, newest last, for drawing on a map view. */
    public List<Waypoint> inDimension(String dimension) {
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint waypoint : waypoints.values()) {
            if (waypoint.dimension().equals(dimension)) {
                out.add(waypoint);
            }
        }
        return out;
    }

    public List<Waypoint> sortedByName() {
        List<Waypoint> out = new ArrayList<>(waypoints.values());
        out.sort(Comparator.comparing(Waypoint::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public Optional<Waypoint> byId(UUID id) {
        return Optional.ofNullable(waypoints.get(id));
    }

    public int size() {
        return waypoints.size();
    }

    /** Replaces the whole list, as sent by the server on join. */
    public void replaceAll(List<Waypoint> incoming) {
        waypoints.clear();
        for (Waypoint waypoint : incoming) {
            waypoints.put(waypoint.id(), waypoint);
        }
    }

    public void apply(S2C.WaypointDelta delta) {
        switch (delta.operation()) {
            case ADDED, UPDATED -> waypoints.put(delta.waypointId(), delta.waypoint());
            case REMOVED -> waypoints.remove(delta.waypointId());
        }
    }

    /** Local-mode mutation. On a server the change is made by the server and echoed back instead. */
    public void putLocal(Waypoint waypoint) {
        waypoints.put(waypoint.id(), waypoint);
        save();
    }

    public void removeLocal(UUID id) {
        waypoints.remove(id);
        save();
    }

    private void load() {
        if (!Files.exists(localFile)) {
            return;
        }
        try {
            List<Waypoint> loaded = GSON.fromJson(Files.readString(localFile, StandardCharsets.UTF_8), LIST_TYPE);
            if (loaded != null) {
                replaceAll(loaded.stream().filter(java.util.Objects::nonNull).map(Waypoint::sanitised).toList());
            }
        } catch (IOException | RuntimeException e) {
            XetiusMap.LOGGER.warn("Could not read local waypoints from {}: {}", localFile, e.toString());
        }
    }

    private void save() {
        if (!local) {
            return;
        }
        Path tmp = localFile.resolveSibling(localFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(localFile.getParent());
            Files.writeString(tmp, GSON.toJson(all(), LIST_TYPE), StandardCharsets.UTF_8);
            Files.move(tmp, localFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            XetiusMap.LOGGER.warn("Could not save local waypoints to {}: {}", localFile, e.toString());
        }
    }
}
