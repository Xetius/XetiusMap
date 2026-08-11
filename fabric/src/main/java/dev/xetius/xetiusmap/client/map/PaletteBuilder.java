package dev.xetius.xetiusmap.client.map;

import dev.xetius.xetiusmap.client.XetiusMap;
import dev.xetius.xetiusmap.common.model.BlockPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the colour table the server uses to render chunks nobody has walked through.
 *
 * <p>The server can read a block's material and biome but has no idea what colour either should be,
 * because all of that knowledge is client side. This walks the client's own registries and hands
 * over a flat table it can look things up in.
 *
 * <p>Which biome colour a tinted block takes is worked out by asking, not by a hardcoded list of
 * block names: the block's tint source is evaluated at the player's position and compared against
 * the grass, foliage and water colours of that same spot. Whichever it matches is the tint it uses.
 * That keeps modded blocks working and cannot drift as vanilla changes.
 */
public final class PaletteBuilder {

    private PaletteBuilder() {
    }

    /**
     * Snapshots the client's block and biome colours.
     *
     * <p>Client thread only — it reads live level state.
     */
    public static BlockPalette build(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return BlockPalette.empty();
        }

        BlockColors blockColors = minecraft.getBlockColors();
        BlockPos probe = minecraft.player.blockPosition();

        // The three biome colours at the probe position, used only to classify tint sources.
        int grassHere = safely(() -> BiomeColors.getAverageGrassColor(level, probe), -1);
        int foliageHere = safely(() -> BiomeColors.getAverageFoliageColor(level, probe), -1);
        int dryFoliageHere = safely(() -> BiomeColors.getAverageDryFoliageColor(level, probe), -1);
        int waterHere = safely(() -> BiomeColors.getAverageWaterColor(level, probe), -1);

        Map<String, BlockPalette.Entry> blocks = new HashMap<>(1024);
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) {
                continue;
            }
            BlockState state = block.defaultBlockState();

            MapColor mapColor = safely(() -> state.getMapColor(level, probe), MapColor.NONE);
            int rgb = mapColor == MapColor.NONE ? 0 : mapColor.col;

            byte tint = classify(state, blockColors, level, probe,
                    grassHere, foliageHere, dryFoliageHere, waterHere);
            if (rgb == 0 && tint == BlockPalette.TINT_NONE) {
                // Air and other invisible blocks: nothing to draw, so leave them out entirely.
                continue;
            }
            blocks.put(id.toString(), new BlockPalette.Entry(rgb, tint));
        }

        Map<String, BlockPalette.BiomeTint> biomes = new HashMap<>(256);
        Registry<Biome> biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        for (Biome biome : biomeRegistry) {
            Identifier id = biomeRegistry.getKey(biome);
            if (id == null) {
                continue;
            }
            biomes.put(id.toString(), new BlockPalette.BiomeTint(
                    safely(() -> biome.getGrassColor(probe.getX(), probe.getZ()), 0) & 0xFFFFFF,
                    safely(biome::getFoliageColor, 0) & 0xFFFFFF,
                    safely(biome::getWaterColor, 0) & 0xFFFFFF));
        }

        XetiusMap.LOGGER.info("Built a colour palette of {} blocks and {} biomes for the server.",
                blocks.size(), biomes.size());
        return new BlockPalette(blocks, biomes);
    }

    /**
     * Works out which biome colour, if any, a block follows, by evaluating its tint source here and
     * seeing which of the local biome colours it comes back as.
     */
    private static byte classify(BlockState state, BlockColors blockColors, ClientLevel level, BlockPos probe,
                                 int grass, int foliage, int dryFoliage, int water) {
        if (state.getFluidState().is(FluidTags.WATER)) {
            return BlockPalette.TINT_WATER;
        }

        List<BlockTintSource> tints = blockColors.getTintSources(state);
        if (tints.isEmpty()) {
            return BlockPalette.TINT_NONE;
        }

        int tinted = safely(() -> tints.getFirst().colorInWorld(state, level, probe), -1) & 0xFFFFFF;
        if (tinted == 0xFFFFFF || tinted == 0) {
            // The untinted sentinel: this source has no world colour of its own.
            return BlockPalette.TINT_NONE;
        }
        if (tinted == (grass & 0xFFFFFF)) {
            return BlockPalette.TINT_GRASS;
        }
        if (tinted == (foliage & 0xFFFFFF)) {
            return BlockPalette.TINT_FOLIAGE;
        }
        if (tinted == (dryFoliage & 0xFFFFFF)) {
            return BlockPalette.TINT_DRY_FOLIAGE;
        }
        if (tinted == (water & 0xFFFFFF)) {
            return BlockPalette.TINT_WATER;
        }
        // A tint of its own that follows no biome colour — redstone, for instance.
        return BlockPalette.TINT_NONE;
    }

    /** Registry sweeps touch every block in the game, including modded ones that may object. */
    private static <T> T safely(java.util.function.Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (RuntimeException | LinkageError e) {
            return fallback;
        }
    }
}
