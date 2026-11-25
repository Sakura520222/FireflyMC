package firefly520.fireflymc.ai.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

public record AiRequestPayload(int version, UUID requestId, UUID sessionId, AiProtocol.Action action,
                               String body) implements CustomPacketPayload {
    public static final Type<AiRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fireflymc", "ai4_request"));
    public static final StreamCodec<ByteBuf, AiRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeInt(value.version);
                buf.writeLong(value.requestId.getMostSignificantBits()).writeLong(value.requestId.getLeastSignificantBits());
                buf.writeLong(value.sessionId.getMostSignificantBits()).writeLong(value.sessionId.getLeastSignificantBits());
                buf.writeByte(value.action.ordinal());
                ByteBufCodecs.stringUtf8(AiProtocol.REQUEST_CHARS).encode(buf, value.body);
            },
            buf -> {
                int version = buf.readInt();
                UUID request = new UUID(buf.readLong(), buf.readLong());
                UUID session = new UUID(buf.readLong(), buf.readLong());
                int action = buf.readUnsignedByte();
                if (action >= AiProtocol.Action.values().length) throw new IllegalArgumentException("AI_ACTION");
                return new AiRequestPayload(version, request, session, AiProtocol.Action.values()[action],
                        ByteBufCodecs.stringUtf8(AiProtocol.REQUEST_CHARS).decode(buf));
            });
    public AiRequestPayload {
        if (version != AiProtocol.VERSION || requestId == null || sessionId == null || action == null)
            throw new IllegalArgumentException("AI_PROTOCOL_VERSION");
        AiProtocol.object(body, AiProtocol.REQUEST_CHARS);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
