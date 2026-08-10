package dev.xetius.xetiusmap.client.net;

import dev.xetius.xetiusmap.client.XetiusMap;
import dev.xetius.xetiusmap.common.net.Framing;
import dev.xetius.xetiusmap.common.net.Packet;
import dev.xetius.xetiusmap.common.net.ProtocolException;
import dev.xetius.xetiusmap.common.net.S2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The client end of the {@code xetiusmap:main} channel.
 *
 * <p>Fabric delivers payloads on the client thread, so decoded packets can be handed straight to
 * the map without any further hand-off.
 */
public final class ClientNetwork {

    private static final Framing.Reassembler REASSEMBLER = new Framing.Reassembler();
    private static final AtomicInteger STREAM_IDS = new AtomicInteger();

    private static Consumer<S2C> handler = packet -> {
    };

    private ClientNetwork() {
    }

    /** Called once during mod initialisation, before any connection exists. */
    public static void registerPayloadTypes() {
        PayloadTypeRegistry.serverboundPlay().register(MapPayload.TYPE, MapPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayload.TYPE, MapPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(MapPayload.TYPE, (payload, context) -> {
            try {
                byte[] packet = REASSEMBLER.accept(payload.data());
                if (packet != null) {
                    handler.accept(S2C.decode(packet));
                }
            } catch (ProtocolException e) {
                REASSEMBLER.reset();
                XetiusMap.LOGGER.warn("Discarding a malformed XetiusMap packet: {}", e.getMessage());
            } catch (RuntimeException e) {
                XetiusMap.LOGGER.warn("Error handling a XetiusMap packet", e);
            }
        });
    }

    public static void setHandler(Consumer<S2C> packetHandler) {
        handler = packetHandler;
    }

    /** Forgets any half-received packet. Called on connect and disconnect. */
    public static void reset() {
        REASSEMBLER.reset();
    }

    /** Whether the server has declared it listens on our channel. */
    public static boolean canSend() {
        try {
            return ClientPlayNetworking.canSend(MapPayload.TYPE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Sends a packet, fragmenting it if necessary.
     *
     * @return false if the server is not listening, in which case nothing was sent
     */
    public static boolean send(Packet packet) {
        if (!canSend()) {
            return false;
        }
        try {
            List<byte[]> frames = Framing.split(packet.encode(), STREAM_IDS);
            for (byte[] frame : frames) {
                ClientPlayNetworking.send(new MapPayload(frame));
            }
            return true;
        } catch (RuntimeException e) {
            XetiusMap.LOGGER.warn("Could not send a XetiusMap packet: {}", e.toString());
            return false;
        }
    }
}
