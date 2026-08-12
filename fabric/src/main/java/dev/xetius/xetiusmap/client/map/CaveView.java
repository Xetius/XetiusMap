package dev.xetius.xetiusmap.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Decides when the map draws caves instead of the surface.
 *
 * <p>The cave view scans downward from the player's eye, which is the only way to see anything at
 * all under a mountain. Used anywhere but underground it is actively wrong: stand halfway up a
 * mountainside and every column whose ground rises above your eye is scanned from inside the rock,
 * so the map fills with the cave floors beneath the peaks. Hence this: the cave view is engaged
 * only while the player really is enclosed, and the surface is drawn the rest of the time.
 *
 * <p>"Enclosed" is judged by sky light, matching how the radar tells a creature out on the land from
 * one in a cave — light spills in through openings, so it reads a cave mouth the way a player would,
 * where a heightmap comparison would call anyone under a tree underground.
 */
public final class CaveView {

    /**
     * Sky light reaches nowhere indoors either, so depth is what separates a cave from a room: a
     * player this far below the top of their own column is under the ground rather than under a
     * roof.
     */
    static final int DEPTH_MARGIN = 6;

    /**
     * How long a change has to hold before the view flips. Walking in and out of a cave mouth would
     * otherwise redraw every loaded chunk twice a second.
     */
    static final int FLIP_DELAY_TICKS = 20;

    private boolean active;
    private int candidateTicks;

    /** Whether the cave view is currently in force. */
    public boolean active() {
        return active;
    }

    /**
     * Feeds in this tick's reading.
     *
     * @return true if the view has just flipped, which means every tile on screen was drawn for the
     *         other one and is now wrong
     */
    public boolean update(boolean underground) {
        if (underground == active) {
            candidateTicks = 0;
            return false;
        }
        if (++candidateTicks < FLIP_DELAY_TICKS) {
            return false;
        }
        active = underground;
        candidateTicks = 0;
        return true;
    }

    /** Adopts a reading outright, with no delay and no flip: for arriving in a new dimension. */
    public void reset(boolean underground) {
        active = underground;
        candidateTicks = 0;
    }

    /**
     * Whether the player is under the ground as opposed to merely below the skyline.
     *
     * <p>Swimming is excluded deliberately. Water dims sky light by a level a block, so anyone diving
     * a deep ocean is in the dark by the sea floor, and flipping the map to a cave view down there
     * would throw away the surface it had just drawn.
     */
    public static boolean isUnderground(ClientLevel level, Player player) {
        if (player.isUnderWater()) {
            return false;
        }
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        if (level.getBrightness(LightLayer.SKY, eye) > 0) {
            return false;
        }
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, eye.getX(), eye.getZ()) - 1;
        return eye.getY() <= top - DEPTH_MARGIN;
    }
}
