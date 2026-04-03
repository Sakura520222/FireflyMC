package firefly520.fireflymc.playtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import firefly520.fireflymc.ServerConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 玩家在线时长限制管理器
 * <p>
 * 跟踪每位玩家的每日总在线时长和连续在线时长，
 * 超过限制时发送警告并最终踢出玩家。OP玩家可配置跳过限制。
 */
public class PlaytimeManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaytimeManager.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // 警告阈值（秒）：30分钟、10分钟、5分钟、1分钟
    private static final long[] CONTINUOUS_WARNING_THRESHOLDS = {1800, 600, 300, 60};
    private static final long[] DAILY_WARNING_THRESHOLDS = {1800, 600, 300, 60};
    private static final long WARN_CONTINUOUS_30M = 1L << 0;
    private static final long WARN_CONTINUOUS_10M = 1L << 1;
    private static final long WARN_CONTINUOUS_5M  = 1L << 2;
    private static final long WARN_CONTINUOUS_1M  = 1L << 3;
    private static final long WARN_DAILY_30M      = 1L << 4;
    private static final long WARN_DAILY_10M      = 1L << 5;
    private static final long WARN_DAILY_5M       = 1L << 6;
    private static final long WARN_DAILY_1M       = 1L << 7;
    private static final long[] WARN_BITS_CONTINUOUS = {WARN_CONTINUOUS_30M, WARN_CONTINUOUS_10M, WARN_CONTINUOUS_5M, WARN_CONTINUOUS_1M};
    private static final long[] WARN_BITS_DAILY = {WARN_DAILY_30M, WARN_DAILY_10M, WARN_DAILY_5M, WARN_DAILY_1M};

    private static PlaytimeManager INSTANCE;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "FireflyMC-Playtime");
        thread.setDaemon(true);
        return thread;
    });

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Type dataType = new TypeToken<Map<String, DailyPlaytimeData>>() {}.getType();

    private MinecraftServer server;
    private ScheduledFuture<?> checkTask;
    private Path dataFile;
    private final Object saveLock = new Object();

    /** 在线会话数据（UUID -> 会话信息） */
    private final ConcurrentHashMap<UUID, PlayerSessionData> activeSessions = new ConcurrentHashMap<>();

    /** 每日累计时间数据（UUID -> 每日数据） */
    private final ConcurrentHashMap<UUID, DailyPlaytimeData> dailyData = new ConcurrentHashMap<>();

    private PlaytimeManager() {
    }

    public static synchronized PlaytimeManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PlaytimeManager();
        }
        return INSTANCE;
    }

    /**
     * 启动时长限制管理器
     */
    public void start(MinecraftServer server) {
        this.server = server;
        if (!ServerConfig.SERVER.enablePlaytimeLimiter.get()) {
            LOGGER.info("[FireflyMC] 玩家在线时长限制已禁用");
            return;
        }

        this.dataFile = server.getServerDirectory().resolve("fireflymc_playtime.json");
        loadData();

        int checkInterval = ServerConfig.SERVER.playtimeCheckIntervalSeconds.get();
        int dailyMinutes = ServerConfig.SERVER.playtimeDailyLimitMinutes.get();
        int continuousMinutes = ServerConfig.SERVER.playtimeContinuousLimitMinutes.get();

        LOGGER.info("[FireflyMC] 玩家在线时长限制已启用 — 每日上限: {}分钟, 连续上限: {}分钟, 检查间隔: {}秒",
                dailyMinutes, continuousMinutes, checkInterval);

        checkTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                server.execute(this::checkAllPlayers);
            } catch (Exception e) {
                LOGGER.error("[FireflyMC] 时长检查任务异常", e);
            }
        }, checkInterval, checkInterval, TimeUnit.SECONDS);
    }

    /**
     * 停止时长限制管理器
     */
    public void stop() {
        if (checkTask != null) {
            checkTask.cancel(false);
            checkTask = null;
        }
        // 累加所有在线玩家的会话时间
        for (Map.Entry<UUID, PlayerSessionData> entry : activeSessions.entrySet()) {
            long elapsed = (System.currentTimeMillis() - entry.getValue().sessionStartTimeMillis) / 1000;
            accumulateDaily(entry.getKey(), elapsed);
        }
        activeSessions.clear();
        saveData();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("[FireflyMC] 玩家在线时长限制已停止");
    }

    /**
     * 玩家登录时调用
     */
    public void onPlayerLogin(ServerPlayer player) {
        if (!ServerConfig.SERVER.enablePlaytimeLimiter.get()) return;
        if (isBypassed(player)) return;

        UUID uuid = player.getUUID();
        String today = LocalDate.now().format(DATE_FORMAT);

        // 检查并加载每日数据
        DailyPlaytimeData data = dailyData.computeIfAbsent(uuid, k -> new DailyPlaytimeData(today, 0));
        // 如果日期不是今天，重置
        if (!today.equals(data.date)) {
            data.date = today;
            data.dailySeconds = 0;
        }

        long dailyLimitSeconds = (long) ServerConfig.SERVER.playtimeDailyLimitMinutes.get() * 60;
        // 如果已达每日上限，立即踢出
        if (data.dailySeconds >= dailyLimitSeconds) {
            String msg = ServerConfig.SERVER.playtimeKickMessageDaily.get();
            player.connection.disconnect(Component.literal(msg));
            return;
        }

        // 创建会话
        activeSessions.put(uuid, new PlayerSessionData(System.currentTimeMillis(), 0));

        // 发送剩余时间提示
        long dailyRemaining = dailyLimitSeconds - data.dailySeconds;
        long continuousLimitSeconds = (long) ServerConfig.SERVER.playtimeContinuousLimitMinutes.get() * 60;
        player.sendSystemMessage(Component.literal(String.format(
                "§e[FireflyMC] 今日剩余: §a%s§e, 本次连续上限: §a%s",
                formatDuration(dailyRemaining), formatDuration(continuousLimitSeconds)
        )));
    }

    /**
     * 玩家登出时调用
     */
    public void onPlayerLogout(UUID playerUuid) {
        if (!ServerConfig.SERVER.enablePlaytimeLimiter.get()) return;

        PlayerSessionData session = activeSessions.remove(playerUuid);
        if (session != null) {
            long elapsed = (System.currentTimeMillis() - session.sessionStartTimeMillis) / 1000;
            accumulateDaily(playerUuid, elapsed);
            saveData();
        }
    }

    /**
     * 周期检查所有在线玩家
     */
    private void checkAllPlayers() {
        if (!ServerConfig.SERVER.enablePlaytimeLimiter.get()) return;

        String today = LocalDate.now().format(DATE_FORMAT);
        long dailyLimitSeconds = (long) ServerConfig.SERVER.playtimeDailyLimitMinutes.get() * 60;
        long continuousLimitSeconds = (long) ServerConfig.SERVER.playtimeContinuousLimitMinutes.get() * 60;

        for (Map.Entry<UUID, PlayerSessionData> entry : activeSessions.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerSessionData session = entry.getValue();

            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                // 玩家已离线但未清理，手动处理
                activeSessions.remove(uuid);
                continue;
            }

            if (isBypassed(player)) continue;

            // 检查每日日期重置
            DailyPlaytimeData data = dailyData.get(uuid);
            if (data != null && !today.equals(data.date)) {
                data.date = today;
                data.dailySeconds = 0;
            }

            long continuousSeconds = (System.currentTimeMillis() - session.sessionStartTimeMillis) / 1000;
            long previousDailySeconds = (data != null) ? data.dailySeconds : 0;
            long totalDailySeconds = previousDailySeconds + continuousSeconds;

            long continuousRemaining = continuousLimitSeconds - continuousSeconds;
            long dailyRemaining = dailyLimitSeconds - totalDailySeconds;

            // 发送连续时长警告
            for (int i = 0; i < CONTINUOUS_WARNING_THRESHOLDS.length; i++) {
                if (continuousRemaining <= CONTINUOUS_WARNING_THRESHOLDS[i] &&
                    continuousRemaining > 0 &&
                    (session.warningBitmask & WARN_BITS_CONTINUOUS[i]) == 0) {
                    session.warningBitmask |= WARN_BITS_CONTINUOUS[i];
                    long secs = CONTINUOUS_WARNING_THRESHOLDS[i];
                    player.sendSystemMessage(Component.literal(String.format(
                            "§6[FireflyMC] §c连续在线时间即将耗尽，剩余 §e%s",
                            formatDuration(continuousRemaining)
                    )));
                }
            }

            // 发送每日时长警告
            for (int i = 0; i < DAILY_WARNING_THRESHOLDS.length; i++) {
                if (dailyRemaining <= DAILY_WARNING_THRESHOLDS[i] &&
                    dailyRemaining > 0 &&
                    (session.warningBitmask & WARN_BITS_DAILY[i]) == 0) {
                    session.warningBitmask |= WARN_BITS_DAILY[i];
                    player.sendSystemMessage(Component.literal(String.format(
                            "§6[FireflyMC] §c今日在线时间即将耗尽，剩余 §e%s",
                            formatDuration(dailyRemaining)
                    )));
                }
            }

            // 踢出检查
            if (continuousRemaining <= 0) {
                // 累加时间后踢出
                accumulateDaily(uuid, continuousSeconds);
                activeSessions.remove(uuid);
                String msg = ServerConfig.SERVER.playtimeKickMessageContinuous.get();
                player.connection.disconnect(Component.literal(msg));
                saveData();
                continue;
            }

            if (dailyRemaining <= 0) {
                accumulateDaily(uuid, continuousSeconds);
                activeSessions.remove(uuid);
                String msg = ServerConfig.SERVER.playtimeKickMessageDaily.get();
                player.connection.disconnect(Component.literal(msg));
                saveData();
            }
        }
    }

    /**
     * 累加每日时间
     */
    private void accumulateDaily(UUID uuid, long seconds) {
        String today = LocalDate.now().format(DATE_FORMAT);
        DailyPlaytimeData data = dailyData.computeIfAbsent(uuid, k -> new DailyPlaytimeData(today, 0));
        if (!today.equals(data.date)) {
            data.date = today;
            data.dailySeconds = 0;
        }
        data.dailySeconds += seconds;
    }

    /**
     * 检查玩家是否跳过限制
     */
    private boolean isBypassed(ServerPlayer player) {
        int bypassLevel = ServerConfig.SERVER.playtimeBypassOpLevel.get();
        return player.createCommandSourceStack().hasPermission(bypassLevel);
    }

    /**
     * 获取玩家每日累计秒数（含当前会话）
     */
    public long getPlayerDailySeconds(UUID uuid) {
        String today = LocalDate.now().format(DATE_FORMAT);
        long stored = 0;
        DailyPlaytimeData data = dailyData.get(uuid);
        if (data != null && today.equals(data.date)) {
            stored = data.dailySeconds;
        }
        PlayerSessionData session = activeSessions.get(uuid);
        if (session != null) {
            stored += (System.currentTimeMillis() - session.sessionStartTimeMillis) / 1000;
        }
        return stored;
    }

    /**
     * 获取玩家当前连续在线秒数
     */
    public long getPlayerContinuousSeconds(UUID uuid) {
        PlayerSessionData session = activeSessions.get(uuid);
        if (session == null) return 0;
        return (System.currentTimeMillis() - session.sessionStartTimeMillis) / 1000;
    }

    /**
     * 重置指定玩家的每日时间
     */
    public void resetDailyPlaytime(UUID uuid) {
        dailyData.remove(uuid);
        PlayerSessionData session = activeSessions.get(uuid);
        if (session != null) {
            session.warningBitmask = 0;
            session.sessionStartTimeMillis = System.currentTimeMillis();
        }
        saveData();
    }

    /**
     * 重置所有玩家的每日时间
     */
    public int resetAllDaily() {
        int count = dailyData.size();
        dailyData.clear();
        for (PlayerSessionData session : activeSessions.values()) {
            session.warningBitmask = 0;
            session.sessionStartTimeMillis = System.currentTimeMillis();
        }
        saveData();
        return count;
    }

    /**
     * 加载持久化数据
     */
    private void loadData() {
        if (dataFile == null || !Files.exists(dataFile)) {
            return;
        }
        try {
            String json = Files.readString(dataFile);
            Map<String, DailyPlaytimeData> loaded = gson.fromJson(json, dataType);
            if (loaded != null) {
                String today = LocalDate.now().format(DATE_FORMAT);
                for (Map.Entry<String, DailyPlaytimeData> entry : loaded.entrySet()) {
                    // 只保留今天的数据
                    if (today.equals(entry.getValue().date)) {
                        dailyData.put(UUID.fromString(entry.getKey()), entry.getValue());
                    }
                }
            }
            LOGGER.info("[FireflyMC] 已加载 {} 条玩家在线时长数据", dailyData.size());
        } catch (Exception e) {
            LOGGER.error("[FireflyMC] 加载在线时长数据失败", e);
        }
    }

    /**
     * 保存持久化数据
     */
    private void saveData() {
        if (dataFile == null) return;
        synchronized (saveLock) {
            try {
                Map<String, DailyPlaytimeData> toSave = new java.util.HashMap<>();
                for (Map.Entry<UUID, DailyPlaytimeData> entry : dailyData.entrySet()) {
                    toSave.put(entry.getKey().toString(), entry.getValue());
                }
                String json = gson.toJson(toSave);
                Files.writeString(dataFile, json);
            } catch (Exception e) {
                LOGGER.error("[FireflyMC] 保存在线时长数据失败", e);
            }
        }
    }

    /**
     * 格式化时长为 Xh Xm 形式
     */
    public static String formatDuration(long seconds) {
        if (seconds <= 0) return "0分钟";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0) {
            return String.format("%d小时%d分钟", hours, minutes);
        }
        return String.format("%d分钟", minutes);
    }

    /**
     * 在线会话数据
     */
    static class PlayerSessionData {
        long sessionStartTimeMillis;
        long warningBitmask;

        PlayerSessionData(long sessionStartTimeMillis, long warningBitmask) {
            this.sessionStartTimeMillis = sessionStartTimeMillis;
            this.warningBitmask = warningBitmask;
        }
    }

    /**
     * 每日累计时间数据
     */
    static class DailyPlaytimeData {
        String date;
        long dailySeconds;

        DailyPlaytimeData(String date, long dailySeconds) {
            this.date = date;
            this.dailySeconds = dailySeconds;
        }
    }
}
