package dev.xetius.xetiusmap.paper.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Hands out the strictly increasing revision numbers that tell a client whether its cached copy of
 * a tile is stale.
 *
 * <p>Persisting on every increment would mean an fsync per uploaded tile, so instead a ceiling is
 * written ahead of use: the file always holds a value no smaller than any revision that has been
 * issued. After a crash the counter resumes from that ceiling, which may skip a block of numbers —
 * harmless, because only the ordering matters.
 */
public final class RevisionCounter {

    private static final long RESERVE_AHEAD = 1000L;

    private final Path file;
    private long next;
    private long persistedCeiling;

    public RevisionCounter(Path file, long floor) throws IOException {
        this.file = file;
        long stored = readStored();
        this.next = Math.max(stored, floor);
        this.persistedCeiling = stored;
        reserve();
    }

    public synchronized long next() {
        long value = ++next;
        if (value >= persistedCeiling) {
            reserve();
        }
        return value;
    }

    public synchronized long current() {
        return next;
    }

    /** Writes the ceiling out; safe to call again at shutdown. */
    public synchronized void flush() {
        writeCeiling(Math.max(persistedCeiling, next));
    }

    private void reserve() {
        persistedCeiling = next + RESERVE_AHEAD;
        writeCeiling(persistedCeiling);
    }

    private void writeCeiling(long value) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, Long.toString(value), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Losing the ceiling costs a rescan at next start, not correctness of the running server.
            throw new IllegalStateException("could not persist the map revision counter to " + file, e);
        }
    }

    private long readStored() {
        try {
            if (!Files.exists(file)) {
                return 0L;
            }
            return Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return 0L;
        }
    }
}
