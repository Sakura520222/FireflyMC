package firefly520.fireflymc.event.websocket;

import firefly520.fireflymc.ServerConfig;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 福利包 WebSocket 管理器
 * 处理福利包领取状态的查询和标记
 */
public class StarterKitWebSocketManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(StarterKitWebSocketManager.class);
    private static final long TIMEOUT_MS = 5000; // 5秒超时

    private static final StarterKitWebSocketManager INSTANCE = new StarterKitWebSocketManager();

    private final Map<String, PendingCheckRequest> pendingChecks = new ConcurrentHashMap<>();
    private final Map<String, PendingClaimRequest> pendingClaims = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FireflyMC-StarterKit-Timeout");
        t.setDaemon(true);
        return t;
    });

    // 启动超时检查任务
    {
        timeoutExecutor.scheduleAtFixedRate(this::checkTimeouts, 1, 1, TimeUnit.SECONDS);
    }

    public static StarterKitWebSocketManager getInstance() {
        return INSTANCE;
    }

    /**
     * 待处理的检查请求
     */
    private static class PendingCheckRequest {
        final UUID playerUuid;
        final String playerName;
        final Consumer<Boolean> callback;
        final long timeoutTime;

        PendingCheckRequest(UUID playerUuid, String playerName, Consumer<Boolean> callback, long timeoutTime) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.callback = callback;
            this.timeoutTime = timeoutTime;
        }
    }

    /**
     * 待处理的标记请求
     */
    private static class PendingClaimRequest {
        final UUID playerUuid;
        final Runnable onSuccess;
        final Runnable onFailure;
        final long timeoutTime;

        PendingClaimRequest(UUID playerUuid, Runnable onSuccess, Runnable onFailure, long timeoutTime) {
            this.playerUuid = playerUuid;
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
            this.timeoutTime = timeoutTime;
        }
    }

    /**
     * 异步检查玩家是否已领取福利包
     * @param player 玩家
     * @param callback 结果回调 (true=已领取, false=未领取)
     * @return true表示请求已发送，false表示WebSocket未连接或配置未启用
     */
    public boolean checkClaimedAsync(ServerPlayer player, Consumer<Boolean> callback) {
        if (!PlayerEventWebSocketClient.isConnected()) {
            LOGGER.warn("[FireflyMC] WebSocket未连接，无法检查福利包状态");
            return false;
        }

        if (!ServerConfig.SERVER.enableStarterKit.get()) {
            return false;
        }

        String requestId = "skc-" + System.currentTimeMillis() + "-" + player.getUUID().toString().substring(0, 8);
        pendingChecks.put(requestId, new PendingCheckRequest(
            player.getUUID(),
            player.getGameProfile().getName(),
            callback,
            System.currentTimeMillis() + TIMEOUT_MS
        ));

        PlayerEventWebSocketClient.sendStarterKitCheck(
            new StarterKitCheckRequest(
                player.getUUID().toString(),
                player.getGameProfile().getName(),
                requestId
            )
        );
        return true;
    }

    /**
     * 标记玩家已领取福利包
     * @param player 玩家
     * @param onSuccess 成功回调
     * @param onFailure 失败回调
     */
    public void markClaimed(ServerPlayer player, Runnable onSuccess, Runnable onFailure) {
        if (!PlayerEventWebSocketClient.isConnected()) {
            LOGGER.warn("[FireflyMC] WebSocket未连接，无法标记福利包");
            if (onFailure != null) onFailure.run();
            return;
        }

        pendingClaims.put(player.getUUID().toString(), new PendingClaimRequest(
            player.getUUID(),
            onSuccess,
            onFailure,
            System.currentTimeMillis() + TIMEOUT_MS
        ));

        PlayerEventWebSocketClient.sendStarterKitClaim(
            new StarterKitClaimRequest(
                player.getUUID().toString(),
                player.getGameProfile().getName()
            )
        );
    }

    /**
     * 处理检查响应
     * @param response 检查响应
     */
    public void handleCheckResponse(StarterKitCheckResponse response) {
        PendingCheckRequest pending = pendingChecks.remove(response.getRequestId());
        if (pending == null) {
            LOGGER.debug("[FireflyMC] 收到未知或已超时的检查响应: {}", response.getRequestId());
            return;
        }

        try {
            pending.callback.accept(response.isClaimed());
        } catch (Exception e) {
            LOGGER.error("[FireflyMC] 处理检查响应回调失败", e);
        }
    }

    /**
     * 处理标记响应
     * @param response 标记响应
     */
    public void handleClaimResponse(StarterKitClaimResponse response) {
        PendingClaimRequest pending = pendingClaims.remove(response.getPlayerUuid());
        if (pending == null) {
            LOGGER.debug("[FireflyMC] 收到未知或已超时的标记响应: {}", response.getPlayerUuid());
            return;
        }

        try {
            if (response.isSuccess()) {
                if (pending.onSuccess != null) pending.onSuccess.run();
            } else {
                if (pending.onFailure != null) pending.onFailure.run();
            }
        } catch (Exception e) {
            LOGGER.error("[FireflyMC] 处理标记响应回调失败", e);
        }
    }

    /**
     * 检查超时请求
     */
    private void checkTimeouts() {
        long now = System.currentTimeMillis();

        // 检查查询超时
        pendingChecks.entrySet().removeIf(entry -> {
            if (entry.getValue().timeoutTime < now) {
                LOGGER.warn("[FireflyMC] 福利包检查请求超时: {}", entry.getKey());
                try {
                    entry.getValue().callback.accept(false); // 超时默认未领取
                } catch (Exception e) {
                    LOGGER.error("[FireflyMC] 超时回调失败", e);
                }
                return true;
            }
            return false;
        });

        // 检查标记超时
        pendingClaims.entrySet().removeIf(entry -> {
            if (entry.getValue().timeoutTime < now) {
                LOGGER.warn("[FireflyMC] 福利包标记请求超时: {}", entry.getKey());
                try {
                    if (entry.getValue().onFailure != null) {
                        entry.getValue().onFailure.run();
                    }
                } catch (Exception e) {
                    LOGGER.error("[FireflyMC] 超时回调失败", e);
                }
                return true;
            }
            return false;
        });
    }
}
