package dev.xetius.xetiusmap.paper.service;

import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds which chunks a world has actually generated, by reading the headers of its Anvil region
 * files.
 *
 * <p>Every {@code r.X.Z.mca} begins with a 4 KiB table of 1024 four-byte entries, one per chunk;
 * a zero entry means that chunk was never written. Reading that table is a few kilobytes per region
 * and tells us exactly which chunks exist, which is far better than probing millions of coordinates
 * with {@code isChunkGenerated} — and it never causes generation as a side effect.
 */
public final class RegionScanner {

    private static final int HEADER_BYTES = 4096;
    private static final int CHUNKS_PER_REGION = 1024;

    private RegionScanner() {
    }

    /** A generated chunk, in chunk coordinates. */
    public record ChunkRef(int x, int z) {
    }

    /**
     * The region directory for a world. Bukkit hands back the world container, but where the
     * regions sit inside it depends on the environment.
     */
    public static File regionDirectory(World world) {
        File base = world.getWorldFolder();
        for (String candidate : new String[]{"region", "DIM-1/region", "DIM1/region"}) {
            File dir = new File(base, candidate);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }

    /**
     * Every generated chunk in a world, read from its region headers.
     *
     * <p>Blocking file I/O: call it off the main thread.
     */
    public static List<ChunkRef> generatedChunks(World world) throws IOException {
        File dir = regionDirectory(world);
        if (dir == null) {
            return List.of();
        }
        File[] files = dir.listFiles((d, name) -> name.startsWith("r.") && name.endsWith(".mca"));
        if (files == null) {
            return List.of();
        }

        List<ChunkRef> chunks = new ArrayList<>();
        for (File file : files) {
            int[] region = parseRegionCoordinates(file.getName());
            if (region == null || file.length() < HEADER_BYTES) {
                continue;
            }
            byte[] header = new byte[HEADER_BYTES];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.readFully(header);
            }
            for (int slot = 0; slot < CHUNKS_PER_REGION; slot++) {
                int p = slot * 4;
                // Three bytes of sector offset plus a length; all zero means "never written".
                int entry = ((header[p] & 0xFF) << 24) | ((header[p + 1] & 0xFF) << 16)
                        | ((header[p + 2] & 0xFF) << 8) | (header[p + 3] & 0xFF);
                if (entry == 0) {
                    continue;
                }
                chunks.add(new ChunkRef(
                        (region[0] << 5) + (slot & 31),
                        (region[1] << 5) + ((slot >> 5) & 31)));
            }
        }
        return chunks;
    }

    private static int[] parseRegionCoordinates(String fileName) {
        String[] parts = fileName.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
