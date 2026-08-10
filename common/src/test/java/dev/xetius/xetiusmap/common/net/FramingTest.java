package dev.xetius.xetiusmap.common.net;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FramingTest {

    private static byte[] payload(int length, int seed) {
        byte[] out = new byte[length];
        new Random(seed).nextBytes(out);
        return out;
    }

    private static byte[] feed(Framing.Reassembler reassembler, List<byte[]> frames) {
        byte[] completed = null;
        for (byte[] frame : frames) {
            byte[] result = reassembler.accept(frame);
            if (result != null) {
                assertNull(completed, "only the final frame should complete the packet");
                completed = result;
            }
        }
        return completed;
    }

    @Test
    void smallPacketTravelsInOneFrame() {
        byte[] packet = payload(200, 1);
        List<byte[]> frames = Framing.split(packet, new AtomicInteger());
        assertEquals(1, frames.size());
        assertTrue(frames.getFirst().length <= Protocol.MAX_FRAME_PAYLOAD);

        assertArrayEquals(packet, feed(new Framing.Reassembler(), frames));
    }

    @Test
    void largePacketFragmentsAndReassembles() {
        byte[] packet = payload(250_000, 2);
        List<byte[]> frames = Framing.split(packet, new AtomicInteger());

        assertTrue(frames.size() > 8, "expected several fragments, got " + frames.size());
        for (byte[] frame : frames) {
            assertTrue(frame.length <= Protocol.MAX_FRAME_PAYLOAD,
                    "frame of " + frame.length + " bytes exceeds the transport limit");
        }
        assertArrayEquals(packet, feed(new Framing.Reassembler(), frames));
    }

    @Test
    void exactlyAtTheSingleFrameBoundary() {
        // One byte under the limit fits whole (the kind byte takes the last slot); one over splits.
        byte[] fits = payload(Protocol.MAX_FRAME_PAYLOAD - 1, 3);
        assertEquals(1, Framing.split(fits, new AtomicInteger()).size());

        byte[] splits = payload(Protocol.MAX_FRAME_PAYLOAD, 4);
        List<byte[]> frames = Framing.split(splits, new AtomicInteger());
        assertTrue(frames.size() > 1);
        assertArrayEquals(splits, feed(new Framing.Reassembler(), frames));
    }

    @Test
    void fragmentsMayArriveOutOfOrder() {
        byte[] packet = payload(120_000, 5);
        List<byte[]> frames = new ArrayList<>(Framing.split(packet, new AtomicInteger()));
        Collections.shuffle(frames, new Random(42));

        assertArrayEquals(packet, feed(new Framing.Reassembler(), frames));
    }

    @Test
    void independentStreamsMayInterleave() {
        AtomicInteger ids = new AtomicInteger();
        byte[] first = payload(90_000, 6);
        byte[] second = payload(70_000, 7);
        List<byte[]> a = Framing.split(first, ids);
        List<byte[]> b = Framing.split(second, ids);

        Framing.Reassembler reassembler = new Framing.Reassembler();
        List<byte[]> completed = new ArrayList<>();
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            if (i < a.size()) {
                byte[] done = reassembler.accept(a.get(i));
                if (done != null) {
                    completed.add(done);
                }
            }
            if (i < b.size()) {
                byte[] done = reassembler.accept(b.get(i));
                if (done != null) {
                    completed.add(done);
                }
            }
        }

        assertEquals(2, completed.size());
        assertTrue(completed.stream().anyMatch(p -> java.util.Arrays.equals(p, first)));
        assertTrue(completed.stream().anyMatch(p -> java.util.Arrays.equals(p, second)));
        assertEquals(0, reassembler.bufferedBytes(), "all buffers should be released");
    }

    @Test
    void duplicateFragmentIsIgnored() {
        byte[] packet = payload(80_000, 8);
        List<byte[]> frames = Framing.split(packet, new AtomicInteger());

        Framing.Reassembler reassembler = new Framing.Reassembler();
        for (int i = 0; i < frames.size() - 1; i++) {
            assertNull(reassembler.accept(frames.get(i)));
            assertNull(reassembler.accept(frames.get(i)), "a repeated fragment must not complete the packet");
        }
        assertNotNull(reassembler.accept(frames.getLast()));
    }

    @Test
    void rejectsMalformedFrames() {
        Framing.Reassembler reassembler = new Framing.Reassembler();
        assertThrows(ProtocolException.class, () -> reassembler.accept(new byte[]{99}));

        ByteWriter bogus = new ByteWriter();
        bogus.writeByte(1);
        bogus.writeInt(1);
        bogus.writeVarInt(5);
        bogus.writeVarInt(2);
        bogus.writeBytes(new byte[10]);
        assertThrows(ProtocolException.class, () -> reassembler.accept(bogus.toByteArray()));
    }

    @Test
    void rejectsPacketOverProtocolLimit() {
        byte[] huge = new byte[Protocol.MAX_PACKET_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> Framing.split(huge, new AtomicInteger()));
    }

    @Test
    void abandonedStreamsAreBounded() {
        AtomicInteger ids = new AtomicInteger();
        Framing.Reassembler reassembler = new Framing.Reassembler();
        // Open far more streams than the cap and never finish any of them.
        for (int i = 0; i < 50; i++) {
            List<byte[]> frames = Framing.split(payload(100_000, i), ids);
            reassembler.accept(frames.getFirst());
        }
        assertTrue(reassembler.bufferedBytes() < 10L * Protocol.MAX_FRAME_PAYLOAD,
                "abandoned streams must not accumulate without bound: " + reassembler.bufferedBytes());
    }
}
