package firefly520.fireflymc.client.music;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.music.MusicApiClient;
import firefly520.fireflymc.network.MusicProxySearchRequestPayload;
import firefly520.fireflymc.network.MusicSearchResultPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端代搜索：服务端无法访问外网时，点歌者的客户端代为执行 搜索+时长探测，
 * 结果回传服务端入队（客户端本来就要访问网易 CDN 下载音频，具备该网络前提）。
 * 发送动作切回客户端主线程（与全项目"发包主线程强制化"规则一致；
 * 断线瞬间虚拟线程里的 sendToServer 存在与连接状态销毁重建的竞争风险）。
 */
public final class MusicProxySearchClient {

    private MusicProxySearchClient() {}

    /** 收到 S→C 代理请求（主线程）：起虚拟线程执行 IO，结果回主线程 sendToServer */
    public static void handle(MusicProxySearchRequestPayload payload) {
        final long sessionId = payload.sessionId();
        final long proxyToken = payload.proxyToken();
        final String keyword = payload.keyword();
        Thread.ofVirtual().name("fireflymc-music-proxy-search").start(() -> {
            FireflyMCMod.LOGGER.info("[Music] 服务端外网不可达，客户端代搜索: {}", keyword);
            MusicApiClient.SongInfo info = null;
            long durationMs = 0L;
            try {
                info = MusicApiClient.search(keyword).join();
                if (info != null) {
                    durationMs = MusicApiClient.probeDurationMs(info.songId()).join();
                }
            } catch (Exception e) {
                // 搜索/探测失败都按"未找到"回传（info==null → songId 为空）
                FireflyMCMod.LOGGER.warn("[Music] 代搜索失败: {}", String.valueOf(e));
                info = null;
            }
            MusicApiClient.SongInfo fInfo = info;
            long fDuration = durationMs;
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().getConnection() == null) {
                    return; // 已断线：服务器侧由 pending 硬超时兜底释放
                }
                PacketDistributor.sendToServer(new MusicSearchResultPayload(
                        sessionId,
                        proxyToken,
                        fInfo == null ? "" : fInfo.songId(),
                        fInfo == null ? "" : fInfo.title(),
                        fInfo == null ? "" : fInfo.author(),
                        fInfo == null ? "" : fInfo.lrc(),
                        fDuration,
                        keyword));
            });
        });
    }
}
