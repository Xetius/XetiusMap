package dev.xetius.xetiusmap.common.net;

import dev.xetius.xetiusmap.common.model.Markers;
import dev.xetius.xetiusmap.common.model.ServerPolicy;
import dev.xetius.xetiusmap.common.model.Waypoint;
import dev.xetius.xetiusmap.common.util.ChunkRef;
import dev.xetius.xetiusmap.common.util.RegionRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketCodecTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static Waypoint waypoint() {
        return new Waypoint(ID, "Base", "minecraft:overworld", 100, 64, -200, 0xFF8800, "home", ID, "Xetius", 1234L);
    }

    private static <T extends Packet> T roundTripC2S(T packet) {
        @SuppressWarnings("unchecked")
        T decoded = (T) C2S.decode(packet.encode());
        assertEquals(packet.id(), decoded.id());
        return decoded;
    }

    private static <T extends Packet> T roundTripS2C(T packet) {
        @SuppressWarnings("unchecked")
        T decoded = (T) S2C.decode(packet.encode());
        assertEquals(packet.id(), decoded.id());
        return decoded;
    }

    @Test
    void helloRoundTrips() {
        assertEquals(new C2S.Hello(Protocol.VERSION, "1.0.0"), roundTripC2S(new C2S.Hello(Protocol.VERSION, "1.0.0")));
    }

    @Test
    void tileUploadRoundTrips() {
        byte[] blob = {1, 2, 3, 4, 5};
        C2S.TileUpload decoded = roundTripC2S(new C2S.TileUpload("minecraft:the_end", -5, 7, 0xDEADBEEFL, blob));
        assertEquals("minecraft:the_end", decoded.dimension());
        assertEquals(-5, decoded.chunkX());
        assertEquals(7, decoded.chunkZ());
        assertEquals(0xDEADBEEFL, decoded.hash());
        assertArrayEquals(blob, decoded.blob());
    }

    @Test
    void tileRequestAndSubscribeRoundTrip() {
        C2S.TileRequest request = roundTripC2S(
                new C2S.TileRequest("minecraft:overworld", List.of(new ChunkRef(1, 2), new ChunkRef(-3, -4))));
        assertEquals(List.of(new ChunkRef(1, 2), new ChunkRef(-3, -4)), request.chunks());

        C2S.RegionSubscribe subscribe = roundTripC2S(
                new C2S.RegionSubscribe("minecraft:overworld", List.of(new RegionRef(0, -1))));
        assertEquals(List.of(new RegionRef(0, -1)), subscribe.regions());
    }

    @Test
    void waypointPacketsRoundTrip() {
        assertEquals(waypoint(), roundTripC2S(new C2S.WaypointCreate(waypoint())).waypoint());
        assertEquals(waypoint(), roundTripC2S(new C2S.WaypointUpdate(waypoint())).waypoint());
        assertEquals(ID, roundTripC2S(new C2S.WaypointDelete(ID)).waypointId());
        assertEquals(ID, roundTripC2S(new C2S.TeleportRequest(ID)).waypointId());
    }

    @Test
    void entityViewAndHideRoundTrip() {
        C2S.EntityView view = roundTripC2S(new C2S.EntityView(true, "minecraft:overworld", -10, -20, 10, 20));
        assertTrue(view.active());
        assertEquals(-20, view.minChunkZ());
        assertEquals(20, view.maxChunkZ());

        assertTrue(roundTripC2S(new C2S.SetHidden(true)).hidden());
    }

    @Test
    void helloOkCarriesPolicy() {
        ServerPolicy policy = new ServerPolicy("Paper 26.2", true, true, false, true, 192, 4, 30,
                List.of("minecraft:overworld", "minecraft:the_nether"));
        S2C.HelloOk decoded = roundTripS2C(new S2C.HelloOk(Protocol.VERSION, policy));
        assertEquals(policy, decoded.policy());
    }

    @Test
    void tileDataAndMissingRoundTrip() {
        byte[] blob = {9, 8, 7};
        S2C.TileData data = roundTripS2C(new S2C.TileData("minecraft:overworld", 4, -9, 77L, 88L, blob));
        assertEquals(77L, data.revision());
        assertArrayEquals(blob, data.blob());

        S2C.TileMissing missing = roundTripS2C(
                new S2C.TileMissing("minecraft:overworld", List.of(new ChunkRef(5, 6))));
        assertEquals(List.of(new ChunkRef(5, 6)), missing.chunks());
    }

    @Test
    void regionIndexRoundTrips() {
        int[] slots = {0, 33, 1023};
        long[] revisions = {1L, 500L, Long.MAX_VALUE};
        S2C.RegionIndex decoded = roundTripS2C(new S2C.RegionIndex("minecraft:overworld", -2, 3, slots, revisions));
        assertArrayEquals(slots, decoded.slots());
        assertArrayEquals(revisions, decoded.revisions());
    }

    @Test
    void waypointSyncAndDeltaRoundTrip() {
        assertEquals(List.of(waypoint()), roundTripS2C(new S2C.WaypointSync(List.of(waypoint()))).waypoints());

        S2C.WaypointDelta added = roundTripS2C(S2C.WaypointDelta.added(waypoint()));
        assertEquals(S2C.WaypointDelta.Operation.ADDED, added.operation());
        assertEquals(waypoint(), added.waypoint());

        S2C.WaypointDelta removed = roundTripS2C(S2C.WaypointDelta.removed(ID));
        assertEquals(S2C.WaypointDelta.Operation.REMOVED, removed.operation());
        assertEquals(ID, removed.waypointId());
    }

    @Test
    void entityUpdateRoundTrips() {
        S2C.EntityUpdate update = new S2C.EntityUpdate(
                "minecraft:overworld",
                List.of("minecraft:zombie", "minecraft:cow"),
                List.of(new Markers.PlayerMarker(ID, "Xetius", "minecraft:overworld", 10, 70, -20, 90.0F)),
                List.of(new Markers.MobMarker(1, Markers.MobCategory.PASSIVE, 12, 71, -25, 180.0F, true))
        );
        S2C.EntityUpdate decoded = roundTripS2C(update);

        assertEquals(update.typePalette(), decoded.typePalette());
        assertEquals(1, decoded.players().size());
        assertEquals("Xetius", decoded.players().getFirst().name());
        assertEquals(10, decoded.players().getFirst().x());
        assertEquals(1, decoded.mobs().size());
        assertEquals(Markers.MobCategory.PASSIVE, decoded.mobs().getFirst().category());
        assertTrue(decoded.mobs().getFirst().skyVisible(), "sky exposure must survive the round trip");
        // Yaw survives quantisation to within one step of 360/256 degrees.
        assertTrue(Math.abs(decoded.mobs().getFirst().yaw() - 180.0F) < 1.5F);
    }

    @Test
    void teleportToRoundTrips() {
        C2S.TeleportTo decoded = roundTripC2S(new C2S.TeleportTo("minecraft:the_end", -1234, 5678));
        assertEquals("minecraft:the_end", decoded.dimension());
        assertEquals(-1234, decoded.x());
        assertEquals(5678, decoded.z());
    }

    @Test
    void tileAcceptedRoundTrips() {
        S2C.TileAccepted decoded = roundTripS2C(new S2C.TileAccepted("minecraft:the_nether", -12, 34, 9001L));
        assertEquals("minecraft:the_nether", decoded.dimension());
        assertEquals(-12, decoded.chunkX());
        assertEquals(34, decoded.chunkZ());
        assertEquals(9001L, decoded.revision());
    }

    @Test
    void everyPacketIdIsDistinct() {
        // A duplicated id would silently route packets to the wrong decoder.
        java.util.Set<Integer> clientIds = new java.util.HashSet<>(List.of(
                C2S.HELLO, C2S.TILE_UPLOAD, C2S.TILE_REQUEST, C2S.REGION_SUBSCRIBE,
                C2S.WAYPOINT_CREATE, C2S.WAYPOINT_UPDATE, C2S.WAYPOINT_DELETE,
                C2S.TELEPORT_REQUEST, C2S.ENTITY_VIEW, C2S.SET_HIDDEN));
        assertEquals(10, clientIds.size());

        java.util.Set<Integer> serverIds = new java.util.HashSet<>(List.of(
                S2C.HELLO_OK, S2C.TILE_DATA, S2C.REGION_INDEX, S2C.WAYPOINT_SYNC,
                S2C.WAYPOINT_DELTA, S2C.ENTITY_UPDATE, S2C.TELEPORT_RESULT, S2C.NOTICE,
                S2C.TILE_MISSING, S2C.TILE_ACCEPTED));
        assertEquals(10, serverIds.size());
    }

    @Test
    void waypointSanitisationClampsHostileInput() {
        Waypoint hostile = new Waypoint(ID, "x".repeat(500), "d".repeat(500), 1, 2, 3,
                0xAABBCCDD, "i".repeat(500), ID, "o".repeat(500), 0L);
        Waypoint clean = hostile.sanitised();

        assertEquals(Waypoint.MAX_NAME_LENGTH, clean.name().length());
        assertEquals(Waypoint.MAX_ICON_LENGTH, clean.icon().length());
        assertEquals(Waypoint.MAX_OWNER_NAME_LENGTH, clean.ownerName().length());
        assertEquals(0xBBCCDD, clean.color(), "alpha must be stripped from the colour");
    }

    @Test
    void teleportResultAndNoticeRoundTrip() {
        S2C.TeleportResult result = roundTripS2C(new S2C.TeleportResult(false, "No permission"));
        assertEquals("No permission", result.message());

        S2C.Notice notice = roundTripS2C(new S2C.Notice(S2C.Notice.Level.WARNING, "Uploads throttled"));
        assertEquals(S2C.Notice.Level.WARNING, notice.level());
    }

    @Test
    void unknownPacketIdIsRejected() {
        assertThrows(ProtocolException.class, () -> C2S.decode(new byte[]{(byte) 200}));
        assertThrows(ProtocolException.class, () -> S2C.decode(new byte[]{(byte) 200}));
    }

    @Test
    void truncatedPacketIsRejectedNotCrashed() {
        byte[] encoded = new C2S.TileUpload("minecraft:overworld", 1, 2, 3L, new byte[]{1, 2, 3}).encode();
        for (int cut = 1; cut < encoded.length; cut++) {
            byte[] truncated = java.util.Arrays.copyOf(encoded, cut);
            assertThrows(ProtocolException.class, () -> C2S.decode(truncated), "cut at " + cut);
        }
    }

    @Test
    void oversizedTileUploadIsRejected() {
        byte[] tooBig = new byte[Protocol.MAX_TILE_BLOB_BYTES + 1];
        byte[] encoded = new C2S.TileUpload("minecraft:overworld", 0, 0, 0L, tooBig).encode();
        assertThrows(ProtocolException.class, () -> C2S.decode(encoded));
    }

    @Test
    void oversizedListsAreRejected() {
        List<ChunkRef> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i <= Protocol.MAX_TILE_REQUESTS_PER_PACKET; i++) {
            tooMany.add(new ChunkRef(i, i));
        }
        byte[] encoded = new C2S.TileRequest("minecraft:overworld", tooMany).encode();
        assertThrows(ProtocolException.class, () -> C2S.decode(encoded));
    }

    @Test
    void decodeReturnsTheRightSubtype() {
        assertInstanceOf(C2S.Hello.class, C2S.decode(new C2S.Hello(1, "x").encode()));
        assertInstanceOf(S2C.Notice.class, S2C.decode(new S2C.Notice(S2C.Notice.Level.INFO, "x").encode()));
    }
}
