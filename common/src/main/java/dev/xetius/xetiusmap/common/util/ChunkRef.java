package dev.xetius.xetiusmap.common.util;

import dev.xetius.xetiusmap.common.net.ByteReader;
import dev.xetius.xetiusmap.common.net.ByteWriter;

/** A chunk coordinate pair. */
public record ChunkRef(int x, int z) {

    public long key() {
        return MapCoords.key(x, z);
    }

    public RegionRef region() {
        return new RegionRef(MapCoords.chunkToRegion(x), MapCoords.chunkToRegion(z));
    }

    public void write(ByteWriter w) {
        w.writeInt(x);
        w.writeInt(z);
    }

    public static ChunkRef read(ByteReader r) {
        return new ChunkRef(r.readInt(), r.readInt());
    }

    public static ChunkRef of(long key) {
        return new ChunkRef(MapCoords.keyX(key), MapCoords.keyZ(key));
    }
}
