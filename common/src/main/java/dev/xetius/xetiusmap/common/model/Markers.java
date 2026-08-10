package dev.xetius.xetiusmap.common.model;

import dev.xetius.xetiusmap.common.net.ByteReader;
import dev.xetius.xetiusmap.common.net.ByteWriter;

import java.util.UUID;

/**
 * Live entity positions pushed by the server. Coordinates are whole blocks: the map never renders
 * at better than one pixel per block, so sub-block precision would be bandwidth spent on nothing.
 */
public final class Markers {

    private Markers() {
    }

    /**
     * An online player. Sent for every dimension, so the world map can show where everyone is even
     * when they are not in the viewer's own world.
     */
    public record PlayerMarker(UUID uuid, String name, String dimension, int x, int y, int z, float yaw) {

        public void write(ByteWriter w) {
            w.writeUuid(uuid);
            w.writeString(name);
            w.writeString(dimension);
            w.writeInt(x);
            w.writeShort(y);
            w.writeInt(z);
            w.writeByte(encodeYaw(yaw));
        }

        public static PlayerMarker read(ByteReader r) {
            return new PlayerMarker(
                    r.readUuid(),
                    r.readString(),
                    r.readString(),
                    r.readInt(),
                    r.readShort(),
                    r.readInt(),
                    decodeYaw(r.readByte())
            );
        }
    }

    /**
     * A non-player entity near the recipient. The type name is not repeated per entity — it is an
     * index into the palette carried by the enclosing packet.
     *
     * @param category see {@link MobCategory}, used to pick the dot colour without the client
     *                 needing to know every entity type
     */
    public record MobMarker(int typeIndex, byte category, int x, int y, int z, float yaw) {

        public void write(ByteWriter w) {
            w.writeVarInt(typeIndex);
            w.writeByte(category);
            w.writeInt(x);
            w.writeShort(y);
            w.writeInt(z);
            w.writeByte(encodeYaw(yaw));
        }

        public static MobMarker read(ByteReader r) {
            return new MobMarker(
                    r.readVarInt(),
                    r.readByte(),
                    r.readInt(),
                    r.readShort(),
                    r.readInt(),
                    decodeYaw(r.readByte())
            );
        }
    }

    /** Coarse buckets the client colours differently. */
    public static final class MobCategory {
        public static final byte HOSTILE = 0;
        public static final byte PASSIVE = 1;
        public static final byte NEUTRAL = 2;
        public static final byte WATER = 3;
        public static final byte TAMED = 4;
        public static final byte BOSS = 5;
        public static final byte OTHER = 6;

        private MobCategory() {
        }
    }

    /** Yaw quantised to 1/256 of a turn — plenty for a directional arrow a few pixels across. */
    static int encodeYaw(float yaw) {
        return (int) Math.floor(yaw * 256.0F / 360.0F) & 0xFF;
    }

    static float decodeYaw(byte packed) {
        return (packed & 0xFF) * 360.0F / 256.0F;
    }
}
