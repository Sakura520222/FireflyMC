package firefly520.fireflymc;

import firefly520.fireflymc.auth.PlayerPasswordManager;
import firefly520.fireflymc.playtime.PlaytimeManager;
import firefly520.fireflymc.network.ModHandshakePayload;
import firefly520.fireflymc.network.ModPayloadHandler;
import firefly520.fireflymc.network.ShowRulesPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ModEventHandler {

    private static final ScheduledExecutorService TIMEOUT_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FireflyMC-Invulnerability-Timeout");
        t.setDaemon(true);
        return t;
    });

    private static final Map<UUID, ScheduledFuture<?>> TIMEOUT_TASKS = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> VERIFY_TASKS = new ConcurrentHashMap<>();

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            UUID playerUuid = serverPlayer.getUUID();
            MinecraftServer server = serverPlayer.server;

            if (server.isSingleplayer()) {
                return;
            }

            ModPayloadHandler.VERIFIED_PLAYERS.remove(playerUuid);
            ModPayloadHandler.CONFIRMED_PLAYERS.remove(playerUuid);
            ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.remove(playerUuid);

            // 发送握手检测包
            PacketDistributor.sendToPlayer(serverPlayer, new ModHandshakePayload());

            // 首次加入判断：上方刚 remove 过，所以首次加入始终为 true
            boolean isFirstJoin = true;

            // 发送显示准则弹窗包
            PacketDistributor.sendToPlayer(serverPlayer, new ShowRulesPayload(isFirstJoin));

            // 设置玩家无敌（密码验证和规则确认都通过后取消）
            serverPlayer.setInvulnerable(true);

            // 开始密码验证流程（多人服务器启用时）
            if (ServerConfig.SERVER.playerAuthEnabled.get()) {
                PlayerPasswordManager.getInstance().startVerification(serverPlayer);
            }

            // 超时保护：60秒后强制取消无敌状态
            ScheduledFuture<?> timeoutTask = TIMEOUT_EXECUTOR.schedule(() -> {
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
                    if (player != null && !ModPayloadHandler.CONFIRMED_PLAYERS.getOrDefault(playerUuid, false)) {
                        player.setInvulnerable(false);
                    }
                    TIMEOUT_TASKS.remove(playerUuid);
                });
            }, 60, TimeUnit.SECONDS);
            TIMEOUT_TASKS.put(playerUuid, timeoutTask);

            // 5秒后检查 Mod 握手验证状态
            ScheduledFuture<?> verifyTask = TIMEOUT_EXECUTOR.schedule(() -> {
                server.execute(() -> {
                    if (!ModPayloadHandler.VERIFIED_PLAYERS.getOrDefault(playerUuid, false)) {
                        ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
                        if (player != null) {
                            player.connection.disconnect(Component.literal(
                                "§c你未安装FireflyMC模组，无法进入本服务器！\n" +
                                "请安装FireflyMC " + FireflyMCMod.VERSION + " 版本后重试。\n" +
                                "§e下载地址: https://mc.firefly520.top"
                            ));
                        }
                    }
                    VERIFY_TASKS.remove(playerUuid);
                });
            }, 5, TimeUnit.SECONDS);
            VERIFY_TASKS.put(playerUuid, verifyTask);

            // 通知在线时长管理器
            PlaytimeManager.getInstance().onPlayerLogin(serverPlayer);
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            UUID playerUuid = serverPlayer.getUUID();
            if (serverPlayer.server.isSingleplayer()) {
                return;
            }

            ModPayloadHandler.VERIFIED_PLAYERS.remove(playerUuid);
            ModPayloadHandler.CONFIRMED_PLAYERS.remove(playerUuid);
            ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.remove(playerUuid);
            // 清理超时任务
            cancelInvulnerabilityTimeout(playerUuid);
            cancelVerifyTimeout(playerUuid);
            // 清理密码验证会话
            PlayerPasswordManager.getInstance().cleanupPlayer(playerUuid);
            // 通知在线时长管理器
            PlaytimeManager.getInstance().onPlayerLogout(playerUuid);
        }
    }

    /**
     * 取消玩家的无敌超时任务
     * 当玩家确认规则或退出游戏时调用
     *
     * @param playerUuid 玩家UUID
     */
    public static void cancelInvulnerabilityTimeout(UUID playerUuid) {
        ScheduledFuture<?> future = TIMEOUT_TASKS.remove(playerUuid);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    /**
     * 取消玩家的验证超时任务
     * 当玩家通过验证或退出游戏时调用
     *
     * @param playerUuid 玩家UUID
     */
    public static void cancelVerifyTimeout(UUID playerUuid) {
        ScheduledFuture<?> future = VERIFY_TASKS.remove(playerUuid);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }
}
