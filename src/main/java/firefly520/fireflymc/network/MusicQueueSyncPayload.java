package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * 队列状态同步：当前曲概要 + 排队列表（不含 url/lrc）
 */
public record MusicQueueSyncPayload(SongSummary current, List<SongSummary> queue) implements CustomPacketPayload {

    public record SongSummary(String title, String author, String requesterName) {
        public static final StreamCodec<ByteBuf, SongSummary> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SongSummary::title,
                ByteBufCodecs.STRING_UTF8, SongSummary::author,
                ByteBufCodecs.STRING_UTF8, SongSummary::requesterName,
                SongSummary::new
        );
    }

    public static final CustomPacketPayload.Type<MusicQueueSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "music_queue_sync"));

    public static final StreamCodec<ByteBuf, MusicQueueSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(SongSummary.STREAM_CODEC), p -> Optional.ofNullable(p.current()),
            SongSummary.STREAM_CODEC.apply(ByteBufCodecs.list(256)), MusicQueueSyncPayload::queue,
            (currentOpt, queue) -> new MusicQueueSyncPayload(currentOpt.orElse(null), queue)
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
