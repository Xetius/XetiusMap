package dev.xetius.xetiusmap.common.model;

import dev.xetius.xetiusmap.common.net.ByteReader;
import dev.xetius.xetiusmap.common.net.ByteWriter;

import java.util.UUID;

/**
 * A shared map marker. Waypoints created against a server live in the server's store and are
 * broadcast to every connected client; in local mode the identical record is persisted client-side.
 *
 * @param id           stable identity, generated client-side so the creator can reference it before the server replies
 * @param name         display name, already length-clamped by {@link #sanitised()}
 * @param dimension    dimension id, e.g. {@code minecraft:the_nether}
 * @param color        0xRRGGBB marker colour (alpha ignored)
 * @param icon         short icon key understood by the client renderer, see {@link WaypointIcon}
 * @param owner        creator's UUID, or {@link #CONSOLE_OWNER} when created from the server console
 * @param ownerName    creator's name at the time of creation, for display only
 * @param createdAt    epoch millis
 */
public record Waypoint(
        UUID id,
        String name,
        String dimension,
        int x,
        int y,
        int z,
        int color,
        String icon,
        UUID owner,
        String ownerName,
        long createdAt
) {

    public static final UUID CONSOLE_OWNER = new UUID(0L, 0L);

    public static final int MAX_NAME_LENGTH = 48;
    public static final int MAX_ICON_LENGTH = 24;
    public static final int MAX_OWNER_NAME_LENGTH = 32;

    /**
     * Returns a copy with all free-text fields clamped and normalised. Applied on the server to
     * every incoming waypoint so a hostile client cannot store a megabyte of text or a colour with
     * junk in the alpha byte.
     */
    public Waypoint sanitised() {
        return new Waypoint(
                id,
                clamp(stripControl(name), MAX_NAME_LENGTH, "Waypoint"),
                clamp(stripControl(dimension), 128, "minecraft:overworld"),
                x, y, z,
                color & 0xFFFFFF,
                clamp(stripControl(icon), MAX_ICON_LENGTH, WaypointIcon.DEFAULT),
                owner,
                clamp(stripControl(ownerName), MAX_OWNER_NAME_LENGTH, "?"),
                createdAt
        );
    }

    private static String stripControl(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= ' ' && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String clamp(String s, int max, String fallback) {
        if (s == null || s.isEmpty()) {
            return fallback;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    public void write(ByteWriter w) {
        w.writeUuid(id);
        w.writeString(name);
        w.writeString(dimension);
        w.writeInt(x);
        w.writeInt(y);
        w.writeInt(z);
        w.writeInt(color);
        w.writeString(icon);
        w.writeUuid(owner);
        w.writeString(ownerName);
        w.writeLong(createdAt);
    }

    public static Waypoint read(ByteReader r) {
        return new Waypoint(
                r.readUuid(),
                r.readString(),
                r.readString(),
                r.readInt(),
                r.readInt(),
                r.readInt(),
                r.readInt(),
                r.readString(),
                r.readUuid(),
                r.readString(),
                r.readLong()
        );
    }
}
