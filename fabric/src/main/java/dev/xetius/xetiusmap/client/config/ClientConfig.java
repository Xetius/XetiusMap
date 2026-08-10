package dev.xetius.xetiusmap.client.config;

/**
 * Everything the player can tune, persisted as JSON. Mutable and read directly by the renderers —
 * it is only ever touched on the client thread.
 */
public final class ClientConfig {

    /** Where the minimap sits relative to the game window. */
    public enum Anchor {
        TOP_LEFT,
        TOP_CENTER,
        TOP_RIGHT,
        MIDDLE_LEFT,
        MIDDLE_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_CENTER,
        BOTTOM_RIGHT
    }

    public enum Shape {
        SQUARE,
        CIRCLE
    }

    /** How the terrain colour for a block is chosen. */
    public enum ColorStyle {
        /** Vanilla map colours, tinted by biome — closest to how the world actually looks. */
        TINTED,
        /** Plain vanilla map colours, exactly as an in-game map draws them. */
        VANILLA_MAP
    }

    // --- Minimap ---
    public boolean minimapEnabled = true;
    public Anchor minimapAnchor = Anchor.TOP_RIGHT;
    public int minimapOffsetX = 8;
    public int minimapOffsetY = 8;
    public int minimapSize = 128;
    public Shape minimapShape = Shape.SQUARE;
    public boolean minimapRotate = true;
    public float minimapOpacity = 1.0F;
    public int minimapZoom = 2;
    public boolean minimapFrame = true;
    public boolean showCoordinates = true;
    public boolean showBiome = false;
    public boolean showDirections = true;

    // --- Markers ---
    public boolean showPlayers = true;
    public boolean showMobs = true;
    public boolean showWaypoints = true;
    public boolean showPlayerNames = true;

    /** Arrows pinned to the minimap edge for markers that have scrolled out of view. */
    public boolean edgeIndicatorWaypoints = true;
    public boolean edgeIndicatorPlayers = true;

    // --- In-world waypoint markers ---
    public boolean showWaypointsInWorld = true;
    public boolean showWaypointDistance = true;
    /** Hide in-world markers beyond this many blocks. 0 means no limit. */
    public int worldWaypointMaxDistance = 0;
    /** Below this distance the marker fades out, so it stops covering what you are looking at. */
    public int worldWaypointFadeNear = 8;

    // --- World map ---
    public int worldMapZoom = 3;

    // --- Rendering ---
    public ColorStyle colorStyle = ColorStyle.TINTED;
    public boolean caveMode = false;

    // --- Sharing ---
    public boolean uploadEnabled = true;
    public boolean hiddenFromOthers = false;

    /** Clamps anything a hand-edited config file might have got wrong. */
    public void validate() {
        minimapSize = clamp(minimapSize, 48, 512);
        minimapOffsetX = clamp(minimapOffsetX, 0, 4096);
        minimapOffsetY = clamp(minimapOffsetY, 0, 4096);
        minimapOpacity = Math.max(0.1F, Math.min(1.0F, minimapOpacity));
        minimapZoom = clamp(minimapZoom, 0, Zoom.LEVELS.length - 1);
        worldMapZoom = clamp(worldMapZoom, 0, Zoom.LEVELS.length - 1);
        worldWaypointMaxDistance = clamp(worldWaypointMaxDistance, 0, 1_000_000);
        worldWaypointFadeNear = clamp(worldWaypointFadeNear, 0, 512);
        if (minimapAnchor == null) {
            minimapAnchor = Anchor.TOP_RIGHT;
        }
        if (minimapShape == null) {
            minimapShape = Shape.SQUARE;
        }
        if (colorStyle == null) {
            colorStyle = ColorStyle.TINTED;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Zoom expressed as screen pixels per world block. Values below 1 show more world at lower
     * detail; values above 1 magnify.
     */
    public static final class Zoom {

        public static final float[] LEVELS = {0.125F, 0.25F, 0.5F, 1.0F, 2.0F, 4.0F, 8.0F};

        private Zoom() {
        }

        public static float scale(int index) {
            return LEVELS[Math.max(0, Math.min(LEVELS.length - 1, index))];
        }

        public static int in(int index) {
            return Math.min(LEVELS.length - 1, index + 1);
        }

        public static int out(int index) {
            return Math.max(0, index - 1);
        }

        public static String label(int index) {
            float scale = scale(index);
            return scale >= 1.0F
                    ? String.format("%.0fx", scale)
                    : String.format("1/%.0fx", 1.0F / scale);
        }
    }
}
