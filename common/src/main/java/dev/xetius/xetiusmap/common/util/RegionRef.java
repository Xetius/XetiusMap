package dev.xetius.xetiusmap.common.util;

import dev.xetius.xetiusmap.common.net.ByteReader;
import dev.xetius.xetiusmap.common.net.ByteWriter;

/** A region coordinate pair — 32x32 chunks, 512x512 blocks. */
public record RegionRef(int x, int z) {

    public long key() {
        return MapCoords.key(x, z);
    }

    /** World-space block coordinate of this region's north-west corner. */
    public int originBlockX() {
        return x * MapCoords.REGION_BLOCKS;
    }

    public int originBlockZ() {
        return z * MapCoords.REGION_BLOCKS;
    }

    public void write(ByteWriter w) {
        w.writeInt(x);
        w.writeInt(z);
    }

    public static RegionRef read(ByteReader r) {
        return new RegionRef(r.readInt(), r.readInt());
    }

    public static RegionRef of(long key) {
        return new RegionRef(MapCoords.keyX(key), MapCoords.keyZ(key));
    }

    public static RegionRef ofChunk(int chunkX, int chunkZ) {
        return new RegionRef(MapCoords.chunkToRegion(chunkX), MapCoords.chunkToRegion(chunkZ));
    }
}
