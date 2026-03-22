package firefly520.fireflymc.event.websocket;

import firefly520.fireflymc.ServerConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 成员验证管理器
 * <p>
 * 负责管理玩家验证流程：
 * <ul>
 *   <li>发起验证请求</li>
 *   <li>处理验证响应</li>
 *   <li>超时处理</li>
 *   <li>踢出未验证玩家</li>
 * </ul>
 */
public class MemberVerificationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemberVerificationManager.class);

    private static MemberVerificationManager INSTANCE;

    /**
     * 待验证玩家封装类，保证玩家和超时时间的原子性
     */
    private static class PendingPlayer {
        final ServerPlayer player;
        final long timeoutTime;

        PendingPlayer(ServerPlayer player, long timeoutTime) {
            this.player = player;
            this.timeoutTime = timeoutTime;
        }
    }

    /**
     * 待验证玩家队列 (playerId -> PendingPlayer)
     */
    private final Map<String, PendingPlayer> pendingPlayers = new ConcurrentHashMap<>();

    /**
     * 定时执行器，用于超时检查
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "FireflyMC-MemberVerification");
        thread.setDaemon(true);
        return thread;
    });

    private MemberVerificationManager() {
        // 每秒检查一次超时
        scheduler.scheduleAtFixedRate(this::checkTimeouts, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * 获取单例实例
     */
    public static synchronized MemberVerificationManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new MemberVerificationManager();
        }
        return INSTANCE;
    }

    /**
     * 发起玩家验证请求
     *
     * @param player 要验证的玩家
     */
    public void requestVerification(ServerPlayer player) {
        String playerId = player.getGameProfile().getName();

        // 检查WebSocket连接状态
        if (!PlayerEventWebSocketClient.isConnected()) {
            player.connection.disconnect(Component.literal(
                "§c验证服务暂时不可用\n" +
                "§e请稍后重试或联系服务器管理员"
            ));
            LOGGER.warn("[FireflyMC] 玩家 {} 验证失败：WebSocket未连接", playerId);
            return;
        }

        LOGGER.info("[FireflyMC] 发起玩家验证: {}", playerId);

        // 加入待验证队列（原子操作）
        long timeoutTime = System.currentTimeMillis() + getTimeoutSeconds() * 1000L;
        pendingPlayers.put(playerId, new PendingPlayer(player, timeoutTime));

        // 发送验证请求到WebSocket服务端
        PlayerEventWebSocketClient.sendVerificationRequest(playerId);
    }

    /**
     * 处理验证响应
     *
     * @param response 验证响应消息
     */
    public void handleVerificationResponse(VerificationResponseMessage response) {
        if (!response.isValid()) {
            LOGGER.warn("[FireflyMC] 收到无效的验证响应");
            return;
        }

        String playerId = response.getPlayerId();
        // 原子移除，避免竞态条件
        PendingPlayer pending = pendingPlayers.remove(playerId);

        if (pending == null) {
            LOGGER.debug("[FireflyMC] 收到验证响应，但玩家不在待验证队列中: {}", playerId);
            return;
        }

        ServerPlayer player = pending.player;

        if (response.isVerified()) {
            // 验证通过，玩家可以继续游戏
            LOGGER.info("[FireflyMC] 玩家 {} 验证通过", playerId);
            // 这里不需要做额外处理，玩家已经可以正常游戏
        } else {
            // 验证失败，踢出玩家
            String message = response.getMessage() != null ? response.getMessage() : "你不在服务器成员列表中";
            player.connection.disconnect(Component.literal(
                "§c" + message + "\n" +
                "§e如需继续，请加入我们的Q群进行验证：480744186"
            ));
            LOGGER.info("[FireflyMC] 玩家 {} 验证失败: {}", playerId, message);
        }
    }

    /**
     * 检查超时的待验证玩家
     */
    private void checkTimeouts() {
        long currentTime = System.currentTimeMillis();

        pendingPlayers.entrySet().removeIf(entry -> {
            String playerId = entry.getKey();
            PendingPlayer pending = entry.getValue();

            if (currentTime >= pending.timeoutTime) {
                // 超时，踢出玩家
                ServerPlayer player = pending.player;
                player.connection.disconnect(Component.literal(
                    "§c验证超时，无法连接到验证服务器\n" +
                    "§e请稍后重试或联系服务器管理员"
                ));
                LOGGER.warn("[FireflyMC] 玩家 {} 验证超时，已踢出", playerId);
                return true;
            }
            return false;
        });
    }

    /**
     * 获取配置的超时时间（秒）
     */
    private int getTimeoutSeconds() {
        try {
            return ServerConfig.SERVER.memberVerificationTimeout.get();
        } catch (Exception e) {
            return 10; // 默认10秒
        }
    }

    /**
     * 清理玩家验证状态（玩家退出时调用）
     *
     * @param playerId 玩家ID
     * @return true表示玩家正在验证中，false表示玩家不在验证队列中
     */
    public boolean cleanupPlayer(String playerId) {
        PendingPlayer removed = pendingPlayers.remove(playerId);
        if (removed != null) {
            LOGGER.info("[FireflyMC] 玩家 {} 在验证过程中退出，已取消验证", playerId);
            return true;
        }
        return false;
    }

    /**
     * 关闭管理器
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
