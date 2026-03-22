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
     * 待验证玩家队列 (playerId -> ServerPlayer)
     */
    private final Map<String, ServerPlayer> pendingPlayers = new ConcurrentHashMap<>();

    /**
     * 待验证玩家的超时时间戳 (playerId -> 超时时间戳)
     */
    private final Map<String, Long> pendingTimeouts = new ConcurrentHashMap<>();

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

        LOGGER.info("[FireflyMC] 发起玩家验证: {}", playerId);

        // 加入待验证队列
        pendingPlayers.put(playerId, player);
        pendingTimeouts.put(playerId, System.currentTimeMillis() + getTimeoutSeconds() * 1000L);

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
        ServerPlayer player = pendingPlayers.remove(playerId);
        pendingTimeouts.remove(playerId);

        if (player == null) {
            LOGGER.debug("[FireflyMC] 收到验证响应，但玩家不在待验证队列中: {}", playerId);
            return;
        }

        if (response.isVerified()) {
            // 验证通过，玩家可以继续游戏
            LOGGER.info("[FireflyMC] 玩家 {} 验证通过", playerId);
            // 这里不需要做额外处理，玩家已经可以正常游戏
        } else {
            // 验证失败，踢出玩家
            String message = response.getMessage() != null ? response.getMessage() : "你不在服务器成员列表中";
            player.connection.disconnect(Component.literal(
                "§c" + message + "\n" +
                "§e如需帮助，请联系服务器管理员"
            ));
            LOGGER.info("[FireflyMC] 玩家 {} 验证失败: {}", playerId, message);
        }
    }

    /**
     * 检查超时的待验证玩家
     */
    private void checkTimeouts() {
        long currentTime = System.currentTimeMillis();

        pendingTimeouts.entrySet().removeIf(entry -> {
            String playerId = entry.getKey();
            long timeoutTime = entry.getValue();

            if (currentTime >= timeoutTime) {
                // 超时，踢出玩家
                ServerPlayer player = pendingPlayers.remove(playerId);
                if (player != null) {
                    player.connection.disconnect(Component.literal(
                        "§c验证超时，无法连接到验证服务器\n" +
                        "§e请稍后重试或联系服务器管理员"
                    ));
                    LOGGER.warn("[FireflyMC] 玩家 {} 验证超时，已踢出", playerId);
                }
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
     */
    public void cleanupPlayer(String playerId) {
        pendingPlayers.remove(playerId);
        pendingTimeouts.remove(playerId);
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
