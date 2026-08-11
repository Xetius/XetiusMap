package dev.xetius.xetiusmap.common.model;

import dev.xetius.xetiusmap.common.net.ByteReader;
import dev.xetius.xetiusmap.common.net.ByteWriter;
import dev.xetius.xetiusmap.common.net.ProtocolException;

import java.util.HashMap;
import java.util.Map;

/**
 * Everything the server needs to colour a block without knowing anything about blocks.
 *
 * <p>The plugin runs on plain {@code paper-api}, which exposes a block's material and biome but has
 * no notion of what colour either should be — that knowledge lives entirely in the client. A client
 * builds this table once from its own registries and uploads it; the server caches it to disk and
 * uses it to render chunks that nobody has walked through, straight from the world files.
 *
 * <p>Colours are the block's default map colour, untinted. Where a block takes its colour from the
 * biome instead — grass, leaves, water — {@link Entry#tint()} says which biome colour to use, and
 * the flat colour is only a fallback for biomes the palette does not cover.
 */
public record BlockPalette(Map<String, Entry> blocks, Map<String, BiomeTint> biomes) {

    /** Which biome colour a block takes, if any. */
    public static final byte TINT_NONE = 0;
    public static final byte TINT_GRASS = 1;
    public static final byte TINT_FOLIAGE = 2;
    public static final byte TINT_WATER = 3;
    public static final byte TINT_DRY_FOLIAGE = 4;

    /** Guards against a hostile or broken client sending something enormous. */
    private static final int MAX_BLOCKS = 65_536;
    private static final int MAX_BIOMES = 8_192;

    /**
     * @param rgb  the block's own colour, with no biome tint applied
     * @param tint one of the {@code TINT_} constants
     */
    public record Entry(int rgb, byte tint) {
    }

    /** The three biome-dependent colours the vanilla renderer uses. */
    public record BiomeTint(int grass, int foliage, int water) {
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public int size() {
        return blocks.size();
    }

    /**
     * The colour for a block in a biome, already resolved.
     *
     * @return the colour, or {@code fallback} if the block is not in the palette
     */
    public int colorOf(String blockId, String biomeId, int fallback) {
        Entry entry = blocks.get(blockId);
        if (entry == null) {
            return fallback;
        }
        if (entry.tint() == TINT_NONE) {
            return entry.rgb();
        }
        BiomeTint tint = biomes.get(biomeId);
        if (tint == null) {
            return entry.rgb();
        }
        int tinted = switch (entry.tint()) {
            case TINT_GRASS -> tint.grass();
            case TINT_FOLIAGE, TINT_DRY_FOLIAGE -> tint.foliage();
            case TINT_WATER -> tint.water();
            default -> entry.rgb();
        };
        // White is the "no tint" sentinel; taking it literally is what once turned water grey.
        int rgb = tinted & 0xFFFFFF;
        return rgb == 0 || rgb == 0xFFFFFF ? entry.rgb() : rgb;
    }

    /** True when a block is water, which the renderer shades by depth. */
    public boolean isWater(String blockId) {
        Entry entry = blocks.get(blockId);
        return entry != null && entry.tint() == TINT_WATER;
    }

    public void write(ByteWriter w) {
        w.writeVarInt(blocks.size());
        for (Map.Entry<String, Entry> entry : blocks.entrySet()) {
            w.writeString(entry.getKey());
            w.writeInt(entry.getValue().rgb());
            w.writeByte(entry.getValue().tint());
        }
        w.writeVarInt(biomes.size());
        for (Map.Entry<String, BiomeTint> entry : biomes.entrySet()) {
            w.writeString(entry.getKey());
            w.writeInt(entry.getValue().grass());
            w.writeInt(entry.getValue().foliage());
            w.writeInt(entry.getValue().water());
        }
    }

    public static BlockPalette read(ByteReader r) {
        int blockCount = r.readVarInt();
        if (blockCount < 0 || blockCount > MAX_BLOCKS) {
            throw new ProtocolException("implausible block palette size: " + blockCount);
        }
        Map<String, Entry> blocks = new HashMap<>(Math.max(16, blockCount));
        for (int i = 0; i < blockCount; i++) {
            String id = r.readString();
            blocks.put(id, new Entry(r.readInt() & 0xFFFFFF, r.readByte()));
        }

        int biomeCount = r.readVarInt();
        if (biomeCount < 0 || biomeCount > MAX_BIOMES) {
            throw new ProtocolException("implausible biome palette size: " + biomeCount);
        }
        Map<String, BiomeTint> biomes = new HashMap<>(Math.max(16, biomeCount));
        for (int i = 0; i < biomeCount; i++) {
            String id = r.readString();
            biomes.put(id, new BiomeTint(r.readInt() & 0xFFFFFF, r.readInt() & 0xFFFFFF, r.readInt() & 0xFFFFFF));
        }
        return new BlockPalette(blocks, biomes);
    }

    public static BlockPalette empty() {
        return new BlockPalette(Map.of(), Map.of());
    }
}
