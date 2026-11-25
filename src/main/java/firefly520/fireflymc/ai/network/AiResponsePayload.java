package firefly520.fireflymc.ai.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** The JSON contains display data only. Client code never treats it as executable actions. */
public record AiResponsePayload(int version, UUID requestId, UUID sessionId, String kind, String body)
        implements CustomPacketPayload {
    public static final Type<AiResponsePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fireflymc", "ai4_response"));
    public static final StreamCodec<ByteBuf, AiResponsePayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeInt(value.version);
                buf.writeLong(value.requestId.getMostSignificantBits()).writeLong(value.requestId.getLeastSignificantBits());
                buf.writeLong(value.sessionId.getMostSignificantBits()).writeLong(value.sessionId.getLeastSignificantBits());
                ByteBufCodecs.stringUtf8(32).encode(buf, value.kind);
                ByteBufCodecs.stringUtf8(AiProtocol.RESPONSE_CHARS).encode(buf, value.body);
            }, buf -> new AiResponsePayload(buf.readInt(), new UUID(buf.readLong(), buf.readLong()),
                    new UUID(buf.readLong(), buf.readLong()), ByteBufCodecs.stringUtf8(32).decode(buf),
                    ByteBufCodecs.stringUtf8(AiProtocol.RESPONSE_CHARS).decode(buf)));
    public AiResponsePayload {
        if (version != AiProtocol.VERSION || requestId == null || sessionId == null || kind == null
                || kind.length() > 32 || body == null || body.length() > AiProtocol.RESPONSE_CHARS)
            throw new IllegalArgumentException("AI_INVALID_RESPONSE");
        firefly520.fireflymc.ai.storage.AiJson.object(body, AiProtocol.RESPONSE_CHARS, 24);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
