package firefly520.fireflymc.client.auth;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端密码限流记录管理器。
 * <p>
 * 服务端在玩家密码错误耗尽时下发 {@link firefly520.fireflymc.network.AuthLockoutPayload}，
 * 客户端收到后在此记录限流到期时间并持久化到本地磁盘文件。
 * 再次尝试连接同一服务器时，由 ConnectScreenMixin 查询本管理器决定是否拦截。
 * <p>
 * 限流维度：服务器地址(ServerData.ip) + 玩家名(小写)。
 * 文件位置：<游戏目录>/fireflymc_auth_lockout.json，结构 Map&lt;key, expireEpochMillis&gt;。
 */
public class ClientAuthLockoutManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("FireflyMC/AuthLockout");
    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type DATA_TYPE = new TypeToken<Map<String, Long>>() {}.getType();
    private static final String FILE_NAME = "fireflymc_auth_lockout.json";

    private static final ClientAuthLockoutManager INSTANCE = new ClientAuthLockoutManager();

    private final Map<String, Long> lockoutMap = new ConcurrentHashMap<>();
    private Path dataFile;
    private boolean loaded = false;

    private ClientAuthLockoutManager() {}

    public static ClientAuthLockoutManager getInstance() {
        return INSTANCE;
    }

    /** 构造限流 key：服务器地址 + 玩家名（小写，与服务端 normalizeName 对齐）。 */
    public static String buildKey(String serverIp, String playerName) {
        return serverIp + "|" + normalizeName(playerName);
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** 懒加载磁盘文件到内存。 */
    private synchronized void ensureLoaded() {
        if (loaded) return;
        this.dataFile = FMLPaths.GAMEDIR.get().resolve(FILE_NAME);
        if (Files.exists(dataFile)) {
            try {
                String json = Files.readString(dataFile);
                Map<String, Long> loaded = GSON.fromJson(json, DATA_TYPE);
                if (loaded != null) {
                    lockoutMap.clear();
                    lockoutMap.putAll(loaded);
                }
            } catch (Exception e) {
                LOGGER.error("[FireflyMC] 加载密码限流记录失败", e);
            }
        }
        this.loaded = true;
        purgeExpired();
    }

    /**
     * 记录限流。
     *
     * @param key            buildKey 构造的 key
     * @param lockoutMinutes 限流分钟数
     */
    public synchronized void recordLockout(String key, int lockoutMinutes) {
        ensureLoaded();
        long expireAt = System.currentTimeMillis() + lockoutMinutes * 60_000L;
        lockoutMap.put(key, expireAt);
        saveData();
    }

    /**
     * 查询 key 的剩余限流时间。
     *
     * @return 剩余毫秒，&gt;0 表示仍在限流期；&lt;=0 表示未限流（已过期记录会被惰性清理）
     */
    public synchronized long getRemainingMillis(String key) {
        ensureLoaded();
        Long expireAt = lockoutMap.get(key);
        if (expireAt == null) return -1;
        long remaining = expireAt - System.currentTimeMillis();
        if (remaining <= 0) {
            lockoutMap.remove(key);
            saveData();
            return -1;
        }
        return remaining;
    }

    /** 清理所有已过期记录。 */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        boolean changed = lockoutMap.entrySet().removeIf(e -> e.getValue() <= now);
        if (changed) saveData();
    }

    private synchronized void saveData() {
        if (dataFile == null) return;
        try {
            String json = GSON.toJson(lockoutMap);
            Files.writeString(dataFile, json);
        } catch (IOException e) {
            LOGGER.error("[FireflyMC] 保存密码限流记录失败", e);
        }
    }
}
