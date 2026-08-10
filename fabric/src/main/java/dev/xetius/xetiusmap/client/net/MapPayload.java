package dev.xetius.xetiusmap.client.net;

import dev.xetius.xetiusmap.common.net.Protocol;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The single custom payload the mod uses, carrying one already-framed packet.
 *
 * <p>The body is written as raw bytes with no length prefix of its own. That matters: on the server
 * this arrives through Bukkit's plugin-messaging API, which hands the plugin exactly the bytes that
 * follow the channel id. Anything extra here would appear as corruption there.
 */
public record MapPayload(byte[] data) implements CustomPacketPayload {

    public static final Identifier CHANNEL_ID =
            Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, Protocol.CHANNEL_PATH);

    public static final CustomPacketPayload.Type<MapPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL_ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MapPayload> CODEC =
            StreamCodec.of(MapPayload::encode, MapPayload::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(ByteBuf buf, MapPayload payload) {
        buf.writeBytes(payload.data());
    }

    private static MapPayload decode(ByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return new MapPayload(data);
    }
}
