package firefly520.fireflymc;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 掉落物自动清理管理器
 * <p>
 * 定时清理服务器中所有维度的掉落物实体，并向OP玩家发送清理日志。
 */
public class ItemCleanupManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ItemCleanupManager.class);

    private static ItemCleanupManager INSTANCE;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "FireflyMC-ItemCleanup");
        thread.setDaemon(true);
        return thread;
    });

    private MinecraftServer server;
    private ScheduledFuture<?> cleanupTask;
    private ScheduledFuture<?> warningTask;
    private ScheduledFuture<?> countdownTask;

    private ItemCleanupManager() {
    }

    public static synchronized ItemCleanupManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ItemCleanupManager();
        }
        return INSTANCE;
    }

    /**
     * 启动定时清理任务
     */
    public void start(MinecraftServer server) {
        this.server = server;
        if (!ServerConfig.SERVER.enableItemCleanup.get()) {
            LOGGER.info("[FireflyMC] 掉落物自动清理已禁用");
            return;
        }

        int intervalMinutes = ServerConfig.SERVER.itemCleanupIntervalMinutes.get();
        int warningSeconds = ServerConfig.SERVER.itemCleanupWarningSeconds.get();
        long intervalSeconds = intervalMinutes * 60L;
        LOGGER.info("[FireflyMC] 掉落物自动清理已启用，间隔 {} 分钟", intervalMinutes);

        if (warningSeconds > 0 && warningSeconds < intervalSeconds) {
            long warningDelay = intervalSeconds - warningSeconds;
            warningTask = scheduler.scheduleAtFixedRate(() -> {
                try {
                    server.execute(this::sendWarning);
                    startCountdownSequence();
                } catch (Exception e) {
                    LOGGER.error("[FireflyMC] 掉落物警告任务异常", e);
                }
            }, warningDelay, intervalSeconds, TimeUnit.SECONDS);
        } else if (warningSeconds > 0) {
            LOGGER.warn("[FireflyMC] 掉落物警告时间({}s)大于等于清理间隔({}s)，跳过警告", warningSeconds, intervalSeconds);
        }

        cleanupTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                server.execute(this::performCleanup);
            } catch (Exception e) {
                LOGGER.error("[FireflyMC] 掉落物清理任务异常", e);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * 发送清理警告
     */
    private void sendWarning() {
        int warningSeconds = ServerConfig.SERVER.itemCleanupWarningSeconds.get();
        String message = String.format("§e[FireflyMC] §c掉落物将在 §e%d §c秒后自动清理，请及时捡起重要物品！", warningSeconds);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    /**
     * 启动逐秒倒计时序列，通过 ActionBar 显示
     */
    private void startCountdownSequence() {
        int countdownSeconds = ServerConfig.SERVER.itemCleanupCountdownSeconds.get();
        if (countdownSeconds <= 0) return;

        int warningSeconds = ServerConfig.SERVER.itemCleanupWarningSeconds.get();
        if (countdownSeconds > warningSeconds) {
            LOGGER.warn("[FireflyMC] 倒计时时间({}s)大于警告时间({}s)，跳过倒计时", countdownSeconds, warningSeconds);
            return;
        }

        // 取消上一个周期的倒计时（如有残留）
        if (countdownTask != null) {
            countdownTask.cancel(false);
        }

        AtomicInteger remaining = new AtomicInteger(countdownSeconds);
        countdownTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                int left = remaining.decrementAndGet();
                if (left <= 0) {
                    if (countdownTask != null) {
                        countdownTask.cancel(false);
                        countdownTask = null;
                    }
                    return;
                }
                server.execute(() -> sendActionBarCountdown(left));
            } catch (Exception e) {
                LOGGER.error("[FireflyMC] 倒计时序列异常", e);
            }
        }, (warningSeconds - countdownSeconds) * 1000L, 1000L, TimeUnit.MILLISECONDS);
    }

    /**
     * 通过 ActionBar 发送倒计时消息
     */
    private void sendActionBarCountdown(int seconds) {
        String message = String.format("§c掉落物将在 §e%d §c秒后自动清理！", seconds);
        Component component = Component.literal(message);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.displayClientMessage(component, true);
        }
    }

    /**
     * 执行清理操作
     */
    private void performCleanup() {
        int totalRemoved = 0;

        for (ServerLevel level : server.getAllLevels()) {
            List<? extends ItemEntity> items = level.getEntities(EntityType.ITEM, item -> true);
            totalRemoved += items.size();
            for (ItemEntity item : items) {
                item.discard();
            }
        }

        if (totalRemoved > 0) {
            String message = String.format("§7[FireflyMC] 自动清理了 §e%d §7个掉落物", totalRemoved);
            LOGGER.info("[FireflyMC] 自动清理了 {} 个掉落物", totalRemoved);

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(Component.literal(message));
            }
        }
    }

    /**
     * 停止定时清理任务
     */
    public void stop() {
        if (warningTask != null) {
            warningTask.cancel(false);
            warningTask = null;
        }
        if (countdownTask != null) {
            countdownTask.cancel(false);
            countdownTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
            cleanupTask = null;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("[FireflyMC] 掉落物自动清理已停止");
    }
}
