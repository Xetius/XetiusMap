package dev.xetius.xetiusmap.common.model;

import java.util.List;

/**
 * Icon keys shared by client and server. Kept as plain strings on the wire so a newer client can
 * introduce icons an older server has never heard of without the server rejecting the waypoint.
 */
public final class WaypointIcon {

    public static final String DEFAULT = "marker";

    /** The set the bundled client can draw; anything else falls back to {@link #DEFAULT}. */
    public static final List<String> BUILT_IN = List.of(
            "marker",
            "home",
            "spawn",
            "base",
            "farm",
            "mine",
            "portal",
            "village",
            "shop",
            "danger",
            "star",
            "flag"
    );

    private WaypointIcon() {
    }

    public static String normalise(String icon) {
        return icon != null && BUILT_IN.contains(icon) ? icon : DEFAULT;
    }
}
