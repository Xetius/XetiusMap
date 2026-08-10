package dev.xetius.xetiusmap.common.net;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Minimal big-endian buffer writer. Deliberately free of any Minecraft or Bukkit type so the exact
 * same encoder runs on the Fabric client and the Paper server.
 */
public final class ByteWriter {

    private byte[] buf;
    private int len;

    public ByteWriter() {
        this(64);
    }

    public ByteWriter(int initialCapacity) {
        this.buf = new byte[Math.max(16, initialCapacity)];
    }

    private void ensure(int extra) {
        if (len + extra <= buf.length) {
            return;
        }
        int target = buf.length;
        while (target < len + extra) {
            target = target + (target >> 1) + 16;
        }
        buf = Arrays.copyOf(buf, target);
    }

    public ByteWriter writeByte(int v) {
        ensure(1);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteWriter writeBoolean(boolean v) {
        return writeByte(v ? 1 : 0);
    }

    public ByteWriter writeShort(int v) {
        ensure(2);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteWriter writeInt(int v) {
        ensure(4);
        buf[len++] = (byte) (v >>> 24);
        buf[len++] = (byte) (v >>> 16);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteWriter writeLong(long v) {
        writeInt((int) (v >>> 32));
        writeInt((int) v);
        return this;
    }

    public ByteWriter writeFloat(float v) {
        return writeInt(Float.floatToIntBits(v));
    }

    /** LEB128, as used by the vanilla protocol. Non-negative values only in practice. */
    public ByteWriter writeVarInt(int v) {
        int value = v;
        while ((value & ~0x7F) != 0) {
            writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        return writeByte(value);
    }

    public ByteWriter writeString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        return writeBytes(bytes);
    }

    public ByteWriter writeUuid(UUID uuid) {
        writeLong(uuid.getMostSignificantBits());
        return writeLong(uuid.getLeastSignificantBits());
    }

    public ByteWriter writeBytes(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, len, bytes.length);
        len += bytes.length;
        return this;
    }

    /** Length-prefixed blob. */
    public ByteWriter writeBlob(byte[] bytes) {
        writeVarInt(bytes.length);
        return writeBytes(bytes);
    }

    public <T> ByteWriter writeList(Collection<T> items, BiConsumer<ByteWriter, T> encoder) {
        writeVarInt(items.size());
        for (T item : items) {
            encoder.accept(this, item);
        }
        return this;
    }

    public int length() {
        return len;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, len);
    }
}
