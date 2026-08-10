package dev.xetius.xetiusmap.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import org.joml.Matrix3x2fStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Picture markers: real player faces, and a per-species icon for mobs.
 *
 * <p>Player faces come from the tab-list entry, which the server maintains for everyone online
 * regardless of which dimension they are in — exactly the set the map draws — and are rendered with
 * vanilla's own {@link PlayerFaceExtractor}, so the hat layer and skin model are handled for us.
 *
 * <p>Mobs use their spawn egg. Minecraft has no general per-species head texture: heads exist as
 * items for only a handful of mobs, and entity textures lay out their faces differently for every
 * model, so there is nothing to crop uniformly. The spawn egg is the one icon the game defines for
 * every species, and its two-tone colouring makes them easy to tell apart at a glance.
 */
public final class MarkerIcons {

    /** Item stacks are immutable here and cheap to keep; misses are cached as empty. */
    private static final Map<String, ItemStack> MOB_ICONS = new HashMap<>();

    /** Vanilla renders items at a fixed 16x16, so anything else is a scale of that. */
    private static final float ITEM_PIXELS = 16.0F;

    private MarkerIcons() {
    }

    public static void playerHead(GuiGraphicsExtractor graphics, UUID uuid, float centreX, float centreY, int size) {
        PlayerSkin skin = skinFor(uuid);
        int x = Math.round(centreX - size / 2.0F);
        int y = Math.round(centreY - size / 2.0F);
        // A dark border keeps pale skins legible against snow and sand.
        graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF101010);
        PlayerFaceExtractor.extractRenderState(graphics, skin, x, y, size);
    }

    private static PlayerSkin skinFor(UUID uuid) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(uuid);
            if (info != null) {
                return info.getSkin();
            }
        }
        // Not in the tab list (or single player): the UUID still picks a stable default skin.
        return DefaultPlayerSkin.get(uuid);
    }

    /**
     * Draws the icon for an entity type.
     *
     * @return false when the species has no spawn egg, so the caller can fall back to a dot
     */
    public static boolean mobIcon(GuiGraphicsExtractor graphics, String typeId,
                                  float centreX, float centreY, int size) {
        ItemStack stack = MOB_ICONS.computeIfAbsent(typeId, MarkerIcons::lookupIcon);
        if (stack.isEmpty()) {
            return false;
        }

        float scale = size / ITEM_PIXELS;
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(centreX - size / 2.0F, centreY - size / 2.0F);
        pose.scale(scale);
        graphics.item(stack, 0, 0);
        pose.popMatrix();
        return true;
    }

    private static ItemStack lookupIcon(String typeId) {
        Identifier id = Identifier.tryParse(typeId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                .flatMap(SpawnEggItem::byId)
                .map(holder -> new ItemStack(holder.value()))
                .orElse(ItemStack.EMPTY);
    }

    /** The localised name of an entity type, falling back to the raw id for unknown species. */
    public static net.minecraft.network.chat.Component speciesName(String typeId) {
        Identifier id = Identifier.tryParse(typeId);
        if (id != null) {
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (type.isPresent()) {
                return type.get().getDescription();
            }
        }
        return net.minecraft.network.chat.Component.literal(typeId);
    }

    /** Dropped between worlds so a resource reload cannot leave stale stacks behind. */
    public static void clearCache() {
        MOB_ICONS.clear();
    }
}
