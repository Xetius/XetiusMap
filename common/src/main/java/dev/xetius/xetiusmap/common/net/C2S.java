package dev.xetius.xetiusmap.common.net;

import dev.xetius.xetiusmap.common.model.Waypoint;
import dev.xetius.xetiusmap.common.util.ChunkRef;
import dev.xetius.xetiusmap.common.util.RegionRef;

import java.util.List;
import java.util.UUID;

/**
 * Everything a client may send. All of it arrives from an untrusted source, so every decoder is
 * bounded and the server re-validates the contents regardless of what the client claims.
 */
public sealed interface C2S extends Packet {

    int HELLO = 1;
    int TILE_UPLOAD = 2;
    int TILE_REQUEST = 3;
    int REGION_SUBSCRIBE = 4;
    int WAYPOINT_CREATE = 5;
    int WAYPOINT_UPDATE = 6;
    int WAYPOINT_DELETE = 7;
    int TELEPORT_REQUEST = 8;
    int ENTITY_VIEW = 9;
    int SET_HIDDEN = 10;
    int BLOCK_PALETTE = 11;
    int TELEPORT_TO = 12;

    /** First thing a client sends after joining. */
    record Hello(int protocolVersion, String modVersion) implements C2S {
        @Override
        public int id() {
            return HELLO;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeVarInt(protocolVersion);
            w.writeString(modVersion);
        }

        static Hello read(ByteReader r) {
            return new Hello(r.readVarInt(), r.readString());
        }
    }

    /** A freshly rendered tile offered to the shared store. */
    record TileUpload(String dimension, int chunkX, int chunkZ, long hash, byte[] blob) implements C2S {
        @Override
        public int id() {
            return TILE_UPLOAD;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeString(dimension);
            w.writeInt(chunkX);
            w.writeInt(chunkZ);
            w.writeLong(hash);
            w.writeBlob(blob);
        }

        static TileUpload read(ByteReader r) {
            String dimension = r.readString();
            int chunkX = r.readInt();
            int chunkZ = r.readInt();
            long hash = r.readLong();
            byte[] blob = r.readBlob();
            if (blob.length == 0 || blob.length > Protocol.MAX_TILE_BLOB_BYTES) {
                throw new ProtocolException("tile upload of " + blob.length + " bytes rejected");
            }
            return new TileUpload(dimension, chunkX, chunkZ, hash, blob);
        }
    }

    /** Asks for specific tiles the client does not have, or has an older revision of. */
    record TileRequest(String dimension, List<ChunkRef> chunks) implements C2S {
        @Override
        public int id() {
            return TILE_REQUEST;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeString(dimension);
            w.writeList(chunks, (buf, c) -> c.write(buf));
        }

        static TileRequest read(ByteReader r) {
            String dimension = r.readString();
            List<ChunkRef> chunks = r.readList(ChunkRef::read);
            if (chunks.size() > Protocol.MAX_TILE_REQUESTS_PER_PACKET) {
                throw new ProtocolException("tile request for " + chunks.size() + " chunks rejected");
            }
            return new TileRequest(dimension, chunks);
        }
    }

    /**
     * Replaces the client's whole subscription set. The server answers each region with a
     * {@link S2C.RegionIndex} and thereafter pushes updates for those regions only.
     */
    record RegionSubscribe(String dimension, List<RegionRef> regions) implements C2S {
        @Override
        public int id() {
            return REGION_SUBSCRIBE;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeString(dimension);
            w.writeList(regions, (buf, region) -> region.write(buf));
        }

        static RegionSubscribe read(ByteReader r) {
            String dimension = r.readString();
            List<RegionRef> regions = r.readList(RegionRef::read);
            if (regions.size() > Protocol.MAX_REGION_SUBSCRIPTIONS) {
                throw new ProtocolException("subscription to " + regions.size() + " regions rejected");
            }
            return new RegionSubscribe(dimension, regions);
        }
    }

    record WaypointCreate(Waypoint waypoint) implements C2S {
        @Override
        public int id() {
            return WAYPOINT_CREATE;
        }

        @Override
        public void write(ByteWriter w) {
            waypoint.write(w);
        }

        static WaypointCreate read(ByteReader r) {
            return new WaypointCreate(Waypoint.read(r));
        }
    }

    record WaypointUpdate(Waypoint waypoint) implements C2S {
        @Override
        public int id() {
            return WAYPOINT_UPDATE;
        }

        @Override
        public void write(ByteWriter w) {
            waypoint.write(w);
        }

        static WaypointUpdate read(ByteReader r) {
            return new WaypointUpdate(Waypoint.read(r));
        }
    }

    record WaypointDelete(UUID waypointId) implements C2S {
        @Override
        public int id() {
            return WAYPOINT_DELETE;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeUuid(waypointId);
        }

        static WaypointDelete read(ByteReader r) {
            return new WaypointDelete(r.readUuid());
        }
    }

    record TeleportRequest(UUID waypointId) implements C2S {
        @Override
        public int id() {
            return TELEPORT_REQUEST;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeUuid(waypointId);
        }

        static TeleportRequest read(ByteReader r) {
            return new TeleportRequest(r.readUuid());
        }
    }

    /**
     * Tells the server which part of the world the player is looking at, so entity updates can
     * cover the visible area of the world map instead of just the area around the player.
     * Sending it with {@code active = false} reverts to minimap-sized updates.
     */
    record EntityView(boolean active, String dimension, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ)
            implements C2S {
        @Override
        public int id() {
            return ENTITY_VIEW;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeBoolean(active);
            w.writeString(dimension);
            w.writeInt(minChunkX);
            w.writeInt(minChunkZ);
            w.writeInt(maxChunkX);
            w.writeInt(maxChunkZ);
        }

        static EntityView read(ByteReader r) {
            return new EntityView(r.readBoolean(), r.readString(), r.readInt(), r.readInt(), r.readInt(), r.readInt());
        }
    }

    /** Opts the sender out of appearing on other players' maps. */
    record SetHidden(boolean hidden) implements C2S {
        @Override
        public int id() {
            return SET_HIDDEN;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeBoolean(hidden);
        }

        static SetHidden read(ByteReader r) {
            return new SetHidden(r.readBoolean());
        }
    }

    /**
     * The block and biome colour table, sent when the server asks for it. This is what lets a
     * server with no block knowledge of its own render chunks nobody has visited.
     */
    record BlockPaletteUpload(dev.xetius.xetiusmap.common.model.BlockPalette palette) implements C2S {
        @Override
        public int id() {
            return BLOCK_PALETTE;
        }

        @Override
        public void write(ByteWriter w) {
            palette.write(w);
        }

        static BlockPaletteUpload read(ByteReader r) {
            return new BlockPaletteUpload(dev.xetius.xetiusmap.common.model.BlockPalette.read(r));
        }
    }

    /**
     * Asks to be moved to a point picked off the map. The height is deliberately not sent: the
     * server works out a safe landing spot itself, because the client has no idea what is there.
     */
    record TeleportTo(String dimension, int x, int z) implements C2S {
        @Override
        public int id() {
            return TELEPORT_TO;
        }

        @Override
        public void write(ByteWriter w) {
            w.writeString(dimension);
            w.writeInt(x);
            w.writeInt(z);
        }

        static TeleportTo read(ByteReader r) {
            return new TeleportTo(r.readString(), r.readInt(), r.readInt());
        }
    }

    static C2S decode(byte[] data) {
        ByteReader r = new ByteReader(data);
        int id = r.readUnsignedByte();
        return switch (id) {
            case HELLO -> Hello.read(r);
            case TILE_UPLOAD -> TileUpload.read(r);
            case TILE_REQUEST -> TileRequest.read(r);
            case REGION_SUBSCRIBE -> RegionSubscribe.read(r);
            case WAYPOINT_CREATE -> WaypointCreate.read(r);
            case WAYPOINT_UPDATE -> WaypointUpdate.read(r);
            case WAYPOINT_DELETE -> WaypointDelete.read(r);
            case TELEPORT_REQUEST -> TeleportRequest.read(r);
            case ENTITY_VIEW -> EntityView.read(r);
            case SET_HIDDEN -> SetHidden.read(r);
            case BLOCK_PALETTE -> BlockPaletteUpload.read(r);
            case TELEPORT_TO -> TeleportTo.read(r);
            default -> throw new ProtocolException("unknown client packet id " + id);
        };
    }
}
