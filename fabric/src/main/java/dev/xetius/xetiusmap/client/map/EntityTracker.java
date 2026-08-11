package dev.xetius.xetiusmap.client.map;

import dev.xetius.xetiusmap.common.model.Markers;
import dev.xetius.xetiusmap.common.net.S2C;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Live markers to draw.
 *
 * <p>When a server plugin is present its updates are authoritative — that is the only way to see
 * players in other dimensions or mobs beyond render distance. Without one, the same markers are
 * derived from the entities the client already knows about, so the minimap still shows what is
 * nearby in single player.
 */
public final class EntityTracker {

    private volatile S2C.EntityUpdate latest =
            new S2C.EntityUpdate("", List.of(), List.of(), List.of());
    private volatile long lastServerUpdateMillis;

    public void accept(S2C.EntityUpdate update) {
        this.latest = update;
        this.lastServerUpdateMillis = System.currentTimeMillis();
    }

    public void clear() {
        latest = new S2C.EntityUpdate("", List.of(), List.of(), List.of());
        lastServerUpdateMillis = 0;
    }

    public List<Markers.PlayerMarker> players() {
        return latest.players();
    }

    public List<Markers.MobMarker> mobs() {
        return latest.mobs();
    }

    public List<String> typePalette() {
        return latest.typePalette();
    }

    public String dimension() {
        return latest.dimension();
    }

    /** True when server updates have stopped arriving, so the local fallback should take over. */
    public boolean stale() {
        return System.currentTimeMillis() - lastServerUpdateMillis > 5000L;
    }

    /**
     * Builds markers from the client's own entity list. Used in local mode, and as a stopgap if the
     * server stops sending updates.
     */
    public void refreshFromClient(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        String dimension = minecraft.level.dimension().identifier().toString();

        List<Markers.PlayerMarker> players = new ArrayList<>();
        List<Markers.MobMarker> mobs = new ArrayList<>();
        List<String> palette = new ArrayList<>();

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof Player player) {
                players.add(new Markers.PlayerMarker(
                        player.getUUID(),
                        player.getName().getString(),
                        dimension,
                        player.getBlockX(),
                        player.getBlockY(),
                        player.getBlockZ(),
                        player.getYRot()));
                continue;
            }
            if (!(entity instanceof LivingEntity) || mobs.size() >= 512) {
                continue;
            }
            String type = net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString();
            int index = palette.indexOf(type);
            if (index < 0) {
                palette.add(type);
                index = palette.size() - 1;
            }
            // Without a server to ask, the heightmap is the closest available stand-in for
            // "is this creature out in the open".
            boolean skyVisible = minecraft.level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    entity.getBlockX(), entity.getBlockZ()) <= entity.getBlockY();
            mobs.add(new Markers.MobMarker(
                    index,
                    categorise(entity),
                    entity.getBlockX(),
                    entity.getBlockY(),
                    entity.getBlockZ(),
                    entity.getYRot(),
                    skyVisible));
        }

        latest = new S2C.EntityUpdate(dimension, palette, players, mobs);
    }

    private static byte categorise(Entity entity) {
        if (entity instanceof TamableAnimal tamable && tamable.isTame()) {
            return Markers.MobCategory.TAMED;
        }
        if (entity instanceof Enemy) {
            return Markers.MobCategory.HOSTILE;
        }
        if (entity instanceof Animal) {
            return Markers.MobCategory.PASSIVE;
        }
        return Markers.MobCategory.OTHER;
    }

    /** Colour for a marker dot, chosen from the coarse category the server assigned. */
    public static int colorFor(byte category) {
        return switch (category) {
            case Markers.MobCategory.HOSTILE -> 0xFFE04030;
            case Markers.MobCategory.PASSIVE -> 0xFF60C060;
            case Markers.MobCategory.NEUTRAL -> 0xFFD8B040;
            case Markers.MobCategory.WATER -> 0xFF40A0E0;
            case Markers.MobCategory.TAMED -> 0xFFE0A0E0;
            case Markers.MobCategory.BOSS -> 0xFFB030C0;
            default -> 0xFFB0B0B0;
        };
    }
}
