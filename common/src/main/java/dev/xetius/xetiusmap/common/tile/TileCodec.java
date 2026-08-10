package dev.xetius.xetiusmap.common.tile;

import dev.xetius.xetiusmap.common.net.ProtocolException;

import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Deflate wrapper used for every tile blob, both on the wire and on disk. The uncompressed length
 * is stored up front and validated before allocation so a malicious blob cannot be used as a
 * decompression bomb.
 */
public final class TileCodec {

    /** Generous ceiling: a legitimate tile decodes to roughly 2 KB. */
    public static final int MAX_DECOMPRESSED_BYTES = 256 * 1024;

    private TileCodec() {
    }

    public static byte[] compress(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            byte[] out = new byte[Math.max(64, raw.length / 2)];
            int len = 4;
            out = ensure(out, len + 64);
            writeInt(out, 0, raw.length);
            while (!deflater.finished()) {
                out = ensure(out, len + 1024);
                len += deflater.deflate(out, len, out.length - len);
            }
            return Arrays.copyOf(out, len);
        } finally {
            deflater.end();
        }
    }

    public static byte[] decompress(byte[] blob) {
        if (blob.length < 4) {
            throw new ProtocolException("tile blob too short");
        }
        int size = readInt(blob, 0);
        if (size < 0 || size > MAX_DECOMPRESSED_BYTES) {
            throw new ProtocolException("implausible decompressed tile size: " + size);
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(blob, 4, blob.length - 4);
            byte[] out = new byte[size];
            int written = 0;
            while (written < size && !inflater.finished()) {
                int n = inflater.inflate(out, written, size - written);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new ProtocolException("truncated tile blob");
                    }
                    break;
                }
                written += n;
            }
            if (written != size) {
                throw new ProtocolException("tile blob declared " + size + " bytes but yielded " + written);
            }
            return out;
        } catch (DataFormatException e) {
            throw new ProtocolException("corrupt tile blob", e);
        } finally {
            inflater.end();
        }
    }

    /** 64-bit FNV-1a over the uncompressed body — cheap, and only used for change detection. */
    public static long hash(byte[] raw) {
        long h = 0xCBF29CE484222325L;
        for (byte b : raw) {
            h ^= (b & 0xFF);
            h *= 0x100000001B3L;
        }
        return h;
    }

    private static byte[] ensure(byte[] arr, int needed) {
        return arr.length >= needed ? arr : Arrays.copyOf(arr, Math.max(needed, arr.length * 2));
    }

    private static void writeInt(byte[] arr, int off, int v) {
        arr[off] = (byte) (v >>> 24);
        arr[off + 1] = (byte) (v >>> 16);
        arr[off + 2] = (byte) (v >>> 8);
        arr[off + 3] = (byte) v;
    }

    private static int readInt(byte[] arr, int off) {
        return ((arr[off] & 0xFF) << 24)
                | ((arr[off + 1] & 0xFF) << 16)
                | ((arr[off + 2] & 0xFF) << 8)
                | (arr[off + 3] & 0xFF);
    }
}
