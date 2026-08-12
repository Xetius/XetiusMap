package dev.xetius.xetiusmap.client.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the part of the cave view that can be reasoned about without a world: when it flips.
 *
 * <p>The reading itself — sky light and depth at the player — needs a live {@code ClientLevel} and
 * is left to the game.
 */
class CaveViewTest {

    @Test
    void startsOnTheSurface() {
        CaveView view = new CaveView();
        assertFalse(view.active());
    }

    @Test
    void waitsBeforeFlipping() {
        CaveView view = new CaveView();
        for (int tick = 1; tick < CaveView.FLIP_DELAY_TICKS; tick++) {
            assertFalse(view.update(true), "flipped after only " + tick + " ticks underground");
            assertFalse(view.active());
        }
        assertTrue(view.update(true));
        assertTrue(view.active());
    }

    @Test
    void aTripPastACaveMouthDoesNotFlip() {
        CaveView view = new CaveView();
        for (int pass = 0; pass < 10; pass++) {
            for (int tick = 0; tick < CaveView.FLIP_DELAY_TICKS - 1; tick++) {
                assertFalse(view.update(true));
            }
            assertFalse(view.update(false));
        }
        assertFalse(view.active());
    }

    @Test
    void flipsBackOnceAboveGround() {
        CaveView view = new CaveView();
        for (int tick = 0; tick < CaveView.FLIP_DELAY_TICKS; tick++) {
            view.update(true);
        }
        assertTrue(view.active());

        for (int tick = 1; tick < CaveView.FLIP_DELAY_TICKS; tick++) {
            assertFalse(view.update(false));
            assertTrue(view.active());
        }
        assertTrue(view.update(false));
        assertFalse(view.active());
    }

    @Test
    void stayingPutNeverFlips() {
        CaveView view = new CaveView();
        for (int tick = 0; tick < CaveView.FLIP_DELAY_TICKS * 3; tick++) {
            assertFalse(view.update(false));
        }
        assertFalse(view.active());
    }

    @Test
    void arrivingInADimensionAdoptsTheViewAtOnce() {
        CaveView view = new CaveView();
        view.reset(true);
        assertTrue(view.active());

        // And with no half-counted ticks left over from before.
        assertFalse(view.update(false));
        view.reset(false);
        assertFalse(view.active());
        for (int tick = 1; tick < CaveView.FLIP_DELAY_TICKS; tick++) {
            assertFalse(view.update(true));
        }
        assertTrue(view.update(true));
    }
}
