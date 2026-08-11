package dev.xetius.xetiusmap.common.model;

import dev.xetius.xetiusmap.common.net.ByteReader;
import dev.xetius.xetiusmap.common.net.ByteWriter;

import java.util.List;

/**
 * What the server tells a client at handshake time. The client uses this to grey out features it
 * is not allowed to use rather than letting the player try and be rejected.
 *
 * @param serverBrand        free-text, shown in the client's status line
 * @param uploadsEnabled     whether the server accepts tile uploads at all
 * @param teleportEnabled    whether waypoint teleporting is enabled server-wide
 * @param teleportPermitted  whether <em>this</em> player currently holds the teleport permission
 * @param teleportAnywherePermitted whether they may also teleport to any point on the map
 * @param mobRadius          radius in blocks within which mobs are reported, 0 disables mob radar
 * @param entityIntervalTicks how often the server pushes entity updates
 * @param maxUploadsPerSecond per-player upload budget, so the client can pace itself
 * @param dimensions         dimension ids the server will serve maps for
 */
public record ServerPolicy(
        String serverBrand,
        boolean uploadsEnabled,
        boolean teleportEnabled,
        boolean teleportPermitted,
        boolean teleportAnywherePermitted,
        int mobRadius,
        int entityIntervalTicks,
        int maxUploadsPerSecond,
        List<String> dimensions
) {

    public void write(ByteWriter w) {
        w.writeString(serverBrand);
        w.writeBoolean(uploadsEnabled);
        w.writeBoolean(teleportEnabled);
        w.writeBoolean(teleportPermitted);
        w.writeBoolean(teleportAnywherePermitted);
        w.writeVarInt(mobRadius);
        w.writeVarInt(entityIntervalTicks);
        w.writeVarInt(maxUploadsPerSecond);
        w.writeList(dimensions, ByteWriter::writeString);
    }

    public static ServerPolicy read(ByteReader r) {
        return new ServerPolicy(
                r.readString(),
                r.readBoolean(),
                r.readBoolean(),
                r.readBoolean(),
                r.readBoolean(),
                r.readVarInt(),
                r.readVarInt(),
                r.readVarInt(),
                r.readList(ByteReader::readString)
        );
    }

    /** The policy the client assumes when it is running without a server plugin. */
    public static ServerPolicy localMode() {
        return new ServerPolicy("local", false, false, false, false, 0, 0, 0, List.of());
    }
}
