package dev.xetius.xetiusmap.common.tile;

import dev.xetius.xetiusmap.common.net.ProtocolException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rendered surface of a single chunk: one colour, one height and one water depth per block
 * column, in {@code z * 16 + x} order.
 *
 * <p>Colours are fully resolved on the client (map colour, biome tint and height shading already
 * applied) so the server never needs to know anything about blocks — it stores and forwards the
 * encoded blob verbatim. Heights are kept alongside the colours so the client can re-shade a tile
 * against its neighbours when a chunk arrives before the chunk to its north.
 */
public record ChunkTile(int chunkX, int chunkZ, int[] colors, short[] heights, byte[] waterDepth) {

    public static final int COLUMNS = 256;
    private static final int FORMAT_VERSION = 1;
    private static final int FLAG_WATER_DEPTH = 0x1;

    public ChunkTile {
        if (colors.length != COLUMNS || heights.length != COLUMNS) {
            throw new IllegalArgumentException("a chunk tile must hold exactly " + COLUMNS + " columns");
        }
        if (waterDepth != null && waterDepth.length != COLUMNS) {
            throw new IllegalArgumentException("water depth array must hold exactly " + COLUMNS + " columns");
        }
    }

    public static int index(int localX, int localZ) {
        return (localZ << 4) | localX;
    }

    public int colorAt(int localX, int localZ) {
        return colors[index(localX, localZ)];
    }

    public int heightAt(int localX, int localZ) {
        return heights[index(localX, localZ)];
    }

    /**
     * Serialises to the uncompressed wire/disk body. Colours are palettised because a typical chunk
     * uses only a few dozen distinct shades, which takes a 1 KB colour array down to well under 300
     * bytes once {@link TileCodec#compress} has run over it.
     */
    public byte[] encode() {
        Map<Integer, Integer> paletteIndex = new LinkedHashMap<>();
        for (int color : colors) {
            paletteIndex.putIfAbsent(color, paletteIndex.size());
        }
        int paletteSize = paletteIndex.size();
        int bits = bitsFor(paletteSize);

        int minHeight = Short.MAX_VALUE;
        for (short h : heights) {
            minHeight = Math.min(minHeight, h);
        }

        boolean hasWater = waterDepth != null;
        int indexBytes = (COLUMNS * bits + 7) / 8;
        int size = 1 + 1 + 2 + 1 + 2 + paletteSize * 4 + indexBytes + COLUMNS * 2 + (hasWater ? COLUMNS : 0);

        byte[] out = new byte[size];
        int p = 0;
        out[p++] = FORMAT_VERSION;
        out[p++] = (byte) (hasWater ? FLAG_WATER_DEPTH : 0);
        out[p++] = (byte) (minHeight >>> 8);
        out[p++] = (byte) minHeight;
        out[p++] = (byte) bits;
        out[p++] = (byte) (paletteSize >>> 8);
        out[p++] = (byte) paletteSize;
        for (int color : paletteIndex.keySet()) {
            out[p++] = (byte) (color >>> 24);
            out[p++] = (byte) (color >>> 16);
            out[p++] = (byte) (color >>> 8);
            out[p++] = (byte) color;
        }

        if (bits > 0) {
            long acc = 0;
            int accBits = 0;
            for (int color : colors) {
                acc = (acc << bits) | paletteIndex.get(color);
                accBits += bits;
                while (accBits >= 8) {
                    accBits -= 8;
                    out[p++] = (byte) (acc >>> accBits);
                }
            }
            if (accBits > 0) {
                out[p++] = (byte) (acc << (8 - accBits));
            }
        }

        for (short h : heights) {
            int delta = h - minHeight;
            out[p++] = (byte) (delta >>> 8);
            out[p++] = (byte) delta;
        }

        if (hasWater) {
            System.arraycopy(waterDepth, 0, out, p, COLUMNS);
        }
        return out;
    }

    public static ChunkTile decode(int chunkX, int chunkZ, byte[] body) {
        try {
            int p = 0;
            int version = body[p++] & 0xFF;
            if (version != FORMAT_VERSION) {
                throw new ProtocolException("unsupported tile format version " + version);
            }
            int flags = body[p++] & 0xFF;
            int minHeight = (short) (((body[p++] & 0xFF) << 8) | (body[p++] & 0xFF));
            int bits = body[p++] & 0xFF;
            int paletteSize = ((body[p++] & 0xFF) << 8) | (body[p++] & 0xFF);
            if (bits > 16 || paletteSize > COLUMNS) {
                throw new ProtocolException("implausible tile palette: " + paletteSize + " entries, " + bits + " bits");
            }

            int[] palette = new int[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                palette[i] = ((body[p++] & 0xFF) << 24)
                        | ((body[p++] & 0xFF) << 16)
                        | ((body[p++] & 0xFF) << 8)
                        | (body[p++] & 0xFF);
            }

            int[] colors = new int[COLUMNS];
            if (bits == 0) {
                Arrays.fill(colors, paletteSize > 0 ? palette[0] : 0);
            } else {
                long acc = 0;
                int accBits = 0;
                int mask = (1 << bits) - 1;
                for (int i = 0; i < COLUMNS; i++) {
                    while (accBits < bits) {
                        acc = (acc << 8) | (body[p++] & 0xFF);
                        accBits += 8;
                    }
                    accBits -= bits;
                    int idx = (int) ((acc >>> accBits) & mask);
                    if (idx >= paletteSize) {
                        throw new ProtocolException("tile palette index out of range: " + idx);
                    }
                    colors[i] = palette[idx];
                }
            }

            short[] heights = new short[COLUMNS];
            for (int i = 0; i < COLUMNS; i++) {
                int delta = ((body[p++] & 0xFF) << 8) | (body[p++] & 0xFF);
                heights[i] = (short) (minHeight + delta);
            }

            byte[] water = null;
            if ((flags & FLAG_WATER_DEPTH) != 0) {
                water = Arrays.copyOfRange(body, p, p + COLUMNS);
            }
            return new ChunkTile(chunkX, chunkZ, colors, heights, water);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new ProtocolException("truncated tile body", e);
        }
    }

    private static int bitsFor(int paletteSize) {
        if (paletteSize <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }
}
