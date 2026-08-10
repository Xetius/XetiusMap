package dev.xetius.xetiusmap.common.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Splits logical packets across the size limit imposed on a single plugin message and puts them
 * back together on the far side.
 *
 * <p>A frame is {@code kind | ...}: kind 0 carries a whole packet and is the overwhelmingly common
 * case; kind 1 carries {@code streamId | index | total | part}. Streams are independent, so a large
 * tile transfer in flight never blocks a small waypoint update behind it.
 */
public final class Framing {

    private static final int KIND_WHOLE = 0;
    private static final int KIND_FRAGMENT = 1;

    private Framing() {
    }

    /** Encodes a packet into one or more frames, each safe to hand to the transport as-is. */
    public static List<byte[]> split(byte[] packet, AtomicInteger streamIds) {
        if (packet.length > Protocol.MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("packet of " + packet.length + " bytes exceeds the protocol limit");
        }
        if (packet.length + 1 <= Protocol.MAX_FRAME_PAYLOAD) {
            ByteWriter w = new ByteWriter(packet.length + 1);
            w.writeByte(KIND_WHOLE);
            w.writeBytes(packet);
            return List.of(w.toByteArray());
        }

        // Header is kind(1) + streamId(4) + two VarInts; 16 bytes is a safe upper bound.
        int chunkSize = Protocol.MAX_FRAME_PAYLOAD - 16;
        int total = (packet.length + chunkSize - 1) / chunkSize;
        int streamId = streamIds.incrementAndGet();

        List<byte[]> frames = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int from = index * chunkSize;
            int length = Math.min(chunkSize, packet.length - from);
            ByteWriter w = new ByteWriter(length + 16);
            w.writeByte(KIND_FRAGMENT);
            w.writeInt(streamId);
            w.writeVarInt(index);
            w.writeVarInt(total);
            w.writeBytes(java.util.Arrays.copyOfRange(packet, from, from + length));
            frames.add(w.toByteArray());
        }
        return frames;
    }

    /**
     * Per-peer reassembly state. One instance per connected player on the server, one per
     * connection on the client. Not thread-safe: call it from a single thread, or guard it.
     */
    public static final class Reassembler {

        /** Bounds the damage a peer can do by opening many streams and never finishing them. */
        private static final int MAX_CONCURRENT_STREAMS = 8;

        private final Map<Integer, PartialStream> streams = new HashMap<>();
        private long bufferedBytes;

        /**
         * Feeds one received frame in.
         *
         * @return the complete packet, or {@code null} if more frames are still needed
         */
        public byte[] accept(byte[] frame) {
            ByteReader r = new ByteReader(frame);
            int kind = r.readUnsignedByte();
            if (kind == KIND_WHOLE) {
                return r.readBytes(r.remaining());
            }
            if (kind != KIND_FRAGMENT) {
                throw new ProtocolException("unknown frame kind " + kind);
            }

            int streamId = r.readInt();
            int index = r.readVarInt();
            int total = r.readVarInt();
            byte[] part = r.readBytes(r.remaining());

            if (total <= 1 || index < 0 || index >= total) {
                throw new ProtocolException("bogus fragment " + index + "/" + total);
            }
            if ((long) total * Protocol.MAX_FRAME_PAYLOAD > Protocol.MAX_PACKET_BYTES) {
                throw new ProtocolException("fragmented packet would exceed the protocol limit");
            }

            PartialStream stream = streams.get(streamId);
            if (stream == null) {
                if (streams.size() >= MAX_CONCURRENT_STREAMS) {
                    dropOldest();
                }
                stream = new PartialStream(total);
                streams.put(streamId, stream);
            }
            if (stream.parts.length != total) {
                throw new ProtocolException("fragment count changed mid-stream");
            }
            if (stream.parts[index] == null) {
                stream.parts[index] = part;
                stream.received++;
                stream.bytes += part.length;
                bufferedBytes += part.length;
                if (stream.bytes > Protocol.MAX_PACKET_BYTES) {
                    streams.remove(streamId);
                    bufferedBytes -= stream.bytes;
                    throw new ProtocolException("fragmented packet exceeded the protocol limit");
                }
            }

            if (stream.received < total) {
                return null;
            }

            streams.remove(streamId);
            bufferedBytes -= stream.bytes;
            byte[] out = new byte[(int) stream.bytes];
            int p = 0;
            for (byte[] chunk : stream.parts) {
                System.arraycopy(chunk, 0, out, p, chunk.length);
                p += chunk.length;
            }
            return out;
        }

        public void reset() {
            streams.clear();
            bufferedBytes = 0;
        }

        public long bufferedBytes() {
            return bufferedBytes;
        }

        private void dropOldest() {
            Iterator<Map.Entry<Integer, PartialStream>> it = streams.entrySet().iterator();
            if (it.hasNext()) {
                Map.Entry<Integer, PartialStream> entry = it.next();
                bufferedBytes -= entry.getValue().bytes;
                it.remove();
            }
        }

        private static final class PartialStream {
            final byte[][] parts;
            int received;
            long bytes;

            PartialStream(int total) {
                this.parts = new byte[total][];
            }
        }
    }
}
