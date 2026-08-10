package dev.xetius.xetiusmap.common.net;

/** Constants both ends must agree on. */
public final class Protocol {

    public static final String NAMESPACE = "xetiusmap";
    public static final String CHANNEL_PATH = "main";

    /** The plugin-messaging channel / custom-payload id. */
    public static final String CHANNEL = NAMESPACE + ":" + CHANNEL_PATH;

    /**
     * Bumped whenever the wire format changes incompatibly. The server refuses a client whose
     * version differs and tells it why, rather than letting it fail in confusing ways later.
     */
    public static final int VERSION = 1;

    /**
     * Bukkit caps a plugin message at {@code Messenger.MAX_MESSAGE_SIZE} (32766 bytes). Fragments
     * are kept comfortably under it to leave room for the framing header.
     */
    public static final int MAX_FRAME_PAYLOAD = 30_000;

    /** A single logical packet may not exceed this once reassembled. */
    public static final int MAX_PACKET_BYTES = 4 * 1024 * 1024;

    /** Upper bound on a single encoded tile blob; a real one is a few hundred bytes. */
    public static final int MAX_TILE_BLOB_BYTES = 64 * 1024;

    /** Caps on list sizes accepted from a client, to bound the work a single packet can cause. */
    public static final int MAX_TILE_REQUESTS_PER_PACKET = 512;
    public static final int MAX_REGION_SUBSCRIPTIONS = 64;

    private Protocol() {
    }
}
