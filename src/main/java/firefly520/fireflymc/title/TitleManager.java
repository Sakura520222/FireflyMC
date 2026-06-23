package firefly520.fireflymc.title;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家称号数据管理器
 * <p>
 * 单例模式，使用 ConcurrentHashMap 存储 UUID→称号 映射，
 * 持久化到 fireflymc_titles.json 文件。
 * 称号使用 Minecraft § 颜色代码格式。
 */
public class TitleManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(TitleManager.class);
    private static final TitleManager INSTANCE = new TitleManager();
    private static final Gson GSON = new Gson();
    private static final Type DATA_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final ConcurrentHashMap<UUID, String> titles = new ConcurrentHashMap<>();
    private Path dataFile;

    private TitleManager() {
    }

    public static TitleManager getInstance() {
        return INSTANCE;
    }

    // ========== 数据持久化 ==========

    /**
     * 从 fireflymc_titles.json 加载称号数据
     */
    public synchronized void load(MinecraftServer server) {
        this.dataFile = server.getServerDirectory().resolve("fireflymc_titles.json");
        if (Files.exists(dataFile)) {
            try {
                String json = Files.readString(dataFile);
                Map<String, String> loaded = GSON.fromJson(json, DATA_TYPE);
                if (loaded != null) {
                    titles.clear();
                    for (Map.Entry<String, String> entry : loaded.entrySet()) {
                        titles.put(UUID.fromString(entry.getKey()), entry.getValue());
                    }
                }
                LOGGER.info("[FireflyMC] 已加载 {} 条称号记录", titles.size());
            } catch (Exception e) {
                LOGGER.error("[FireflyMC] 加载称号记录失败", e);
            }
        }
    }

    /**
     * 保存称号数据到 JSON 文件
     */
    private synchronized void saveData() {
        if (dataFile == null) return;
        try {
            Map<String, String> toSave = new java.util.HashMap<>();
            for (Map.Entry<UUID, String> entry : titles.entrySet()) {
                toSave.put(entry.getKey().toString(), entry.getValue());
            }
            String json = GSON.toJson(toSave);
            Files.writeString(dataFile, json);
        } catch (Exception e) {
            LOGGER.error("[FireflyMC] 保存称号记录失败", e);
        }
    }

    // ========== 称号操作 ==========

    /**
     * 设置玩家称号
     *
     * @param uuid  玩家UUID
     * @param title 称号文本（使用 § 颜色代码）
     */
    public void setTitle(UUID uuid, String title) {
        titles.put(uuid, title);
        saveData();
    }

    /**
     * 获取玩家称号
     *
     * @param uuid 玩家UUID
     * @return 称号文本，如果无称号返回 null
     */
    public String getTitle(UUID uuid) {
        return titles.get(uuid);
    }

    /**
     * 移除玩家称号
     *
     * @param uuid 玩家UUID
     * @return 是否成功移除
     */
    public boolean removeTitle(UUID uuid) {
        String removed = titles.remove(uuid);
        if (removed != null) {
            saveData();
            return true;
        }
        return false;
    }

    /**
     * 获取所有称号数据（不可变快照）
     *
     * @return UUID→称号 的不可变映射
     */
    public Map<UUID, String> getAllTitles() {
        return Collections.unmodifiableMap(new java.util.HashMap<>(titles));
    }

    /**
     * 检查玩家是否有称号
     */
    public boolean hasTitle(UUID uuid) {
        return titles.containsKey(uuid);
    }

    /**
     * 获取称号数量
     */
    public int size() {
        return titles.size();
    }

    /**
     * 关闭管理器，清理资源
     */
    public void shutdown() {
        saveData();
        titles.clear();
    }

    /**
     * 将 & 颜色代码转换为 § 颜色代码
     * 如果输入已包含 § 代码，不做二次转换
     *
     * @param input 原始输入
     * @return 转换后的字符串
     */
    public static String convertColorCodes(String input) {
        if (input == null) return null;
        // 如果已经包含 § 代码，直接返回
        if (input.contains("§")) return input;
        return input.replace('&', '§');
    }
}
