package dev.xetius.xetiusmap.common.net;

import dev.xetius.xetiusmap.common.model.Markers;
import dev.xetius.xetiusmap.common.model.ServerPolicy;
import dev.xetius.xetiusmap.common.model.Waypoint;
import dev.xetius.xetiusmap.common.util.ChunkRef;

import java.util.List;
import java.util.UUID;

/** Everything the server may send. */
public sealed interface S2C extends Packet {

    int HELLO_OK = 1;
    int TILE_DATA = 2;
    int REGION_INDEX = 3;
    int WAYPOINT_SYNC = 4;
    int WAYPOINT_DELTA = 5;
    int ENTITY_UPDATE = 6;
    int TELEPORT_RESULT = 7;
    int NOTICE = 8;
    int TILE_MISSING = 9;
    int TILE_ACCEPTED = 10;

    record HelloOk(int protocolVersion, ServerPolicy policy) implements S2C {
        @Override
        public int id() {
            return HELLO_OK;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeVarInt(protocolVersion);
            policy.write(w);
        }

        static HelloOk read(ByteReader r) {
            return new HelloOk(r.readVarInt(), ServerPolicy.read(r));
        }
    }

    record TileData(String dimension, int chunkX, int chunkZ, long revision, long hash, byte[] blob) implements S2C {
        @Override
        public int id() {
            return TILE_DATA;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeString(dimension);
            w.writeInt(chunkX);
            w.writeInt(chunkZ);
            w.writeLong(revision);
            w.writeLong(hash);
            w.writeBlob(blob);
        }

        static TileData read(ByteReader r) {
            return new TileData(r.readString(), r.readInt(), r.readInt(), r.readLong(), r.readLong(), r.readBlob());
        }
    }

    /**
     * Which tiles of a region the server holds, and at what revision. Lets a client work out in one
     * round trip exactly which tiles it needs, instead of probing chunk by chunk. Only populated
     * slots are listed.
     */
    record RegionIndex(String dimension, int regionX, int regionZ, int[] slots, long[] revisions) implements S2C {
        public RegionIndex {
            if (slots.length != revisions.length) {
                throw new IllegalArgumentException("slot and revision arrays must be the same length");
            }
        }

        @Override
        public int id() {
            return REGION_INDEX;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeString(dimension);
            w.writeInt(regionX);
            w.writeInt(regionZ);
            w.writeVarInt(slots.length);
            for (int i = 0; i < slots.length; i++) {
                w.writeShort(slots[i]);
                w.writeLong(revisions[i]);
            }
        }

        static RegionIndex read(ByteReader r) {
            String dimension = r.readString();
            int regionX = r.readInt();
            int regionZ = r.readInt();
            int count = r.readVarInt();
            if (count < 0 || count > dev.xetius.xetiusmap.common.util.MapCoords.TILES_PER_REGION) {
                throw new ProtocolException("region index claims " + count + " slots");
            }
            int[] slots = new int[count];
            long[] revisions = new long[count];
            for (int i = 0; i < count; i++) {
                slots[i] = r.readUnsignedShort();
                revisions[i] = r.readLong();
            }
            return new RegionIndex(dimension, regionX, regionZ, slots, revisions);
        }
    }

    /** The full waypoint list, sent on join and after a bulk change. */
    record WaypointSync(List<Waypoint> waypoints) implements S2C {
        @Override
        public int id() {
            return WAYPOINT_SYNC;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeList(waypoints, (buf, wp) -> wp.write(buf));
        }

        static WaypointSync read(ByteReader r) {
            return new WaypointSync(r.readList(Waypoint::read));
        }
    }

    /** A single waypoint added, changed or removed. */
    record WaypointDelta(Operation operation, UUID waypointId, Waypoint waypoint) implements S2C {

        public enum Operation {
            ADDED,
            UPDATED,
            REMOVED
        }

        public static WaypointDelta added(Waypoint waypoint) {
            return new WaypointDelta(Operation.ADDED, waypoint.id(), waypoint);
        }

        public static WaypointDelta updated(Waypoint waypoint) {
            return new WaypointDelta(Operation.UPDATED, waypoint.id(), waypoint);
        }

        public static WaypointDelta removed(UUID waypointId) {
            return new WaypointDelta(Operation.REMOVED, waypointId, null);
        }

        @Override
        public int id() {
            return WAYPOINT_DELTA;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeByte(operation.ordinal());
            w.writeUuid(waypointId);
            if (operation != Operation.REMOVED) {
                waypoint.write(w);
            }
        }

        static WaypointDelta read(ByteReader r) {
            int ordinal = r.readUnsignedByte();
            Operation[] values = Operation.values();
            if (ordinal >= values.length) {
                throw new ProtocolException("unknown waypoint delta operation " + ordinal);
            }
            Operation operation = values[ordinal];
            UUID id = r.readUuid();
            Waypoint waypoint = operation == Operation.REMOVED ? null : Waypoint.read(r);
            return new WaypointDelta(operation, id, waypoint);
        }
    }

    /**
     * Live positions. {@code typePalette} holds the distinct entity type ids referenced by
     * {@link Markers.MobMarker#typeIndex()}.
     */
    record EntityUpdate(
            String dimension,
            List<String> typePalette,
            List<Markers.PlayerMarker> players,
            List<Markers.MobMarker> mobs
    ) implements S2C {
        @Override
        public int id() {
            return ENTITY_UPDATE;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeString(dimension);
            w.writeList(typePalette, ByteWriter::writeString);
            w.writeList(players, (buf, p) -> p.write(buf));
            w.writeList(mobs, (buf, m) -> m.write(buf));
        }

        static EntityUpdate read(ByteReader r) {
            return new EntityUpdate(
                    r.readString(),
                    r.readList(ByteReader::readString),
                    r.readList(Markers.PlayerMarker::read),
                    r.readList(Markers.MobMarker::read)
            );
        }
    }

    record TeleportResult(boolean accepted, String message) implements S2C {
        @Override
        public int id() {
            return TELEPORT_RESULT;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeBoolean(accepted);
            w.writeString(message);
        }

        static TeleportResult read(ByteReader r) {
            return new TeleportResult(r.readBoolean(), r.readString());
        }
    }

    /** Out-of-band message for the client to surface in its map UI. */
    record Notice(Level level, String message) implements S2C {

        public enum Level {
            INFO,
            WARNING,
            ERROR
        }

        @Override
        public int id() {
            return NOTICE;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeByte(level.ordinal());
            w.writeString(message);
        }

        static Notice read(ByteReader r) {
            int ordinal = r.readUnsignedByte();
            Level[] values = Level.values();
            if (ordinal >= values.length) {
                throw new ProtocolException("unknown notice level " + ordinal);
            }
            return new Notice(values[ordinal], r.readString());
        }
    }

    /** Negative answer to a {@link C2S.TileRequest}, so the client stops asking. */
    record TileMissing(String dimension, List<ChunkRef> chunks) implements S2C {
        @Override
        public int id() {
            return TILE_MISSING;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeString(dimension);
            w.writeList(chunks, (buf, c) -> c.write(buf));
        }

        static TileMissing read(ByteReader r) {
            return new TileMissing(r.readString(), r.readList(ChunkRef::read));
        }
    }

    /**
     * Confirms an upload and tells the sender which revision the tile was stored under. Without it
     * a client would have no way to record the server's revision for a tile it rendered itself, and
     * would re-download its own work on the next login.
     */
    record TileAccepted(String dimension, int chunkX, int chunkZ, long revision) implements S2C {
        @Override
        public int id() {
            return TILE_ACCEPTED;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeString(dimension);
            w.writeInt(chunkX);
            w.writeInt(chunkZ);
            w.writeLong(revision);
        }

        static TileAccepted read(ByteReader r) {
            return new TileAccepted(r.readString(), r.readInt(), r.readInt(), r.readLong());
        }
    }

    static S2C decode(byte[] data) {
        ByteReader r = new ByteReader(data);
        int id = r.readUnsignedByte();
        return switch (id) {
            case HELLO_OK -> HelloOk.read(r);
            case TILE_DATA -> TileData.read(r);
            case REGION_INDEX -> RegionIndex.read(r);
            case WAYPOINT_SYNC -> WaypointSync.read(r);
            case WAYPOINT_DELTA -> WaypointDelta.read(r);
            case ENTITY_UPDATE -> EntityUpdate.read(r);
            case TELEPORT_RESULT -> TeleportResult.read(r);
            case NOTICE -> Notice.read(r);
            case TILE_MISSING -> TileMissing.read(r);
            case TILE_ACCEPTED -> TileAccepted.read(r);
            default -> throw new ProtocolException("unknown server packet id " + id);
        };
    }
}
