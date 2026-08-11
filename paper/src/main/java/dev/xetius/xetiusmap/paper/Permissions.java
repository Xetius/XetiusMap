package dev.xetius.xetiusmap.paper;

/** Permission node names, kept in one place so plugin.yml and the code cannot drift apart. */
public final class Permissions {

    public static final String USE = "xetiusmap.use";
    public static final String UPLOAD = "xetiusmap.upload";
    public static final String WAYPOINT_CREATE = "xetiusmap.waypoint.create";
    public static final String WAYPOINT_EDIT_OWN = "xetiusmap.waypoint.edit.own";
    public static final String WAYPOINT_EDIT_OTHER = "xetiusmap.waypoint.edit.other";
    public static final String WAYPOINT_DELETE_OWN = "xetiusmap.waypoint.delete.own";
    public static final String WAYPOINT_DELETE_OTHER = "xetiusmap.waypoint.delete.other";

    /** Deliberately granted to nobody by default. */
    public static final String TELEPORT = "xetiusmap.teleport";

    /**
     * Teleport to any point picked off the map, rather than only to a curated waypoint. Strictly
     * more powerful, so it is a separate node and also granted to nobody by default.
     */
    public static final String TELEPORT_ANYWHERE = "xetiusmap.teleport.anywhere";

    /** Holders never appear on anybody else's map. */
    public static final String HIDDEN = "xetiusmap.hidden";

    public static final String ADMIN = "xetiusmap.admin";

    private Permissions() {
    }
}
