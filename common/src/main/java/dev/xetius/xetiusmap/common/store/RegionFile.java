package dev.xetius.xetiusmap.common.store;

import dev.xetius.xetiusmap.common.util.MapCoords;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Storage for the 32x32 tiles of one region, in one file. Identical code backs the server's shared
 * store and the client's local cache.
 *
 * <p>Layout: a fixed header holding {@code magic | version | entryCount | reserved} followed by
 * 1024 slot records of {@code revision | offset | length | hash}. A revision of zero means the slot
 * is empty. Blobs are appended at the end of the file and the slot record rewritten in place, so a
 * write is two small seeks and never rewrites the file; the space left behind by a superseded blob
 * is reclaimed by {@link #compactIfWasteful()}.
 *
 * <p>Every method is synchronised on the instance. This class does no threading of its own — the
 * caller is responsible for keeping it off latency-sensitive threads.
 */
public final class RegionFile implements Closeable {

    private static final int MAGIC = 0x584D5231; // "XMR1"
    private static final int FORMAT_VERSION = 1;
    private static final int SLOTS = MapCoords.TILES_PER_REGION;
    private static final int SLOT_BYTES = 24;
    private static final int HEADER_BYTES = 16 + SLOTS * SLOT_BYTES;

    /** Only bother compacting once there is real space to reclaim. */
    private static final long COMPACT_MIN_WASTE = 512 * 1024;
    private static final double COMPACT_WASTE_RATIO = 0.5;

    private final Path path;
    private final long[] revisions = new long[SLOTS];
    private final int[] offsets = new int[SLOTS];
    private final int[] lengths = new int[SLOTS];
    private final long[] hashes = new long[SLOTS];

    private RandomAccessFile file;
    private long dataEnd;
    private long wasted;
    private boolean closed;

    public RegionFile(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.getParent());
        this.file = new RandomAccessFile(path.toFile(), "rw");
        if (file.length() < HEADER_BYTES) {
            initialiseHeader();
        } else {
            readHeader();
        }
    }

    private void initialiseHeader() throws IOException {
        file.setLength(HEADER_BYTES);
        file.seek(0);
        file.writeInt(MAGIC);
        file.writeInt(FORMAT_VERSION);
        file.writeInt(SLOTS);
        file.writeInt(0);
        byte[] blank = new byte[SLOTS * SLOT_BYTES];
        file.write(blank);
        dataEnd = HEADER_BYTES;
        wasted = 0;
    }

    private void readHeader() throws IOException {
        file.seek(0);
        int magic = file.readInt();
        int version = file.readInt();
        int slots = file.readInt();
        file.readInt();
        if (magic != MAGIC || version != FORMAT_VERSION || slots != SLOTS) {
            throw new IOException("not a XetiusMap region file (or wrong version): " + path);
        }

        byte[] table = new byte[SLOTS * SLOT_BYTES];
        file.readFully(table);
        long used = 0;
        long fileLength = file.length();
        for (int i = 0; i < SLOTS; i++) {
            int p = i * SLOT_BYTES;
            revisions[i] = readLong(table, p);
            offsets[i] = readInt(table, p + 8);
            lengths[i] = readInt(table, p + 12);
            hashes[i] = readLong(table, p + 16);

            boolean sane = revisions[i] != 0
                    && lengths[i] > 0
                    && offsets[i] >= HEADER_BYTES
                    && (long) offsets[i] + lengths[i] <= fileLength;
            if (!sane) {
                // Torn write or corruption: drop the slot rather than fail the whole region.
                revisions[i] = 0;
                offsets[i] = 0;
                lengths[i] = 0;
                hashes[i] = 0;
            } else {
                used += lengths[i];
            }
        }
        dataEnd = fileLength;
        wasted = Math.max(0, dataEnd - HEADER_BYTES - used);
    }

    public synchronized boolean has(int slot) {
        checkSlot(slot);
        return !closed && revisions[slot] != 0;
    }

    public synchronized TileMeta meta(int slot) {
        checkSlot(slot);
        if (closed || revisions[slot] == 0) {
            return null;
        }
        return new TileMeta(revisions[slot], hashes[slot], lengths[slot]);
    }

    /** Snapshot of every populated slot, for answering a client's region index request. */
    public synchronized long[] revisionSnapshot() {
        return Arrays.copyOf(revisions, SLOTS);
    }

    public synchronized byte[] read(int slot) throws IOException {
        checkSlot(slot);
        ensureOpen();
        if (revisions[slot] == 0) {
            return null;
        }
        byte[] blob = new byte[lengths[slot]];
        file.seek(offsets[slot]);
        file.readFully(blob);
        return blob;
    }

    public synchronized void write(int slot, byte[] blob, long revision, long hash) throws IOException {
        checkSlot(slot);
        ensureOpen();
        if (blob.length == 0) {
            throw new IllegalArgumentException("refusing to store an empty tile");
        }
        if (revision == 0) {
            throw new IllegalArgumentException("revision 0 is reserved for empty slots");
        }

        if (revisions[slot] != 0) {
            wasted += lengths[slot];
        }

        long at = dataEnd;
        if (at > Integer.MAX_VALUE - blob.length) {
            throw new IOException("region file exceeded 2 GiB: " + path);
        }
        file.seek(at);
        file.write(blob);
        dataEnd = at + blob.length;

        revisions[slot] = revision;
        offsets[slot] = (int) at;
        lengths[slot] = blob.length;
        hashes[slot] = hash;
        writeSlotRecord(slot);
    }

    private void writeSlotRecord(int slot) throws IOException {
        file.seek(16L + (long) slot * SLOT_BYTES);
        file.writeLong(revisions[slot]);
        file.writeInt(offsets[slot]);
        file.writeInt(lengths[slot]);
        file.writeLong(hashes[slot]);
    }

    /** Rewrites the file without the dead blobs if enough space is recoverable. */
    public synchronized boolean compactIfWasteful() throws IOException {
        ensureOpen();
        long live = dataEnd - HEADER_BYTES - wasted;
        if (wasted < COMPACT_MIN_WASTE || wasted < live * COMPACT_WASTE_RATIO) {
            return false;
        }
        compact();
        return true;
    }

    public synchronized void compact() throws IOException {
        ensureOpen();
        Path tmp = path.resolveSibling(path.getFileName() + ".compact");
        Files.deleteIfExists(tmp);

        long[] newRevisions = new long[SLOTS];
        int[] newOffsets = new int[SLOTS];
        int[] newLengths = new int[SLOTS];
        long[] newHashes = new long[SLOTS];

        try (RandomAccessFile out = new RandomAccessFile(tmp.toFile(), "rw")) {
            out.setLength(HEADER_BYTES);
            out.seek(HEADER_BYTES);
            long cursor = HEADER_BYTES;
            for (int i = 0; i < SLOTS; i++) {
                if (revisions[i] == 0) {
                    continue;
                }
                byte[] blob = new byte[lengths[i]];
                file.seek(offsets[i]);
                file.readFully(blob);
                out.seek(cursor);
                out.write(blob);
                newRevisions[i] = revisions[i];
                newOffsets[i] = (int) cursor;
                newLengths[i] = lengths[i];
                newHashes[i] = hashes[i];
                cursor += blob.length;
            }
            out.seek(0);
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeInt(SLOTS);
            out.writeInt(0);
            for (int i = 0; i < SLOTS; i++) {
                out.writeLong(newRevisions[i]);
                out.writeInt(newOffsets[i]);
                out.writeInt(newLengths[i]);
                out.writeLong(newHashes[i]);
            }
            out.getChannel().force(true);
        }

        file.close();
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        file = new RandomAccessFile(path.toFile(), "rw");
        System.arraycopy(newRevisions, 0, revisions, 0, SLOTS);
        System.arraycopy(newOffsets, 0, offsets, 0, SLOTS);
        System.arraycopy(newLengths, 0, lengths, 0, SLOTS);
        System.arraycopy(newHashes, 0, hashes, 0, SLOTS);
        dataEnd = file.length();
        wasted = 0;
    }

    public synchronized void flush() throws IOException {
        if (!closed) {
            file.getChannel().force(false);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            file.close();
        }
    }

    public Path path() {
        return path;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("region file already closed: " + path);
        }
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            throw new IndexOutOfBoundsException("tile slot out of range: " + slot);
        }
    }

    private static int readInt(byte[] a, int p) {
        return ((a[p] & 0xFF) << 24) | ((a[p + 1] & 0xFF) << 16) | ((a[p + 2] & 0xFF) << 8) | (a[p + 3] & 0xFF);
    }

    private static long readLong(byte[] a, int p) {
        return ((long) readInt(a, p) << 32) | (readInt(a, p + 4) & 0xFFFFFFFFL);
    }

    /** What the store knows about a tile without reading its bytes. */
    public record TileMeta(long revision, long hash, int length) {
    }
}
