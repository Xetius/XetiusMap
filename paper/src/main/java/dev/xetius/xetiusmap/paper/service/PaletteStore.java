package dev.xetius.xetiusmap.paper.service;

import dev.xetius.xetiusmap.common.model.BlockPalette;
import dev.xetius.xetiusmap.common.net.ByteReader;
import dev.xetius.xetiusmap.common.net.ByteWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps the colour table a client uploaded, so the server only has to ask once ever rather than
 * once per start.
 */
public final class PaletteStore {

    private final Logger logger;
    private final Path file;
    private volatile BlockPalette palette = BlockPalette.empty();

    public PaletteStore(Logger logger, Path file) {
        this.logger = logger;
        this.file = file;
    }

    public BlockPalette palette() {
        return palette;
    }

    public boolean isEmpty() {
        return palette.isEmpty();
    }

    public void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            palette = BlockPalette.read(new ByteReader(Files.readAllBytes(file)));
            logger.info("Loaded a colour palette of " + palette.size() + " blocks.");
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not read the colour palette; a client will be asked for a new one", e);
            palette = BlockPalette.empty();
        }
    }

    /** Replaces the stored palette. Called on the store thread. */
    public void accept(BlockPalette incoming) {
        if (incoming.isEmpty()) {
            return;
        }
        palette = incoming;
        try {
            ByteWriter w = new ByteWriter(1 << 16);
            incoming.write(w);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectories(file.getParent());
            Files.write(tmp, w.toByteArray());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Stored a colour palette of " + incoming.size() + " blocks; "
                    + "/xmap generate can now render undiscovered chunks.");
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save the colour palette", e);
        }
    }
}
