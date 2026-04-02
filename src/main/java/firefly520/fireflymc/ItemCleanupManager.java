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
        LOGGER.info("[FireflyMC] 掉落物自动清理已启用，间隔 {} 分钟", intervalMinutes);

        cleanupTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                server.execute(this::performCleanup);
            } catch (Exception e) {
                LOGGER.error("[FireflyMC] 掉落物清理任务异常", e);
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
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
