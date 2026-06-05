package firefly520.fireflymc.network;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import firefly520.fireflymc.FireflyMCMod;

import java.util.Collections;
import java.util.Map;

/**
 * 服务端发送给客户端的称号同步包
 * <p>
 * 全量同步所有玩家的称号数据（UUID字符串 → 称号文本）
 */
public record TitleSyncPayload(Map<String, String> titles) implements CustomPacketPayload {

    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    public static final CustomPacketPayload.Type<TitleSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "title_sync"));

    /**
     * StreamCodec：将 Map 序列化为 JSON 字符串传输
     */
    public static final StreamCodec<ByteBuf, TitleSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    payload -> GSON.toJson(payload.titles),
                    TitleSyncPayload::fromJson
            );

    private static TitleSyncPayload fromJson(String json) {
        try {
            Map<String, String> map = GSON.fromJson(json, MAP_TYPE);
            return new TitleSyncPayload(map != null ? map : Collections.emptyMap());
        } catch (Exception e) {
            return new TitleSyncPayload(Collections.emptyMap());
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
