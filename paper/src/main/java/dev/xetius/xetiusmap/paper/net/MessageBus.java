package dev.xetius.xetiusmap.paper.net;

import dev.xetius.xetiusmap.common.net.C2S;
import dev.xetius.xetiusmap.common.net.Framing;
import dev.xetius.xetiusmap.common.net.Packet;
import dev.xetius.xetiusmap.common.net.Protocol;
import dev.xetius.xetiusmap.common.net.ProtocolException;
import dev.xetius.xetiusmap.paper.PluginConfig;
import dev.xetius.xetiusmap.paper.session.PlayerSession;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The single plugin-messaging channel, in both directions.
 *
 * <p>Inbound frames arrive on the main thread and are reassembled per player before being decoded
 * and handed to the dispatcher. Outbound packets are queued from whichever thread produced them and
 * drained on the main thread once per tick with a per-player cap, which both keeps Bukkit happy and
 * stops a burst of tile data from swamping a client's connection.
 */
public final class MessageBus implements PluginMessageListener {

    private final Plugin plugin;
    private final Supplier<PluginConfig> config;
    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    private PacketHandler handler = (player, session, packet) -> {
    };

    public MessageBus(Plugin plugin, Supplier<PluginConfig> config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void setHandler(PacketHandler handler) {
        this.handler = handler;
    }

    public void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, Protocol.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, Protocol.CHANNEL, this);
    }

    public void unregister() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, Protocol.CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, Protocol.CHANNEL);
        sessions.clear();
    }

    public PlayerSession openSession(Player player) {
        PlayerSession session = new PlayerSession(player.getUniqueId(), player.getName(), config.get());
        sessions.put(player.getUniqueId(), session);
        return session;
    }

    public void closeSession(UUID playerId) {
        sessions.remove(playerId);
    }

    public PlayerSession session(UUID playerId) {
        return sessions.get(playerId);
    }

    public Collection<PlayerSession> sessions() {
        return sessions.values();
    }

    /** Sessions that have completed the handshake and are therefore safe to send map data to. */
    public List<PlayerSession> activeSessions() {
        return sessions.values().stream().filter(PlayerSession::handshakeComplete).toList();
    }

    public void applyConfig(PluginConfig updated) {
        for (PlayerSession session : sessions.values()) {
            session.applyConfig(updated);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Protocol.CHANNEL.equals(channel)) {
            return;
        }
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        try {
            byte[] packet = session.reassembler().accept(message);
            if (packet == null) {
                return;
            }
            C2S decoded = C2S.decode(packet);
            handler.handle(player, session, decoded);
        } catch (ProtocolException e) {
            // A malformed frame desynchronises the stream, so start the peer's reassembly afresh
            // rather than trying to interpret whatever follows.
            session.reassembler().reset();
            plugin.getLogger().log(Level.FINE,
                    () -> "Discarding malformed XetiusMap packet from " + player.getName() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, e,
                    () -> "Error handling a XetiusMap packet from " + player.getName());
        }
    }

    /** Queues a packet. Safe to call from any thread; delivery happens on the next tick. */
    public void send(PlayerSession session, Packet packet) {
        if (session == null) {
            return;
        }
        try {
            session.outgoing().addAll(Framing.split(packet.encode(), session.streamIds()));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, e,
                    () -> "Could not encode a XetiusMap packet for " + session.playerName());
        }
    }

    public void sendAll(Packet packet) {
        for (PlayerSession session : activeSessions()) {
            send(session, packet);
        }
    }

    /** Main thread, once per tick. */
    public void flush() {
        int budget = config.get().maxFramesPerPlayerPerTick();
        for (Map.Entry<UUID, PlayerSession> entry : sessions.entrySet()) {
            PlayerSession session = entry.getValue();
            if (session.outgoing().isEmpty()) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                session.outgoing().clear();
                continue;
            }
            for (int sent = 0; sent < budget; sent++) {
                byte[] frame = session.outgoing().poll();
                if (frame == null) {
                    break;
                }
                try {
                    player.sendPluginMessage(plugin, Protocol.CHANNEL, frame);
                    session.bytesSent().addAndGet(frame.length);
                } catch (RuntimeException e) {
                    // The most likely cause is the client disconnecting mid-flush.
                    session.framesDropped().incrementAndGet();
                    session.outgoing().clear();
                    break;
                }
            }
        }
    }

    /** Where decoded client packets go. */
    @FunctionalInterface
    public interface PacketHandler {
        void handle(Player player, PlayerSession session, C2S packet);
    }
}
