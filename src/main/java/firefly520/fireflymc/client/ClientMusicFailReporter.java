package firefly520.fireflymc.client;

import firefly520.fireflymc.client.music.MusicPlayer;
import firefly520.fireflymc.network.MusicPlaybackFailedPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/** 客户端→服务端播放失败上报（只在主线程调用） */
public final class ClientMusicFailReporter {
    private ClientMusicFailReporter() {}

    public static void report(long playbackId, MusicPlayer.LocalFailure code) {
        MusicPlaybackFailedPayload.FailureCode failureCode = switch (code) {
            case HTTP_FAILED -> MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED;
            case STREAM_INTERRUPTED -> MusicPlaybackFailedPayload.FailureCode.STREAM_INTERRUPTED;
            case MP3_DECODE_FAILED -> MusicPlaybackFailedPayload.FailureCode.MP3_DECODE_FAILED;
            case NONE -> null;
        };
        if (failureCode != null) {
            PacketDistributor.sendToServer(new MusicPlaybackFailedPayload(playbackId, failureCode));
        }
    }
}
