package dev.xetius.xetiusmap.common.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Counterpart to {@link ByteWriter}. Every read is bounds-checked and throws
 * {@link ProtocolException} rather than an unchecked array exception, so a malformed packet from an
 * untrusted client is a recoverable error at the dispatch site instead of a crash.
 */
public final class ByteReader {

    /** Anything larger than this in a length prefix is treated as malicious rather than allocated. */
    private static final int MAX_ALLOC = 8 * 1024 * 1024;

    private final byte[] buf;
    private final int limit;
    private int pos;

    public ByteReader(byte[] data) {
        this(data, 0, data.length);
    }

    public ByteReader(byte[] data, int offset, int length) {
        this.buf = data;
        this.pos = offset;
        this.limit = offset + length;
    }

    private void require(int n) {
        if (n < 0 || pos + n > limit) {
            throw new ProtocolException("truncated packet: wanted " + n + " byte(s), " + (limit - pos) + " remaining");
        }
    }

    public int readUnsignedByte() {
        require(1);
        return buf[pos++] & 0xFF;
    }

    public byte readByte() {
        require(1);
        return buf[pos++];
    }

    public boolean readBoolean() {
        return readUnsignedByte() != 0;
    }

    public int readUnsignedShort() {
        require(2);
        return ((buf[pos++] & 0xFF) << 8) | (buf[pos++] & 0xFF);
    }

    public short readShort() {
        return (short) readUnsignedShort();
    }

    public int readInt() {
        require(4);
        return ((buf[pos++] & 0xFF) << 24)
                | ((buf[pos++] & 0xFF) << 16)
                | ((buf[pos++] & 0xFF) << 8)
                | (buf[pos++] & 0xFF);
    }

    public long readLong() {
        return ((long) readInt() << 32) | (readInt() & 0xFFFFFFFFL);
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public int readVarInt() {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new ProtocolException("VarInt too long");
    }

    /** Reads a length prefix and validates it against the remaining bytes before allocating. */
    private int readLengthPrefix() {
        int n = readVarInt();
        if (n < 0 || n > MAX_ALLOC || n > limit - pos) {
            throw new ProtocolException("bogus length prefix: " + n);
        }
        return n;
    }

    public String readString() {
        int n = readLengthPrefix();
        String s = new String(buf, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }

    public UUID readUuid() {
        return new UUID(readLong(), readLong());
    }

    public byte[] readBytes(int n) {
        require(n);
        byte[] out = Arrays.copyOfRange(buf, pos, pos + n);
        pos += n;
        return out;
    }

    public byte[] readBlob() {
        return readBytes(readLengthPrefix());
    }

    public <T> List<T> readList(Function<ByteReader, T> decoder) {
        int n = readLengthPrefix();
        List<T> out = new ArrayList<>(Math.min(n, 1024));
        for (int i = 0; i < n; i++) {
            out.add(decoder.apply(this));
        }
        return out;
    }

    public int remaining() {
        return limit - pos;
    }
}
