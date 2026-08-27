package firefly520.fireflymc.music;

import firefly520.fireflymc.network.ModPayloadHandler;
import firefly520.fireflymc.network.MusicPlaybackFailedPayload;
import firefly520.fireflymc.network.MusicQueueSyncPayload;
import firefly520.fireflymc.network.MusicSearchResultPayload;
import firefly520.fireflymc.network.MusicStartPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 音乐模块与 MC 服务端的集成层：
 * - 持有每个 server 实例的 MusicQueueManager（广播经 PacketDistributor 发包）
 * - 维护 musicCapablePlayers ∩ 在线 的判定与计数（quorum 分母）
 */
public final class MusicServerBridge {

    private static final AtomicReference<MusicQueueManager> INSTANCE = new AtomicReference<>();
    private static volatile MinecraftServer server;

    public static void onServerStarted(MinecraftServer mcServer) {
        server = mcServer;
        MusicQueueManager manager = new MusicQueueManager(
                System::nanoTime,
                start -> PacketDistributor.sendToAllPlayers(start),
                stop -> PacketDistributor.sendToAllPlayers(stop),
                sync -> PacketDistributor.sendToAllPlayers(sync),
                new MusicQueueManager.CapabilityLookup() {
                    @Override
                    public boolean isCapable(UUID player) {
                        MinecraftServer s = server;
                        return s != null
                                && s.getPlayerList().getPlayer(player) != null
                                && ModPayloadHandler.MUSIC_CAPABLE_PLAYERS.containsKey(player);
                    }

                    @Override
                    public int capableOnlineCount() {
                        MinecraftServer s = server;
                        if (s == null) {
                            return 0;
                        }
                        int n = 0;
                        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                            if (ModPayloadHandler.MUSIC_CAPABLE_PLAYERS.containsKey(p.getUUID())) {
                                n++;
                            }
                        }
                        return n;
                    }
                },
                song -> {
                    // FAILED 终态：告知点歌者（可能为付费歌曲导致直链不可播）
                    MinecraftServer s = server;
                    if (s == null) {
                        return;
                    }
                    ServerPlayer requester = s.getPlayerList().getPlayer(song.requesterId());
                    if (requester != null) {
                        requester.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                                "fireflymc.music.error.playback_failed", song.title(), song.author()));
                    }
                });
        INSTANCE.set(manager);
    }

    public static void onServerStopping() {
        INSTANCE.set(null);
        server = null;
    }

    public static MusicQueueManager manager() {
        return INSTANCE.get();
    }

    /** 玩家登录：集成服务器（单人/LAN）直接记音乐能力；定向发送当前曲（中途加入）+ 队列快照 */
    public static void onPlayerLoggedIn(ServerPlayer player) {
        MusicQueueManager m = manager();
        if (m == null) {
            return;
        }
        MinecraftServer s = server;
        if (s != null && s.isSingleplayer()) {
            // 集成服务器不发握手协议：登录即记为音乐能力客户端。
            // 单人时 capableOnline=1 维持"唯一客户端失败立即 FAILED"语义；
            // LAN 时分母=在线人数，quorum 正常生效（否则恒为 0，任一客户端失败即全服切歌）
            ModPayloadHandler.MUSIC_CAPABLE_PLAYERS.put(player.getUUID(), true);
        }
        MusicStartPayload current = m.currentStartPayload();
        if (current != null) {
            PacketDistributor.sendToPlayer(player, current);
        }
        // 队列快照：队列此后无变化时不会再广播，漏发会让登录者的队列 HUD 一直为空
        MusicQueueSyncPayload sync = m.currentQueueSyncPayload();
        if (sync != null) {
            PacketDistributor.sendToPlayer(player, sync);
        }
    }

    /** 玩家登出：capability 移除（locked 保留，掉线不解锁） */
    public static void onPlayerLoggedOut(ServerPlayer player) {
        ModPayloadHandler.MUSIC_CAPABLE_PLAYERS.remove(player.getUUID());
        MusicQueueManager m = manager();
        if (m != null) {
            m.onPlayerLogout(player.getUUID());
        }
    }

    /** 服务端每 tick（ServerTickEvent 驱动） */
    public static void tick() {
        MusicQueueManager m = manager();
        if (m != null) {
            m.tick();
        }
    }

    /** 客户端失败上报入口（ModPayloadHandler 收包后调用） */
    public static void onClientFailure(ServerPlayer reporter, MusicPlaybackFailedPayload payload) {
        MusicQueueManager m = manager();
        if (m != null) {
            m.onClientFailure(reporter.getUUID(), payload.playbackId(), payload.failureCode());
        }
    }

    /**
     * 客户端代搜索回包入口（ModPayloadHandler 收包后调用，服务端线程）。
     * 回包字段是不可信输入：songId 校验、字段截断、时长 clamp 后才入队；
     * 会话按 sessionId 认领；requesterName 服务端自填，不信任客户端。
     */
    public static void onClientSearchResult(ServerPlayer player, MusicSearchResultPayload payload) {
        MusicQueueManager m = manager();
        if (m == null) {
            return;
        }
        MinecraftServer s = server;
        String songId = payload.songId();
        // 防伪造：只受理明确进入代搜索的会话，且回包 token 必须与签发值一致
        MusicQueueManager.SearchSession session = m.findPendingSession(player.getUUID(), payload.sessionId());
        if (session == null || !m.isProxyTokenValid(payload.sessionId(), payload.proxyToken())) {
            return; // 迟到/被 stop 清掉/从未委托过的会话或 token 不符：一律丢弃
        }
        if (!MusicApiClient.isValidSongId(songId)) {
            // 客户端未找到或同样无法访问外网 → 走正常"未找到"链路
            m.failRequest(player.getUUID(), session);
            if (!player.hasDisconnected()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "fireflymc.music.error.not_found", payload.keyword()));
            }
            return;
        }
        long durationMs = Math.max(1_000L, Math.min(payload.durationMs(), 1_800_000L));
        QueuedSong song = new QueuedSong(
                songId,
                truncate(payload.title(), MusicApiClient.MAX_TITLE),
                truncate(payload.author(), MusicApiClient.MAX_AUTHOR),
                truncate(payload.lrc(), MusicApiClient.MAX_LRC),
                player.getGameProfile().getName(),
                player.getUUID(),
                durationMs);
        boolean accepted = m.completeRequest(player.getUUID(), session, song);
        if (s != null) {
            firefly520.fireflymc.music.MusicCommandHandler.finishRequest(
                    player, s, m, session, accepted, song.title(), song.author());
        }
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }
}
